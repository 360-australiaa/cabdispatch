# Deploying Cab Dispatch to an Ubuntu server

Docker Compose deploy behind HTTPS. Five containers: Caddy (TLS-terminating
reverse proxy), Postgres, Redis, the FastAPI backend, and the dashboard
(built React SPA served by nginx). The backend applies its own database
migrations on every boot (see `backend/entrypoint.sh`) -- no separate manual
migration step.

> **You need a domain name before you start.** HTTPS is a required step in
> this runbook, not an optional one at the end. Earlier versions of this
> document deployed on a bare IP over plaintext HTTP; a backend audit rated
> that the single largest operational risk in the deployment, because this
> API carries JWTs, device shared secrets, live GPS traces and duress **audio
> recordings** -- all of which travelled in the clear, readable by anything
> between the tablet and the server. Two DNS A records and Step 5 below fix
> it, with automatic certificate renewal and nothing to remember annually.
> The Android app's cleartext allowance (`network_security_config.xml`)
> exists only until this is done, and is meant to be deleted afterwards.

**Read before your first deploy:** "Operating notes" near the end covers the
things that bite a live deployment -- why the backend must stay on exactly one
worker, what backups have to include beyond `pg_dump`, and how to rotate each
secret. None of it is optional reading once you have real drivers on the
system.

Repo: `git@github.com:360-australiaa/cabdispatch.git` (private -- see Step 2
for read-only server access via a deploy key).

---

## 1. Install Docker on the server

SSH into the server, then:

```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Run docker without sudo (log out/in once after this for it to take effect)
sudo usermod -aG docker $USER
```

Verify: `docker --version && docker compose version` (this check runs before .env.production exists, so no --env-file flag here -- every command from Step 6 onward needs it, see the note there)

## 2. Give the server read-only access to the private GitHub repo

Generate a dedicated deploy key on the server (do this on the server, not
your own machine -- the private key never leaves it):

```bash
ssh-keygen -t ed25519 -C "cabdispatch-deploy" -f ~/.ssh/cabdispatch_deploy -N ""
cat ~/.ssh/cabdispatch_deploy.pub
```

Copy that public key, then on GitHub: **repo → Settings → Deploy keys →
Add deploy key** → paste it, leave "Allow write access" **unchecked**
(read-only is all a pull-based server needs) → Add key.

Tell the server to use this key for GitHub specifically:

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  HostName github.com
  User git
  IdentityFile ~/.ssh/cabdispatch_deploy
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
```

Test it: `ssh -T git@github.com` (a "successful authentication" message is
correct -- GitHub deploy keys never grant shell access, that "can't provide
shell access" line is expected).

## 3. Clone the repo

```bash
sudo mkdir -p /opt/cabdispatch
sudo chown $USER:$USER /opt/cabdispatch
git clone git@github.com:360-australiaa/cabdispatch.git /opt/cabdispatch
cd /opt/cabdispatch
```

## 4. Create your real secrets file

```bash
cp .env.production.example .env.production
nano .env.production   # or vim/vi -- fill in every "change-me" line
```

**Set POSTGRES_PASSWORD correctly the FIRST time -- Postgres only reads it on its very first boot against an empty data volume.** If you change it later (after `docker compose up` has already run once), the backend will fail with `asyncpg.exceptions.InvalidPasswordError` because Postgres kept the old password from its already-initialized volume while the backend reads the new one. If that happens before you have any real data, the fix is `docker compose --env-file .env.production down -v` (the `-v` wipes the Postgres + uploads volumes -- only safe pre-data) then `up -d --build` again to let Postgres re-initialize fresh. Once you have real data, changing the password instead requires connecting to Postgres directly and running `ALTER USER cabdispatch WITH PASSWORD '...';`.

At minimum you MUST replace:

| variable | value | if you get it wrong |
|---|---|---|
| `POSTGRES_PASSWORD` | a generated random password | see the warning above -- Postgres reads it once, on first boot |
| `JWT_SECRET` | `openssl rand -hex 32` | **the backend refuses to start** |
| `SECRET_ENCRYPTION_KEY` | generation command is in the file's comments | **the backend refuses to start** |
| `TARIFF_SIGNING_PRIVATE_KEY` | generation command is in the file's comments | **the backend refuses to start** |
| `VITE_API_URL` | `https://api.yourdomain.example` (step 5b) | dashboard talks to the wrong host, or is blocked as mixed content |
| `CORS_ORIGINS` | `["https://admin.yourdomain.example"]` (step 5b) | the dashboard's API calls are rejected by the browser |
| `DURESS_ESCALATION_CALL_PHONE` | the real number to dial on a duress escalation | **nothing fails.** The escalation call is silently skipped -- see "Required environment variables that fail quietly" |

