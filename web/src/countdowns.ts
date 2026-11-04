import type { Countdown } from './protocol';
import { el } from './login';

/** Player-only display: no editable controls or outgoing commands. */
export function renderCountdowns(container: HTMLElement, countdowns: readonly Countdown[] = []): void {
  container.classList.toggle('hidden', countdowns.length === 0);
  const heading = countdowns.length ? [el('div', { class: 'countdowns-heading' }, 'COUNTDOWNS')] : [];
  container.replaceChildren(...heading, ...countdowns.map(countdown =>
    el('div', { class: 'countdown-row' },
      el('span', { class: 'countdown-name', title: countdown.name }, countdown.name),
      el('strong', { class: 'countdown-value' }, `${countdown.value} / ${countdown.initialValue}`),
    ),
  ));
}
