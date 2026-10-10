#!/usr/bin/env node

const fs = require('fs')
const path = require('path')

function main() {
  const reportDirArg = process.argv[2]
  const outputArg = process.argv[3]

  if (!reportDirArg) {
    console.error('用法: node scripts/report-chunks/render-report.js <reportDir> [outputFile]')
    process.exit(1)
  }

  const reportDir = path.resolve(process.cwd(), reportDirArg)
  const manifestPath = path.join(reportDir, 'manifest.json')

  if (!fs.existsSync(manifestPath)) {
    console.error(`manifest 不存在: ${manifestPath}`)
    process.exit(1)
  }

  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
  const merged = manifest.chunks
    .slice()
    .sort((a, b) => a.seq - b.seq)
    .map(chunk => fs.readFileSync(path.join(reportDir, chunk.file), 'utf8').trim())
    .join('\n\n') + '\n'

  if (outputArg) {
    const outputPath = path.resolve(process.cwd(), outputArg)
    fs.mkdirSync(path.dirname(outputPath), { recursive: true })
    fs.writeFileSync(outputPath, merged, 'utf8')
    console.log(`rendered: ${outputPath}`)
    return
  }

  process.stdout.write(merged)
}

main()
