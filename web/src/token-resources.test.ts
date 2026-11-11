import { describe, expect, it } from 'vitest';
import { tokenResourcesOf } from './token-resources';

describe('tokenResourcesOf', () => {
  it('returns null without HP slots', () => {
    expect(tokenResourcesOf({ hp: { slots: 0, total: 0, on: 0 } } as never)).toBeNull();
  });
});
