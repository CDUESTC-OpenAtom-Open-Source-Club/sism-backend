# Stress Test Scripts

Extracted from `docs/STRESS_TEST_PLAN.md` (sections 5.2 and 5.3).

## Files

| File | Source | Purpose |
|------|--------|---------|
| `main-test.js` | STRESS_TEST_PLAN.md §5.2 | k6 load test script (login + task/indicator/report workflows) |
| `run.sh` | STRESS_TEST_PLAN.md §5.3 | Wrapper to invoke k6 with the standard stage profile |

## Prerequisites

Install [k6](https://k6.io/):

```bash
# macOS
brew install k6

# Linux
sudo gpg -k && sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

## Dry-run smoke (RECOMMENDED first step)

Validates the script parses and the API URL is reachable, **without** the 500-concurrent ramp:

```bash
# 1. Parse-only: confirms k6 can load the script
k6 inspect scripts/stress/main-test.js

# 2. Tiny smoke (5 vus, 30s) against a running backend
BASE_URL=http://localhost:8080 \
  k6 run --vus 5 --duration 30s scripts/stress/main-test.js
```

## Full load test (production-grade)

Read `docs/STRESS_TEST_PLAN.md` end-to-end first. The full stage profile in `run.sh`
ramps to **500 concurrent VUs** and will saturate a single-node backend. Run only
against a dedicated load environment with the monitoring stack documented in §7.

```bash
./scripts/stress/run.sh
```