The three secrets marked "refuses to start" are enforced at boot by
`assert_production_secrets_safe` (`app/core/config.py`): each ships a working
default that is committed to this repository, and production will not run on
any of them. That is deliberate — a published tariff-signing key defeats the
tablet's signature verification entirely.

`DURESS_ESCALATION_CALL_PHONE` is the dangerous one, because it is the only
required value here whose absence produces no error at all. Set it, and set
the Twilio trio (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
`TWILIO_FROM_NUMBER`) with it.

Everything else (Stripe/SendGrid/CabCharge/TTSS) can stay as placeholders --
the app runs in a clearly-flagged mock-fallback mode for any of those until
you add real credentials.

**This file holds real secrets -- it's gitignored, never commit it.**

## 5. Firewall

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp     # HTTP -- redirects to HTTPS, and serves the ACME
                          # certificate challenge. Must stay open or Caddy
                          # cannot renew your certificates.
sudo ufw allow 443/tcp    # HTTPS -- everything real goes through here
sudo ufw enable
```

**Do not open 8001.** The API is no longer published on a host port at all;
Caddy reaches it over the internal compose network. If you ever need it
directly for debugging, tunnel over SSH (`ssh -L 8001:localhost:8001 ...`)
rather than opening the port.

## 5b. DNS and the Caddyfile (required -- this is what gives you HTTPS)

Pick two hostnames on a domain you control and point both at this server's
public IP with A records, e.g.:

```
api.yourdomain.example.     A   <this server's public IP>
admin.yourdomain.example.   A   <this server's public IP>
```

**Wait for DNS to actually resolve before starting the stack** (`dig +short
api.yourdomain.example` from anywhere should return your IP). Caddy proves
domain ownership over HTTP-01 on port 80; if the name does not resolve yet,
certificate issuance fails and Let's Encrypt applies a rate limit that will
have you waiting an hour to retry.

Create `/opt/cabdispatch/Caddyfile` -- this file names your specific domains,
which is why it is not in the repo:

```caddyfile
# The dashboard (React SPA served by the `dashboard` container).
admin.yourdomain.example {
    encode gzip zstd
    reverse_proxy dashboard:80
}

# The API.
api.yourdomain.example {
    encode gzip zstd
    # WebSockets (live map, job offers, messages, duress) need no special
    # config -- Caddy proxies the Upgrade handshake transparently. It does
    # need a generous timeout, because these sockets are long-lived and idle
    # between GPS fixes.
    reverse_proxy backend:8001 {
        flush_interval -1
    }
    request_body {
        # Duress audio uploads are the largest thing this API accepts.
        max_size 50MB
    }
}
```

Caddy redirects HTTP to HTTPS automatically: any request to
`http://api.yourdomain.example` gets a `308` to the `https://` URL without
you configuring anything. Port 80 stays open purely for that redirect and for
ACME renewals.

Now set the two domain-dependent values in `.env.production`:

```
VITE_API_URL=https://api.yourdomain.example
CORS_ORIGINS=["https://admin.yourdomain.example"]
```

Both must be `https://`. `VITE_API_URL` is baked into the dashboard bundle at
**build** time, so changing it later requires a rebuild, not just a restart.

## 6. Build and start

```bash
docker compose --env-file .env.production up -d --build --wait
```

