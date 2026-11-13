/** Plain-text adapter for the same Foliate layout, font, CFI and annotation pipeline as EPUB. */
// Keep this structure aligned with server/src/modules/metadata/lib/txt-to-epub.ts
// and TxtReaderCacheService's 100000-character sections. CFI anchors then round-trip.
const SECTION_LIMIT = 100_000;
const MAX_LINE = 1_048_576;
const HEADING =
  /^(?:第[零〇一二三四五六七八九十百千万两\d]+[章节卷回部篇].*|(?:序章|序言|楔子|前言|后记|尾声|番外(?:篇)?)(?:[\s:：].*)?)$|^chapter\s+\d+(?:\s*[:.-].*|\s+.*)?$/i;

export function detectTextEncoding(bytes) {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return "utf-16le";
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return "utf-16be";
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf)
    return "utf-8";
  let oddZeros = 0;
  let evenZeros = 0;
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] === 0) {
      if (i % 2) oddZeros++;
      else evenZeros++;
    }
  }
  if (oddZeros > bytes.length / 5 && evenZeros < oddZeros / 4)
    return "utf-16le";
  if (evenZeros > bytes.length / 5 && oddZeros < evenZeros / 4)
    return "utf-16be";
  try {
    new TextDecoder("utf-8", { fatal: true }).decode(bytes, { stream: true });
    return "utf-8";
  } catch {
    return "gb18030";
  }
}

function xml(text) {
  return text.replace(
    /[<>&"']/g,
    (c) =>
      ({
        "<": "&lt;",
        ">": "&gt;",
        "&": "&amp;",
        '"': "&quot;",
        "'": "&apos;",
      })[c],
  );
}

export async function readTextSections(blob, title = "TXT") {
  if (!blob.size || blob.size > 128 * 1024 * 1024) throw new Error("TXT_SIZE");
  const sample = new Uint8Array(await blob.slice(0, 65_536).arrayBuffer());
  let encoding = detectTextEncoding(sample);
  const hasUtf8Bom =
    sample[0] === 0xef && sample[1] === 0xbb && sample[2] === 0xbf;
  if (encoding.startsWith("utf-16") && blob.size % 2)
    throw new Error("TXT_ENCODING");
  async function decode(encoding) {
    const decoder = new TextDecoder(encoding, { fatal: encoding === "utf-8" });
    const reader = blob.stream().getReader();
    const sections = [];
    let heading = title;
    let headingTitle = title;
    let continuation = 1;
    let paragraphs = [];
    let length = 0;
    let pending = "";
    let characters = 0;
    function flush() {
      sections.push({ title: heading, paragraphs, characters: length });
      if (sections.length >= 10_000) throw new Error("TXT_SECTIONS");
      paragraphs = [];
      length = 0;
    }
    function line(text) {
      if (text.length > MAX_LINE) throw new Error("TXT_LINE");
      if (text.includes("\0")) throw new Error("TXT_ENCODING");
      text = text.trim();
      if (!text) return;
      characters += Array.from(text).length;
      if (text.length <= 100 && HEADING.test(text)) {
        flush();
        heading = headingTitle = text;
        continuation = 1;
        return;
      }
      while (text.length) {
        let end = Math.min(text.length, SECTION_LIMIT - length);
        if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1] ?? ""))
          end--;
        if (end <= 0) {
          flush();
          heading = `${headingTitle} (${++continuation})`;
          continue;
        }
        const chunk = text.slice(0, end);
        text = text.slice(end);
        paragraphs.push(chunk);
        length += chunk.length;
      }
    }

    function consume(text, final = false) {
      pending += text;
      let start = 0;
      const breaks = /\r\n|\r|\n/g;
      let match;
      while ((match = breaks.exec(pending))) {
        // A CR at the end of a stream chunk may be the first half of CRLF.
        if (!final && match[0] === "\r" && breaks.lastIndex === pending.length)
          break;
        line(pending.slice(start, match.index));
        start = breaks.lastIndex;
      }
      pending = pending.slice(start);
      if (pending.length > MAX_LINE) throw new Error("TXT_LINE");
      if (final) line(pending);
    }
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        consume(decoder.decode(value, { stream: true }));
      }
      consume(decoder.decode(), true);
      flush();
    } finally {
      reader.releaseLock();
    }
    if (!sections.some((section) => section.paragraphs.length))
      throw new Error("TXT_EMPTY");
    if (sections.length > 1 && !sections[0].paragraphs.length) sections.shift();
    return { sections, characters, encoding };
  }
  try {
    return await decode(encoding);
  } catch (error) {
    if (hasUtf8Bom && error instanceof TypeError)
      throw new Error("TXT_ENCODING");
    if (encoding !== "utf-8" || !(error instanceof TypeError)) throw error;
    // A valid ASCII/UTF-8 prefix can precede GB18030 text later in a file.
    encoding = "gb18030";
    return decode(encoding);
  }
}

export function textSectionDocument(section) {
  const body = section.paragraphs
    .map(
      (text) =>
        `<p>${xml(text.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\ufffe\uffff]/g, ""))}</p>`,
    )
    .join("");
  return `<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${xml(section.title)}</title></head><body><h1>${xml(section.title)}</h1>${body}</body></html>`;
}

export async function makeTextBook(blob, { title = "TXT", author = "" } = {}) {
  const { sections, characters, encoding } = await readTextSections(
    blob,
    title || "TXT",
  );
  const urls = new Map();
  const references = new Map();
  return {
    metadata: { title, author, language: "zh", characters, encoding },
    sections: sections.map((section, index) => ({
      id: `OEBPS/chapter${index ? `-${index}` : ""}.xhtml`,
      cfi: `epubcfi(/6/${(index + 1) * 2})`,
      size: new Blob([textSectionDocument(section)]).size,
      load() {
        references.set(index, (references.get(index) ?? 0) + 1);
        if (!urls.has(index))
          urls.set(
            index,
            URL.createObjectURL(
              new Blob([textSectionDocument(section)], {
                type: "application/xhtml+xml",
              }),
            ),
          );
        return urls.get(index);
      },
      unload() {
        const count = references.get(index) ?? 0;
        if (count > 1) {
          references.set(index, count - 1);
          return;
        }
        references.delete(index);
        if (urls.has(index)) URL.revokeObjectURL(urls.get(index));
        urls.delete(index);
      },
      createDocument: () =>
        new DOMParser().parseFromString(
          textSectionDocument(section),
          "application/xhtml+xml",
        ),
    })),
    toc: sections.map((section, index) => ({
      label: section.title,
      href: `OEBPS/chapter${index ? `-${index}` : ""}.xhtml`,
    })),
    splitTOCHref: (href) => {
      const [path, fragment] = href.split("#");
      const match = path.match(/chapter(?:-(\d+))?\.xhtml$/);
      const index = match ? Number(match[1] || 0) : Number(path);
      return [`OEBPS/chapter${index ? `-${index}` : ""}.xhtml`, fragment];
    },
    getTOCFragment: (doc, id) => (id ? doc.getElementById(id) : doc.body),
    resolveHref(href) {
      const [path, id] = href.split("#");
      const match = path.match(/chapter(?:-(\d+))?\.xhtml$/);
      const index = match ? Number(match[1] || 0) : Number(path);
      return {
        index,
        anchor: (doc) => (id ? doc.getElementById(id) : doc.body),
      };
    },
    destroy() {
      for (const url of urls.values()) URL.revokeObjectURL(url);
      urls.clear();
      references.clear();
    },
  };
}
