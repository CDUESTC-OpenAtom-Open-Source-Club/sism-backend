# SISM 部署下载/落服规范与排错手册（2026-11-15 定版 · 2026-11-15 修订归因）

> 本手册依据 2026-11-15 v1.4.1 部署的**实测数据**写成，含踩坑复现与验证方法。

---

## 一、根因（实测，不是推测）

**现象**：本机下载 GitHub Actions artifact（158MB）时，进度长期停在 0 字节，被误判为"脚本卡死"。

**实测证据链**：
1. 同一台机、同一 URL、同一命令连测 3 次 → 速度 **9.4 KB/s → 6.4 KB/s → 5.7 MB/s**（**速率剧烈跳变**）；
2. 走本地代理 10808 反而更慢（3.3 MB/s vs 直连 6.7 MB/s），**代理不是解药**；
3. 用 `--continue-at -`（断点续传）分 4 轮拉完 165MB：每轮分别下 **134MB → 260KB → 277KB → 补完**，总时长正常；
4. 服务器侧 `docker pull` 同一镜像 **21 秒**（且二次拉取 **3.2 秒**，层缓存命中）。

**结论**：
- 本机到 GitHub 的链路**存在间歇性劣化**（拥塞/丢包重传），**速率不可预测**，不是配置错、不是脚本 bug、也不是"境内拉境外天生慢"；
- **正确解法 = 断点续传 + 重试 + 超时降级**，而不是换路径；
- 服务器快是因为**腾讯云内网 registry-mirror**（`/etc/docker/daemon.json` 配了 `mirror.ccs.tencentyun.com`，从腾讯云服务器走内网）+ **镜像层缓存**（已缓存 135 镜像/6.5GB，新旧前端镜像共享基础层，只需下增量层）。**与代理无关**（docker daemon 的 Environment 为空，未使用 xray）。

---

## 二、路径选择（主线 = 原始设计：本地拉 → 上传服务器）

| 环节 | 实测速度 | 性质 |
|---|---|---|
| 本地 → GitHub artifact 下载 | **0.006 ~ 5.7 MB/s 剧烈跳变**（劣化时必须断点续传，见 §四） | **唯一不稳定环节** |
| 本地 → 服务器上传（境内） | **~2.2 MB/s 稳定**（10MB/4.8s 含建连） | 稳 |
| 服务器 → ghcr.io 直拉 | 21 秒（内网 registry-mirror + 层缓存） | 稳 |

| 路径 | 适用 | 推荐 |
|---|---|---|
| **A. 离线包：GitHub 构建 → 本地拉(断点续传) → upload+install** | **主线（原始设计）**。服务器不需连 GHCR，落服物是本地校验过 SHA256 的确定产物 | ⭐⭐⭐⭐⭐ |
| B. 服务器直拉 GHCR（`deploy-via-ghcr.sh`） | **降级**：服务器不能出网 / 本地链路持续劣化 / 只想快速更新单一服务 | ⭐⭐⭐⭐ |

**为什么主线仍是 A**：服务器在境内，**上传是稳定环节**；本地下载虽是唯一不稳的一环，但断点续传已能可靠解决；
且 A 的落服物是**本地 SHA256 校验过的确定文件**，B 是服务器自己拉的（多一层"拉的到底对不对"的间接性）。

**A 的执行**：download-offline-release.sh（**必须按 §四 断点续传范式**）→ upload-offline-release.sh（**内嵌 install**，一步完成部署）。
**B 的执行**：`deploy-via-ghcr.sh --frontend-sha <40位SHA> [--backend-sha <SHA>]`
（内含：服务器 pull 带超时 → 备份并改 `.env` 镜像行 → `compose up -d --no-deps` 不动 postgres → 等 healthy → 域名+登录冒烟）

---

## 三、标准流程（路径 A）

```
0. 立回滚点（四件套，缺一不可）：
   ① cp -a .env .env.bak-<ts>          ② 当前镜像 SHA 写入 /root/ROLLBACK-<ts>.txt
   ③ keep 副本：cp -a /opt/sism-releases/<当前包> /opt/sism-releases/keep-<ts>
   ④ pg_dump | gzip > /root/sism-prod-backup-<ts>.sql.gz 并验证非零字节
   ⚠ 发布期间禁止 docker image prune（旧镜像是回滚第一路）

1. 推 main → 等 build-and-push-image.yml SUCCESS → 拿到完整 40 位 SHA
2. ./deploy-via-ghcr.sh --frontend-sha <SHA> [--backend-sha <SHA>]
3. 验证：容器 healthy + 域名 200 + 登录 200 + 关键交互抽查
4. 打 tag + GitHub Release
```

---

## 四、本地下载规范（路径 B 必读）

**必须用断点续传**，因为本机链路速率会跳变到 KB/s 级：

