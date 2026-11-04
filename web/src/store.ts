import { GameState, DeltaOp, normalizeState, Token } from './protocol';

export type ApplyResult = 'ok' | 'stale' | 'gap';

const SET_PATHS = new Set([
  'images', 'walls', 'doors', 'lights', 'lighting', 'weather', 'dmFog', 'fogGrid', 'fogMemory',
  'paused', 'visionTiles', 'mapName', 'actors', 'grid', 'overlays', 'texts', 'view', 'pings', 'indoor',
  'effectTextures', 'penWidthCells', 'fear', 'countdowns',
]);

/** Holds the replicated game state and applies snapshots and deltas. */
export class Store {
  state: GameState | null = null;
  lastSeq = 0;
  /** Categories touched by the last applied snapshot/delta ('*' after a snapshot). */
  changed = new Set<string>();

  applySnapshot(seq: number, raw: Partial<GameState>): void {
    this.state = normalizeState(raw);
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
      this.applyOp(this.state, op);
    }
    this.lastSeq = seq;
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
