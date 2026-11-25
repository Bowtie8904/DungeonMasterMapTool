export interface ChatActionLabel {
  label: string;
  disabled: boolean;
  detail: string;
}

/** Read the rendered controls, not a second implementation of feature eligibility. */
export function chatActionLabels(card: HTMLElement): ChatActionLabel[] {
  return Array.from(card.querySelectorAll<HTMLButtonElement>('button'))
    .filter(button => {
      const hidden = button.closest('[hidden], .hidden');
      return !hidden || !card.contains(hidden);
    })
    .map(button => ({
      label: (button.textContent?.trim() || button.getAttribute('aria-label') || button.title).slice(0, 200),
      disabled: button.disabled,
      detail: button.title.slice(0, 200),
    }))
    .filter(action => !!action.label)
    .slice(0, 128);
}

/** One observer for the bounded chat tail; coalesce asynchronous action paints and sheet refreshes. */
export class ChatActionReports {
  private signatures = new Map<string, string>();
  private timer: number | undefined;

  constructor(private root: HTMLElement, private send: (report: Record<string, unknown>) => boolean) {
    new MutationObserver(() => this.schedule()).observe(root, {
      subtree: true, childList: true, characterData: true, attributes: true,
      attributeFilter: ['disabled', 'hidden', 'title', 'aria-label'],
    });
  }

  reset(): void {
    this.signatures.clear();
    this.schedule();
  }

  private schedule(): void {
    if (this.timer !== undefined) return;
    this.timer = window.setTimeout(() => {
      this.timer = undefined;
      const current = new Map<string, string>();
      for (const card of Array.from(this.root.children) as HTMLElement[]) {
        const { messageId, messageTs } = card.dataset;
        if (messageId === undefined || messageTs === undefined) continue;
        const key = `${messageId}:${messageTs}`;
        const actions = chatActionLabels(card);
        const signature = JSON.stringify(actions);
        if (!actions.length && !this.signatures.has(key) || this.signatures.get(key) === signature
            || this.send({ type: 'chatActions', id: Number(messageId), ts: Number(messageTs), actions })) {
          current.set(key, signature);
        }
      }
      this.signatures = current;
    }, 100);
  }
}
