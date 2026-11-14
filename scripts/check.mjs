#!/usr/bin/env node
/* 语法门禁：对所有手写 JS/MJS/CJS 执行 node --check（不依赖任何包）。 */
import { readdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { join, extname } from 'node:path'

const ROOTS = ['gateway', 'plugin', 'tests', 'scripts']
const SKIP = new Set(['node_modules', 'dist', '.git'])
const files = []
function walk(dir) {
  let entries
  try { entries = readdirSync(dir, { withFileTypes: true }) } catch { return }
  for (const entry of entries) {
    if (SKIP.has(entry.name)) continue
    const path = join(dir, entry.name)
    if (entry.isDirectory()) walk(path)
    else if (['.js', '.mjs', '.cjs'].includes(extname(entry.name))) files.push(path)
  }
}
for (const root of ROOTS) walk(root)
if (files.length === 0) { console.error('no script files found'); process.exit(1) }
let failed = 0
for (const file of files.sort()) {
  const r = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' })
  if (r.status !== 0) { failed++; console.error(`FAIL ${file}\n${r.stderr}`) }
}
console.log(`${files.length - failed}/${files.length} files passed node --check`)
process.exit(failed === 0 ? 0 : 1)
