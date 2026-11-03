import { el } from '../login';
import { renderSheetView, SheetViewOptions } from './view';

const HIDDEN = 'hidden';
export const DRAG_TYPE = 'application/x-dmmt-compendium';

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
    onDrop?: (payload: string) => void,
  ) {
    const mk = (text: string, title: string, fn: () => void) => {
      const b = el('button', { type: 'button', title }, text);
      b.addEventListener('click', fn);
      return b;
    };
    this.title = el('div', { class: 'dh-win-title' });
    this.body = el('div', { class: 'dh-win-body' }, renderSheetView(options));
    this.title.append(
      el('h2', {}, `${actorName} - Daggerheart sheet`),
      mk('\u29C9', 'Pop out to a new browser window', () => { this.setOpen(false); onPopOut(); }),
      mk('\u2013', 'Minimise', () => this.element.classList.toggle('minimised')),
      mk('\u2715', 'Close', () => this.setOpen(false)),
    );
    this.element = el('section', { class: `dh-win ${HIDDEN}`, ariaLabel: `${actorName} character sheet` }, this.title, this.body);
    this.enableDrag();
    if (onDrop) this.enableDrop(onDrop);
  }

  private enableDrop(onDrop: (payload: string) => void): void {
    const accepts = (e: DragEvent) => !!e.dataTransfer?.types.includes(DRAG_TYPE);
    this.element.addEventListener('dragover', (e) => {
      if (!accepts(e)) return;
      e.preventDefault();
      this.element.classList.add('drop-target');
    });
    this.element.addEventListener('dragleave', (e) => {
      if (e.relatedTarget instanceof Node && this.element.contains(e.relatedTarget)) return;
      this.element.classList.remove('drop-target');
    });
    this.element.addEventListener('drop', (e) => {
      this.element.classList.remove('drop-target');
      if (!accepts(e)) return;
      e.preventDefault();
      onDrop(e.dataTransfer!.getData(DRAG_TYPE));
    });
  }

  get isOpen(): boolean {
    return !this.element.classList.contains(HIDDEN);
  }

  setOpen(open: boolean): void {
    this.element.classList.toggle(HIDDEN, !open);
    if (open) this.element.classList.remove('minimised');
    this.onVisibility(open);
  }

  toggle(): void {
    this.setOpen(!this.isOpen);
  }

  private enableDrag(): void {
    let drag: { x: number; y: number } | null = null;
    const win = this.element;
    this.title.addEventListener('pointerdown', (e) => {
      if ((e.target as HTMLElement).closest('button')) return;
      drag = { x: e.clientX - win.offsetLeft, y: e.clientY - win.offsetTop };
      this.title.setPointerCapture(e.pointerId);
    });
    this.title.addEventListener('pointermove', (e) => {
      if (!drag || !win.parentElement) return;
      const max = win.parentElement;
      win.style.left = `${Math.max(0, Math.min(max.clientWidth - 120, e.clientX - drag.x))}px`;
      win.style.top = `${Math.max(0, Math.min(max.clientHeight - 40, e.clientY - drag.y))}px`;
    });
    this.title.addEventListener('pointerup', () => { drag = null; });
  }
}

