# OpenTenBase Deployment Guide

This guide defines the supported handoff path for moving SISM from a local
PostgreSQL deployment to an external OpenTenBase endpoint while keeping the
application-side JDBC contract unchanged.

## Scope

Use this guide when:

- the backend must connect to an external OpenTenBase instance
- Flyway migration compatibility must be verified before rollout
- other developers or operators need a repeatable verification path

## Core Principle

SISM continues to use the PostgreSQL wire protocol and JDBC URL shape:

```properties
DB_URL=jdbc:postgresql://<host>:<port>/<database>?stringtype=unspecified&sslmode=disable&connectTimeout=10&socketTimeout=30&tcpKeepAlive=true
```

For OpenTenBase, the application-side driver remains `org.postgresql.Driver`.
The migration risk is therefore not in the frontend and not in JDBC driver
replacement. The risk is in:

- OpenTenBase runtime readiness
- Flyway compatibility
- SQL dialect compatibility inside active migrations and runtime queries

## Fast Verification Flow

### 1. Use the same `.env` file and point it to OpenTenBase

Example:

```properties
DB_URL="jdbc:postgresql://otb-host:5432/strategic?stringtype=unspecified&sslmode=disable&connectTimeout=10&socketTimeout=30&tcpKeepAlive=true"
DB_USERNAME=opentenbase
DB_PASSWORD=change-me
SPRING_PROFILES_ACTIVE=opentenbase
```

For PostgreSQL, keep using the same `.env` file and either clear
`SPRING_PROFILES_ACTIVE` or omit it:

```properties
DB_URL=jdbc:postgresql://localhost:5432/strategic?stringtype=unspecified&sslmode=disable&connectTimeout=10&socketTimeout=30&tcpKeepAlive=true
DB_USERNAME=postgres
DB_PASSWORD=change-me
SPRING_PROFILES_ACTIVE=
```

This keeps database switching inside one configuration file.

### 2. Run endpoint validation

```bash
./database/scripts/validate-opentenbase-connection.sh
```

This checks:

- network connectivity
- login success
- server version visibility
- Flyway `info`
- Flyway `validate`

### 3. Run application startup verification

```bash
mvn -pl sism-main spring-boot:run
```

Expected minimum checks:

- `/api/v1/actuator/health`
- login API
- one write path and one read path against business tables

### 4. Use external DB compose mode for container deployment

```bash
docker compose -f deploy/compose/docker-compose.external-db.yml --env-file deploy/compose/.env up -d
```

