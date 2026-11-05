import { ClockSync } from './clock';
import { ServerMessage } from './protocol';

export interface ConnectionEvents {
  onMessage(msg: ServerMessage): void;
  /** Called when a socket opened and we must (re)send resync info. */
  onOpen(): void;
  onStatus(status: 'connecting' | 'open' | 'closed' | 'kicked' | 'unauthorized'): void;
}

export function backoffDelay(attempt: number): number {
  return Math.min(15000, 500 * 2 ** Math.min(attempt, 6));
}

/** Auto-reconnecting WebSocket with clock sync. */
export class Connection {
  keptDice = false;
  rollMultipliers = false;
  rollRewards = false;
  deathMoves = false;
  deathMoveOptions = false;
  readonly clock = new ClockSync();
  private ws: WebSocket | null = null;
  private attempts = 0;
  private stopped = false;
  private everOpened = false;
  private timers: number[] = [];
  private syncId = 0;
  private reconnectTimer: number | null = null;

  constructor(private events: ConnectionEvents, private getLastSeq: () => number) {}

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.clearTimers();
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.ws?.close();
  }

  get isOpen(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  send(obj: Record<string, unknown>): boolean {
    if (!this.isOpen) return false;
    this.ws!.send(JSON.stringify(obj));
    return true;
  }

  private url(): string {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${location.host}/ws`;
  }

  private open(): void {
    this.keptDice = false;
    this.rollMultipliers = false;
    this.rollRewards = false;
    this.deathMoves = false;
    this.deathMoveOptions = false;
    this.events.onStatus('connecting');
    const ws = new WebSocket(this.url());
    this.ws = ws;
    let opened = false;
    ws.onopen = () => {
      opened = true;
      this.everOpened = true;
      this.attempts = 0;
      this.events.onStatus('open');
      this.startClockSync();
      this.events.onOpen();
      const seq = this.getLastSeq();
      if (seq > 0) this.send({ type: 'resync', lastSeq: seq });
    };
    ws.onmessage = (ev) => {
      let msg: ServerMessage;
      try {
        msg = JSON.parse(String(ev.data)) as ServerMessage;
      } catch {
        return;
      }
      if (msg.type === 'clockSync') {
        this.clock.addSample(msg.clientSent, msg.serverTime, performance.now());
        return;
      }
      if (msg.type === 'ping') {
        this.send({ type: 'pong', id: msg.id });
        return;
      }
      if (msg.type === 'hello') {
        this.keptDice = msg.keptDice === true;
        this.rollMultipliers = msg.rollMultipliers === true;
        this.rollRewards = msg.rollRewards === true;
        this.deathMoves = msg.deathMoves === true;
        this.deathMoveOptions = msg.deathMoveOptions === true;
        if (this.clock.samples === 0) this.clock.seed(msg.serverTime, performance.now());
      }
      if (msg.type === 'kicked') {
        this.stopped = true;
        this.events.onStatus('kicked');
      }
      this.events.onMessage(msg);
    };
    ws.onclose = () => {
      this.clearTimers();
      if (this.stopped) return;
      if (!opened && !this.everOpened) {
        this.events.onStatus('unauthorized');
        this.stopped = true;
        return;
      }
      this.events.onStatus('closed');
      const delay = backoffDelay(this.attempts++);
      this.reconnectTimer = window.setTimeout(() => this.open(), delay);
    };
    ws.onerror = () => {
      /* close follows */
    };
  }

  private sample(): void {
    this.send({ type: 'clockSync', id: ++this.syncId, clientSent: performance.now() });
  }

  private startClockSync(): void {
    this.clearTimers();
    for (let i = 0; i < 8; i++) {
      this.timers.push(window.setTimeout(() => this.sample(), i * 200));
    }
    this.timers.push(window.setInterval(() => this.sample(), 30000));
  }

  private clearTimers(): void {
    for (const t of this.timers) {
      clearTimeout(t);
      clearInterval(t);
    }
    this.timers = [];
  }
}
