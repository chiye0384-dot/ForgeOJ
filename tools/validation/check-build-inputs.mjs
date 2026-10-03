// Copyright 2026 池也
// SPDX-License-Identifier: Apache-2.0
// Compare executable/test/tool inputs with the fresh Linux copy; documentation can evolve afterward.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile, realpath, writeFile } from 'node:fs/promises'
import { resolve, sep } from 'node:path'

const root = await realpath(process.cwd())
const directory = await realpath(resolve(root, process.argv[2] ?? ''))
assert.ok(directory.toLowerCase().startsWith((resolve(root, 'target') + sep).toLowerCase()), 'Build must be inside repository target')
const manifest = await readFile(resolve(directory, 'source-files.sha256'), 'utf8')
const inputs = []
for (const line of manifest.split(/\r?\n/)) {
  const match = line.match(/^([a-f0-9]{64})  \.\/(.+)$/)
  if (!match) continue
  const [, expected, relative] = match
  if (!/^(pom\.xml$|forgeoj-(api|judge-worker)\/|frontend\/|contracts\/|tools\/validation\/)/.test(relative)) continue
  const path = resolve(root, relative)
  assert.ok(path.toLowerCase().startsWith((root + sep).toLowerCase()), 'Invalid source path')
  const actual = createHash('sha256').update(await readFile(path)).digest('hex')
  assert.equal(actual, expected, `Source changed after Linux copy: ${relative}`)
  inputs.push(relative)
}
assert.ok(inputs.length > 0, 'No build inputs in manifest')
const result = { verifiedAt: new Date().toISOString(), inputs: inputs.length, allMatch: true,
  manifestSha256: createHash('sha256').update(manifest).digest('hex') }
await writeFile(resolve(directory, 'input-check.json'), JSON.stringify(result, null, 2))
console.log(JSON.stringify(result))
