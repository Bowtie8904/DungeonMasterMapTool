import { afterEach, expect, it, vi } from 'vitest';
import { domainCardPreference } from '../api';
import { createCardPreferences, type CardPreferenceState } from './card-preferences';

vi.mock('../api', () => ({ domainCardPreference: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.resetAllMocks(); });

it('loads the account preference and publishes a saved switch to all sheets', async () => {
  vi.stubGlobal('window', { addEventListener: vi.fn() });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.mocked(domainCardPreference).mockResolvedValueOnce({ userId: 'alice', domainCardView: 'list' })
    .mockResolvedValueOnce({ userId: 'alice', domainCardView: 'card' });
  const preferences = createCardPreferences();
  const first = vi.fn();
  const second = vi.fn();
  preferences.subscribe(first);
  preferences.subscribe(second);
  expect(first).toHaveBeenLastCalledWith({ view: 'card', pending: false, error: '' });
  await preferences.load();
  expect(first).toHaveBeenLastCalledWith({ view: 'list', pending: false, error: '' });
  await preferences.save('card');
  expect(domainCardPreference).toHaveBeenLastCalledWith('card');
  expect(first).toHaveBeenLastCalledWith({ view: 'card', pending: false, error: '' });
  expect(second).toHaveBeenLastCalledWith({ view: 'card', pending: false, error: '' });
});

it('retains the previous view and reports load/save failures, suppressing overlapping saves', async () => {
  vi.stubGlobal('window', { addEventListener: vi.fn() });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.mocked(domainCardPreference).mockResolvedValueOnce({ userId: 'alice', domainCardView: 'list' });
  const preferences = createCardPreferences();
  let state: CardPreferenceState | undefined;
  preferences.subscribe(value => { state = value; });
  await preferences.load();
  let reject!: (error: Error) => void;
  vi.mocked(domainCardPreference).mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail; }));
  const save = preferences.save('card');
  expect(state?.pending).toBe(true);
  await preferences.save('card');
  expect(domainCardPreference).toHaveBeenCalledTimes(2);
  reject(new Error('Could not save preference'));
  await save;
  expect(state).toEqual({ view: 'list', pending: false, error: 'Could not save preference' });
  vi.mocked(domainCardPreference).mockRejectedValueOnce(new Error('Update the host'));
  await preferences.load();
  expect(state).toEqual({ view: 'list', pending: false, error: 'Update the host' });
});
