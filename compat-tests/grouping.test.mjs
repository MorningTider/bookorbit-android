import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../app/src/main/assets/reader/foliate/grouping-compat.js', import.meta.url), 'utf8')
const { objectGroupBy, mapGroupBy } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))

test('EPUB namespace grouping and refinement keys match native behavior', () => {
  const nodes = [{ ns:'dc', ref:null }, { ns:'meta', ref:'#title' }, { ns:'dc', ref:null }]
  assert.deepEqual(objectGroupBy(nodes, x => x.ns), Object.groupBy(nodes, x => x.ns))
  assert.deepEqual(mapGroupBy(nodes, x => x.ref), Map.groupBy(nodes, x => x.ref))
})
test('HTMLCollection-like iterable, index, null prototype, and unusual keys', () => {
  const symbol = Symbol('key')
  const values = ['__proto__', 'constructor', symbol, '__proto__']
  const iterable = { *[Symbol.iterator]() { yield* values } }
  assert.deepEqual(objectGroupBy(iterable, x => x), Object.groupBy(values, x => x))
  assert.deepEqual(objectGroupBy(values, (_, i) => i % 2), Object.groupBy(values, (_, i) => i % 2))
})
test('Map grouping preserves object/null keys and value order', () => {
  const key = {}
  const rows = [key, null, key, NaN, NaN]
  assert.deepEqual(mapGroupBy(rows, x => x), Map.groupBy(rows, x => x))
})
test('works when both ES2024 built-ins are missing', () => {
  const objectNative = Object.groupBy, mapNative = Map.groupBy
  try {
    Object.groupBy = undefined; Map.groupBy = undefined
    assert.deepEqual(objectGroupBy([1, 2, 3], x => x % 2)['1'], [1, 3])
    assert.deepEqual(mapGroupBy([1, 2, 3], x => x % 2).get(1), [1, 3])
  } finally {
    Object.groupBy = objectNative; Map.groupBy = mapNative
  }
})
test('reader no longer calls missing built-ins', () => {
  const epub = readFileSync(new URL('../app/src/main/assets/reader/foliate/epub.js', import.meta.url), 'utf8')
  assert.doesNotMatch(epub, /(?:Object|Map)\.groupBy\s*\(/)
  assert.match(epub, /import \{ objectGroupBy, mapGroupBy \} from '\.\/grouping-compat\.js'/)
})
