import { afterEach, expect, it, vi } from 'vitest';
import { Connection } from './net';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

it('negotiates death option capability additively and clears it on reconnect to older hosts', () => {
  vi.useFakeTimers();
  const sockets: FakeSocket[] = [];
  class FakeSocket {
    static OPEN = 1;
    readyState = 0;
    onopen?: () => void;
    onmessage?: (event: { data: string }) => void;
    onclose?: () => void;
    onerror?: () => void;
    constructor() { sockets.push(this); }
    send(): void {}
    close(): void {}
    hello(expanded?: boolean): void {
      this.onmessage?.({ data: JSON.stringify({ type: 'hello', serverTime: 0, deathMoves: true, deathMoveOptions: expanded, brawlerRolls: expanded, druidAutomation: expanded }) });
    }
  }
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.stubGlobal('location', { protocol: 'http:', host: 'localhost' });
  const conn = new Connection({ onOpen: () => {}, onStatus: () => {}, onMessage: () => {} }, () => 0);
  conn.start();
  expect(conn.deathMoveOptions).toBe(false);
  expect(conn.brawlerRolls).toBe(false);
  expect(conn.druidAutomation).toBe(false);
  sockets[0].hello(true);
  expect(conn.deathMoves).toBe(true);
  expect(conn.deathMoveOptions).toBe(true);
  expect(conn.brawlerRolls).toBe(true);
  expect(conn.druidAutomation).toBe(true);
  sockets[0].hello();
  expect(conn.deathMoveOptions).toBe(false);
  expect(conn.brawlerRolls).toBe(false);
  expect(conn.druidAutomation).toBe(false);
  sockets[0].hello(true);
  conn.stop();
  conn.start();
  expect(conn.deathMoveOptions).toBe(false);
  expect(conn.brawlerRolls).toBe(false);
  expect(conn.druidAutomation).toBe(false);
  sockets[1].hello();
  expect(conn.deathMoves).toBe(true);
  expect(conn.deathMoveOptions).toBe(false);
  conn.stop();
});
