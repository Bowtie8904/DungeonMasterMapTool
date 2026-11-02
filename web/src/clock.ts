/** Best-round-trip clock offset estimator. serverNow() = performance.now() + offset (host milliseconds). */
export class ClockSync {
  offset = 0;
  bestRtt = Infinity;
  samples = 0;

  /** @param now client time (performance.now()) at reception of the reply. */
  addSample(clientSent: number, serverTime: number, now: number): void {
    const rtt = now - clientSent;
    if (!(rtt >= 0) || !Number.isFinite(serverTime)) {
      return;
    }
    this.samples++;
    if (rtt <= this.bestRtt) {
      this.bestRtt = rtt;
      this.offset = serverTime - (clientSent + rtt / 2);
    }
  }

  /** Rough initial estimate (no round trip known) until real samples arrive. */
  seed(serverTime: number, now: number): void {
    if (Number.isFinite(serverTime)) this.offset = serverTime - now;
  }

  serverNow(now: number): number {
    return now + this.offset;
  }
}
