# Container Stack Deployment

This directory is the only supported deployment manifest set for the SISM
container stack.

## Files

- `docker-compose.yml`: frontend + backend + internal postgres stack
- `docker-compose.external-db.yml`: frontend + backend only, for external
  PostgreSQL/OpenTenBase endpoints
- `.env.example`: required runtime variables
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

### External OpenTenBase / PostgreSQL mode

1. Copy `.env.example` to `.env`
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
  Host-level OpenTenBase or other database experiments are out of scope for
  the automated deployment path.
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
  defaults.
- Low-memory hosts should keep the backend JVM and connection-pool sizing in
  `.env` aligned with the compose defaults unless there is a measured reason to
  increase them.
- If GHCR images remain private, configure server-side `docker login` in
  advance or provide optional GHCR credentials to the workflow. Public images
  need no extra registry secret.
