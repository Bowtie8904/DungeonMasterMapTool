import { getDemiplaneCharacter } from '../api';
import { noticeDialog } from '../dialogs';
import { el } from '../login';
import { fetchCompendiumCategory } from './compendium';
import { CharacterSheet } from './data';
import { DemiplaneAncestryChoiceRequired, recreateDemiplane } from './demiplane';
import { loadRulesData } from './rules';

export function openDemiplaneImport(actorId: string, canImport: () => boolean,
                                    save: (character: CharacterSheet, portrait?: string) => Promise<string | void>): void {
  const previous = document.activeElement;
  const dialog = el('dialog', { class: 'app-message-dialog demiplane-dialog', ariaLabel: 'Import from Demiplane' });
  const input = el('input', { type: 'url', required: true, ariaLabel: 'Demiplane character link',
    placeholder: 'https://app.demiplane.com/nexus/daggerheart/character-sheet/...' });
  const error = el('p', { class: 'error', role: 'alert' });
  const status = el('p', { class: 'muted', role: 'status', ariaLive: 'polite' });
  const choiceArea = el('div', {});
  const cancel = el('button', { type: 'button' }, 'Cancel');
  const submit = el('button', { type: 'submit', class: 'primary' }, 'Import');
  const form = el('form', {},
    el('h3', {}, 'Import from Demiplane'),
    el('p', {}, 'Paste a publicly shared Daggerheart character link. The character must be viewable without signing in to Demiplane.'),
    el('label', { class: 'dh-roll-field' }, 'Character link', input), choiceArea, status, error,
    el('div', { class: 'acts' }, cancel, submit));
  let busy = false;
  let choice: { position: 0 | 1; select: HTMLSelectElement } | undefined;
  let choices: Partial<Record<0 | 1, string>> = {};
  let pending: [Awaited<ReturnType<typeof getDemiplaneCharacter>>, Awaited<ReturnType<typeof loadRulesData>>,
    Awaited<ReturnType<typeof fetchCompendiumCategory>>] | undefined;
  input.addEventListener('input', () => {
    pending = undefined;
    choices = {};
    choice = undefined;
    choiceArea.replaceChildren();
    error.textContent = '';
  });
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
        if (choice) {
          if (!choice.select.value) throw new Error('Choose an ancestry before continuing.');
          choices[choice.position] = choice.select.value;
          choice = undefined;
          choiceArea.replaceChildren();
        }
        const [source, rules, items] = pending ?? await Promise.all([
          getDemiplaneCharacter(actorId, input.value.trim()), loadRulesData(), fetchCompendiumCategory('items'),
        ]);
        pending = [source, rules, items];
        const result = recreateDemiplane(source, rules, items.entries, choices);
        if (!canImport()) throw new Error('The actor changed while importing. Nothing was imported.');
        status.textContent = 'Saving the imported character...';
        const portraitWarning = source.portrait
          ? await save(result.character, source.portrait)
          : await save(result.character);
        const reportIssues = [...result.reportIssues, ...(portraitWarning ? [portraitWarning] : [])];
        dialog.close();
        await noticeDialog(
          `${result.character.name} was imported at level ${result.character.level} and saved.`
          + (reportIssues.length ? `\n\n${reportIssues.map(issue => `- ${issue}`).join('\n\n')}` : ''),
          reportIssues.length ? 'Character imported - review needed' : 'Character imported');
      } catch (e) {
        status.textContent = '';
        if (e instanceof DemiplaneAncestryChoiceRequired) {
          const select = el('select', { required: true, ariaLabel: `Ancestry for ${e.feature}` },
            el('option', { value: '' }, 'Choose an ancestry'),
            ...e.candidates.map(name => el('option', { value: name }, name)));
          choice = { position: e.position, select };
          choiceArea.replaceChildren(el('label', { class: 'dh-roll-field' },
            `${e.position === 0 ? 'First' : 'Second'} feature: ${e.feature}`, select));
          error.textContent = e.message;
        } else error.textContent = (e as Error).message;
      } finally {
        busy = false;
        input.disabled = submit.disabled = cancel.disabled = false;
        dialog.removeAttribute('aria-busy');
        if (dialog.isConnected) (choice?.select ?? input).focus();
      }
    })();
  });
  dialog.append(form);
  document.body.append(dialog);
  dialog.showModal();
  input.focus();
}
