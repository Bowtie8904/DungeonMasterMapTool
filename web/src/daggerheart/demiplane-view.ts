import { getDemiplaneCharacter } from '../api';
import { noticeDialog } from '../dialogs';
import { el } from '../login';
import { fetchCompendiumCategory } from './compendium';
import { CharacterSheet } from './data';
import { recreateDemiplane } from './demiplane';
import { loadRulesData } from './rules';

export function openDemiplaneImport(actorId: string, canImport: () => boolean,
                                    save: (character: CharacterSheet) => Promise<void>): void {
  const previous = document.activeElement;
  const dialog = el('dialog', { class: 'app-message-dialog demiplane-dialog', ariaLabel: 'Import from Demiplane' });
  const input = el('input', { type: 'url', required: true, ariaLabel: 'Demiplane character link',
    placeholder: 'https://app.demiplane.com/nexus/daggerheart/character-sheet/...' });
  const error = el('p', { class: 'error', role: 'alert' });
  const status = el('p', { class: 'muted', role: 'status', ariaLive: 'polite' });
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const submit = el('button', { type: 'submit', class: 'primary' }, 'Import');
  const form = el('form', {},
    el('h3', {}, 'Import from Demiplane'),
    el('p', {}, 'Paste a publicly shared Daggerheart character link. The character must be viewable without signing in to Demiplane.'),
    el('label', { class: 'dh-roll-field' }, 'Character link', input), status, error,
    el('div', { class: 'acts' }, cancel, submit));
  let busy = false;
  cancel.addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  dialog.addEventListener('close', () => {
    dialog.remove();
    if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
  }, { once: true });
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (busy) return;
    void (async () => {
      busy = true;
      input.disabled = submit.disabled = cancel.disabled = true;
      dialog.setAttribute('aria-busy', 'true');
      error.textContent = '';
      status.textContent = 'Reading the shared character and matching local catalogs...';
      try {
        if (!canImport()) throw new Error('This character can no longer be imported or is read-only.');
        const [source, rules, items] = await Promise.all([
          getDemiplaneCharacter(actorId, input.value.trim()), loadRulesData(), fetchCompendiumCategory('items'),
        ]);
        const result = recreateDemiplane(source, rules, items.entries);
        if (!canImport()) throw new Error('The actor changed while importing. Nothing was imported.');
        status.textContent = 'Saving the imported character...';
        await save(result.character);
        dialog.close();
        await noticeDialog(
          `${result.character.name} was imported at level ${result.character.level} and saved.`
          + (result.reportIssues.length ? `\n\n${result.reportIssues.map(issue => `- ${issue}`).join('\n\n')}` : ''),
          result.reportIssues.length ? 'Character imported - review needed' : 'Character imported');
      } catch (e) {
        status.textContent = '';
        error.textContent = (e as Error).message;
      } finally {
        busy = false;
        input.disabled = submit.disabled = cancel.disabled = false;
        dialog.removeAttribute('aria-busy');
        if (dialog.isConnected) input.focus();
      }
    })();
  });
  dialog.append(form);
  document.body.append(dialog);
  dialog.showModal();
  input.focus();
}