First build takes a few minutes (Postgres/Redis images pull, backend/dashboard
build from source). Watch it come up:

```bash
docker compose --env-file .env.production logs -f backend
```

You should see `[entrypoint] running alembic migrations...` then
`[entrypoint] starting uvicorn...` then Uvicorn's "Application startup
complete." Ctrl+C to stop tailing (the containers keep running).

## 7. Seed demo data -- **do NOT do this on a production server**

`scripts/seed.py` creates a demo tenant, demo tariffs and a "Demo Driver".
An earlier version of this document told you to run it here. That is how a
working driver login came to exist on the live server, get quoted in three
checked-in documents, and end up compiled into a distributed APK. Do not
seed demo data into a database that will hold real trips.

The script now refuses to run when `ENV=production` and exits `2`:

```
error: Refusing to seed: ENV=production. ...
```

**Create your real tenant, staff and drivers through the dashboard instead**
(Fleet ▸ Drivers mints each driver's `driver_code` and PIN through the normal
audited path). The one thing a brand-new production database genuinely needs
from the seed script is the *global Fares Order reference tariff*; if that is
what you are after, and only then:

```bash
# Note: the runtime image's PATH already points at the venv's python
# (/app/.venv/bin) -- no `uv run` prefix needed inside the container,
# unlike local dev commands elsewhere in this repo.
docker compose --env-file .env.production exec backend   python scripts/seed.py --i-know-this-is-production
```

The script never prints a password, a PIN or a `driver_code`. Staff accounts
it creates get a random password that is generated and immediately discarded,
so nobody can log into them; pass `SEED_OWNER_PASSWORD` in the environment if
you want a usable one. Read any driver's code off Fleet ▸ Drivers, never from
a terminal scrollback.

## 7b. Seed the NSW toll-road registry (NOT optional if you want automatic tolls)

```bash
docker compose --env-file .env.production exec backend python scripts/seed_toll_roads.py
```

**Wait for the backend to be healthy before running this.** `up -d` returns
when the container has STARTED, not when its entrypoint has finished
`alembic upgrade head` -- run the seed back-to-back with the deploy and it
queries a schema the migration has not reached yet, failing with
`column toll_roads.charging_policy does not exist`. That is why the deploy
command above passes `--wait` (Compose then blocks until the backend's
healthcheck passes). If you deployed without it, `docker compose --env-file
.env.production ps` until backend shows `healthy`, then seed. Nothing is
written on that failure and the script is idempotent, so re-running is always
safe -- and the script itself now says so instead of only printing a
traceback.

Loads the real toll roads, their published prices and all 141 physical gantry
coordinates from `app/data/nsw_toll_roads.json` / `nsw_toll_gantries.csv`.

Unlike `seed.py` above this is NOT demo data -- it is the reference data the
meter matches a GPS fix against to auto-detect a toll crossing, and the data
Tariff Studio's "NSW Toll Roads" tab displays. Without it the meter silently
falls back to manual toll entry only, and that tab shows an empty map.

Idempotent: safe to run as often as you like. Re-run it after **every** deploy
that changes toll prices or gantry data -- migrations only change the schema,
they never load reference data (see `app/models/toll.py` for why pricing is
kept append-only rather than migrated in place). Expected tail of the output:

```
  gantries: 141 created, 0 updated (of 141 total real gantries)
Done: 14 toll roads seeded.
```

("14" is the 13 authoritative roads plus the M12 gantry-only stub. `M5E` and
the Lane Cove `military_e_ramp` toll point print a NOTE about having 0
gantries -- that is correct and expected: both are genuinely priced but have
no coordinates in the source dataset, so they are not GPS-auto-detectable.)

## 8. Verify

```bash
# Through Caddy, over TLS, from anywhere:
curl https://api.yourdomain.example/health
# {"status":"ok","env":"production"}

# HTTP must redirect, not serve:
curl -sSI http://api.yourdomain.example/health | head -n 1
# HTTP/1.1 308 Permanent Redirect
```

Note there is no `-k` anywhere in this section. If you find yourself needing
it, the certificate is not valid, TLS is not working, and you are not
finished. Confirm the certificate was actually issued:

