/** Keeps at most the two neighbouring section resources; ownership moves to the visible reader. */
export function createSectionCache(sections) {
  const entries = new Map();
  let closed = false;

  function release(entry) {
    entry.promise.then((src) => {
      if (src) sections[entry.index].unload?.();
    });
  }

  function take(index) {
    if (closed) return Promise.reject(new Error("Reader is closed"));
    const entry = entries.get(index);
    if (entry) {
      entries.delete(index);
      return entry.promise;
    }
    return Promise.resolve().then(() => sections[index].load());
  }

  function warm(indices) {
    if (closed) return;
    const wanted = new Set(
      indices.filter((index) => sections[index]).slice(0, 2),
    );
    for (const [index, entry] of entries) {
      if (!wanted.has(index)) {
        entries.delete(index);
        release(entry);
      }
    }
    for (const index of wanted) {
      if (entries.has(index)) continue;
      const entry = { index, promise: null };
      entry.promise = Promise.resolve()
        .then(() => sections[index].load())
        .catch(() => {
          if (entries.get(index) === entry) entries.delete(index);
          return null;
        });
      entries.set(index, entry);
    }
  }

  function destroy() {
    closed = true;
    for (const entry of entries.values()) release(entry);
    entries.clear();
  }

  return { take, warm, destroy };
}
