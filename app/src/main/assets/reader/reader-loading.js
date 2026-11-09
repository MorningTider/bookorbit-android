/** Complete font layout before revealing or restoring the initial reading position. */
export async function settleReaderFonts(
  view,
  settings,
  faces,
  frame = () => new Promise(requestAnimationFrame),
) {
  const doc = view?.renderer?.getContents?.()[0]?.doc;
  if (!doc) return;
  if (
    settings?.fontFamily &&
    faces?.some((face) => face.family === settings.fontFamily)
  ) {
    await doc.fonts.load(`${settings.fontSize || 16}px ${settings.fontFamily}`);
  }
  await doc.fonts.ready;
  await frame();
  await frame();
}