```bash
# 单次 curl 的 retry 不够（--retry 遇超时可能整体重来），
# 必须「外层循环 + --continue-at - 续传」，并用完整性校验收口：
ART=<artifact_id>; TOKEN=$(gh auth token)
for i in $(seq 1 20); do
  timeout 25 curl --fail --location --silent --show-error \
    --connect-timeout 8 --max-time 24 --continue-at - \
    --header "Accept: application/vnd.github+json" \
    --header "Authorization: Bearer ${TOKEN}" \
    -o /tmp/artifact.zip \
    "https://api.github.com/repos/<repo>/actions/artifacts/${ART}/zip"
  if unzip -tq /tmp/artifact.zip >/dev/null 2>&1; then echo "OK"; break; fi
  echo "轮次 $i: 已下 $(stat -f%z /tmp/artifact.zip 2>/dev/null) 字节，继续续传"
done
```

**要点**：
- `--continue-at -` 是关键；**不加它，速率一劣化就前功尽弃**（这正是本次卡死 20 分钟的原因）；
- 用 `unzip -tq` 做**完整性校验**再收口（不能只看字节数）；
- **不要用旧代理端口**（7897 等已废弃，导出反而把请求导进死端口 → EOF/SSL_ERROR_SYSCALL）；本机直连通常比走代理更快；
- `gh auth token` 取 token（不设任何代理环境变量）。

---

## 五、排错手册（照症状查）

### 症状 1：下载长时间 0 字节 / 进度不动
- **先测速率**：`curl -w "%{speed_download} %{size_download}"` 单测一次（20 秒即可）；
- 速率 KB/s 级 = 命中链路劣化 → **切断点续传循环**（§四），不要干等、也不要改用代理；
- 速率正常但脚本不动 → 脚本自身问题（看是否有 `--silent` 掩盖进度）。

### 症状 2：走代理反而更慢
- 实测本机代理 10808 比直连慢一倍。**默认直连**；确需走代理时先实测对比再定。

### 症状 3：`gh` / `git` 报 EOF、SSL_ERROR_SYSCALL
- **不要 export 旧代理端口**；直接直连，`gh api rate_limit` 探活（正常 <2 秒）。
- 仍不通再考虑代理软件本身状态。

### 症状 4：容器不 healthy
- `docker compose logs backend --since 2m | grep -E "Started|ERROR|Migrating"`；
- Flyway 失败 → 查 `flyway_schema_history` 尾部版本与校验和；
- 服务器仅 3.6G 内存，**不要同时起多个栈**。

### 症状 5：登录 403 / 无法连接后端
- 生产 `ALLOWED_ORIGINS=https://sism.blackevil.cn,https://blackevil.cn`；
- 后端容器必须透传 `X-Request-ID`（前端每请求都带）→ 检查 compose 的 `--app.cors.allowed-headers` 含 `X-Request-ID,x-request-id`。

### 症状 6：服务器 docker pull 慢
- 检查 `/etc/docker/daemon.json` 的 registry-mirrors 是否含 `mirror.ccs.tencentyun.com`（腾讯云服务器的内网加速，秒级）；
- 二次拉取应是 3 秒级（层缓存）；若很慢，检查是否 `docker image prune` 清过缓存。

---

## 六、每次执行前 30 秒自检

```bash
# ① 路径 A 可用性（服务器能否拉 ghcr）
ssh root@175.24.139.148 'docker pull ghcr.io/cduestc-openatom-open-source-club/strategic-task-management:main 2>&1 | tail -1'
# ② 本机链路速率（决定路径 B 是否要走续传）
curl -s -o /dev/null --max-time 15 -w "speed=%{speed_download}B/s\n" \
  -H "Authorization: Bearer $(gh auth token)" \
  "https://api.github.com/repos/<repo>/actions/artifacts/<id>/zip"
```

---

## 七、已知约束备忘

- 本机 → GitHub **速率会间歇劣化到 KB/s**（实测 9KB/s ↔ 5.7MB/s 跳变）→ **下载一律断点续传**；
- 服务器 → ghcr **21 秒**（内网 registry-mirror + 层缓存）；
- `upload-offline-release.sh` **内嵌 install**，输出 `OFFLINE_DEPLOY_SUCCESS backend=<sha> frontend=<sha>` 即成功；
- **重建 jar 前必须先停 8080**（运行中进程读嵌套 jar → NoClassDefFoundError → 500）；
- **`.env` 的 DB_URL 静默压过外部环境变量**（`EnvConfigPostProcessor` 用 addLast）→ 要连非默认库必须改 `.env`；启动后 grep 日志确认 `Database: jdbc:postgresql://...`；
- 生产与 demo-lean **镜像行各自独立**（升生产不自动升 demo-lean）；
- 服务器上的 `xray`（127.0.0.1:10808，9 月 23 日起的常驻进程）与 docker pull **无关**（daemon 未配代理环境变量）。
