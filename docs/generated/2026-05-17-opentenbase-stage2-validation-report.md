# OpenTenBase Stage 2 Validation Report

Date: 2026-05-17

Scope: continue from Stage 1, push the target environment from package-level
inspection into actual endpoint validation, and determine whether SISM can now
run JDBC and Flyway checks against the OpenTenBase experiment host.

## Summary

Stage 2 did not reach an application-usable OpenTenBase endpoint.

What was achieved:

- SISM repository support was improved for external OpenTenBase deployment.
- A dedicated OpenTenBase validation script was added.
- External database compose mode was added.
- The OpenTenBase coordinator could be tuned down enough to start on the low-memory host.

What still failed:

- OpenTenBase SQL remained unusable due GTM-related runtime failures.
- The packaged OpenTenBase client utilities still showed dynamic library issues.
- End-to-end SISM JDBC/Flyway validation against OpenTenBase could not be completed because the database endpoint never became functionally ready.

## Repository-side progress

The following repository-side assets are now in place:

- `database/scripts/validate-opentenbase-connection.sh`
- `deploy/compose/docker-compose.external-db.yml`
- `docs/opentenbase-deployment-guide.md`

These changes are ready for use once a real OpenTenBase endpoint becomes stable.

## Runtime findings

### 1. Memory tuning breakthrough

The original OpenTenBase coordinator startup attempted to reserve about `4.3GB`
 shared memory and could not start on the target server.

By lowering a set of coordinator/runtime defaults, the coordinator was brought
up successfully on port `15432`.

Important tuned values included:

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
- `max_pool_size = 10`
- `pg_workfile_max_entries = 1024`
- `pg_workfile_limit_per_query = 1048576`
- `pg_workfile_limit_files_per_query = 128`
- `max_result_cache_memory = 1048576`

This is a validated operational finding: default OpenTenBase runtime values were
too large for the small test host.

### 2. Coordinator startup is not enough

Even after the coordinator listened on `15432`, SQL was not usable.

Observed failure:

- simple read query attempts failed with:
  - `GTM error, could not obtain global timestamp`

This means the endpoint was not ready for:

- `psql` smoke tests
- JDBC connectivity verification
- Flyway `info`
- Flyway `validate`

### 3. GTM bootstrap path remains inconsistent

An additional GTM node was initialized successfully with `initgtm`.

However, the next bootstrap step still failed in practice:

- `gtm_ctl` could not find `gtm` through its default path handling
- uncommenting or appending `gtm_host` / `gtm_port` in `postgresql.conf`
  caused:
  - `unrecognized configuration parameter "gtm_host"`
  - `unrecognized configuration parameter "gtm_port"`

This is important because the sample configuration comments still mention these
settings, but the tested runtime did not accept them.

### 4. Packaged client/runtime problems remain

The RPM still showed multiple runtime packaging problems:

- `opentenbase_ctl` missing bundled library resolution by default
- bundled client tools mixed badly with system libraries
- packaged `psql` could fail with:
  - `undefined symbol: PQSqlMode`
  - or OpenSSL / Kerberos symbol conflicts

These were product/runtime packaging issues, not SISM application issues.

## Final validation status

### SISM repository readiness

Status: ready

- application-side config and deployment support for an external
  PostgreSQL-wire endpoint is now in place

### OpenTenBase endpoint readiness

Status: not ready

- port could be opened
- SQL could not be used reliably
- GTM path was still unresolved
- Flyway/JDBC validation could not be completed against this host

## Blocking issues to carry forward

1. OpenTenBase runtime packaging still has unresolved library-path problems.
2. OpenTenBase minimum bootstrap for a usable single-node SQL endpoint is still
   unresolved on this release path.
3. The tested configuration comments and the accepted runtime parameters were
   inconsistent for GTM-related settings.

## Recommended next step

Do not continue SISM-side debugging until one of the following is true:

- a vendor-validated OpenTenBase bootstrap path is available for this RPM line
- or a different OpenTenBase deployment method produces a usable SQL endpoint

Only after a successful read query on the target endpoint should the following
SISM checks be retried:

- `./database/scripts/validate-opentenbase-connection.sh`
- `mvn -pl sism-main spring-boot:run`

## Operational lessons

- A listening OpenTenBase port is not proof of a usable database.
- GTM readiness is a hard gate, not an optional refinement.
- For low-memory test hosts, runtime parameter reduction must happen before any
  meaningful validation.
- Product packaging failures and SISM SQL compatibility failures must be
  tracked separately.
