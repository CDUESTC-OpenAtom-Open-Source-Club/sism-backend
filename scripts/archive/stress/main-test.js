import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// 自定义指标
const errorRate = new Rate('errors');
const taskCreateDuration = new Trend('task_create_duration');
const searchDuration = new Trend('search_duration');

// 测试配置
export const options = {
  scenarios: {
    // 认证场景 - 恒定速率
    auth_scenario: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 20,
      maxVUs: 50,
      exec: 'authScenario',
    },
    // 任务场景 - 分阶段递增
    task_scenario: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 40 },   // 升温
        { duration: '3m', target: 125 },  // 达到目标
        { duration: '5m', target: 125 },  // 持续负载
        { duration: '1m', target: 0 },    // 冷却
      ],
      exec: 'taskScenario',
    },
    // 指标场景 - 分阶段递增
    indicator_scenario: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 30 },
        { duration: '3m', target: 100 },
        { duration: '5m', target: 100 },
        { duration: '1m', target: 0 },
      ],
      exec: 'indicatorScenario',
    },
    // 计划场景
    plan_scenario: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 25 },
        { duration: '3m', target: 75 },
        { duration: '5m', target: 75 },
        { duration: '1m', target: 0 },
      ],
      exec: 'planScenario',
    },
    // 报告场景
    report_scenario: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 25 },
        { duration: '3m', target: 75 },
        { duration: '5m', target: 75 },
        { duration: '1m', target: 0 },
      ],
      exec: 'reportScenario',
    },
    // 浏览场景 - 恒定并发
    browse_scenario: {
      executor: 'constant-vus',
      vus: 50,
      duration: '10m',
      exec: 'browseScenario',
    },
  },

  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.01'],
    errors: ['rate<0.01'],
    task_create_duration: ['p(95)<800'],
    search_duration: ['p(95)<600'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 测试账号池
const TEST_ACCOUNTS = [
  { username: 'admin', password: 'admin123', role: 'VICE_PRESIDENT' },
  { username: 'zlb_admin', password: 'admin123', role: 'STRATEGY_DEPT_HEAD' },
  { username: 'zlb_final1', password: 'admin123', role: 'REPORTER' },
  { username: 'jiaowu_report', password: 'admin123', role: 'FUNC_REPORTER' },
  { username: 'jiaowu_audit1', password: 'admin123', role: 'FUNC_APPROVER' },
  { username: 'jisuanji_report', password: 'admin123', role: 'COLLEGE_REPORTER' },
  { username: 'jisuanji_audit1', password: 'admin123', role: 'COLLEGE_APPROVER' },
];

function getToken(account) {
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: account.username, password: account.password }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(loginRes, {
    'login successful': (r) => r.status === 200,
  });

  if (loginRes.status === 200) {
    const body = JSON.parse(loginRes.body);
    return body.data?.token || body.token;
  }
  return null;
}

function authHeaders(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
  };
}

// ==================== 场景实现 ====================

export function authScenario() {
  const account = TEST_ACCOUNTS[__VU % TEST_ACCOUNTS.length];

  group('Auth Flow', () => {
    // 登录
    const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ username: account.username, password: account.password }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    errorRate.add(loginRes.status !== 200);
    check(loginRes, { 'auth login 200': (r) => r.status === 200 });

    if (loginRes.status === 200) {
      const body = JSON.parse(loginRes.body);
      const token = body.data?.token || body.token;

      // 获取当前用户
      const meRes = http.get(`${BASE_URL}/api/v1/auth/me`, authHeaders(token));
      errorRate.add(meRes.status !== 200);
      check(meRes, { 'auth me 200': (r) => r.status === 200 });

      sleep(1);

      // 刷新 Token
      const refreshRes = http.post(`${BASE_URL}/api/v1/auth/refresh`,
        JSON.stringify({ token: token }),
        authHeaders(token)
      );
      errorRate.add(refreshRes.status !== 200);
    }
  });

  sleep(Math.random() * 2 + 1);
}

