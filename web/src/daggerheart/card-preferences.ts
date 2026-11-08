import { domainCardPreference, type DomainCardPreference, type DomainCardView } from '../api';

export interface CardPreferenceState {
  view: DomainCardView;
  pending: boolean;
  error: string;
}

export interface CardPreferences {
  subscribe(listener: (state: CardPreferenceState) => void): void;
  load(): Promise<void>;
  save(view: DomainCardView): Promise<void>;
}

export function createCardPreferences(): CardPreferences {
  let preference: DomainCardPreference | undefined;
  let pending = false;
  let error = '';
  let revision = 0;
  let channel: BroadcastChannel | undefined;
  const listeners = new Set<(state: CardPreferenceState) => void>();
  const publish = () => listeners.forEach(listener => listener({ view: preference?.domainCardView ?? 'card', pending, error }));
  const refresh = async () => {
    if (pending) return;
    const current = ++revision;
    try {
      const loaded = await domainCardPreference();
      if (current !== revision) return;
      preference = loaded;
      error = '';
    } catch (e) {
      if (current !== revision) return;
      error = (e as Error).message;
    }
    publish();
  };
  return {
    subscribe(listener) {
      if (!listeners.size) {
        window.addEventListener('focus', () => { void refresh(); });
        if (typeof BroadcastChannel !== 'undefined') {
          channel = new BroadcastChannel('dmmt-card-preferences');
          channel.addEventListener('message', event => {
            if (event.data?.userId === preference?.userId) void refresh();
          });
        }
      }
      listeners.add(listener);
      listener({ view: preference?.domainCardView ?? 'card', pending, error });
    },
    load: refresh,
    async save(view) {
      if (pending) return;
      ++revision;
      pending = true;
      error = '';
      publish();
      try {
        preference = await domainCardPreference(view);
        channel?.postMessage({ userId: preference.userId });
      } catch (e) {
        error = (e as Error).message;
      } finally {
        pending = false;
        publish();
      }
    },
  };
}

export const cardPreferences = createCardPreferences();
