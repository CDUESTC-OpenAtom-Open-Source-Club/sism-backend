#!/usr/bin/env node

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'))
}

function sha256(content) {
  return crypto.createHash('sha256').update(content, 'utf8').digest('hex')
}

function normalizeWhitespace(text) {
  return text.replace(/\s+/g, ' ').trim()
}

function extractPreview(content, maxLength = 140) {
  const plain = normalizeWhitespace(
    content
      .replace(/^#+\s+/gm, '')
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/`([^`]+)`/g, '$1')
  )
  return plain.length <= maxLength ? plain : `${plain.slice(0, maxLength - 1)}…`
}

function extractKeywords(content, fallbackTitle, headings = [], metadataTags = []) {
  const codeTokens = (content.match(/`([^`]+)`/g) || [])
    .map(item => item.replace(/`/g, '').trim())
    .filter(Boolean)

  const merged = [fallbackTitle, ...headings, ...metadataTags, ...codeTokens]
    .map(item => item.trim())
    .filter(Boolean)

  const seen = new Set()
  return merged.filter(item => {
    const key = item.toLowerCase()
    if (seen.has(key)) {
      return false
    }
    seen.add(key)
    return true
  }).slice(0, 16)
}

function ensureReportDirectory(reportDir) {
  if (!fs.existsSync(reportDir) || !fs.statSync(reportDir).isDirectory()) {
    throw new Error(`报告目录不存在: ${reportDir}`)
  }
}

function collectChunks(reportDir, metadata) {
  const chunksDir = path.join(reportDir, 'chunks')
  if (!fs.existsSync(chunksDir) || !fs.statSync(chunksDir).isDirectory()) {
    throw new Error(`chunks 目录不存在: ${chunksDir}`)
  }

  const files = fs.readdirSync(chunksDir)
    .filter(file => /^\d{4}-.+\.md$/.test(file))
    .sort((a, b) => a.localeCompare(b, 'en'))

  if (files.length === 0) {
    throw new Error(`未找到任何分片文件: ${chunksDir}`)
  }

  return files.map(file => {
    const absolutePath = path.join(chunksDir, file)
    const content = fs.readFileSync(absolutePath, 'utf8')
    const lines = content.split(/\r?\n/)
    const titleLine = lines.find(line => /^(##|#)\s+/.test(line))
    const title = titleLine ? titleLine.replace(/^(##|#)\s+/, '').trim() : path.basename(file, '.md')
    const headings = lines
      .filter(line => /^(##|###)\s+/.test(line))
      .map(line => line.replace(/^(##|###)\s+/, '').trim())
      .slice(0, 12)
    const seq = Number(file.slice(0, 4))

    return {
      seq,
      file: `chunks/${file}`,
      title,
      headings,
      preview: extractPreview(content),
      byteSize: Buffer.byteLength(content, 'utf8'),
      sha256: sha256(content),
      keywords: extractKeywords(content, title, headings, metadata.tags || [])
    }
  })
}

function buildManifest(reportDir) {
  ensureReportDirectory(reportDir)

  const metadataPath = path.join(reportDir, 'report.meta.json')
  if (!fs.existsSync(metadataPath)) {
    throw new Error(`缺少元数据文件: ${metadataPath}`)
  }

  const metadata = readJson(metadataPath)
  const chunks = collectChunks(reportDir, metadata)
  const totalBytes = chunks.reduce((sum, chunk) => sum + chunk.byteSize, 0)

  const manifest = {
    reportId: metadata.reportId,
    title: metadata.title,
    module: metadata.module,
    category: metadata.category || 'performance-report',
    createdAt: metadata.createdAt,
    source: metadata.source,
    storage: {
      strategy: 'ordered-chunks',
      chunkCount: chunks.length,
      totalBytes,
      namingPattern: 'chunks/NNNN-slug.md',
      orderingKey: 'seq',
      renderCommand: `node scripts/report-chunks/render-report.js ${path.relative(process.cwd(), reportDir)}`
    },
    retrieval: {
      tags: metadata.tags || [],
      keywords: Array.from(new Set(chunks.flatMap(chunk => chunk.keywords))).slice(0, 32),
      lastUpdatedAt: new Date().toISOString()
    },
    chunks
  }

  const outputPath = path.join(reportDir, 'manifest.json')
  fs.writeFileSync(outputPath, JSON.stringify(manifest, null, 2) + '\n', 'utf8')
  return outputPath
}

function main() {
  const reportDirArg = process.argv[2]
  if (!reportDirArg) {
    console.error('用法: node scripts/report-chunks/build-manifest.js <reportDir>')
    process.exit(1)
  }

  const reportDir = path.resolve(process.cwd(), reportDirArg)
  const outputPath = buildManifest(reportDir)
  console.log(`manifest generated: ${outputPath}`)
}

main()
