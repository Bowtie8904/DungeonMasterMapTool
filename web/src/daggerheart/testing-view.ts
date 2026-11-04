import { el } from '../login';
import { CharacterSheet, TestingBuild } from './data';
import { classEntries, findClass, loadRulesData, MAX_LEVEL, STAGES, subclassesOf } from './rules';
import { applyTestingBuild, defaultTestingBuild, testingBuildProblems } from './testing';

export async function openTestingEditor(host: HTMLElement, c: CharacterSheet, onDone: () => void): Promise<void> {
  if (!c.testingCharacter) throw new Error('This is not a testing character.');
  const overlay = el('div', { class: 'dh dh-wiz-backdrop' });
  const box = el('div', { class: 'dh-wiz', role: 'dialog', ariaLabel: 'Testing character builder' });
  const body = el('div', { class: 'dh-wiz-body' }, 'Loading compendium...');
  const error = el('p', { class: 'dh-wiz-warn', role: 'alert' });
  const close = () => { overlay.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') close(); };
  const cancel = el('button', { type: 'button' }, 'Cancel');
  cancel.addEventListener('click', close);
  const apply = el('button', { type: 'button', class: 'primary', disabled: true }, 'Apply testing build');
  box.append(el('div', { class: 'dh-wiz-head' }, el('h2', {}, 'Testing character')),
    el('p', { class: 'dh-wiz-hint' }, 'Testing only. All cards in your domains are active, regardless of level. Class and heritage changes replace their features and resources; equipment and notes are kept.'),
    body, el('div', { class: 'dh-wiz-foot' }, cancel, error, apply));
  overlay.append(box);
  host.ownerDocument.body.append(overlay);
  document.addEventListener('keydown', onKey);
  try {
    const data = await loadRulesData();
    if (!overlay.isConnected) return;
    const draft: TestingBuild = structuredClone(c.testingBuild ?? defaultTestingBuild(data));
    const field = (label: string, values: string[], selected: string, change: (value: string) => void): HTMLElement => {
      const select = el('select', { ariaLabel: label });
      for (const value of values) {
        const option = el('option', { value }, value || 'None');
        option.selected = value === selected;
        select.append(option);
      }
      select.addEventListener('change', () => { change(select.value); draw(); });
      return el('label', { class: 'dh-wiz-row' }, el('span', {}, label), select);
    };
    const classFields = (entry: Pick<TestingBuild, 'className' | 'subclass' | 'stage'>, label: string) => [
      field(`${label} class`, classEntries(data).map((e) => e.name), entry.className, (name) => {
        entry.className = name;
        entry.subclass = subclassesOf(data, name)[0] ?? '';
        if ('domain' in entry) entry.domain = findClass(data, name)?.domains?.[0] ?? '';
      }),
      field(`${label} subclass`, subclassesOf(data, entry.className), entry.subclass, (name) => { entry.subclass = name; }),
      field(`${label} stage`, STAGES, entry.stage, (stage) => { if (STAGES.some((s) => s === stage)) entry.stage = STAGES.find((s) => s === stage)!; }),
    ];
    const draw = () => {
      const level = el('input', { type: 'number', min: '1', max: String(MAX_LEVEL), step: '1', value: String(draft.level), ariaLabel: 'Testing level' });
      level.addEventListener('input', () => { draft.level = level.valueAsNumber; validate(); });
      const add = el('button', { type: 'button' }, 'Add multiclass');
      const available = classEntries(data).filter((e) => e.name !== draft.className && !draft.multiclasses.some((m) => m.className === e.name));
      add.disabled = !available.length;
      add.addEventListener('click', () => {
        const cls = available[0];
        draft.multiclasses.push({ className: cls.name, subclass: subclassesOf(data, cls.name)[0] ?? '', domain: cls.domains?.[0] ?? '', stage: 'mastery' });
        draw();
      });
      body.replaceChildren(el('label', { class: 'dh-wiz-row' }, 'Level', level), ...classFields(draft, 'Primary'));
      draft.multiclasses.forEach((m, i) => {
        const remove = el('button', { type: 'button' }, `Remove multiclass ${i + 1}`);
        remove.addEventListener('click', () => { draft.multiclasses.splice(i, 1); draw(); });
        body.append(el('h3', {}, `Multiclass ${i + 1}`), ...classFields(m, `Multiclass ${i + 1}`),
          field(`Multiclass ${i + 1} domain`, findClass(data, m.className)?.domains ?? [], m.domain, (value) => { m.domain = value; }), remove);
      });
      body.append(add,
        field('Ancestry', data.ancestries.map((e) => e.name), draft.ancestry, (value) => { draft.ancestry = value; }),
        field('Mixed ancestry (second feature)', ['', ...data.ancestries.map((e) => e.name)], draft.ancestry2 ?? '', (value) => { draft.ancestry2 = value || undefined; }),
        field('Community', data.communities.map((e) => e.name), draft.community, (value) => { draft.community = value; }),
        field('Transformation', ['', ...data.transformations.map((e) => e.name)], draft.transformation ?? '', (value) => { draft.transformation = value || undefined; }));
      validate();
    };
    const validate = () => {
      const problems = testingBuildProblems(data, draft);
      error.textContent = problems.join(' ');
      apply.disabled = problems.length > 0;
    };
    apply.addEventListener('click', () => {
      try { applyTestingBuild(data, c, draft); onDone(); close(); }
      catch (e) { error.textContent = (e as Error).message; }
    });
    draw();
  } catch (e) {
    body.replaceChildren();
    error.textContent = `Could not load testing choices: ${(e as Error).message}`;
  }
}
