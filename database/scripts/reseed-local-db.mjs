#!/usr/bin/env node
/**
 * 本地库重灌（种子基线还原）—— 不依赖 psql 客户端
 *
 * 为什么不用 database/scripts/rebuild-local-db.sh：
 *   该脚本要求 psql / dropdb / createdb / pg_dump，而本机 PostgreSQL 只装了
 *   服务端二进制（~/Tools/pg16/bin 只有 initdb/pg_ctl/postgres），没有客户端工具。
 *   本脚本用 node-postgres 直连，把 reset-and-load-clean-seeds.sql 的
 *   `\i` 包含就地展开、去掉 psql 元命令，再整段执行，效果等价。
 *
 * 破坏性：会 TRUNCATE 业务表并重灌种子数据。仅用于本地库。
 *
 * 用法：
 *   node database/scripts/reseed-local-db.mjs [--file reset-and-load-clean-seeds.sql] [--dry-run]
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const HERE = path.dirname(fileURLToPath(import.meta.url))
const SEEDS_DIR = path.resolve(HERE, '..', 'seeds')
const BACKEND_DIR = path.resolve(HERE, '..', '..')

function arg(name, fallback) {
  const idx = process.argv.indexOf(`--${name}`)
  return idx > -1 && process.argv[idx + 1] ? process.argv[idx + 1] : fallback
}

const TARGET = arg('file', 'reset-and-load-clean-seeds.sql')
const DRY_RUN = process.argv.includes('--dry-run')

// ---- 读 .env（DB_URL / DB_USERNAME / DB_PASSWORD） ----
function readEnv() {
  const envPath = path.join(BACKEND_DIR, '.env')
  const out = {}
  if (!fs.existsSync(envPath)) return out
  for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/)
    if (!m) continue
    let value = m[2].trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    out[m[1]] = value
  }
  return out
}

const env = readEnv()
const jdbcUrl = env.DB_URL || 'jdbc:postgresql://localhost:5432/sism?stringtype=unspecified'
const user = env.DB_USERNAME || 'postgres'
const password = env.DB_PASSWORD || 'postgres'

// jdbc:postgresql://host:port/db?params  ->  postgres://user:pass@host:port/db
function toPgUrl(jdbc, pgUser, pgPass) {
  const m = jdbc.match(/^jdbc:postgresql:\/\/([^:/]+):(\d+)\/([^?]+)/)
  if (!m) throw new Error(`无法解析 DB_URL: ${jdbc}`)
  const [, host, port, db] = m
  return `postgresql://${encodeURIComponent(pgUser)}:${encodeURIComponent(pgPass)}@${host}:${port}/${db}`
}

/** 把 `\i file.sql` 就地展开，去掉 psql 元命令 */
function expandSql(file, seen = new Set()) {
  const abs = path.isAbsolute(file) ? file : path.join(SEEDS_DIR, file)
  if (seen.has(abs)) return ''
  seen.add(abs)
  if (!fs.existsSync(abs)) {
    throw new Error(`种子文件不存在: ${abs}`)
  }
  const raw = fs.readFileSync(abs, 'utf8')
  const out = []
  for (const line of raw.split('\n')) {
    const include = line.match(/^\s*\\i\s+(.+?)\s*$/)
    if (include) {
      out.push(`-- >>> include: ${include[1]}`)
      out.push(expandSql(include[1], seen))
      out.push(`-- <<< include: ${include[1]}`)
      continue
    }
    if (/^\s*\\set\b/.test(line)) continue // psql 元命令，node-postgres 不认
    out.push(line)
  }
  return out.join('\n')
}

/** pg 驱动可能只装在 frontend 里，两个位置都找一下 */
function resolvePg() {
  const candidates = [
    path.join(BACKEND_DIR, 'node_modules', 'pg'),
    path.resolve(BACKEND_DIR, '..', 'frontend', 'node_modules', 'pg')
  ]
  for (const c of candidates) {
    if (fs.existsSync(path.join(c, 'package.json'))) return c
  }
  throw new Error('找不到 pg 驱动（试过 backend 与 frontend 的 node_modules）')
}

async function main() {
  const sql = expandSql(TARGET)
  console.log(`[reseed] 目标脚本: ${TARGET}`)
  console.log(`[reseed] 展开后长度: ${sql.length} 字符`)

  if (DRY_RUN) {
    fs.writeFileSync('/tmp/reseed-expanded.sql', sql)
    console.log('[reseed] dry-run：展开结果已写入 /tmp/reseed-expanded.sql，未连接数据库')
    return
  }

  const { Client } = require(resolvePg())
  const client = new Client({ connectionString: toPgUrl(jdbcUrl, user, password) })
  await client.connect()
  console.log(`[reseed] 已连接 ${jdbcUrl.replace(/\/\/[^@]*@/, '//***@')}`)

  try {
    await client.query(sql)
    console.log('[reseed] 重灌完成')

    const { rows } = await client.query(
      `SELECT (SELECT COUNT(*) FROM public.plan) AS plans,
              (SELECT COUNT(*) FROM public.indicator) AS indicators,
              (SELECT COUNT(*) FROM public.sys_user) AS users,
              (SELECT COUNT(*) FROM public.audit_instance) AS audit_instances,
              (SELECT COUNT(*) FROM public.plan_report) AS reports`
    )
    console.log('[reseed] 基线统计:', JSON.stringify(rows[0]))
  } catch (err) {
    console.error('[reseed] 失败:', err.message)
    process.exitCode = 1
  } finally {
    await client.end()
  }
}

main().catch(err => {
  console.error('[reseed] 致命错误:', err.message)
  process.exit(1)
})
