const CONDITION_ICON_IDS = [
  'cond_bleeding', 'cond_blessed', 'cond_blinded', 'cond_burning', 'cond_charmed',
  'cond_concentrating', 'cond_dead', 'cond_deafened', 'cond_exhaustion', 'cond_frightened',
  'cond_grappled', 'cond_hasted', 'cond_hidden', 'cond_incapacitated', 'cond_invisible',
  'cond_paralyzed', 'cond_petrified', 'cond_poisoned', 'cond_prone', 'cond_raging',
  'cond_restrained', 'cond_slowed', 'cond_stunned', 'cond_unconscious',
] as const;

const BUILT_IN_ICONS: Record<string, string> = {
  hidden: 'cond_invisible',
  restrained: 'cond_restrained',
  vulnerable: 'cond_concentrating',
};

const RESERVED_ICON_IDS = new Set(Object.values(BUILT_IN_ICONS));

export const DEFAULT_CUSTOM_CONDITION_ICON = 'cond_bleeding';
export const CUSTOM_CONDITION_ICON_IDS = CONDITION_ICON_IDS.filter(iconId => !RESERVED_ICON_IDS.has(iconId));
const CUSTOM_ICON_SET = new Set<string>(CUSTOM_CONDITION_ICON_IDS);

export function conditionIconId(type: string, requestedIconId?: string): string {
  if (BUILT_IN_ICONS[type]) return BUILT_IN_ICONS[type];
  return requestedIconId && CUSTOM_ICON_SET.has(requestedIconId)
    ? requestedIconId : DEFAULT_CUSTOM_CONDITION_ICON;
}

export function conditionIconUrl(iconId: string): string {
  return `/condition-icons/${iconId}_round.png`;
}
