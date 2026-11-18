"""AudioScribe mobile API for Lokal Transkript. No machine-specific configuration.

Install with install(server_module) after creating the FastAPI app. Uses the existing
single GPU worker and its resource limits; never starts a second inference worker.
"""
import asyncio
import hashlib
import hmac
import re
import shutil
import time

from fastapi import HTTPException, Request


def install(s):
    def authenticate(request):
        value = request.headers.get("authorization", "")
        if not value.startswith("Bearer ") or len(value) > 300:
            raise HTTPException(401, "App pairing required")
        hashed = hashlib.sha256(value[7:].encode()).hexdigest()
        with s.connect() as db:
            row = db.execute("SELECT value FROM settings WHERE key='app_token_sha256'").fetchone()
        if row is None or not hmac.compare_digest(hashed, row["value"]):
            raise HTTPException(401, "Invalid app credential")

    def valid_id(job_id):
        if not re.fullmatch(r"[0-9a-f]{32}", job_id):
            raise HTTPException(400, "Invalid job ID")

    def lookup(job_id):
        valid_id(job_id)
        row = s.get_job(job_id)
        if row["app_language"] is None:
            raise HTTPException(404, "App job not found")
        return row

    @s.app.get("/health")
    def health(request: Request):
        authenticate(request)
        ready = s.engine["status"] != "error" and not s.stop.is_set()
        worker = getattr(s.app.state, "worker", None)
        ready = ready and (worker is None or worker.is_alive())
        return s.JSONResponse({"ready": ready, "api": "audioscribe-pc-v1"}, status_code=200 if ready else 503)

    @s.app.get("/v1/models")
    def models(request: Request):
        authenticate(request)
        return {"models": [{"id": "large-v3", "label": "PC GPU · Whisper large-v3"}]}

    @s.app.put("/v1/jobs/{job_id}")
    async def upload(job_id: str, request: Request):
        authenticate(request)
        valid_id(job_id)
        language = request.headers.get("x-language", "de")
        if language not in ("de", "en"):
            raise HTTPException(400, "Unsupported language")
        if s.engine["status"] == "error":
            raise HTTPException(503, "Worker stopped; restart the service")
        if not s.upload_lock.acquire(blocking=False):
            raise HTTPException(409, "Another upload is in progress")
        partial = s.DATA / "uploads" / (job_id + ".part")
        final = partial.with_suffix(".media")
        accepted = False
        try:
            with s.connect() as db:
                existing = db.execute("SELECT app_language,status FROM jobs WHERE id=?", (job_id,)).fetchone()
            if existing is not None:
                if existing["app_language"] is None:
                    raise HTTPException(409, "ID conflict")
                accepted = True
                return {"id": job_id, "status": existing["status"]}
            maximum = min(s.CFG["max_upload_gb"] * 1024**3, 100 * 1024**2)
            try:
                length = int(request.headers.get("content-length", "0"))
            except ValueError:
                raise HTTPException(400, "Invalid size")
            if length < 0 or length > maximum:
                raise HTTPException(413, "Audio exceeds 100 MB limit")
            if shutil.disk_usage(s.DATA).free < max(length, 1024**3) + 5 * 1024**3:
                raise HTTPException(507, "Insufficient disk space")
            size = 0
            with partial.open("wb") as output:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > maximum:
                        raise HTTPException(413, "Audio exceeds 100 MB limit")
                    await asyncio.to_thread(output.write, chunk)
            if not size:
                raise HTTPException(400, "Empty audio")
            # Android URI caches can have .tmp names. Probe only our explicitly allowed
            # demuxers, never let an untrusted playlist select a network protocol.
            def detect():
                for suffix in (".ogg", ".wav", ".mp3", ".m4a", ".flac", ".webm", ".aac", ".wma"):
                    try:
                        s.probe_media(partial, "audio" + suffix)
                        return "audio" + suffix
                    except Exception:
                        pass
                raise HTTPException(400, "Unsupported or invalid audio")
            name = await asyncio.to_thread(detect)
            partial.rename(final)
            with s.connect() as db:
                # Cancellation can insert a tombstone while upload/probing is in progress.
                db.execute("INSERT OR IGNORE INTO jobs(id,name,status,created,size,app_language) VALUES (?,?,'queued',?,?,?)",
                           (job_id, name, time.time(), size, language))
                state = db.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()["status"]
            accepted = state != "cancelled"
            if not accepted:
                final.unlink(missing_ok=True)
            s.wake.set()
            return {"id": job_id, "status": state}
        finally:
            partial.unlink(missing_ok=True)
            if not accepted:
                final.unlink(missing_ok=True)
            s.upload_lock.release()

    @s.app.get("/v1/jobs/{job_id}")
    def status(job_id: str, request: Request):
        authenticate(request)
        row = lookup(job_id)
        result = {"id": job_id, "status": row["status"], "progress": row["progress"]}
        if row["status"] == "done":
            result["text"] = (s.DATA / "results" / (job_id + ".txt")).read_text(encoding="utf-8")
        return result

    @s.app.delete("/v1/jobs/{job_id}")
    def cancel(job_id: str, request: Request):
        authenticate(request)
        valid_id(job_id)
        with s.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT status,app_language FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                db.execute("INSERT INTO jobs(id,name,status,created,size,app_language) VALUES (?,'audio.wav','cancelled',?,0,'de')",
                           (job_id, time.time()))
            elif row["app_language"] is None:
                raise HTTPException(404, "App job not found")
            elif row["status"] not in ("done", "error", "cancelled"):
                db.execute("UPDATE jobs SET status='cancelled' WHERE id=?", (job_id,))
        # Running inference cooperatively observes this state at the next segment boundary.
        return {"id": job_id, "status": "cancelled" if row is None else s.get_job(job_id)["status"]}
