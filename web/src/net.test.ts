import { afterEach, expect, it, vi } from 'vitest';
import { Connection } from './net';
import { ONLINE_CONTRACT_VERSION } from './api';

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
    hello(expanded?: boolean, domainV3 = expanded): void {
      this.onmessage?.({ data: JSON.stringify({ type: 'hello', serverTime: 0, automationContract: ONLINE_CONTRACT_VERSION, deathMoves: true, deathMoveOptions: expanded, brawlerRolls: expanded, druidAutomation: expanded, guardianRolls: expanded, warlockAutomation: expanded, witchAutomation: expanded, domainCardAutomation: expanded, domainCardAutomationV2: expanded, domainCardAutomationV3: domainV3, bloodAutomation: expanded, splendorAutomation: expanded }) });
    }
  }
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.stubGlobal('location', { protocol: 'http:', host: 'localhost' });
  const conn = new Connection({ onOpen: () => {}, onStatus: () => {}, onMessage: () => {} }, () => 0);
  conn.start();
  expect(conn.chatActionReports).toBe(false);
  sockets[0].onmessage?.({ data: JSON.stringify({ type: 'hello', serverTime: 0, automationContract: ONLINE_CONTRACT_VERSION, chatActionReports: true }) });
  expect(conn.chatActionReports).toBe(true);
  expect(conn.deathMoveOptions).toBe(false);
  expect(conn.brawlerRolls).toBe(false);
  expect(conn.druidAutomation).toBe(false);
  expect(conn.guardianRolls).toBe(false);
  expect(conn.warlockAutomation).toBe(false);
  expect(conn.witchAutomation).toBe(false);
  expect(conn.domainCardAutomation).toBe(false);
  expect(conn.bloodAutomation).toBe(false);
  expect(conn.splendorAutomation).toBe(false);
  sockets[0].hello(true);
  expect(conn.deathMoves).toBe(true);
  expect(conn.deathMoveOptions).toBe(true);
  expect(conn.brawlerRolls).toBe(true);
  expect(conn.druidAutomation).toBe(true);
  expect(conn.guardianRolls).toBe(true);
  expect(conn.warlockAutomation).toBe(true);
  expect(conn.witchAutomation).toBe(true);
  expect(conn.domainCardAutomation).toBe(true);
  expect(conn.bloodAutomation).toBe(true);
  expect(conn.splendorAutomation).toBe(true);
  sockets[0].hello(true, false);
  expect(conn.domainCardAutomation).toBe(false);
  sockets[0].hello();
  expect(conn.chatActionReports).toBe(false);
  expect(conn.splendorAutomation).toBe(false);
  expect(conn.bloodAutomation).toBe(false);
  expect(conn.deathMoveOptions).toBe(false);
  expect(conn.brawlerRolls).toBe(false);
  expect(conn.druidAutomation).toBe(false);
  expect(conn.guardianRolls).toBe(false);
  expect(conn.warlockAutomation).toBe(false);
  expect(conn.witchAutomation).toBe(false);
  expect(conn.domainCardAutomation).toBe(false);
  sockets[0].hello(true);
  sockets[0].onmessage?.({ data: JSON.stringify({ type: 'hello', serverTime: 0, automationContract: ONLINE_CONTRACT_VERSION, chatActionReports: true }) });
  conn.stop();
  conn.start();
  expect(conn.chatActionReports).toBe(false);
  expect(conn.splendorAutomation).toBe(false);
  expect(conn.bloodAutomation).toBe(false);
  expect(conn.deathMoveOptions).toBe(false);
  expect(conn.brawlerRolls).toBe(false);
  expect(conn.druidAutomation).toBe(false);
  expect(conn.guardianRolls).toBe(false);
  expect(conn.warlockAutomation).toBe(false);
  expect(conn.witchAutomation).toBe(false);
  expect(conn.domainCardAutomation).toBe(false);
  sockets[1].hello();
  expect(conn.deathMoves).toBe(true);
  expect(conn.deathMoveOptions).toBe(false);
  conn.stop();
});
it('sends its contract version and stops on an old host or a mismatch error', () => {
  const urls: string[] = [];
  const sockets: Array<{ onmessage?: (event: { data: string }) => void; closed: boolean }> = [];
  class FakeSocket {
    static OPEN = 1;
    readyState = 0;
    closed = false;
    onmessage?: (event: { data: string }) => void;
    constructor(url: string) { urls.push(url); sockets.push(this); }
    send(): void {}
    close(): void { this.closed = true; }
  }
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.stubGlobal('location', { protocol: 'http:', host: 'localhost' });
  const statuses: Array<[string, string | undefined]> = [];
  const conn = new Connection({ onOpen: () => {}, onStatus: (s, d) => statuses.push([s, d]), onMessage: () => {} }, () => 0);
  conn.start();
  expect(urls[0]).toContain('contract=' + ONLINE_CONTRACT_VERSION);
  sockets[0].onmessage?.({ data: JSON.stringify({ type: 'hello', serverTime: 0 }) });
  expect(sockets[0].closed).toBe(true);
  expect(statuses.at(-1)?.[0]).toBe('incompatible');
  expect(statuses.at(-1)?.[1]).toMatch(/unknown/);
  conn.start();
  sockets[1].onmessage?.({ data: JSON.stringify({ type: 'error', code: 'automationCompatibilityMismatch', hostVersion: 7, message: 'x' }) });
  expect(statuses.at(-1)?.[0]).toBe('incompatible');
  expect(statuses.at(-1)?.[1]).toContain('7');
});
