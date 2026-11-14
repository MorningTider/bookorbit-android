/** Bounded UTF-16 chunks accepted by Android TTS, without splitting surrogate pairs. */
export function speechEnd(text, start, limit = 800) {
  let end = Math.min(text.length, start + limit);
  if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--;
  if (end < text.length) {
    const part = text.slice(start, end);
    const boundary = Math.max(...["。", "！", "？", ". ", "! ", "? ", "\n"].map(x => part.lastIndexOf(x)));
    if (boundary >= part.length / 2) end = start + boundary + 1;
  }
  return end;
}

/** Reads from the current visible anchor and advances only after native playback completes. */
export function createReaderSpeech(getView, post, navigationReady = () => Promise.resolve()) {
  let session = null;
  let iterator = null;
  let busy = false;
  let generation = 0;
  let index = 0;
  let doc = null;
  let display = Promise.resolve();
  let position = null;

  function syncDisplay() {
    if (document.hidden || !position) return display;
    const target = position;
    display = display.catch(() => {}).then(async () => {
      if (position !== target || document.hidden) return;
      await getView().goTo(target);
    });
    return display;
  }
  if (typeof document !== "undefined")
    document.addEventListener("visibilitychange", () => { void syncDisplay(); });

  function* chunks(doc, anchor) {
    const walker = doc.createTreeWalker(doc.body, 4);
    const nodes = [];
    for (let node = walker.nextNode(); node; node = walker.nextNode()) nodes.push(node);
    const total = nodes.reduce((sum, node) => sum + node.textContent.length, 0);
    let offset = 0;
    for (const node of nodes) {
      const before = offset;
      offset += node.textContent.length;
      const parent = node.parentElement;
      if (!parent || parent.closest('script,style,noscript,nav,[hidden],[aria-hidden="true"]')) continue;
      const style = doc.defaultView?.getComputedStyle(parent) ?? parent.style;
      if (style.display === "none" || style.visibility === "hidden") continue;
      const text = node.textContent ?? "";
      for (let start = 0; start < text.length;) {
        const end = speechEnd(text, start);
        const range = doc.createRange();
        range.setStart(node, start);
        range.setEnd(node, end);
        start = end;
        if (!range.toString().trim()) continue;
        if (anchor?.startContainer?.ownerDocument === doc &&
            range.compareBoundaryPoints(3, anchor) <= 0) continue;
        // The visible anchor may start in the middle of this text node.
        if (anchor?.startContainer === node && anchor.startOffset > range.startOffset)
          range.setStart(node, Math.min(anchor.startOffset, end));
        if (range.toString().trim()) yield { range, fraction: (before + range.startOffset) / Math.max(1, total) };
      }
    }
  }

  async function next(token) {
    const view = getView();
    if (!doc) {
      await navigationReady();
      if (session !== token) return;
      const content = view?.renderer?.getContents?.()[0];
      if (!content?.doc?.body) throw new Error("speechUnavailable");
      index = content.index;
      const initialDoc = await view.book.sections[index].createDocument();
      if (session !== token) return;
      doc = initialDoc;
      const anchor = view.lastLocation?.cfi ? view.resolveCFI(view.lastLocation.cfi).anchor(doc) : null;
      iterator = chunks(doc, anchor);
    }
    while (session === token) {
      const item = iterator.next();
      if (!item.done) {
        const { range, fraction: inSection } = item.value;
        const cfi = view.getCFI(index, range);
        position = cfi;
        void syncDisplay();
        const fractions = view.getSectionFractions();
        const start = fractions[index] ?? 0;
        const end = fractions[index + 1] ?? 1;
        const toc = view.getProgressOf(index, range)?.tocItem;
        post({ type: "speech", session: token, text: range.toString(), cfi,
          fraction: Math.max(0, Math.min(1, start + inSection * (end - start))),
          language: String(doc.documentElement.lang || view.book.metadata?.language || ""),
          chapter: toc?.label || doc.querySelector("h1,h2,title")?.textContent || "",
          chapterHref: toc?.href || view.book.sections[index].id || "" });
        return;
      }
      let nextIndex = index + 1;
      while (view.book.sections[nextIndex]?.linear === "no") nextIndex++;
      if (!view.book.sections[nextIndex]) {
        const endRange = position ? view.resolveCFI(position).anchor(doc) : null;
        endRange?.collapse(false);
        const cfi = endRange ? view.getCFI(index, endRange) : null;
        if (cfi) { position = cfi; void syncDisplay(); }
        post({ type: "speech", session: token, done: true, cfi, fraction: 1 });
        stop(false);
        return;
      }
      const nextDoc = await view.book.sections[nextIndex].createDocument();
      if (session !== token) return;
      index = nextIndex;
      doc = nextDoc;
      iterator = chunks(doc, null);
    }
  }

  function stop(clearPosition = true) {
    session = null;
    iterator = null;
    doc = null;
    busy = false;
    generation++;
    if (clearPosition) position = null;
  }

  async function command(cmd) {
    if (cmd.action === "stop") { stop(false); return; }
    if (cmd.action === "start") { stop(); session = cmd.session; }
    if (cmd.session !== session || busy) return;
    const token = session;
    const version = generation;
    busy = true;
    try { await next(token); }
    catch { if (session === token) post({ type: "speech", session: token, error: true }); }
    finally { if (generation === version) busy = false; }
  }
  return { command, stop, settleDisplay: () => display };
}
