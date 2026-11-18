# PC integration

The Android app supports the existing homelab API and an optional preferred PC
running Lokal Transkript. It checks the PC before each audio job, then tries the
homelab, then the phone. The server preference switch must be enabled. No LAN
addresses, credentials, certificates or automatic network scans are built in.

The PC profile is private JSON with `version: 1`, `url` (HTTPS origin), `token`
(random bearer credential) and `caPem` (the public CA certificate). Transfer it
privately and use **Settings → Preferred PC → Import PC profile**. The app encrypts
it with Android Keystore and excludes it from cloud backups and device transfer.
Delete transfer copies after import. Never publish or commit this file. Rotate the
server-side token if a copy is lost; import the replacement profile on your phone.

For the supported Lokal Transkript service version, stop the service, back up
`server.py`, apply `lokal-transkript.patch` to it and copy `app_api.py` beside it.
The patch adds the migration, authentication hook, language handling and cooperative
cancellation described below. Review patch failures rather than forcing a mismatch.
Run `create_profile.py SERVICE_DIRECTORY` on the PC to create the private profile;
use `--rotate` to invalidate the old app credential. Restart the existing service
using its installer to retain its startup trigger and private-subnet firewall.
Copy `test_app_api.py` into the service directory and run its isolated tests with
the service's Python environment (requires the existing synthetic test WAV).

TLS uses only the imported CA for that PC and retains normal hostname checks.
Credentials are never forwarded through redirects. LAN access requires the PC
to be awake, a reachable address and a private-subnet firewall rule. Remote access
requires separately configuring a VPN, its firewall rules and a matching certificate.

`app_api.py` is an adapter for the existing Python service, not a standalone server.
The service must add nullable `app_language` to its jobs table, pass this language
to Whisper, exempt `/v1/` requests with valid bearer credentials from browser Origin
checks/body buffering, and call `app_api.install(server_module)` after app creation.
Keep existing browser authentication unchanged. Store only SHA-256 of the random
token in settings under `app_token_sha256`. Keep autostart on the existing service
identity and reuse its single GPU worker and resource limits.

The PC protocol uses `GET /health`, `PUT /v1/jobs/{random-id}` with a raw audio body,
`GET /v1/jobs/{id}` and `DELETE /v1/jobs/{id}`. IDs make a lost upload response
recoverable without re-uploading. After an uncertain accepted job the app shows an
error instead of sending the recording to another server. Cancellation is
cooperative: it prevents queued work and stops running work at the next boundary,
without resetting the GPU or terminating unrelated jobs. Upload limit: 100 MB.
