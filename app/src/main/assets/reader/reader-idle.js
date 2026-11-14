/** Shared by gestures and native paper animation so speculative layout yields to reading. */
export function createReaderIdleGate(now = () => performance.now()) {
  const active = new Set();
  let lastInput = -Infinity;
  return {
    touch() { lastInput = now(); },
    setActive(value, source = "native") {
      if (value) active.add(source); else active.delete(source);
      lastInput = now();
    },
    isBusy() { return active.size > 0 || now() - lastInput < 750; },
  };
}
