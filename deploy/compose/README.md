# Container Stack Deployment

This directory is the only supported deployment manifest set for the SISM
container stack.

## Files

- `docker-compose.yml`: frontend + backend + internal postgres stack
- `docker-compose.external-db.yml`: frontend + backend only, for external
  PostgreSQL/OpenTenBase endpoints
- `.env.example`: required runtime variables
- `.env.external.example`: minimal template for host-level PostgreSQL
- `nginx/frontend.conf`: SPA + `/api` reverse proxy config

## Usage

### Internal PostgreSQL mode

1. Copy `.env.example` to `.env`
2. Fill in database credentials, JWT secret, allowed origins, and image tags
3. Keep this mode as the default production path. It assumes the server runs
   only the compose-managed PostgreSQL container and does not depend on any
   host-level OpenTenBase experiment processes.
3. Run:

```bash
docker compose pull
docker compose up -d
docker compose ps
```

### Production replacement runbook

This is the verified path used to replace the old `blackevil.cn` SISM instance
on `175.24.139.148` on 2026-05-27.

Target boundary:

- `blackevil.cn`: SISM frontend and `/api/` reverse proxy target
- `/www/server/panel/vhost/nginx/blackevil.cn.conf`: active nginx site config
- `api.blackevil.cn`: separate API site, do not change for this replacement
- `www.blackevil.cn`: unrelated n8n site, do not change

Deployment boundary:

- Use the compose-managed PostgreSQL container for a fresh database.
- Do not reuse the old host PostgreSQL/OpenTenBase database.
- Let Flyway create and migrate the schema.
- Load clean seed data after the backend is healthy.
- Keep old database files untouched unless a separate cleanup task explicitly
  approves deletion.

Recommended sequence:

1. Back up the old app paths and nginx configs:

```bash
ts="$(date +%Y%m%d-%H%M%S)"
mkdir -p "/root/sism-migration-backup-${ts}"
cp -a /opt/sism/backend "/root/sism-migration-backup-${ts}/backend" 2>/dev/null || true
cp -a /var/www/sism "/root/sism-migration-backup-${ts}/frontend" 2>/dev/null || true
cp -a /www/server/panel/vhost/nginx/blackevil.cn.conf "/root/sism-migration-backup-${ts}/blackevil.cn.conf" 2>/dev/null || true
printf '%s\n' "${ts}" > /root/sism-last-backup-ts
```

2. Build or pull `linux/amd64` images. If building manually from Apple Silicon,
   force `--platform linux/amd64` and verify architecture before upload. See
   `docs/deployment-image-arch-note.md`.

3. Prepare `/opt/sism-stack/production`:

```bash
mkdir -p /opt/sism-stack/production
cp docker-compose.yml /opt/sism-stack/production/docker-compose.yml
cp .env /opt/sism-stack/production/.env
cp -a nginx /opt/sism-stack/production/nginx
```

4. Stop the old backend service before starting the compose backend:

```bash
systemctl stop sism-backend || true
systemctl disable sism-backend || true
```

5. Start the new stack:

```bash
cd /opt/sism-stack/production
docker compose down -v || true
docker compose up -d
docker compose ps
```

6. Copy the full seed bundle, not only helper scripts. The
   `reset-and-load-clean-seeds.sql` script uses relative `\i` includes and must
   run from the seed file directory.

```bash
docker cp /opt/sism-stack/production/seed-bundle production-postgres-1:/tmp/sism-seed-bundle
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" production-postgres-1 \
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f /tmp/sism-seed-bundle/scripts/bootstrap-local-seed-support.sql
docker exec -w /tmp/sism-seed-bundle/seeds -e PGPASSWORD="$POSTGRES_PASSWORD" production-postgres-1 \
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f reset-and-load-clean-seeds.sql
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" production-postgres-1 \
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f /tmp/sism-seed-bundle/scripts/validate-clean-seeds.sql
```

7. Restart backend once after seed loading and verify locally:

```bash
docker compose restart backend
docker compose ps
curl -fsS http://127.0.0.1:${BACKEND_PORT}/api/v1/actuator/health
curl -fsS http://127.0.0.1:${FRONTEND_PORT}/ >/dev/null
```

8. Switch only `blackevil.cn` nginx after local checks pass:

```bash
cp /root/sism-manual-bundle/blackevil.cn.conf /www/server/panel/vhost/nginx/blackevil.cn.conf
nginx -t
bt reload >/dev/null 2>&1 || true
/etc/init.d/nginx reload || true
```

9. Verify public traffic:

```bash
curl -fsS https://blackevil.cn/api/v1/actuator/health
curl -fsS https://blackevil.cn/ | grep -o '<title>[^<]*</title>'
```

10. After verification, remove inactive old payload paths if backups exist:

```bash
rm -rf /opt/sism/backend /var/www/sism
systemctl reset-failed sism-backend || true
```

Expected healthy state:

- `production-backend-1`: healthy, bound to `127.0.0.1:${BACKEND_PORT}`
- `production-frontend-1`: healthy, bound to `127.0.0.1:${FRONTEND_PORT}`
- `production-postgres-1`: healthy, no host port published
- Old `sism-backend.service`: disabled and inactive
- Old host database port, for example `8386`: not listening unless another
  explicitly approved service still needs it

### External OpenTenBase / PostgreSQL mode

1. Copy `.env.external.example` to `.env`
2. Point `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to the external endpoint
3. Keep `POSTGRES_*` values unused or blank in this mode
4. Run:

```bash
docker compose -f docker-compose.external-db.yml pull
docker compose -f docker-compose.external-db.yml up -d
docker compose -f docker-compose.external-db.yml ps
```

## Notes

- Production deployment defaults to the compose-managed PostgreSQL container.
  GitHub Actions `workflow_dispatch` can override this with `db_mode=external`.
- If a server operator manually starts OpenTenBase on the same host for
  experiments, they must stop those processes before running a normal
  production deployment. The automated workflow does not coordinate or resize
  itself around host-level OTB memory usage.
- Empty databases must start from active Flyway `V1` baseline plus `V2+`
  migrations.
- External OpenTenBase deployments must not rely on the internal `postgres`
  container path.
- If `DB_URL`, `DB_USERNAME`, or `DB_PASSWORD` are omitted in external-db mode,
  Docker Compose will substitute empty strings and the backend will fail at startup.
- Archived legacy migrations under `db/migration-archive/` must never be added
  back to runtime Flyway locations.
- The GitHub Actions deployment workflow can bootstrap `/opt/sism-stack/<env>/.env`
  on first deploy. It auto-generates `JWT_SECRET` and `POSTGRES_PASSWORD`, keeps
  them stable on later deploys, and only refreshes image tags plus missing
  defaults. In `db_mode=external`, it also uploads
  `docker-compose.external-db.yml` and skips container seed loading.
- Low-memory hosts should keep the backend JVM and connection-pool sizing in
  `.env` aligned with the compose defaults unless there is a measured reason to
  increase them.
- If GHCR images remain private, configure server-side `docker login` in
  advance or provide optional GHCR credentials to the workflow. Public images
  need no extra registry secret.
