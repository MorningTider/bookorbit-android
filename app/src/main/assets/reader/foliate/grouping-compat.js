// EPUB metadata grouping without the ES2024 Object.groupBy/Map.groupBy APIs.
// Kept local to the reader; does not modify built-in globals.
export function objectGroupBy(items, callback) {
  const groups = Object.create(null)
  let index = 0
  for (const item of items) {
    const rawKey = callback(item, index++)
    const key = typeof rawKey === 'symbol' ? rawKey : String(rawKey)
    if (Object.prototype.hasOwnProperty.call(groups, key)) groups[key].push(item)
    else groups[key] = [item]
  }
  return groups
}

export function mapGroupBy(items, callback) {
  const groups = new Map()
  let index = 0
  for (const item of items) {
    const key = callback(item, index++)
    if (groups.has(key)) groups.get(key).push(item)
    else groups.set(key, [item])
  }
  return groups
}
