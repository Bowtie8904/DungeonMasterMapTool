import { describe, expect, it, vi } from 'vitest';
import { App } from './app';

function fixture() {
  const context = {
    accountColorKey: '', drawingColor: '#55aa33', colorSelection: null as string | null,
    colorInput: { value: '#55aa33' }, measureColorInput: { value: '#80d8ff' },
    view: { measurementColor: '#80d8ff', invalidate: vi.fn() }, readStyle: vi.fn(),
  };
  const apply = (campaign: string, player: string, color?: string) =>
    Reflect.get(App.prototype, 'setAccountColor').call(context, campaign, player, color);
  return { context, apply };
}

describe('account color defaults', () => {
  it('initializes both pickers and retains overrides on reconnect to the same account', () => {
    const { context, apply } = fixture();
    apply('campaign', 'player', '#2979ff');
    expect(context.colorInput.value).toBe('#2979ff');
    expect(context.drawingColor).toBe('#2979ff');
    expect(context.measureColorInput.value).toBe('#2979ff');
    expect(context.view.measurementColor).toBe('#2979ff');
    context.colorInput.value = '#ff9100';
    context.measureColorInput.value = '#ffd600';
    context.view.measurementColor = '#ffd600';
    apply('campaign', 'player', '#2979ff');
    expect(context.colorInput.value).toBe('#ff9100');
    expect(context.measureColorInput.value).toBe('#ffd600');
    expect(context.view.measurementColor).toBe('#ffd600');
    expect(context.readStyle).toHaveBeenCalledTimes(1);
  });

  it('updates defaults for another campaign/account without recoloring a selected drawing', () => {
    const { context, apply } = fixture();
    apply('campaign', 'player', '#2979ff');
    context.colorSelection = 'saved-drawing';
    context.colorInput.value = '#123456';
    apply('another-campaign', 'player', '#00c853');
    expect(context.colorInput.value).toBe('#123456');
    expect(context.drawingColor).toBe('#00c853');
    expect(context.view.measurementColor).toBe('#00c853');
    context.colorSelection = null;
    apply('another-campaign', 'another-player', '#ffd600');
    expect(context.colorInput.value).toBe('#ffd600');
    expect(context.measureColorInput.value).toBe('#ffd600');
  });

  it('keeps legacy defaults when an older host omits account colors', () => {
    const { context, apply } = fixture();
    apply('campaign', 'player');
    expect(context.colorInput.value).toBe('#55aa33');
    expect(context.measureColorInput.value).toBe('#80d8ff');
    expect(context.readStyle).not.toHaveBeenCalled();
  });
});
