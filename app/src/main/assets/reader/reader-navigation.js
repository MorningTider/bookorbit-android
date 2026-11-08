/** Foliate parses gap as a percentage. One half is outside the iframe, one inside. */
export function applySidePadding(renderer, sidePadding, viewportWidth) {
  const width = Math.max(1, Number(viewportWidth) || 1);
  const padding = Math.max(0, Number(sidePadding) || 0);
  const gap = `${Math.min(40, (padding / width) * 100)}%`;
  if (renderer.getAttribute("gap") !== gap) renderer.setAttribute("gap", gap);
}

export function applyPageAnimation(renderer, settings) {
  if (
    settings.pageAnimation !== false &&
    settings.pageCurl !== true &&
    settings.flow !== "scrolled"
  )
    renderer.setAttribute("animated", "");
  else renderer.removeAttribute("animated");
}

export function createAutoReader({
  next,
  isScrolled,
  isAtEnd,
  viewportHeight,
  onStopped,
  clock = () => performance.now(),
  timers = globalThis,
}) {
  let timer = null;
  let active = false;
  let generation = 0;
  let seconds = 15;
  let previous = 0;

  function stop(notify = false) {
    generation++;
    const wasActive = active;
    active = false;
    if (timer !== null) timers.clearTimeout(timer);
    timer = null;
    if (notify && wasActive) onStopped();
  }

  function schedule(token) {
    timer = timers.setTimeout(
      () => tick(token),
      isScrolled() ? 100 : seconds * 1000,
    );
  }

  async function tick(token) {
    if (token !== generation) return;
    if (isAtEnd()) {
      stop(true);
      return;
    }
    const now = clock();
    const distance = isScrolled()
      ? Math.max(
          1,
          (viewportHeight() * Math.min(1000, now - previous)) /
            (seconds * 1000),
        )
      : undefined;
    previous = now;
    try {
      await next(distance);
    } catch {
      stop(true);
      return;
    }
    if (token !== generation) return;
    if (isAtEnd()) stop(true);
    else schedule(token);
  }

  function start(intervalSeconds) {
    stop();
    seconds = Math.min(120, Math.max(5, Number(intervalSeconds) || 15));
    previous = clock();
    active = true;
    schedule(generation);
  }

  return { start, stop };
}
