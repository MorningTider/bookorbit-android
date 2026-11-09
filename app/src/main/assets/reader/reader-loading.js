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
export async function prepareSectionFonts(doc, settings, faces) {
  if (
    settings?.fontFamily &&
    faces?.some((face) => face.family === settings.fontFamily)
  ) {
    await doc.fonts.load(
      `${settings.fontWeight >= 700 ? 700 : 400} ${settings.fontSize || 16}px ${settings.fontFamily}`,
    );
  }
  await doc.fonts.ready;
}
