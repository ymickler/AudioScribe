"""Run on the PC: python create_profile.py SERVICE_DIRECTORY [--rotate].

Never prints the credential. Copies belong only on the user's own devices.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('service_directory', type=Path)
parser.add_argument('--rotate', action='store_true', help='Invalidate the previously paired app credential')
args = parser.parse_args()
root = args.service_directory.resolve()
destination = root / 'data/AudioScribe-pc-profile.json'
if destination.exists() and not args.rotate:
    print('Existing private PC profile:', destination)
    raise SystemExit(0)
config = json.loads((root / 'config.json').read_text(encoding='utf-8'))
token = secrets.token_urlsafe(48)
profile = {'version': 1, 'url': f"https://{config['lan_ip']}:{int(config['port'])}",
           'token': token, 'caPem': (root / 'data/tls/ca.pem').read_text(encoding='utf-8')}
# Create a new file before updating credentials. Restrict access before writing secrets.
temporary = destination.with_suffix('.tmp')
fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
try:
    if os.name == 'nt':
        account = subprocess.check_output(['whoami'], text=True).strip()
        subprocess.run(['icacls', str(temporary), '/inheritance:r', '/grant:r',
                        account + ':(F)', '*S-1-5-18:(F)', '*S-1-5-32-544:(F)'],
                       check=True, capture_output=True)
    with os.fdopen(fd, 'w', encoding='utf-8') as output:
        fd = None
        json.dump(profile, output, indent=2)
    with sqlite3.connect(root / 'data/jobs.sqlite3') as db:
        db.execute("INSERT OR REPLACE INTO settings(key,value) VALUES ('app_token_sha256',?)",
                   (hashlib.sha256(token.encode()).hexdigest(),))
    temporary.replace(destination)
finally:
    if fd is not None:
        os.close(fd)
    temporary.unlink(missing_ok=True)
print('Private PC profile created:', destination)
