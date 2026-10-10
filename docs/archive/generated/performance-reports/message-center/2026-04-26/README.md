# 消息中心模块性能排查报告（分片存储）

本目录保存的是**按顺序分片**存储的后端性能排查报告，而不是整篇重复副本。

## 目录说明

- `report.meta.json`：报告元数据，定义模块、标题、标签和时间。
- `chunks/`：正文分片，使用 `0001-*.md` 这样的固定序号命名。
- `manifest.json`：由脚本生成的可检索清单，包含顺序、摘要、关键词、字节大小和哈希。

## 使用方式

- **生成清单**：`node scripts/report-chunks/build-manifest.js docs/generated/performance-reports/message-center/2026-04-26`
- **重建全文到终端**：`node scripts/report-chunks/render-report.js docs/generated/performance-reports/message-center/2026-04-26`
- **重建全文到文件**：`node scripts/report-chunks/render-report.js docs/generated/performance-reports/message-center/2026-04-26 /tmp/message-center-report.md`

## 存储特性

- **有序**：依赖文件名前缀和 `manifest.json` 的 `seq` 字段双重保证。
- **可检索**：通过标签、章节标题、关键词和预览摘要定位目标分片。
- **省空间**：只保存分片内容，不额外保留完整拼接副本；完整报告在需要时再动态重建。
