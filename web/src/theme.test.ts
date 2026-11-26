import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const styles = readFileSync(new URL('./style.css', import.meta.url), 'utf8');
const sheet = readFileSync(new URL('./daggerheart/sheet.css', import.meta.url), 'utf8');
const desktop = readFileSync(new URL('../../src/main/resources/dmmt/ui/dark.css', import.meta.url), 'utf8');

function rule(css: string, selector: string): string {
  const block = css.replace(/\/\*[\s\S]*?\*\//g, '').split('}')
    .find(block => block.slice(0, block.indexOf('{')).trim() === selector);
  expect(block, selector).toBeDefined();
  return block!.slice(block!.indexOf('{') + 1);
}

describe('shared interface accents', () => {
  it('shows cyan accents on chat source previews and breakdowns in both clients', () => {
    const preview = rule(styles, '.roll-source-preview');
    expect(preview).toContain('border: 1px solid var(--accent-border)');
    expect(preview).toContain('border-left: 3px solid var(--accent)');
    expect(preview).toContain('background: linear-gradient(135deg, var(--accent-soft), transparent 60%), var(--panel)');
    expect(preview).toContain('color: var(--text)');
    for (const selector of ['.roll-source-preview > strong', '.roll-detail-total']) {
      expect(rule(styles, selector)).toContain('color: var(--accent)');
    }
    expect(rule(styles, '.source-preview-detail')).toContain('color: var(--muted)');
    expect(rule(styles, '.roll-breakdown summary::marker')).toContain('color: var(--accent)');
    expect(rule(styles, '.roll-breakdown summary:hover')).toContain('color: var(--accent)');
    expect(rule(styles, '.dh-pool-die')).toContain('border: 1px solid var(--border)');
    expect(rule(desktop, '.root')).toContain('-dm-chat-accent: #4fc3f7');
    expect(rule(desktop, '.chat-source-preview')).toContain('-fx-border-color: -dm-chat-accent-border -dm-chat-accent-border -dm-chat-accent-border -dm-chat-accent');
    for (const selector of ['.chat-summary:hover', '.chat-detail-total .label', '.chat-source-preview .chat-source-name']) {
      expect(rule(desktop, selector)).toContain('-fx-text-fill: -dm-chat-accent');
    }
  });

  it('uses the interaction accent throughout creation, advancement and sheet controls', () => {
    for (const selector of [
      '.dh-wiz-backdrop .dh-wiz', '.dh-wiz-head h2, .dh-wiz-head h3',
      '.dh-wiz-progress span.now', '.dh-wiz-sec > h4', '.dh .cho .tag',
      '.dh .cho.sel', '.dh .cho.sel > b', '.dh .side h4', '.dh .side-feat h5',
      '.dh .chip.on', '.trait-tile .tt-val', '.trait-tile .seg button.on',
      'table.gear tr.grp th', 'table.gear tr.pick.sel', 'table.gear tr.pick.sel td.nm',
      '.dh > .stat-tooltip', '.dh .stat-tooltip .sr.tot',
      '.sheet-edit-switch[aria-checked="true"] .switch-track',
      '.sheet-edit-switch:focus-visible, .editable-portrait:focus-visible',
      '.mini-trait:hover', '.mini-evasion', '.dh .row.exp .mod',
      '.mini-quick-access[aria-expanded="true"]',
      '.dh button.favorite-button[aria-pressed="true"]',
      '.dh .favorite-button:focus-visible, .mini-quick-access:focus-visible, .dh .quick-description-toggle:focus-visible',
    ]) {
      const declarations = rule(sheet, selector);
      expect(declarations, selector).toContain('var(--accent)');
      expect(declarations, selector).not.toContain('--hope');
      expect(declarations, selector).not.toContain('#f5b942');
    }
    expect(rule(sheet, '.dh-wiz-backdrop .dh-wiz')).toContain('border-top: 3px solid var(--accent)');
    expect(rule(sheet, '.dh-wiz-head')).toContain('background: linear-gradient(90deg, var(--accent-soft)');
    expect(rule(sheet, '.dh-wiz-side')).toContain('border-left: 1px solid var(--accent-border)');
    expect(rule(sheet, '.dh > .stat-tooltip')).toContain('border-left: 3px solid var(--accent)');
    for (const selector of ['.dh .cho.sel', 'table.gear tr.pick.sel']) {
      expect(rule(sheet, selector)).toContain('background: var(--accent-soft)');
    }
    expect(rule(sheet, '.trait-tile .seg button.on')).toContain('color: var(--bg)');
  });

  it('preserves meaningful Hope, currency, critical, reaction and warning colors', () => {
    expect(rule(sheet, '.dh')).toContain('--hope: #f5b942');
    expect(rule(sheet, '.dh .feat.hope .src')).toContain('color: var(--hope)');
    expect(rule(sheet, '.dh .goldbar .cat')).toContain('color: var(--hope)');
    expect(rule(sheet, '.dh-wiz-warn')).toContain('color: #ffb74d');
    expect(rule(styles, '.roll-outcome.hope')).toContain('color: #ffda84');
    expect(rule(styles, '.roll-outcome.crit')).toContain('color: #ffe3a0');
    expect(rule(styles, '.reaction-banner')).toContain('color: #e6c46a');
  });
});
