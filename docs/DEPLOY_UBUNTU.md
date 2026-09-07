# Deploying Cab Dispatch to an Ubuntu server

Docker Compose deploy, reachable by the server's bare IP address (no domain
or HTTPS yet -- see "Adding HTTPS later" at the end for when you're ready).
Four containers: Postgres, Redis, the FastAPI backend, and the dashboard
(built React SPA served by nginx). The backend applies its own database
migrations on every boot (see `backend/entrypoint.sh`) -- no separate manual
migration step.

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

At minimum you MUST replace: `POSTGRES_PASSWORD`, `JWT_SECRET`,
`SECRET_ENCRYPTION_KEY`, `TARIFF_SIGNING_PRIVATE_KEY`, `VITE_API_URL` (set
to `http://<this-server's-public-IP>:8001`), and `CORS_ORIGINS` (include
`http://<this-server's-public-IP>`). The file itself has the exact
generation commands for each secret in its comments. Everything else
(Stripe/Twilio/SendGrid/CabCharge/TTSS) can stay as placeholders -- the app
runs in a clearly-flagged mock-fallback mode for any of those until you add
real credentials.

**This file holds real secrets -- it's gitignored, never commit it.**

## 5. Firewall

```bash
sudo ufw allow OpenSSH
sudo ufw allow 8001/tcp   # backend API
sudo ufw allow 80/tcp     # dashboard
sudo ufw enable
```

## 6. Build and start

```bash
docker compose --env-file .env.production up -d --build
```

First build takes a few minutes (Postgres/Redis images pull, backend/dashboard
build from source). Watch it come up:

```bash
docker compose --env-file .env.production logs -f backend
```

You should see `[entrypoint] running alembic migrations...` then
`[entrypoint] starting uvicorn...` then Uvicorn's "Application startup
complete." Ctrl+C to stop tailing (the containers keep running).

## 7. Seed demo data (optional, for a fresh database)

```bash
# Note: the runtime image's PATH already points at the venv's python
# (/app/.venv/bin) -- no `uv run` prefix needed inside the container,
# unlike local dev commands elsewhere in this repo.
docker compose --env-file .env.production exec backend python scripts/seed.py
```

## 7b. Seed the NSW toll-road registry (NOT optional if you want automatic tolls)

```bash
docker compose --env-file .env.production exec backend python scripts/seed_toll_roads.py
```

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
curl http://localhost:8001/health
# {"status":"ok","env":"production"}

curl -X POST http://localhost:8001/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@cabdispatch.test","password":"ChangeMe123!"}'
# should return an access_token
```

Open `http://<server-ip>/` in a browser -- the dashboard login page should load
and log in with the same demo credentials.

## Day 2: deploying an update

```bash
cd /opt/cabdispatch
git pull
docker compose --env-file .env.production up -d --build
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

## Backups

Postgres data lives in the named volume `cabdispatch_pgdata`; uploaded files
(receipts, compliance docs, duress audio) live in `cabdispatch_uploads`.
A simple periodic dump:

```bash
docker compose --env-file .env.production exec db pg_dump -U cabdispatch cabdispatch > backup-$(date +%F).sql
```

## Adding HTTPS later

You chose bare-IP/HTTP for now. When you have a domain, the simplest path is
adding a reverse proxy in front of both services:

- **Caddy** (recommended, automatic free certs): point `api.yourdomain.com`
  and `admin.yourdomain.com` at this server's IP, install Caddy, and a
  handful of lines in a `Caddyfile` reverse-proxying to `localhost:8001` and
  `localhost:80` gets you HTTPS with zero manual certificate renewal.
- **Cloudflare in front**: point your domain's DNS through Cloudflare
  (proxied/orange-cloud), Cloudflare terminates TLS for you, origin traffic
  to this server can stay plain HTTP.

Either way, once you have a real domain: update `VITE_API_URL` and
`CORS_ORIGINS` in `.env.production` to the real domain(s), rebuild
(`docker compose --env-file .env.production up -d --build`), and you can stop publishing port 8001
directly (only the reverse proxy needs to reach it, on the docker network).