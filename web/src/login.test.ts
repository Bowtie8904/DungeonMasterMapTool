import { afterEach, expect, it, vi } from 'vitest';
import { getSession, login } from './api';
import { showLogin } from './login';

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  getSession: vi.fn(),
  login: vi.fn(),
}));
vi.mock('./settings', () => ({ markAudioGesture: vi.fn() }));

class Element {
  children: Array<Element | string> = [];
  isConnected = true;
  value = '';
  required = false;
  disabled = false;
  private listeners = new Map<string, (event: { preventDefault(): void }) => Promise<void>>();

  constructor(public tag: string) {}

  append(...children: Array<Element | string>): void { this.children.push(...children); }
  replaceChildren(...children: Element[]): void {
    this.children = children;
    if (this.tag === 'select') this.value = children[0]?.value ?? '';
  }
  addEventListener(type: string, handler: (event: { preventDefault(): void }) => Promise<void>): void {
    this.listeners.set(type, handler);
  }
  async submit(): Promise<void> { await this.listeners.get('submit')?.({ preventDefault() {} }); }
  all(): Element[] {
    return [this, ...this.children.flatMap(child => typeof child === 'string' ? [] : child.all())];
  }
}

afterEach(() => {
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

it('allows submitting an empty password for a selected player', async () => {
  vi.stubGlobal('document', { createElement: (tag: string) => new Element(tag) });
  vi.stubGlobal('window', { setInterval: vi.fn(() => 1) });
  vi.stubGlobal('clearInterval', vi.fn());
  vi.mocked(getSession).mockResolvedValue({
    protocol: 1, sessionName: 'Session', campaignName: 'Campaign', paused: false, maxPlayers: 4,
    players: [{ id: 'player', name: 'A' }],
  });
  vi.mocked(login).mockResolvedValue(undefined);
  const root = new Element('div');
  const success = vi.fn();
  await showLogin(root as unknown as HTMLElement, success);
  const form = root.all().find(element => element.tag === 'form')!;
  const password = root.all().find(element => element.tag === 'input')!;
  expect(password.required).toBe(false);
  await form.submit();
  expect(login).toHaveBeenCalledWith('player', '');
  expect(success).toHaveBeenCalledOnce();
});
