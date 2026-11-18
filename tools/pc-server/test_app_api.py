import hashlib
import tempfile
import time
import unittest
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import server


class AppApiTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(dir=server.ROOT / 'test-artifacts')
        self.addCleanup(self.folder.cleanup)
        root = Path(self.folder.name)
        for name in ('uploads', 'results'):
            (root / name).mkdir()
        for target, value in (('server.DATA', root), ('server.DB', root / 'jobs.sqlite3'),
                              ('server.engine', {'status': 'ready'})):
            p = patch(target, value); p.start(); self.addCleanup(p.stop)
        server.init_db()
        server.stop.clear()
        if hasattr(server.app.state, 'worker'):
            del server.app.state.worker
        with server.connect() as db:
            db.execute("INSERT INTO settings VALUES ('app_token_sha256',?)", (hashlib.sha256(b'test-only').hexdigest(),))
        self.client = TestClient(server.app, base_url='https://localhost:8443')
        self.addCleanup(self.client.close)
        self.auth = {'Authorization': 'Bearer test-only', 'X-Language': 'en'}
        self.path = '/v1/jobs/' + 'a' * 32
        self.audio = (server.ROOT / 'test-artifacts/deutsch-test.wav').read_bytes()

    def test_requires_app_credential_and_preserves_browser_origin_checks(self):
        self.assertEqual(self.client.get('/health').status_code, 401)
        self.assertEqual(self.client.put(self.path, content=self.audio).status_code, 401)
        self.assertEqual(self.client.get('/health', headers=self.auth).status_code, 200)
        self.assertEqual(self.client.post('/api/login', json={'password':'test'}).status_code, 403)
        server.engine['status'] = 'error'
        self.assertEqual(self.client.get('/health', headers=self.auth).status_code, 503)

    def test_upload_idempotency_language_results_and_cancellation(self):
        first = self.client.put(self.path, headers=self.auth, content=self.audio)
        self.assertEqual(first.status_code, 200, first.text)
        with server.connect() as db:
            row = db.execute('SELECT * FROM jobs').fetchone()
            self.assertEqual(row['app_language'], 'en')
            self.assertEqual(row['name'], 'audio.wav')
        media = server.DATA / 'uploads' / ('a' * 32 + '.media')
        self.assertTrue(media.exists())
        self.assertEqual(self.client.put(self.path, headers=self.auth, content=b'invalid replay').status_code, 200)
        self.assertEqual(media.read_bytes(), self.audio)
        with server.connect() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0], 1)
        self.assertEqual(self.client.delete(self.path, headers=self.auth).json()['status'], 'cancelled')
        self.assertFalse(media.exists())
        self.assertEqual(self.client.get(self.path, headers=self.auth).json()['status'], 'cancelled')

    def test_cancel_before_upload_leaves_tombstone(self):
        self.assertEqual(self.client.delete(self.path, headers=self.auth).status_code, 200)
        result = self.client.put(self.path, headers=self.auth, content=self.audio)
        self.assertEqual(result.json()['status'], 'cancelled')
        self.assertEqual(list((server.DATA / 'uploads').iterdir()), [])

    def test_invalid_audio_ids_and_limits(self):
        self.assertEqual(self.client.put(self.path, headers=self.auth, content=b'bad').status_code, 400)
        self.assertEqual(self.client.put('/v1/jobs/not-an-id', headers=self.auth, content=self.audio).status_code, 400)
        self.assertEqual(self.client.put(self.path, headers={**self.auth, 'Content-Length':str(101*1024**2)}, content=b'bad').status_code, 413)
        self.assertEqual(list((server.DATA / 'uploads').iterdir()), [])

    def test_app_cannot_read_or_delete_browser_jobs(self):
        with server.connect() as db:
            db.execute("INSERT INTO jobs(id,name,status,created,size) VALUES (?,'test.wav','queued',?,1)", ('a'*32,time.time()))
        media = server.DATA / 'uploads' / ('a'*32 + '.media')
        media.write_bytes(b'keep')
        self.assertEqual(self.client.get(self.path, headers=self.auth).status_code, 404)
        self.assertEqual(self.client.delete(self.path, headers=self.auth).status_code, 404)
        self.assertEqual(self.client.put(self.path, headers=self.auth, content=self.audio).status_code, 409)
        self.assertEqual(media.read_bytes(), b'keep')

    def test_completed_job_returns_text(self):
        self.client.put(self.path, headers=self.auth, content=self.audio)
        server.update_job('a'*32, status='done')
        (server.DATA / 'results' / ('a'*32 + '.txt')).write_text('Test text', encoding='utf-8')
        self.assertEqual(self.client.get(self.path, headers=self.auth).json()['text'], 'Test text')

    def test_shared_worker_uses_app_language(self):
        observed = []
        class Pipeline:
            def __init__(self, model):
                pass
            def transcribe(self, audio, **kwargs):
                observed.append(kwargs['language'])
                return iter([SimpleNamespace(start=0, end=1, text='Hello')]), SimpleNamespace(duration=1)
        with patch('server.gpu_preflight'), patch('faster_whisper.WhisperModel', return_value=object()), \
                patch('faster_whisper.BatchedInferencePipeline', Pipeline):
            with TestClient(server.app, base_url='https://localhost:8443') as client:
                result = client.put(self.path, headers=self.auth, content=self.audio)
                self.assertEqual(result.status_code, 200)
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    state = client.get(self.path, headers=self.auth).json()
                    if state['status'] in ('done', 'error'):
                        break
                    time.sleep(.05)
                self.assertEqual(state['status'], 'done')
                self.assertEqual(state['text'].strip(), 'Hello')
                self.assertEqual(observed, ['en'])


if __name__ == '__main__':
    unittest.main()
