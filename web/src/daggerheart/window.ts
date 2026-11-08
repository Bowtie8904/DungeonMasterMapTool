import { el } from '../login';
import { renderSheetView, SheetViewOptions } from './view';

const HIDDEN = 'hidden';

const BASE_Z = 4;
const stack: HTMLElement[] = [];

/** Raises a floating window above all others (sheets, tools, notes and handouts share one stack). */
export function bringToFront(win: HTMLElement): void {
  const at = stack.indexOf(win);
  if (at >= 0) stack.splice(at, 1);
  stack.push(win);
  for (let i = stack.length - 1; i >= 0; i--) if (!stack[i].isConnected && stack[i] !== win) stack.splice(i, 1);
  stack.forEach((w, i) => { w.style.zIndex = String(BASE_Z + i); });
}

/** Brings the window to the front whenever it is clicked, even when a child handles the event. */
export function trackStacking(win: HTMLElement): void {
  win.addEventListener('pointerdown', () => bringToFront(win), true);
}

/** Floating, draggable sheet window over the map view (minimise, close, pop out). */
export class SheetWindow {
  readonly element: HTMLElement;
  private readonly title: HTMLElement;
  private readonly body: HTMLElement;

  constructor(
    options: SheetViewOptions,
    actorName: string,
    onPopOut: () => void,
    private readonly onVisibility: (open: boolean) => void,
  ) {
    const mk = (text: string, title: string, fn: () => void) => {
      const b = el('button', { type: 'button', title }, text);
      b.addEventListener('click', fn);
      return b;
    };
    this.title = el('div', { class: 'dh-win-title' });
    const mini = el('div', { class: 'dh-win-mini' });
    const editControl = el('div', { class: 'sheet-edit-host' });
    const label = el('h2', {}, `${actorName} - Daggerheart sheet`);
    this.body = el('div', { class: 'dh-win-body' }, renderSheetView({
      ...options,
      onEditControl: control => {
        options.onEditControl?.(control);
        editControl.replaceChildren(control);
        label.textContent = `${options.character.name} - Daggerheart sheet`;
      },
      onMiniStrip: (strip) => { options.onMiniStrip?.(strip); mini.replaceChildren(...(strip ? [strip] : [])); },
    }));
    this.title.append(
      el('div', { class: 'sheet-heading' }, label, editControl),
      mk('\u29C9', 'Pop out to a new browser window', () => { this.setOpen(false); onPopOut(); }),
      mk('\u2013', 'Minimise', () => this.element.classList.toggle('minimised')),
      mk('\u2715', 'Close', () => this.setOpen(false)),
    );
    this.element = el('section', { class: `dh-win ${HIDDEN}`, ariaLabel: `${actorName} character sheet` }, this.title, mini, this.body);
    this.enableDrag();
    trackStacking(this.element);
  }

  get isOpen(): boolean {
    return !this.element.classList.contains(HIDDEN);
  }

  setOpen(open: boolean): void {
    this.element.classList.toggle(HIDDEN, !open);
    if (open) { this.element.classList.remove('minimised'); bringToFront(this.element); }
    this.onVisibility(open);
  }

  toggle(): void {
    this.setOpen(!this.isOpen);
  }

  private enableDrag(): void {
    makeDraggable(this.element, this.title);
  }
}

/** Lets a floating window be moved by its title bar. */
export function makeDraggable(win: HTMLElement, title: HTMLElement): void {
  let drag: { x: number; y: number } | null = null;
  title.addEventListener('pointerdown', (e) => {
    if ((e.target as HTMLElement).closest('button')) return;
    drag = { x: e.clientX - win.offsetLeft, y: e.clientY - win.offsetTop };
    title.setPointerCapture(e.pointerId);
  });
  title.addEventListener('pointermove', (e) => {
    if (!drag || !win.parentElement) return;
    const max = win.parentElement;
    win.style.left = `${Math.max(0, Math.min(max.clientWidth - 120, e.clientX - drag.x))}px`;
    win.style.top = `${Math.max(0, Math.min(max.clientHeight - 40, e.clientY - drag.y))}px`;
  });
  title.addEventListener('pointerup', () => { drag = null; });
}

/** Generic floating tool window (minimise, close) sharing the sheet window's look. */
export class ToolWindow {
  readonly element: HTMLElement;

  constructor(title: string, body: HTMLElement, private readonly onVisibility: (open: boolean) => void = () => {}, extraClass = '',
    onPopOut?: () => void) {
    const mk = (text: string, label: string, fn: () => void) => {
      const b = el('button', { type: 'button', title: label }, text);
      b.addEventListener('click', fn);
      return b;
    };
    const bar = el('div', { class: 'dh-win-title' }, el('h2', {}, title),
      ...(onPopOut ? [mk('\u29C9', 'Pop out to a new browser window', onPopOut)] : []),
      mk('\u2013', 'Minimise', () => this.element.classList.toggle('minimised')),
      mk('\u2715', 'Close', () => this.setOpen(false)));
    this.element = el('section', { class: `dh-win ${HIDDEN} ${extraClass}`.trim(), ariaLabel: title }, bar, el('div', { class: 'dh-win-body' }, body));
    makeDraggable(this.element, bar);
    trackStacking(this.element);
  }

  get isOpen(): boolean {
    return !this.element.classList.contains(HIDDEN);
  }

  setOpen(open: boolean): void {
    this.element.classList.toggle(HIDDEN, !open);
    if (open) { this.element.classList.remove('minimised'); bringToFront(this.element); }
    this.onVisibility(open);
  }

  toggle(): void {
    this.setOpen(!this.isOpen);
  }
}