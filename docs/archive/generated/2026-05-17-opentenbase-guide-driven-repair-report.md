# OpenTenBase Guide-Driven Repair Report

Date: 2026-05-17

Scope: use the public "OpenTenBase 5.0 编译与使用指南" single-node cluster
path to repair the earlier RPM-only startup attempts on `47.108.249.115`.

## Summary

The guide materially improved the repair path.

What the guide changed:

- stop treating OpenTenBase as a single bare coordinator
- move to the intended single-node cluster model:
  - `GTM + 1 CN + 1 DN`
  - managed by `pgxc_ctl`

What was repaired successfully:

- extracted the guide's single-node layout and `pgxc_ctl.conf` shape
- reproduced the required directory topology under `/data/opentenbase`
- fixed the hidden localhost SSH dependency for the `opentenbase` user
- exposed required OpenTenBase helper binaries in the default shell `PATH`
- got `pgxc_ctl` to execute the single-node cluster flow
- got GTM initialization and GTM startup working
- got CN and DN data directories initialized
- injected low-memory extra config files into the `pgxc_ctl` path

What still blocks final success:

- CN startup remained sensitive to runtime defaults and startup checks
- DN bootstrap still failed later in the cycle
- the current RPM/runtime path still does not complete a clean usable
  GTM + CN + DN single-node cluster on this server

## Guide-derived facts that mattered

The guide clearly indicated that the intended single-node deployment model is
not:

- one bare `postgres --coordinator`

It is:

- one GTM
- one coordinator
- one datanode
- all managed by `pgxc_ctl`

Key guide-derived configuration shape:

- `gtmMasterServer=localhost`
- `gtmMasterPort=6666`
- `coordNames=(cn001)`
- `coordPorts=(30004)`
- `datanodeNames=(dn001)`
- `datanodePorts=(20008)`

This matched the runtime behavior much better than earlier manual experiments.

## Repairs completed

### 1. Localhost SSH was made usable

The guide flow uses `pgxc_ctl`, and `pgxc_ctl` uses SSH even in single-node
mode.

Repaired:

- generated an `opentenbase` SSH key
- added it to `authorized_keys`
- trusted `localhost`, `127.0.0.1`, and `::1`

Validated:

- `ssh localhost` works for `opentenbase`

### 2. Helper binaries were made reachable

The guide assumes helper programs can be found directly by name.

Repaired by exposing these commands in the default shell path:

- `gtm`
- `gtm_ctl`
- `initgtm`
- `postgres`
- `initdb`
- `pg_ctl`
- `psql`
- `pgxc_ctl`

### 3. `pgxc_ctl` configuration was recreated

A single-node `pgxc_ctl.conf` was created based on the guide's structure and
adapted to the current install root:

- binaries from `/usr/local/install/opentenbase`
- data under `/data/opentenbase/data`

### 4. Low-memory extra config was injected

To avoid the previously observed multi-GB startup footprint, coordinator and
datanode extra config files were added with reduced values including:

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
- `pg_workfile_max_entries = 1024`
- `pg_workfile_limit_per_query = 1048576`
- `pg_workfile_limit_files_per_query = 128`
- `max_result_cache_memory = 1048576`
- `max_wal_senders = 4`
- `max_replication_slots = 4`

## Remaining blockers

### 1. Coordinator path improved, but was still not fully stable

The earlier `max_wal_senders` conflict was reached and then mitigated, which
shows the guide-based path was progressing further than the previous RPM-only
attempts.

### 2. Datanode bootstrap still failed

Observed failure modes during the guide-driven flow:

- `Segmentation fault` during datanode initialization
- `could not open control file "global/pg_control"`
- datanode start failures after partial init

This means the current runtime remains unstable even after the guide path and
memory reductions were applied.

## Final status

### SISM side

Ready.

The repository now contains:

- external-db deployment support
- OpenTenBase endpoint validation script
- runbooks and failure notes

### OpenTenBase runtime side

Partially repaired, not yet production-usable.

The guide helped confirm the correct bootstrap model and removed several false
assumptions, but the runtime still fails before yielding a reliable SQL
endpoint.

## Operational lessons added

1. For OpenTenBase single-node guide deployments, `localhost` SSH is mandatory.
2. `pgxc_ctl` is closer to the intended runtime than manual coordinator-only startup.
3. RPM-installed binaries and guide-based source-install assumptions are not equivalent.
4. Even after GTM works, CN/DN bootstrap can still fail later due runtime defects.
5. A working GTM is necessary but still not sufficient to declare the endpoint migration-ready.
