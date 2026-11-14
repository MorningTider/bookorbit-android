/** Counts real reflowed screens. Only one hidden section exists at a time. */
export function pagePosition(counts, index, page) {
  const total = counts.reduce((sum, count) => sum + (count ?? 0), 0);
  if (!counts.every((count) => Number.isInteger(count))) return null;
  return {
    current: Math.min(
      total,
      counts.slice(0, index).reduce((a, b) => a + b, 0) + page,
    ),
    total,
  };
}

export function createPageCounter({
  book,
  renderer,
  styles,
  prepare,
  onChange,
  isBusy = () => false,
}) {
  let closed = false;
  let measuring = null;
  const counts = book.sections.map((section) =>
    section.linear === "no" ? 0 : null,
  );
  const run = async () => {
    const rect = renderer.getBoundingClientRect();
    measuring = document.createElement("foliate-paginator");
    for (const attr of renderer.attributes)
      measuring.setAttribute(attr.name, attr.value);
    measuring.setAttribute("no-preload", "");
    measuring.style.cssText = `position:fixed;left:-100000px;top:0;width:${rect.width}px;height:${rect.height}px;visibility:hidden;pointer-events:none;`;
    document.body.append(measuring);
    measuring.prepareDocument = prepare;
    measuring.open(book);
    measuring.setStyles(styles());
    try {
      for (let index = 0; index < counts.length && !closed; index++) {
        if (counts[index] !== null) continue;
        await new Promise((resolve) => setTimeout(resolve, 200));
        if (globalThis.requestIdleCallback)
          await new Promise((resolve) => requestIdleCallback(resolve));
        if (closed) return;
        while ((document.hidden || isBusy()) && !closed)
          await new Promise((resolve) => setTimeout(resolve, 500));
        if (closed) return;
        await measuring.goTo({ index });
        if (!closed) {
          counts[index] = Math.max(1, measuring.pages - 2);
          onChange(counts);
        }
      }
    } catch {
      // Failed speculative layout leaves chapter page numbers available.
    } finally {
      measuring.destroy();
      measuring.remove();
      measuring = null;
    }
  };
  void run();
  return {
    counts,
    destroy() {
      closed = true;
    },
  };
}
