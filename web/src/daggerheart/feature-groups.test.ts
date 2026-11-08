import { describe, expect, it } from 'vitest';
import { characterFeatureGroups } from './feature-groups';
import type { Feature } from './data';

describe('character feature groups', () => {
  it('orders categories stably without changing saved arrays', () => {
    const features: Feature[] = [
      { source: 'Subclass foundation - Test', name: 'Foundation', text: '' },
      { source: 'Class feature - Test', name: 'Class one', text: '' },
      { source: 'Hope feature - Test', name: 'Hope', text: '', hope: true },
      { source: 'Class feature - Test', name: 'Class two', text: '' },
      { source: 'Subclass mastery - Test', name: 'Mastery', text: '' },
    ];
    const heritage: Feature[] = [
      { source: 'Transformation - Test', name: 'Transformation', text: '' },
      { source: 'Community - Test', name: 'Community', text: '' },
      { source: 'Ancestry - Test', name: 'Ancestry', text: '' },
    ];
    const saved = [...features, ...heritage];
    expect(characterFeatureGroups(features, heritage).map(g => g.feature.name))
      .toEqual(['Hope', 'Class one', 'Class two', 'Foundation', 'Mastery', 'Ancestry', 'Community', 'Transformation']);
    expect([...features, ...heritage]).toEqual(saved);
  });

  it('keeps explicitly granted features inside their parent', () => {
    const parent: Feature = {
      source: 'Subclass mastery - Test', name: 'Elements', text: '',
      automation: { groupGrantedFeatures: true, grantsFeatures: ['Fire', 'Earth'] },
    };
    const fire: Feature = { source: parent.source, name: 'Fire', text: 'Fire rules' };
    const earth: Feature = { source: parent.source, name: 'Earth', text: 'Earth rules' };
    const other: Feature = { source: 'Class feature - Test', name: 'Fire', text: 'Different source' };
    expect(characterFeatureGroups([fire, parent, earth, other], []))
      .toEqual([{ feature: other, children: [] }, { feature: parent, children: [fire, earth] }]);
  });
});
