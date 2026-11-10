import { describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { Store } from './store';
import type { MoveCommit } from './render';
import type { ServerMessage, Token } from './protocol';
import { normalizeState } from './protocol';

function fixture() {
  const store = new Store();
  const token: Token = { id: 'token', x: 0, y: 0, sizeTiles: 1, controlledBy: ['player'] };
  store.applySnapshot(1, { mapId: 'map', levelId: 'level', tokens: [token] });
  const context = {
    store, playerId: 'player', cmdCounter: 0, pendingMoves: new Map<string, MoveCommit>(),
    pendingDrawings: new Map(), drawingHistory: [], conn: { send: vi.fn((_message: { cmdId: string }) => true) },
    view: { setMovesInFlight: vi.fn(), setState: vi.fn(), invalidateVision: vi.fn(),
      corrected: vi.fn(), tokenMoved: vi.fn() },
    toast: vi.fn(), afterState: vi.fn(),
    syncInFlight() { Reflect.get(App.prototype, 'syncInFlight').call(context); },
    restore(id: string, x: number, y: number) { Reflect.get(App.prototype, 'restore').call(context, id, x, y); },
    onReject(message: ServerMessage) { Reflect.get(App.prototype, 'onReject').call(context, message); },
  };
  const handle = (message: ServerMessage) => Reflect.get(App.prototype, 'handle').call(context, message);
  const move = (x: number) => {
    const current = store.state!.tokens[0];
    Reflect.get(App.prototype, 'commitMove').call(context,
      { tokenId: token.id, x, y: 0, prevX: current.x, prevY: current.y });
    return context.conn.send.mock.lastCall![0];
  };
  const delta = (seq: number, x: number, extra: Partial<Token> = {}) => handle({
    type: 'delta', seq, ops: [{ op: 'tokens', upsert: [{ ...token, x, ...extra }] }],
  });
  const ack = (cmdId: string, seq: number) => handle({ type: 'ack', cmdId, seq });
  const x = () => store.state!.tokens[0]?.x;
  return { store, context, handle, move, delta, ack, x };
}

describe('online movement reconciliation', () => {
  it('holds the latest release through stale state and ack until token publication catches up', () => {
    const f = fixture();
    const first = f.move(50);
    const final = f.move(100);
    f.delta(2, 50, { rotationDeg: 90 });
    expect(f.x()).toBe(100);
    expect(f.store.state!.tokens[0].rotationDeg).toBe(90);
    f.ack(first.cmdId, 2);
    f.ack(final.cmdId, 2);
    expect(f.x()).toBe(100);
    expect(f.context.pendingMoves.size).toBe(0);
    expect(f.store.movesAwaitingState).toBe(1);
    expect(f.context.view.setMovesInFlight.mock.lastCall![0]).toBeGreaterThan(0);
    f.delta(3, 100);
    expect(f.x()).toBe(100);
    expect(f.store.movesAwaitingState).toBe(0);
    expect(f.context.view.setMovesInFlight).toHaveBeenLastCalledWith(0);
    f.delta(4, 150);
    expect(f.x()).toBe(150);
  });

  it('handles publication before acknowledgement, including intervening unrelated deltas', () => {
    const f = fixture();
    const m = f.move(100);
    f.delta(2, 100);
    f.handle({ type: 'delta', seq: 3, ops: [{ op: 'set', path: 'fear', value: 4 }] });
    f.ack(m.cmdId, 3);
    expect(f.store.movesAwaitingState).toBe(0);
    expect(f.x()).toBe(100);
  });

  it('waits for the ack sequence when the acknowledgement arrives before its state', () => {
    const f = fixture();
    const m = f.move(100);
    f.ack(m.cmdId, 3);
    f.delta(2, 50);
    expect(f.x()).toBe(100);
    expect(f.store.movesAwaitingState).toBe(1);
    f.delta(3, 100);
    expect(f.store.movesAwaitingState).toBe(0);
  });

  it('applies a genuine host correction published after acknowledgement', () => {
    const f = fixture();
    const m = f.move(100);
    f.ack(m.cmdId, 1);
    f.delta(2, 75);
    expect(f.x()).toBe(75);
    expect(f.store.movesAwaitingState).toBe(0);
  });

  it('supports acknowledgements without a sequence from older hosts', () => {
    const f = fixture();
    const m = f.move(100);
    f.handle({ type: 'ack', cmdId: m.cmdId });
    expect(f.x()).toBe(100);
    expect(f.store.movesAwaitingState).toBe(1);
    f.delta(2, 100);
    expect(f.store.movesAwaitingState).toBe(0);
  });

  it('does not let superseded acknowledgements, rejections or relays undo newer local input', () => {
    const f = fixture();
    const first = f.move(50);
    const final = f.move(100);
    f.handle({ type: 'reject', cmdId: first.cmdId, reason: 'blocked', token: { id: 'token', x: 0, y: 0 } });
    expect(f.x()).toBe(100);
    expect(f.context.view.corrected).not.toHaveBeenCalled();
    f.ack(first.cmdId, 1);
    f.handle({ type: 'tokenMoved', mapKey: 'map/level', id: 'token', x: 50, y: 0 });
    expect(f.context.view.tokenMoved).not.toHaveBeenCalled();
    f.handle({ type: 'reject', cmdId: final.cmdId, reason: 'blocked', token: { id: 'token', x: 25, y: 0 } });
    expect(f.x()).toBe(25);
    expect(f.context.view.corrected).toHaveBeenCalledWith('token', 25, 0);
    expect(f.store.movesAwaitingState).toBe(0);
    expect(f.context.toast).toHaveBeenCalledWith('Edit rejected: blocked');
  });

  it('rolls back a failed send and a legacy rejection without coordinates', () => {
    const f = fixture();
    f.context.conn.send.mockReturnValueOnce(false);
    f.move(50);
    expect(f.x()).toBe(0);
    expect(f.store.movesAwaitingState).toBe(0);
    const m = f.move(100);
    f.handle({ type: 'reject', cmdId: m.cmdId, reason: 'blocked' });
    expect(f.x()).toBe(0);
    expect(f.store.movesAwaitingState).toBe(0);
  });

  it.each(['removal', 'control', 'snapshot'] as const)('clears local positions on authoritative %s', kind => {
    const f = fixture();
    f.move(100);
    if (kind === 'removal') f.handle({ type: 'delta', seq: 2, ops: [{ op: 'tokens', remove: ['token'] }] });
    if (kind === 'control') f.delta(2, 30, { controlledBy: ['other'] });
    if (kind === 'snapshot') f.handle({ type: 'snapshot', seq: 2,
      state: normalizeState({ mapId: 'new-map', levelId: 'level', tokens: [] }) });
    expect(f.store.movesAwaitingState).toBe(0);
    expect(f.x()).toBe(kind === 'control' ? 30 : undefined);
  });
});
