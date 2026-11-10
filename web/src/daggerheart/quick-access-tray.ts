import { el } from '../login';
import { quickAccessPlacement, quickAccessSidePlacement } from './quick-access';

let nextTrayId = 0;

/** Portal outside the clipped sheet, below gameplay dialogs. */
export class QuickAccessTray {
  readonly element: HTMLElement;
  readonly button: HTMLButtonElement;
  private readonly content = el('div', { class: 'quick-scroll' });
  private readonly observer: ResizeObserver;
  private open = false;
  private readonly id: string;
  /** External trigger (e.g. the map-tool rail star) the tray is currently opened from, if any. */
  private external: HTMLElement | null = null;

  constructor(private readonly sheetAnchor: HTMLElement, private readonly refresh: () => void) {
    const id = this.id = `quick-access-${++nextTrayId}`;
    this.button = el('button', { type: 'button', class: 'mini-quick-access', ariaLabel: 'Quick Access' }, '\u2605 Quick Access');
    this.button.setAttribute('aria-controls', id);
    this.button.setAttribute('aria-expanded', 'false');
    this.button.addEventListener('click', () => this.isOpen && !this.external ? this.close() : this.show(null));
    const close = el('button', { type: 'button', ariaLabel: 'Close Quick Access' }, '\u00d7');
    close.addEventListener('click', () => this.close(true));
    this.element = el('div', { id, class: 'dh quick-access-tray', role: 'region', ariaLabel: 'Quick Access' },
      el('div', { class: 'quick-title' }, el('strong', {}, 'Quick Access'), close), this.content);
    this.element.hidden = true;
    this.observer = new ResizeObserver(() => this.position());
  }

  get isOpen(): boolean { return this.open; }

  /** True while the tray is open from an external trigger rather than the minimized sheet bar. */
  get isExternal(): boolean { return this.open && !!this.external; }

  isOpenFrom(trigger: HTMLElement): boolean { return this.open && this.external === trigger; }

  /** Toggles the tray from an external trigger, placed beside that trigger. */
  toggleFrom(trigger: HTMLElement): void {
    if (this.open && this.external === trigger) this.close();
    else this.show(trigger);
  }

  private get anchor(): HTMLElement { return this.external ?? this.sheetAnchor; }
  private get trigger(): HTMLElement { return this.external ?? this.button; }

  setContent(content: HTMLElement): void {
    const scroll = this.content.scrollTop;
    const previous = [...this.content.querySelectorAll<HTMLElement>('button, input, select')];
    const focused = document.activeElement;
    const focusIndex = previous.findIndex(node => node === focused);
    const entryKey = focusIndex >= 0 ? previous[focusIndex].closest<HTMLElement>('[data-quick-key]')?.dataset.quickKey : undefined;
    const focusLabel = focusIndex >= 0 ? previous[focusIndex].getAttribute('aria-label') ?? previous[focusIndex].textContent : undefined;
    this.content.replaceChildren(content);
    this.content.scrollTop = scroll;
    if (focusIndex >= 0) {
      const controls = [...content.querySelectorAll<HTMLElement>('button, input, select')];
      const replacement = controls.find(node =>
        node.closest<HTMLElement>('[data-quick-key]')?.dataset.quickKey === entryKey
        && (node.getAttribute('aria-label') ?? node.textContent) === focusLabel);
      (replacement ?? this.trigger).focus({ preventScroll: true });
    }
    this.position();
  }

  private show(external: HTMLElement | null): void {
    if (this.open) this.close();
    this.external = external;
    this.open = true;
    this.element.classList.toggle('quick-access-side', !!external);
    if (external) external.setAttribute('aria-controls', this.id);
    this.trigger.setAttribute('aria-expanded', 'true');
    document.body.append(this.element);
    this.element.hidden = false;
    document.addEventListener('pointerdown', this.outside, true);
    document.addEventListener('keydown', this.keydown);
    window.addEventListener('resize', this.position);
    window.visualViewport?.addEventListener('resize', this.position);
    window.visualViewport?.addEventListener('scroll', this.position);
    this.anchor.addEventListener('pointermove', this.position);
    this.observer.observe(this.anchor);
    this.refresh();
    this.position();
    this.element.querySelector<HTMLButtonElement>('.quick-title button')?.focus({ preventScroll: true });
  }

  close(restoreFocus = false): void {
    if (!this.open) return;
    this.open = false;
    this.element.hidden = true;
    this.element.remove();
    const trigger = this.trigger;
    trigger.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', this.outside, true);
    document.removeEventListener('keydown', this.keydown);
    window.removeEventListener('resize', this.position);
    window.visualViewport?.removeEventListener('resize', this.position);
    window.visualViewport?.removeEventListener('scroll', this.position);
    this.anchor.removeEventListener('pointermove', this.position);
    this.observer.disconnect();
    this.external = null;
    if (restoreFocus) trigger.focus({ preventScroll: true });
    this.refresh();
  }

  private readonly outside = (event: Event) => {
    if ((event.target as Element).closest('dialog, .dh-rolldlg-backdrop, .dh-wiz-backdrop')) return;
    if (!this.element.contains(event.target as Node) && !this.anchor.contains(event.target as Node)) this.close();
  };

  private readonly keydown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && !event.defaultPrevented && !(event.target as Element).closest('dialog, .dh-rolldlg-backdrop, .dh-wiz-backdrop')) {
      event.preventDefault();
      this.close(true);
    }
  };

  private readonly position = () => {
    if (!this.open) return;
    const viewport = window.visualViewport;
    const offsetX = viewport?.offsetLeft ?? 0;
    const offsetY = viewport?.offsetTop ?? 0;
    const rect = this.anchor.getBoundingClientRect();
    const width = viewport?.width ?? window.innerWidth;
    const height = viewport?.height ?? window.innerHeight;
    const placement = this.external
      ? quickAccessSidePlacement({ right: rect.right - offsetX, bottom: rect.bottom - offsetY }, width, height)
      : quickAccessPlacement({ left: rect.left - offsetX, top: rect.top - offsetY, bottom: rect.bottom - offsetY }, width, height);
    Object.assign(this.element.style, {
      left: `${placement.left + offsetX}px`, width: `${placement.width}px`,
      top: placement.top === undefined ? 'auto' : `${placement.top + offsetY}px`,
      bottom: placement.bottom === undefined ? 'auto' : `${window.innerHeight - offsetY - height + placement.bottom}px`,
      maxHeight: `${placement.maxHeight}px`,
    });
  };
}
