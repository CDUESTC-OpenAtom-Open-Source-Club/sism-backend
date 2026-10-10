#!/bin/bash
set -euo pipefail

# 配置
BASE_URL="${BASE_URL:-http://localhost:8080}"
RESULTS_DIR="./results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

mkdir -p "${RESULTS_DIR}"

echo "============================================"
echo "SISM API Stress Test"
echo "Target: ${BASE_URL}"
echo "Time: ${TIMESTAMP}"
echo "============================================"

# 运行测试
k6 run \
  --env BASE_URL="${BASE_URL}" \
  --out json="${RESULTS_DIR}/raw-${TIMESTAMP}.json" \
  --summary-export="${RESULTS_DIR}/summary-${TIMESTAMP}.json" \
  main-test.js 2>&1 | tee "${RESULTS_DIR}/output-${TIMESTAMP}.log"

echo ""
echo "Test completed. Results saved to ${RESULTS_DIR}/"
echo "  - Raw data: raw-${TIMESTAMP}.json"
echo "  - Summary:  summary-${TIMESTAMP}.json"
echo "  - Log:      output-${TIMESTAMP}.log"