The external-db manifest does not start an internal `postgres` container. It
expects `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to already target the
OpenTenBase endpoint.

## Validated Single-Node Startup Recipe

The following sequence was validated on `47.108.249.115` after switching from
the RPM runtime path to a source-built OpenTenBase 5.0 installation:

1. Build OpenTenBase from source branch `v5.0-release_new`.
2. Use compile flags:
   - `-msse4.2 -mcrc32 -DNOLIC`
3. Install under:
   - `/data/opentenbase/install/opentenbase_bin_v5.0`
4. Ensure `opentenbase` can `ssh localhost` without prompts.
5. Ensure `gtm`, `gtm_ctl`, `initgtm`, `postgres`, `initdb`, `pg_ctl`, `psql`
   are resolvable from `PATH`.
6. Start the three single-node roles:
   - GTM on `6666`
   - coordinator on `30004`
   - datanode on `20008`

Validated final port map:

- `6666`: GTM
- `30004`: coordinator
- `20008`: datanode
- `30024`: coordinator forward port
- `20028`: datanode forward port

Validated SQL checks:

- `select version();`
- `select current_database(), current_user;`
- `select node_name, node_type, node_host, node_port from pgxc_node order by node_name;`

## Source-Build-Specific Repair Notes

### 1. GTM CPU affinity hotfix was required

The default GTM startup path could fail on the target cloud host with:

- `binding threads failed for 22`

Validated repair:

- patch `src/gtm/main/main.c`
- downgrade the affinity failure from `elog(ERROR, ...)` to a warning and continue

Without this change, GTM did not stay up long enough for CN/DN to stabilize.

### 2. Low-memory extra configs were still required

Even with the source build, low-memory single-node bootstrap still needed
smaller runtime values for CN/DN, including:

- `max_connections = 10`
- `shared_buffers = 32MB`
- `temp_buffers = 1MB`
- `work_mem = 1MB`
- `maintenance_work_mem = 16MB`
- `max_prepared_transactions = 0`
- `max_worker_processes = 8`
- `max_parallel_workers = 0`
- `max_parallel_workers_per_gather = 0`
- `max_files_per_process = 256`
- `max_wal_senders = 4`
- `max_replication_slots = 4`
- `max_result_cache_memory = 1048576`

### 3. Source build was blocked by zstd/lz4 development artifacts

Observed configure behavior:

- OpenTenBase 5.0 `configure` checks for:
  - `/usr/local/lib/libzstd.a`
  - `/usr/local/lib/liblz4.a`

Validated fix:

- install `libzstd-devel` and `libzstd-static`
- install `lz4` headers and static/shared libs under `/usr/local`

### 4. `pgxc_ctl` single-node mode still depends on localhost SSH

Even in one-host mode, `pgxc_ctl` uses SSH to `localhost`.

Validated requirement:

- `opentenbase` must have self-key access to `localhost`
- host key verification must be pre-accepted

## Common Failure Modes

### 1. `opentenbase_ctl` cannot run after RPM install

Symptom:

- `libssh2.so.1` not found
- or OpenSSL / Kerberos symbol conflicts after forcing `LD_LIBRARY_PATH`

Meaning:

- this is an OpenTenBase runtime packaging issue, not a SISM code issue

Action:

- do not patch SISM for this
- stabilize the OpenTenBase runtime first

### 2. OpenTenBase coordinator starts, but SQL still fails with GTM errors

Symptom:

- listener port comes up
- `select version()` or any simple query fails with:
  - `GTM error, could not obtain global timestamp`

Meaning:

- the instance is not yet a usable SQL endpoint
- a listening port is not enough to treat the target as migration-ready

Action:

- do not point SISM `DB_URL` at this instance yet
- fix the OpenTenBase cluster bootstrap path first
- require at least one successful read query before JDBC/Flyway validation

### 3. `pgxc_ctl` single-node bootstrap fails on `localhost`

Symptom:

- `Host key verification failed`
- `gtm_ctl: command not found`
- `initgtm: command not found`

Meaning:

- the guide's single-node mode still depends on `ssh localhost`
- the `opentenbase` user must be able to SSH to itself without prompts
- helper binaries must be resolvable from the default shell `PATH`

Action:

- create `opentenbase` self-SSH keys and trust `localhost`
- expose `gtm_ctl`, `initgtm`, `pg_ctl`, `psql`, `postgres`, and `pgxc_ctl` in `PATH`
- do not assume a single host means no SSH setup is required

### 4. `pgxc_ctl` guide path works partially, but CN/DN still fail to become usable

Symptom:

- GTM initializes and starts
- CN/DN data directories are created
- CN/DN startup fails later with parameter or bootstrap errors

Observed examples:

- `max_wal_senders must be less than max_connections`
- `Segmentation fault` during datanode init
- `could not open control file "global/pg_control"`

Meaning:

- the guide path is closer to the intended runtime than ad hoc single-process startup
- but the current RPM/runtime still has unresolved single-node bootstrap issues

Action:

- keep low-memory node-specific extra configs under versioned control
- capture the first failing node and first failing stage separately:
  - GTM init
  - GTM start
  - CN init
  - CN start
  - DN init
  - DN start

### 5. OpenTenBase source build can pass `configure` but still miss static codec libs

Symptom:

- `configure: error: zstd library not found.`

Meaning:

- the project probes static codec libs in `/usr/local/lib`
- runtime shared libraries alone are not enough

Action:

- install `libzstd-devel` and `libzstd-static`
- ensure `liblz4.a` and `libzstd.a` exist where the configure script expects them

### 6. Flyway fails but direct SQL login works

Meaning:

- the OpenTenBase instance is reachable
- the incompatibility is now inside active migration SQL or metadata handling

Action:

- capture the first failing migration version
- verify whether the failure is caused by:
  - `plpgsql`
  - `::regclass`
  - `RETURNING`
  - sequence defaults
  - DDL behavior differences

### 7. Application starts locally against PostgreSQL but not against OpenTenBase

Meaning:

- migration or runtime SQL behavior differs under OpenTenBase

Action:

- compare logs around:
  - Flyway startup
  - repository initialization
  - first authenticated API request

## Operator Checklist

Before rollout:

- OpenTenBase endpoint is already stable
- `./database/scripts/validate-opentenbase-connection.sh` passes
- backend startup passes
- health endpoint returns `UP`
- login succeeds
- one business read/write verification succeeds

Do not proceed if:

- the database server still needs manual `LD_LIBRARY_PATH` fixes
- the database port listens but simple SQL still fails with GTM errors
- `pgxc_ctl` still cannot complete a clean GTM + CN + DN startup cycle
- Flyway validate fails
- startup depends on an internal PostgreSQL container that is not part of the target design

## Notes for Future Maintainers

- Keep `DB_URL` PostgreSQL-wire compatible for both PostgreSQL and OpenTenBase.
- Do not hard-code internal `postgres` service assumptions into external-db deployment paths.
- Treat OpenTenBase runtime packaging failures and SISM SQL compatibility failures as separate classes of problems.
- Treat `port is open` and `database is actually usable` as two different gates.
- For guide-based single-node OpenTenBase, treat `localhost SSH readiness` as a required bootstrap dependency.
- For source-built OpenTenBase 5.0 on small cloud hosts, keep the GTM affinity hotfix and low-memory CN/DN configs documented together.
