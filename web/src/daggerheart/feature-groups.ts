import type { Automation } from './engine';

type GroupableFeature = {
  name: string;
  source?: string;
  automation?: Automation;
};

/** Group only explicitly linked, unambiguous features from the same source. */
export function groupFeatures<T extends GroupableFeature>(features: T[]): Array<{ feature: T; children: T[] }> {
  const claimed = new Set<T>();
  const childrenByParent = new Map<T, T[]>();
  for (const parent of features) {
    if (!parent.automation?.groupGrantedFeatures) continue;
    const children: T[] = [];
    for (const name of parent.automation.grantsFeatures ?? []) {
      const matches = features.filter(f => f !== parent && f.name === name && f.source === parent.source);
      const parents = features.filter(f => f.source === parent.source && f.automation?.groupGrantedFeatures
        && f.automation.grantsFeatures?.includes(name));
      if (matches.length !== 1 || parents.length !== 1 || claimed.has(matches[0]) || matches[0].automation?.groupGrantedFeatures) continue;
      claimed.add(matches[0]);
      children.push(matches[0]);
    }

    childrenByParent.set(parent, children);
  }
  return features.filter(f => !claimed.has(f)).map(feature => ({ feature, children: childrenByParent.get(feature) ?? [] }));
}

export function characterFeatureGroups<T extends GroupableFeature & { hope?: boolean }>(features: T[], heritage: T[]): Array<{ feature: T; children: T[] }> {
  const rank = (f: T) => {
    if (f.hope || f.source?.startsWith('Hope feature')) return 0;
    if (f.source?.startsWith('Subclass')) return 2;
    if (f.source?.startsWith('Ancestry')) return 3;
    if (f.source?.startsWith('Community')) return 4;
    if (f.source?.startsWith('Transformation')) return 5;
    return 1;
  };
  return [...groupFeatures(features), ...groupFeatures(heritage)]
    .sort((a, b) => rank(a.feature) - rank(b.feature));
}