```bash
docker compose --env-file .env.production logs caddy | grep -i "certificate obtained"
```

There is deliberately **no copy-paste login command here any more.** This
section used to carry a real email and password; a verification step is not
worth a live credential sitting in a checked-in document. To verify auth end
to end, open `https://admin.yourdomain.example/` in a browser and log in as
the owner account you created on the dashboard.

## Day 2: deploying an update

```bash
cd /opt/cabdispatch
git pull
docker compose --env-file .env.production up -d --build --wait
```

That's the whole update workflow -- rebuilds only what changed, migrations
re-run automatically (no-op if already at head), containers restart with
zero manual steps.

Two things migrations do NOT do, so watch for them in a release's notes:

- **Reference data.** If a release changes toll prices or gantry coordinates,
  re-run the toll seed (step 7b) after the rebuild. Migrations only move the
  schema; they never load or correct data.
- **Anything baked into the dashboard bundle.** Vite inlines every `VITE_*`
  value at BUILD time (see `docker-compose.yml`'s `dashboard.build.args`), so
  adding `VITE_MAPBOX_TOKEN` to `.env.production` changes nothing until the
  next `up -d --build`. Set it first, then build. Without it the Live Map, the
  Toll Zones centre picker and the toll gantry map each fall back to a
  non-map view rather than erroring, which is easy to mistake for a bug.

Useful commands:

```bash
docker compose --env-file .env.production ps                    # container status
docker compose --env-file .env.production logs -f backend       # tail backend logs
docker compose --env-file .env.production logs -f dashboard     # tail dashboard/nginx logs
docker compose --env-file .env.production restart backend       # restart one service
docker compose --env-file .env.production down                  # stop everything (data volumes persist)
```

---

# Operating notes

Everything below is about keeping a live deployment alive and recoverable.
Read it before you have real drivers on the system, not after.

## Worker count: exactly one, and it must stay that way

`backend/entrypoint.sh` runs `uvicorn` with **no `--workers` flag**, i.e. a
single worker process. Do not add one, and do not add `--workers` to the
compose `command:`.

This is not a performance oversight, it is a correctness constraint. Several
parts of the app hold state in one worker process's memory and break
**silently** — no error, no log line, just missing data — the moment a second
worker exists:

- **The live-ops broadcaster** (`app/services/live_ops.py`) fans vehicle
  position updates out to connected WebSocket clients through an in-process
  registry. `JobOfferBroadcaster` and `message_broadcaster` work the same way.
  With two workers, a dispatcher whose browser is connected to worker A never
  receives an event published by worker B: the live map stops moving for
  roughly half your users, and nobody gets an error.
- **JWT revocation** (`app/core/security.py`) uses Redis when it can reach it
  and falls back to a per-process dictionary when it cannot. With more than
  one worker that fallback means a token you revoked still works on every
  other worker — a logged-out or suspended user stays logged in.

If you outgrow one worker, the fix is to run multiple *backend containers*
behind Caddy with the broadcasters moved onto Redis pub/sub, not to raise the
worker count. Until that work is done, one worker is the supported topology.

## Log rotation

Configured in `docker-compose.yml` — every service sets the `json-file` driver
with `max-size: 10m` and `max-file: 3`. Without it, container logs grow without
limit; on a small VPS they eventually fill the disk, and the first thing to
fall over when the disk is full is Postgres.

Check what logs currently cost you:

```bash
sudo du -sh /var/lib/docker/containers/*/*-json.log | sort -h | tail
```

Rotation applies to containers created *after* the setting was added. If you
added it to an existing deployment, `docker compose --env-file .env.production
up -d --force-recreate` once to apply it.

## Backups and the restore drill

Two volumes matter, and **backing up only the first one does not work**:

| volume | holds | why it matters |
|---|---|---|
| `cabdispatch_pgdata` | Postgres: trips, fares, users, audit chain | the obvious one |
| `cabdispatch_uploads` | duress audio, compliance documents, receipts | a `pg_dump` restores rows that *point at* these files; without the files you restore a database full of dead links, including the audio evidence attached to a panic event |

### Nightly backup

Create `/opt/cabdispatch/backup.sh`:

```bash
#!/bin/bash
set -euo pipefail
cd /opt/cabdispatch
STAMP=$(date +%F-%H%M)
DEST=/var/backups/cabdispatch
mkdir -p "$DEST"

# 1. Database. --clean --if-exists makes the dump safe to replay over an
#    existing database. Custom format (-Fc) so pg_restore can be selective.
docker compose --env-file .env.production exec -T db \
  pg_dump -U cabdispatch -Fc --clean --if-exists cabdispatch \
  > "$DEST/db-$STAMP.dump"

# 2. Uploads volume. Read it through a throwaway container -- the volume is
#    not mounted on the host, so there is no directory to tar directly.
docker run --rm \
  -v cabdispatch_uploads:/data:ro \
  -v "$DEST":/backup \
  alpine tar czf "/backup/uploads-$STAMP.tar.gz" -C /data .

# 3. Off the box. A backup on the same disk as the thing it protects is not
#    a backup. Replace with your own target (rclone, aws s3 sync, restic...).
rclone copy "$DEST" remote:cabdispatch-backups/

# 4. Local retention: 14 days.
find "$DEST" -type f -mtime +14 -delete
```

```bash
chmod +x /opt/cabdispatch/backup.sh
sudo crontab -e
# 15 3 * * *  /opt/cabdispatch/backup.sh >> /var/log/cabdispatch-backup.log 2>&1
```

Also back up `.env.production` itself, **separately and encrypted** (it is the
only copy of your secrets, and it is deliberately not in git). Losing it is
described under "Rotating secrets" below.

### The restore drill — do this once, before you need it

An untested backup is a guess. Run this end to end on a scratch server, or on
the same server with the stack stopped and volumes renamed, and write down how
long it took:

1. **Provision** a fresh Ubuntu host and complete steps 1–5b of this document
   (Docker, deploy key, clone, `.env.production` restored from its encrypted
   copy, DNS, Caddyfile). Do not start the stack yet.
2. **Start only the database:**
   `docker compose --env-file .env.production up -d db`
3. **Restore the dump:**
   ```bash
   cat db-<stamp>.dump | docker compose --env-file .env.production exec -T db \
     pg_restore -U cabdispatch -d cabdispatch --clean --if-exists
   ```
4. **Restore the uploads volume:**
   ```bash
   docker run --rm -v cabdispatch_uploads:/data \
     -v "$PWD":/backup alpine \
     tar xzf /backup/uploads-<stamp>.tar.gz -C /data
   ```
5. **Start everything:** `docker compose --env-file .env.production up -d --build --wait`.
   The backend runs `alembic upgrade head` on boot, so a dump taken from an
   older schema is migrated forward automatically.
6. **Verify, and be specific about it.** Check each of these, because each one
   fails differently:
   - `curl https://api.yourdomain.example/health` returns `ok`;
   - the dashboard loads over HTTPS and an owner can log in;
   - a closed trip from before the backup shows its full fare breakdown;
   - **open a duress event that had an audio recording and play it** — this is
     the check that catches a database-only backup, and it is the one people
     skip;
   - a driver can log in from a tablet and start a shift.

Record the wall-clock time. That number is your recovery time objective; if
you have never measured it, you do not have one.

### Rollback

A deploy is a rebuild from a git checkout, so rolling back code is
`git checkout <previous-tag>` then
`docker compose --env-file .env.production up -d --build --wait`.

**Migrations do not roll back with it.** Alembic runs `upgrade head` on every
boot and never downgrades, and several revisions in this repo cannot be
downgraded safely at all (merge revisions, and
`a9c1f4e7d2b8_nsw_toll_road_registry` which deletes seeded rows). If a release
included a migration, rolling the code back to before it leaves old code
against a newer schema. For anything beyond a same-day revert, restore from
the backup instead — which is the other reason to have done the drill.

## Required environment variables that fail quietly

The backend refuses to start in production if `JWT_SECRET`,
`TARIFF_SIGNING_PRIVATE_KEY` or `SECRET_ENCRYPTION_KEY` is still at its
committed development default (`app/core/config.py`'s
`assert_production_secrets_safe`) — you will get a loud
`InsecureProductionConfigError` and a container that will not come up. That is
the intended behaviour; fix `.env.production` rather than working around it.

**`DURESS_ESCALATION_CALL_PHONE` is not covered by that guard and must be
set.** Treat it as required. It is the number dialled when a duress
escalation reaches its final stage. Left empty, the escalation still runs, the
event still records, and **the phone call is silently skipped** — no error, no
warning, nothing in the UI to tell an operator that the last link in a panic
alarm is not connected. You discover it during a real incident.

It needs the Twilio trio (`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
`TWILIO_FROM_NUMBER`) set alongside it; with any of those missing the call
goes to mock-fallback instead of being placed. Verify after deploying by
triggering a test duress event out of hours and confirming the phone rings.

## Rotating secrets

You will need this after a suspected disclosure, when someone with access
leaves, or on a routine schedule. Rotate one at a time and know what each one
breaks, because two of the three invalidate things that live on tablets.

Edit `.env.production`, then
`docker compose --env-file .env.production up -d --force-recreate backend`.

### `JWT_SECRET` — logs everyone out

Generate: `openssl rand -hex 32`

Every access and refresh token this server has ever issued becomes invalid
immediately. Consequences: every dashboard user is logged out and must sign in
again; **every driver tablet is logged out mid-shift** and the driver must
re-enter their PIN. An open trip is not lost (it is held on the device and
syncs afterwards), but the driver cannot do anything until they log back in.
Rotate at a shift boundary, not at 5pm on a Friday, and tell your drivers
first.

### `TARIFF_SIGNING_PRIVATE_KEY` — invalidates every tariff signature on every tablet

Generate:

```bash
docker run --rm python:3.12-slim bash -c "pip install -q cryptography && python -c \"
import base64
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization
k = ed25519.Ed25519PrivateKey.generate()
print(base64.b64encode(k.private_bytes(
    serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
    serialization.NoEncryption())).decode())\""
```

This is the Ed25519 key the server signs the active tariff with, and which
each tablet verifies against the public key it cached at commissioning.
Rotating it means **every tablet's cached tariff signature stops verifying.**
Each device must fetch `GET /v1/tariffs/active` again, over the network,
before it will accept a fare table — a tablet that is offline when you rotate
stays broken until it next has coverage.

Rotate this if the key was ever committed, published, or present in a
distributed build. The default in `app/core/config.py` is exactly such a key:
it is in this repository, so anyone can sign a tariff with it. A deployment
running on that default has no tariff anti-tamper protection whatsoever, which
is why the startup guard now refuses to boot on it.

### `SECRET_ENCRYPTION_KEY` — permanently destroys stored duress-device secrets

Generate:

```bash
docker run --rm python:3.12-slim python -c \
  "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

**Read this paragraph before you rotate it.** This is a Fernet key used to
encrypt each duress device's shared secret at rest (`app/core/crypto.py`). The
server needs the plaintext back to verify a device's HMAC — so unlike a
password hash, this is reversible, and the ciphertext in the database is
worthless without the key that produced it. Change the key and **every stored
duress-device secret becomes permanently undecryptable.** Every CT-DPD-01
panic device stops authenticating and must be physically re-paired. There is
no recovery path and no partial failure: the devices simply stop working, and
a panic button that stops working is not obviously broken until it is pressed.

Plan a rotation as a re-pairing exercise across the whole fleet, with the
devices in front of you. As with the tariff key, the committed default in
`app/core/config.py` is a real, published key — a deployment on that default
is storing device secrets in effectively-plaintext.

### If you lose `.env.production`

You lose all three at once, which means every consequence above simultaneously
and no way to decrypt existing duress-device secrets. Keep an encrypted copy
somewhere other than this server.
