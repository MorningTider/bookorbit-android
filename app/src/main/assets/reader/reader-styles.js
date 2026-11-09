export function fontFaceCSS(faces) {
  const formats = {
    ttf: "truetype",
    otf: "opentype",
    woff: "woff",
    woff2: "woff2",
  };
  return faces
    .filter(
      (face) =>
        /^__(?:server|user)font_[a-z0-9_]+$/.test(face.family) &&
        /^(?:server|user)-[0-9]+-[0-9]+-[a-f0-9]{12}\.(?:ttf|otf|woff|woff2)$/.test(
          face.fileName,
        ) &&
        Object.hasOwn(formats, face.format),
    )
    .map((face) => {
      const min = Math.max(
        1,
        Math.min(1000, Number(face.weightMin ?? face.weight) || 400),
      );
      const max = Math.max(
        min,
        Math.min(1000, Number(face.weightMax ?? face.weight) || min),
      );
      return `@font-face { font-family: "${face.family}"; src: url("https://appassets.androidplatform.net/reader-fonts/${face.fileName}") format("${formats[face.format]}"); font-weight: ${min} ${max}; font-style: ${face.style === "italic" ? "italic" : "normal"}; font-display: swap; }`;
    })
    .join("\n");
}

export function typographyCSS(settings) {
  const spacing = Math.max(
    0,
    Math.min(2, Number(settings.paragraphSpacing) || 0),
  );
  const letters = Math.max(
    0,
    Math.min(0.3, Number(settings.letterSpacing) || 0),
  );
  return `
    body { line-height: ${settings.lineHeight} !important; font-size: ${settings.fontSize}px !important; font-weight: ${settings.fontWeight >= 700 ? 700 : 400} !important; }
    body p, body li, body dd, body blockquote { font-size: inherit !important; line-height: inherit !important; letter-spacing: ${letters}em !important; }
    body p { margin-block: ${spacing}em !important; ${settings.paragraphIndent ? "text-indent: 2em !important;" : ""} }
  `;
}

export function paperBackground(style, color) {
  const image =
    style === "paper"
      ? "repeating-linear-gradient(0deg, rgba(80,60,35,.025) 0, rgba(80,60,35,.025) 1px, transparent 1px, transparent 3px)"
      : style === "soft"
        ? "radial-gradient(ellipse at 80% 0%, rgba(160,190,115,.22), transparent 55%), radial-gradient(ellipse at 10% 70%, rgba(255,255,255,.16), transparent 60%)"
        : "none";
  return { color, image };
}