export function taskScenario() {
  const account = TEST_ACCOUNTS[__VU % TEST_ACCOUNTS.length];
  const token = getToken(account);
  if (!token) return;

  const headers = authHeaders(token);

  group('Task Operations', () => {
    // 搜索任务 (高频操作)
    const searchStart = Date.now();
    const searchRes = http.get(`${BASE_URL}/api/v1/tasks/search?page=0&size=10`, headers);
    searchDuration.add(Date.now() - searchStart);
    errorRate.add(searchRes.status !== 200);
    check(searchRes, { 'task search 200': (r) => r.status === 200 });

    sleep(0.5);

    // 获取所有任务
    const listRes = http.get(`${BASE_URL}/api/v1/tasks`, headers);
    errorRate.add(listRes.status !== 200);
    check(listRes, { 'task list 200': (r) => r.status === 200 });

    sleep(0.5);

    // 如果是 REPORTER 角色，创建任务
    if (account.role === 'STRATEGY_DEPT_HEAD' || account.role === 'REPORTER') {
      const createStart = Date.now();
      const createRes = http.post(`${BASE_URL}/api/v1/tasks`,
        JSON.stringify({
          name: `压力测试任务-${Date.now()}-${__VU}`,
          taskType: 'BASIC',
          planId: 1,
          cycleId: 1,
          orgId: 36,
          createdByOrgId: 35,
          desc: '压力测试自动创建的任务',
        }),
        headers
      );
      taskCreateDuration.add(Date.now() - createStart);
      errorRate.add(createRes.status !== 200);
      check(createRes, { 'task create 200': (r) => r.status === 200 });

      // 如果创建成功，获取详情
      if (createRes.status === 200) {
        const body = JSON.parse(createRes.body);
        const taskId = body.data?.id;
        if (taskId) {
          sleep(0.3);
          const detailRes = http.get(`${BASE_URL}/api/v1/tasks/${taskId}`, headers);
          errorRate.add(detailRes.status !== 200);
          check(detailRes, { 'task detail 200': (r) => r.status === 200 });
        }
      }
    }

    sleep(Math.random() * 2 + 1);
  });
}

export function indicatorScenario() {
  const account = TEST_ACCOUNTS[__VU % TEST_ACCOUNTS.length];
  const token = getToken(account);
  if (!token) return;

  const headers = authHeaders(token);

  group('Indicator Operations', () => {
    // 搜索指标
    const searchRes = http.get(`${BASE_URL}/api/v1/indicators/search?page=0&size=10`, headers);
    errorRate.add(searchRes.status !== 200);
    check(searchRes, { 'indicator search 200': (r) => r.status === 200 });

    sleep(0.5);

    // 获取指标列表
    const listRes = http.get(`${BASE_URL}/api/v1/indicators`, headers);
    errorRate.add(listRes.status !== 200);
    check(listRes, { 'indicator list 200': (r) => r.status === 200 });

    sleep(Math.random() * 2 + 1);
  });
}

export function planScenario() {
  const account = TEST_ACCOUNTS[__VU % TEST_ACCOUNTS.length];
  const token = getToken(account);
  if (!token) return;

  const headers = authHeaders(token);

  group('Plan Operations', () => {
    // 获取计划列表
    const listRes = http.get(`${BASE_URL}/api/v1/plans`, headers);
    errorRate.add(listRes.status !== 200);
    check(listRes, { 'plan list 200': (r) => r.status === 200 });

    sleep(0.5);

    // 按周期查询计划
    const cycleRes = http.get(`${BASE_URL}/api/v1/plans/cycle/1`, headers);
    errorRate.add(cycleRes.status !== 200);
    check(cycleRes, { 'plan by cycle 200': (r) => r.status === 200 });

    sleep(Math.random() * 2 + 1);
  });
}

export function reportScenario() {
  const account = TEST_ACCOUNTS[__VU % TEST_ACCOUNTS.length];
  const token = getToken(account);
  if (!token) return;

  const headers = authHeaders(token);

  group('Report Operations', () => {
    // 搜索报告
    const searchRes = http.get(`${BASE_URL}/api/v1/reports/search?page=0&size=10`, headers);
    errorRate.add(searchRes.status !== 200);
    check(searchRes, { 'report search 200': (r) => r.status === 200 });

    sleep(0.5);

    // 获取待审批报告
    const pendingRes = http.get(`${BASE_URL}/api/v1/reports/pending`, headers);
    errorRate.add(pendingRes.status !== 200);
    check(pendingRes, { 'report pending 200': (r) => r.status === 200 });

    sleep(Math.random() * 2 + 1);
  });
}

export function browseScenario() {
  const account = TEST_ACCOUNTS[__VU % TEST_ACCOUNTS.length];
  const token = getToken(account);
  if (!token) return;

  const headers = authHeaders(token);

  group('Browse Operations', () => {
    // 仪表盘
    const dashRes = http.get(`${BASE_URL}/api/v1/dashboard`, headers);
    errorRate.add(dashRes.status !== 200);
    check(dashRes, { 'dashboard 200': (r) => r.status === 200 });

    sleep(1);

    // 组织列表
    const orgRes = http.get(`${BASE_URL}/api/v1/organizations`, headers);
    errorRate.add(orgRes.status !== 200);
    check(orgRes, { 'organizations 200': (r) => r.status === 200 });

    sleep(1);

    // 周期列表
    const cycleRes = http.get(`${BASE_URL}/api/v1/cycles`, headers);
    errorRate.add(cycleRes.status !== 200);
    check(cycleRes, { 'cycles 200': (r) => r.status === 200 });

    sleep(1);

    // 我的通知
    const notifRes = http.get(`${BASE_URL}/api/v1/notifications/my`, headers);
    errorRate.add(notifRes.status !== 200);
    check(notifRes, { 'notifications 200': (r) => r.status === 200 });

    sleep(Math.random() * 3 + 2);
  });
}
