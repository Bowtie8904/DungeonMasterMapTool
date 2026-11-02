import { ApiError, getSession, login } from './api';
import { markAudioGesture } from './settings';

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Omit<Partial<HTMLElementTagNameMap[K]>, 'style'> & { class?: string; style?: string } = {} as never,
  ...children: Array<Node | string>
): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  const { class: cls, style, ...rest } = props as Record<string, unknown>;
  if (cls) e.className = String(cls);
  if (style) e.style.cssText = String(style);
  Object.assign(e, rest);
  for (const c of children) e.append(c);
  return e;
}

/** Renders the login form into `root`; calls onSuccess after a successful login. */
export async function showLogin(root: HTMLElement, onSuccess: () => void): Promise<void> {
  root.replaceChildren();
  const card = el('form', { class: 'login-card' });
  const title = el('h1', {}, 'Online Play');
  const info = el('p', { class: 'muted' }, 'Loading session...');
  const select = el('select', { name: 'player', required: true });
  const password = el('input', { type: 'password', name: 'password', required: true, placeholder: 'Password', autocomplete: 'current-password' });
  const error = el('p', { class: 'error' });
  const button = el('button', { type: 'submit', class: 'primary' }, 'Join and enable sound');
  button.disabled = true;
  card.append(title, info, el('label', {}, 'Player', select), el('label', {}, 'Password', password), error, button);
  root.append(el('div', { class: 'center' }, card));

  let joining = false;
  let refreshing = false;
  let stopped = false;
  let timer: number | undefined;
  const refresh = async (): Promise<void> => {
    if (refreshing || joining || stopped) return;
    refreshing = true;
    try {
      const session = await getSession();
      if (stopped || !card.isConnected) return;
      info.textContent = `${session.sessionName || 'Session'}${session.campaignName ? ' - ' + session.campaignName : ''}${session.paused ? ' (paused)' : ''}`;
      const selected = select.value;
      select.replaceChildren(...session.players.map(p => el('option', { value: p.id }, p.name)));
      if (session.players.some(p => p.id === selected)) select.value = selected;
      select.disabled = session.players.length === 0;
      button.disabled = joining || select.disabled;
      if (select.disabled) info.textContent += ' - No player accounts are currently available.';
    } catch (e) {
      if (stopped || !card.isConnected) return;
      info.textContent = `Cannot reach the session: ${(e as Error).message}`;
      button.disabled = true;
    } finally {
      refreshing = false;
    }
  };
  await refresh();
  timer = window.setInterval(() => {
    if (!card.isConnected || stopped) {
      clearInterval(timer);
      return;
    }
    void refresh();
  }, 2000);

  card.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    if (joining || button.disabled) return;
    joining = true;
    error.textContent = '';
    button.disabled = true;
    markAudioGesture();
    try {
      await login(select.value, password.value);
      stopped = true;
      clearInterval(timer);
      onSuccess();
    } catch (e) {
      error.textContent = e instanceof ApiError ? e.message : `Login failed: ${(e as Error).message}`;
      joining = false;
      await refresh();
    }
  });
}
