import { readerFontSources } from "./reader-styles.js";

const documentFaces = new WeakMap();

/** Share decoded faces across section documents and the hidden page counter. */
export function createSectionFontLoader(
  faces,
  Font = globalThis.FontFace,
  isCurrent = () => true,
) {
  const sources = readerFontSources(faces);
  let loading = null;
  return async (doc) => {
    loading ??= Promise.all(
      sources.map((face) =>
        new Font(face.family, face.source, {
          weight: face.weight,
          style: face.style,
          display: "block",
        }).load(),
      ),
    );
    const request = loading;
    try {
      const loaded = await request;
      if (!isCurrent()) return;
      for (const face of documentFaces.get(doc) ?? []) {
        if (!loaded.includes(face)) doc.fonts.delete(face);
      }
      for (const face of loaded) doc.fonts.add(face);
      documentFaces.set(doc, loaded);
    } catch (error) {
      if (loading === request) loading = null;
      throw error;
    }
  };
}

/** Complete font layout before revealing or restoring the initial reading position. */
export async function settleReaderFonts(
  view,
  settings,
  faces,
  frame = () => new Promise(requestAnimationFrame),
) {
  const doc = view?.renderer?.getContents?.()[0]?.doc;
  if (!doc) return;
  await prepareSectionFonts(doc, settings, faces);
  await frame();
  await frame();
}

/** Each section owns a fresh FontFaceSet, including chapters revisited from the contents. */
export async function prepareSectionFonts(doc, settings, faces, loadFaces) {
  await loadFaces?.(doc);
  const selected =
    faces?.filter((face) => face.family === settings?.fontFamily) ?? [];
  if (selected.length) {
    const styles = selected.some((face) => face.style === "italic")
      ? ["", "italic "]
      : [""];
    const requests = styles.flatMap((style) =>
      [400, 700].map((weight) =>
        doc.fonts.load(
          `${style}${weight} ${settings.fontSize || 16}px ${settings.fontFamily}`,
        ),
      ),
    );
    await Promise.all(requests);
  }
  // The hidden iframe is laid out before this hook. This includes embedded heading fonts too.
  await doc.fonts.ready;
}
