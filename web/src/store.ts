import { GameState, DeltaOp, normalizeState, Token, type ServerMessage } from './protocol';

export type ApplyResult = 'ok' | 'stale' | 'gap';

const SET_PATHS = new Set([
  'images', 'walls', 'doors', 'lights', 'lighting', 'weather', 'dmFog', 'fogGrid', 'fogMemory',
  'paused', 'visionTiles', 'mapName', 'actors', 'grid', 'overlays', 'texts', 'view', 'pings', 'indoor',
  'effectTextures', 'penWidthCells', 'fear', 'countdowns',
]);

type Position = { x: number; y: number };
type LocalMove = Position & { cmdId: string; playerId: string; ackSeq?: number };

/** Holds the replicated game state and applies snapshots and deltas. */
export class Store {
  state: GameState | null = null;
  lastSeq = 0;
  /** Categories touched by the last applied snapshot/delta ('*' after a snapshot). */
  changed = new Set<string>();
  private localMoves = new Map<string, LocalMove>();
  private hostPositions = new Map<string, Position & { seq: number }>();

  get movesAwaitingState(): number { return this.localMoves.size; }

  hasLocalMove(tokenId: string): boolean { return this.localMoves.has(tokenId); }

  beginMove(cmdId: string, move: Position & { tokenId: string }, playerId: string): void {
    this.localMoves.set(move.tokenId, { cmdId, x: move.x, y: move.y, playerId });
    this.reconcileMoves();
  }

  acknowledgeMove(cmdId: string, seq = this.lastSeq): void {
    for (const move of this.localMoves.values()) {
      if (move.cmdId === cmdId) move.ackSeq = seq;
    }
    this.reconcileMoves();
  }

  cancelMove(cmdId: string, tokenId: string, position: Position): boolean {
    if (this.localMoves.get(tokenId)?.cmdId !== cmdId) return false;
    this.localMoves.delete(tokenId);
    this.hostPositions.set(tokenId, { ...position, seq: this.lastSeq });
    return true;
  }

  private reconcileMoves(): void {
    for (const [id, move] of this.localMoves) {
      const token = this.state?.tokens.find(t => t.id === id);
      const host = this.hostPositions.get(id);
      if (!token || !token.controlledBy?.includes(move.playerId)) {
        this.localMoves.delete(id);
        continue;
      }
      // An ack's sequence may still describe the state before the move was published.
      if (host && move.ackSeq !== undefined && (host.seq > move.ackSeq
        || this.lastSeq >= move.ackSeq && host.x === move.x && host.y === move.y)) {
        this.setPosition(token, host);
        this.localMoves.delete(id);
      } else {
        this.setPosition(token, move);
      }
    }
  }

  private setPosition(token: Token, position: Position): void {
    if (!this.state || token.x === position.x && token.y === position.y) return;
    // Keep an active drag's token reference intact so MapView can reapply its newer pointer position.
    this.state.tokens = this.state.tokens.map(t => t.id === token.id ? { ...t, x: position.x, y: position.y } : t);
  }

  applyRollUpdate(update: Extract<ServerMessage, { type: 'rollUpdate' }>): boolean {
    const roll = this.state?.chatTail?.find(entry => entry.id === update.id && entry.ts === update.ts && !('text' in entry));
    if (!roll) return false;
    const { type: _type, ...fields } = update;
    Object.assign(roll, fields);
    return true;
  }

  applySnapshot(seq: number, raw: Partial<GameState>): void {
    this.state = normalizeState(raw);
    this.localMoves.clear();
    this.hostPositions = new Map(this.state.tokens.map(t => [t.id, { x: t.x, y: t.y, seq }]));
    this.lastSeq = seq;
    this.changed = new Set(['*']);
  }

  applyDelta(seq: number, ops: DeltaOp[]): ApplyResult {
    if (!this.state) {
      return 'gap';
    }
    if (seq <= this.lastSeq) {
      return 'stale';
    }
    if (seq !== this.lastSeq + 1) {
      return 'gap';
    }
    this.changed = new Set();
    for (const op of ops) {
      if (op.op === 'tokens') {
        for (const id of op.remove ?? []) this.hostPositions.delete(id);
        for (const t of op.upsert ?? []) this.hostPositions.set(t.id, { x: t.x, y: t.y, seq });
      }
      this.applyOp(this.state, op);
    }
    this.lastSeq = seq;
    this.reconcileMoves();
    return 'ok';
  }

  private applyOp(state: GameState, op: DeltaOp): void {
    if (op.op === 'set') {
      if (!SET_PATHS.has(op.path)) {
        return;
      }
      (state as unknown as Record<string, unknown>)[op.path] = op.value;
      if (op.path === 'lighting' || op.path === 'weather') {
        const norm = normalizeState({ [op.path]: op.value } as Partial<GameState>);
        (state as unknown as Record<string, unknown>)[op.path] = (norm as unknown as Record<string, unknown>)[op.path];
      }
      this.changed.add(op.path);
    } else if (op.op === 'tokens') {
      const remove = new Set(op.remove ?? []);
      let tokens: Token[] = state.tokens.filter((t) => !remove.has(t.id));
      for (const token of op.upsert ?? []) {
        const i = tokens.findIndex((t) => t.id === token.id);
        if (i >= 0) {
          tokens[i] = token;
        } else {
          tokens = [...tokens, token];
        }
      }
      state.tokens = tokens;
      this.changed.add('tokens');
    }
  }
}
