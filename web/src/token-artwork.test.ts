import { afterEach, expect, it, vi } from 'vitest';
import { cropGeometry, editTokenArtwork } from './token-artwork';

const settings = { color: '#123456', sizeTiles: 1, zoom: 1, centerX: .5, centerY: .5 };

it('centers a square crop of landscape and portrait sources', () => {
  expect(cropGeometry(800, 400, settings)).toEqual({ scale: .7, x: -140, y: 0 });
  expect(cropGeometry(400, 800, settings)).toEqual({ scale: .7, x: 0, y: -140 });
});

it('preserves the DM crop geometry when zooming and panning to image edges', () => {
  expect(cropGeometry(800, 400, { ...settings, zoom: 2, centerX: 0, centerY: 1 }, 256))
    .toEqual({ scale: 1.28, x: 128, y: -384 });
});

class Element {
  children: Array<Element | string> = [];
  listeners = new Map<string, (event: object) => unknown>();
  className = '';
  textContent = '';
  type = '';
  value = '';
  disabled = false;
  isConnected = true;
  files?: File[];
  context = { clearRect: vi.fn(), save: vi.fn(), beginPath: vi.fn(), arc: vi.fn(), clip: vi.fn(),
    fillRect: vi.fn(), drawImage: vi.fn(), restore: vi.fn(), stroke: vi.fn() };
  constructor(readonly tag: string) {}
  append(...children: Array<Element | string>) { this.children.push(...children); }
  addEventListener(type: string, callback: (event: object) => unknown) { this.listeners.set(type, callback); }
  fire(type: string, event = {}) { return this.listeners.get(type)?.(event); }
  showModal() {}
  close() { this.fire('close'); }
  remove() { this.isConnected = false; }
  focus() {}
  getContext() { return this.context; }
  reportValidity() { return Number(this.value) >= .5 && Number(this.value) % .5 === 0; }
  setPointerCapture() {}
  getBoundingClientRect() { return { width: 280 }; }
  all(): Element[] { return this.children.flatMap(child => typeof child === 'string' ? [] : [child, ...child.all()]); }
  text(): string { return this.textContent + this.children.map(child => typeof child === 'string' ? child : child.text()).join(''); }
}

afterEach(() => vi.unstubAllGlobals());

function environment() {
  const body = new Element('body');
  vi.stubGlobal('document', { body, activeElement: null, createElement: (tag: string) => new Element(tag) });
  vi.stubGlobal('HTMLElement', Element);
  vi.stubGlobal('navigator', {});
  vi.stubGlobal('Image', class {
    src = '';
    naturalWidth = 800;
    naturalHeight = 400;
    decode() { return Promise.resolve(); }
  });
  vi.stubGlobal('FileReader', class {
    result = 'data:image/png;base64,YQ==';
    onload?: () => void;
    readAsDataURL() { queueMicrotask(() => this.onload?.()); }
  });
  const fetch = vi.fn().mockImplementation(async (_url: string, options?: RequestInit) =>
    new Response(JSON.stringify(options?.method === 'PUT' ? { artwork: '/a/finished.png' } : settings)));
  vi.stubGlobal('fetch', fetch);
  return { body, fetch };
}

it('saves clipboard-event images with a circular border, pan, zoom, color and tile size', async () => {
  const { body, fetch } = environment();
  const completion = editTokenArtwork('hero', () => true);
  await vi.waitFor(() => expect(body.all().some(e => e.tag === 'dialog')).toBe(true));
  const dialog = body.all().find(e => e.tag === 'dialog')!;
  const save = dialog.all().find(e => e.text() === 'Save token')!;
  expect(save.disabled).toBe(true);
  const preventDefault = vi.fn();
  dialog.fire('paste', { preventDefault, clipboardData: { items: [{
    type: 'image/png', getAsFile: () => new File(['image'], 'image.png', { type: 'image/png' }),
  }] } });
  await vi.waitFor(() => expect(save.disabled).toBe(false));
  expect(preventDefault).toHaveBeenCalledOnce();
  const canvas = dialog.all().find(e => e.tag === 'canvas')!;
  expect(canvas.context.arc).toHaveBeenLastCalledWith(140, 140, (280 - 280 * .055) / 2, 0, Math.PI * 2);
  canvas.fire('wheel', { preventDefault: vi.fn(), deltaY: -1 });
  canvas.fire('pointerdown', { pointerId: 1, button: 0, clientX: 0, clientY: 0 });
  canvas.fire('pointermove', { clientX: 28, clientY: 0 });
  const color = dialog.all().find(e => e.type === 'color')!;
  color.value = '#abcdef';
  color.fire('input');
  const size = dialog.all().find(e => e.type === 'number')!;
  size.value = '2.5';
  save.fire('click');
  expect(await completion).toBe('/a/finished.png');
  const submitted = JSON.parse(fetch.mock.calls[1][1].body);
  expect(submitted).toMatchObject({ color: '#abcdef', sizeTiles: 2.5, zoom: 1.1, centerY: .5, image: 'data:image/png;base64,YQ==' });
  expect(submitted.centerX).toBeCloseTo(.5 - 28 * (400 / 1.1 / 280) / 800);
  expect(dialog.all().some(e => ['checkbox', 'text'].includes(e.type))).toBe(false);
});

it('supports file selection and blocks saving if the actor becomes read-only', async () => {
  const { body, fetch } = environment();
  let editable = true;
  const completion = editTokenArtwork('hero', () => editable);
  await vi.waitFor(() => expect(body.all().some(e => e.tag === 'dialog')).toBe(true));
  const dialog = body.all().find(e => e.tag === 'dialog')!;
  const file = dialog.all().find(e => e.type === 'file')!;
  const save = dialog.all().find(e => e.text() === 'Save token')!;
  file.files = [new File(['image'], 'image.png', { type: 'image/png' })];
  file.fire('change');
  await vi.waitFor(() => expect(save.disabled).toBe(false));
  editable = false;
  save.fire('click');
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(dialog.all().some(e => e.textContent === 'This character sheet is read-only.')).toBe(true);
  dialog.all().find(e => e.text() === 'Cancel')!.fire('click');
  expect(await completion).toBeUndefined();
});
