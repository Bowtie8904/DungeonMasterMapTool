"""Converts testdata/*.txt (Daggerheart exports) into dmmap-daggerheart/*.json.

Run from the repository root:  python tools/daggerheart/convert.py
See docs/DAGGERHEART_DATA.md for the output format.
"""
import json
import os
import re
import sys
from copy import deepcopy
from collections import Counter

sys.path.insert(0, os.path.dirname(__file__))
from parse import records, markdown  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = os.path.join(ROOT, 'testdata')
OUT = os.path.join(ROOT, 'dmmap-daggerheart')
with open(os.path.join(os.path.dirname(__file__), 'rest-automation.json'), encoding='utf-8') as _rest_file:
    REST_AUTOMATION = json.load(_rest_file)
with open(os.path.join(os.path.dirname(__file__), 'damage-automation.json'), encoding='utf-8') as _damage_file:
    DAMAGE_AUTOMATION = json.load(_damage_file)
DAMAGE_USED = set()

MINUS = '[−–-]'
TRAITS = ['Agility', 'Strength', 'Finesse', 'Instinct', 'Presence', 'Knowledge']
UNTIL = {'long rest': 'longRest', 'short rest': 'shortRest', 'rest': 'rest', 'scene': 'scene', 'session': 'session'}
stats = Counter()


def slug(s):
    return re.sub(r'[^a-z0-9]+', '-', s.lower().replace('’', '').replace("'", '')).strip('-')


def num(sign, n):
    return -int(n) if re.match(MINUS, sign) else int(n)


def plain(s):
    return re.sub(r'\*+', '', markdown(s)) if s else ''


# ------------------------------------------------------------------ modifiers

STAT_NAMES = {'evasion': 'evasion', 'armor score': 'armorScore', 'proficiency': 'proficiency'}
for _t in TRAITS:
    STAT_NAMES[_t.lower()] = 'trait.' + _t


def flat_modifiers(text):
    """Passive modifiers of simple, unconditional statements such as '+1 to Evasion; -1 to Agility'."""
    mods = []
    if re.match(r'^(?:When|While|If|Before|After|Until|Once)\b', text, re.I):
        return mods
    for seg in re.split(r';|\.(?=\s)', text):
        seg = seg.strip()
        if re.search(r'\b(?:when|while|if|before|after|until|once)\b', seg, re.I):
            continue
        m = re.match(r'^(?:[A-Za-z\' -]+: )?(' + MINUS + r'|\+)\s*(\d+)\s*(?:bonus |penalty )?(?:to |on )?(?:your )?(.*?)\.?$', seg)
        if not m:
            continue
        v = num(m.group(1), m.group(2))
        what = m.group(3).strip().lower()
        if what in STAT_NAMES:
            mods.append({'stat': STAT_NAMES[what], 'value': v})
        elif what == 'all character traits and evasion':
            mods.append({'stat': 'traits', 'value': v})
            mods.append({'stat': 'evasion', 'value': v})
        elif what in ('damage thresholds', 'damage threshold'):
            mods.append({'stat': 'thresholds', 'value': v})
        elif what == 'severe damage threshold':
            mods.append({'stat': 'severeThreshold', 'value': v})
        elif what == 'major damage threshold':
            mods.append({'stat': 'majorThreshold', 'value': v})
        elif what == 'spellcast rolls':
            mods.append({'roll': 'spellcast', 'value': v})
        elif what == 'attack rolls':
            mods.append({'roll': 'attack', 'value': v})
    return mods


FORMULAS = [
    (r'bonus to your damage thresholds equal to your Spellcast trait', [{'stat': 'thresholds', 'value': '@spellcast'}]),
    (r'bonus to your Armor Score equal to your Presence', [{'stat': 'armorScore', 'value': '@presence'}]),
    (r"armor's score and thresholds increase by a number equal to your spellcasting trait",
     [{'stat': 'armorScore', 'value': '@spellcast'}, {'stat': 'thresholds', 'value': '@spellcast'}]),
    (r'bonus to your damage thresholds equal to your tier',
     [{'stat': 'thresholds', 'value': '@tier'}, {'stat': 'loadoutMax', 'value': -1}]),
    (r'bonus to your damage rolls equal to your Agility', [{'roll': 'damage', 'value': '@agility'}]),
    (r'bonus to your damage rolls equal to your level', [{'roll': 'damage', 'value': '@level'}]),
]


def modifiers_from(text):
    text = plain(text)
    mods = flat_modifiers(text)
    for pat, m in FORMULAS:
        # A matching phrase inside "while/when/if ..." is not an always-on bonus.
        if re.fullmatch(r'(?:Gain a |Requires spellcasting trait; the )' + pat + r'\.?', text, re.I):
            mods.extend(m)
    return mods


# ------------------------------------------------------------------ uses / actions

NUMWORDS = {'a': 1, 'an': 1, 'one': 1, 'two': 2, 'three': 3, 'four': 4}


def count(word):
    word = word.lower()
    return NUMWORDS.get(word, int(word) if word.isdigit() else None)


def uses_from(text):
    if re.search(r'\b(?:instead of|such as)\b', text, re.I):
        return None
    m = re.search(r'\b(once|twice|(\w+) times) per (long rest|short rest|rest|scene|session)', text, re.I)
    if not m:
        return None
    n = 1 if m.group(1).lower() == 'once' else 2 if m.group(1).lower() == 'twice' else count(m.group(2))
    if not n:
        return None
    return {'max': n, 'per': UNTIL[m.group(3).lower()]}


def cost_from(text):
    """Resource costs written as 'spend a Hope', 'mark a Stress', 'mark an Armor Slot'."""
    # Only voluntary costs of the character: "you can/may mark ...", or a sentence starting with "Mark ...".
    cost = {}
    lead = r"(?:\byou (?:can|may) (?!not\b)|(?:^|[.;:] ))"
    n = r'(a|an|one|two|three|\d+)'
    for key, verb, res in (('hope', 'spend', 'Hope'), ('stress', 'mark', 'Stress'),
                           ('armor', 'mark', 'Armor Slots?'), ('hp', 'mark', 'Hit Points?')):
        m = re.search(lead + verb + ' ' + n + ' ' + res + r'\b', text, re.I)
        if m and 'instead' not in text[m.end():m.end() + 12]:
            cost[key] = count(m.group(1))
    # Separate choices/triggered subfeatures must never be charged together.
    return cost if len(cost) < 2 else {}


def action_from(label, text):
    """A 'Use' button that spends what the text says it costs and/or consumes a use."""
    text = re.sub(r'\s+', ' ', plain(text)).strip()
    cost = cost_from(text)
    uses = uses_from(text)
    if not cost and not uses:
        return None, None
    action = {'id': 'use', 'label': label or 'Use'}
    if cost:
        action['cost'] = cost
    return action, uses


def automation_for(label, text, extra_mods=True):
    auto = {}
    mods = modifiers_from(text) if extra_mods else []
    if mods:
        auto['modifiers'] = mods
    action, uses = action_from(label, text)
    if uses:
        auto['uses'] = uses
    if action:
        auto['actions'] = [action]
    return auto


# Curated mechanics, not prose inference. Keys are export feature/card/item names;
# source descriptions remain authoritative for applicability and early expiry.
def step(op, resource, amount):
    return {'op': op, 'resource': resource, 'amount': amount}


def action(aid, label, steps=None, cost=None):
    a = {'id': aid, 'label': label}
    if steps:
        a['steps'] = steps
    if cost:
        a['cost'] = cost
    return a


def option(oid, label, roll, value=None, dice=None, cost=None, description=None, **extra):
    o = {'id': oid, 'label': label, 'roll': roll}
    for key, val in [('value', value), ('dice', dice), ('cost', cost), ('description', description)]:
        if val is not None:
            o[key] = val
    o.update(extra)
    return o


def passive(stat, value):
    return {'modifiers': [{'stat': stat, 'value': value}]}


def pool(rid, label, maximum=None, initial=0):
    r = {'id': rid, 'label': label, 'initial': initial}
    if maximum is not None:
        r['max'] = maximum
    return r


def effect_action(label, mods, until='rest', cost=None, aid='use'):
    return action(aid, label, [{'op': 'effect', 'effect': {
        'name': label, 'kind': 'buff', 'until': until, 'text': label,
        'modifiers': mods}}], cost)


def trigger(tid, label, on, when=None, description=None, **extra):
    t = {'id': tid, 'label': label, 'on': on}
    if when:
        t['when'] = when
    if description:
        t['description'] = description
    t.update(extra)
    return t


CURATED_FEATURES = {
    'Strange Patterns': {
        'resources': [pool('strange-pattern', 'Strange Patterns number', 12)],
        'rollTriggers': [
            trigger('hope', 'Strange Patterns: gain a Hope', 'duality', 'dieMatches', matchResource='strange-pattern',
                    steps=[step('gain', 'hope', 1)], description='A Duality Die matches your chosen number.'),
            trigger('stress', 'Strange Patterns: clear a Stress', 'duality', 'dieMatches', matchResource='strange-pattern',
                    steps=[step('clear', 'stress', 1)], description='A Duality Die matches your chosen number.')]},
    'Ranger’s Focus': {
        'rollOptions': [option('rangers-focus', 'Ranger’s Focus', 'attack', cost={'hope': 1},
                               description='Spend a Hope on this attack; on a success the target becomes your Focus.',
                               effect={'name': 'Ranger’s Focus', 'kind': 'buff', 'until': 'scene',
                                      'text': 'Your attacks against your Focus can be rerolled once by ending this feature.'})],
        'rollTriggers': [trigger('reroll', 'Ranger’s Focus: reroll (ends the feature)', 'attack', 'failure', reroll='duality',
                                 requiresEffect='Ranger’s Focus', endEffect='Ranger’s Focus',
                                 description='Reroll a failed attack against your Focus.')]},
    'Hawkeye': {'rollTriggers': [trigger('reroll', 'Hawkeye: reroll Hope Die', 'attack', cost={'hope': 2}, reroll='hope',
                                         description='Attack Roll at Close range or further.')]},
    'Nimble Fingers': {'rollTriggers': [trigger('reroll', 'Nimble Fingers: reroll Hope Die', 'trait:Finesse',
                                                cost={'hope': 2}, reroll='hope')]},
    'Feline Instincts': {'rollTriggers': [trigger('reroll', 'Feline Instincts: reroll Hope Die', 'trait:Agility',
                                                  cost={'hope': 2}, reroll='hope')]},
    'Internal Compass': {'rollTriggers': [trigger('reroll', 'Internal Compass: reroll Hope Die', 'duality', 'hopeDie',
                                                  die=1, reroll='hope')]},
    'Luckbender': {'uses': {'max': 1, 'per': 'session'},
                   'rollTriggers': [trigger('reroll', 'Luckbender: reroll Duality Dice', 'duality', cost={'hope': 3},
                                            reroll='duality')]},
    'Adaptability': {'rollTriggers': [trigger('reroll', 'Adaptability: reroll', 'duality', 'failure', cost={'stress': 1},
                                              reroll='duality', description='Only when the failed roll used one of your Experiences.')]},
    'Fearless': {'rollTriggers': [trigger('hope', 'Fearless: change Fear into Hope', 'duality', 'fear', cost={'stress': 2},
                                          steps=[step('gain', 'hope', 1)], description='The GM does not gain Fear.')]},
    'Unbound': {'uses': {'max': 1, 'per': 'session'},
                'rollTriggers': [trigger('hope', 'Unbound: change Fear into Hope', 'duality', 'fear',
                                         steps=[step('gain', 'hope', 1)], description='The GM does not gain Fear.')]},
    'Shadowed Grit': {'rollTriggers': [trigger('hope', 'Shadowed Grit: gain a Hope', 'duality', 'fear', cost={'stress': 1},
                                               steps=[step('gain', 'hope', 1)], description='The GM gained a Fear from this roll.')]},
    'Patron’s Boon': {'rollTriggers': [trigger('reroll', 'Patron’s Boon: reroll with advantage', 'duality', 'failure',
                                               cost={'hope': 3}, reroll='duality', advantage=True)]},
    'Courage': {'rollTriggers': [trigger('hope', 'Courage: gain a Hope', 'duality', 'fear', steps=[step('gain', 'hope', 1)])]},
    'Endurance': {'creation': {'hp': 1}},
    'High Stamina': {'creation': {'stress': 1}},
    'Nimble': {'creation': {'evasion': 1}},
    'Prepared': {'creation': {'domainCards': 1}},
    'Battlemage': {'creation': {'hp': 1}},
    'At Ease': {'creation': {'stress': 1}},
    'Stoneskin': {'modifiers': [{'stat': 'armorScore', 'value': 1}, {'stat': 'thresholds', 'value': 1}]},
    'Shell': passive('thresholds', '@proficiency'),
    'Rugged': passive('severeThreshold', 3),
    'Fleeting Shadow': passive('evasion', 1),
    'Ascendant': passive('severeThreshold', 4),
    'Unwavering': passive('thresholds', 1),
    'Unrelenting': passive('thresholds', 2),
    'Undaunted': passive('thresholds', 3),
    'Gifted': {'modifiers': [{'roll': 'action', 'value': 1}, {'roll': 'damage', 'value': 1}]},
    'Favor': {
        'resources': [pool('favor', 'Favor', initial=3)],
        'actions': [
            action('tribute', 'Downtime tribute', [step('gain', 'favor', '@spellcast')]),
            action('gain-favor', 'Successful Hope roll: gain Favor instead (do not gain Hope)',
                   [step('gain', 'favor', 1)]),
            action('hope-to-favor', 'Successful Hope roll: replace gained Hope with Favor',
                   [step('gain', 'favor', 1)], {'hope': 1})],
        'manual': ['Choose only one Favor button for a successful action roll with Hope. Gain instead does not '
                   'award Hope (use this if Hope is already full); replace spends Hope already awarded. '
                   'Tribute uses a downtime move. Favor has no cap.']},
    'Patron’s Pact': {'rollOptions': [option(
        'patron-die', 'Patron Die', 'action', dice='1d6', cost={'favor': 1},
        diceByLevel={'5': '1d8'}, description='Only an action related to your patron’s sphere of influence.')]},
    'Patron’s Mantle': {
        'actions': [effect_action('Patron’s Mantle', [{'stat': 'thresholds', 'value': '@tier'}],
                                  'scene', {'favor': 1})],
        'manual': ['End the effect early on Severe damage; intimidation advantage is manual.']},
    'Draining Bane': {'actions': [action('drain', 'Drain attacker; clear own Stress',
                                                     [step('clear', 'stress', 1)], {'favor': 1})]},
    'Harrowing Invocation': {'actions': [action('use', 'Harrowing Invocation', cost={'favor': 1})]},
    'Damage Sink': {'uses': {'max': 1, 'per': 'rest'},
                    'actions': [action('use', 'Halve incoming damage (manual)', cost={'favor': 1})]},
    'Dark Aegis': {'uses': {'max': 1, 'per': 'longRest'},
                   'actions': [action('use', 'Prevent damage (manual)', cost={'favor': 1})]},
    'Fearsome Attack': {'actions': [action('use', 'Reroll damage dice (manual)', cost={'favor': 1})]},
    'Menacing Reach': {'actions': [action('use', 'Increase weapon range (manual)', cost={'favor': 1})]},
    'Patron’s Fury': {
        'rollOptions': [option('patrons-fury', 'Patron’s Fury', 'damage', dice='1d6',
                               diceByLevel={'2': '2d6', '5': '3d8', '8': '4d8'}, cost={'favor': 1},
                               description='Tier-scaled Patron Dice; lasts until you deal Severe damage or the scene ends.',
                               effect={'name': 'Patron’s Fury', 'kind': 'buff', 'until': 'scene',
                                      'text': 'Patron Dice are added to your damage rolls.'})]},
    'Slayer': {
        'resources': [pool('slayer-dice', 'Slayer Dice', '@proficiency')],
        'actions': [action('store', 'Roll with Hope: store Slayer Die instead (do not gain Hope)',
                           [step('gain', 'slayer-dice', 1)]),
                    action('replace', 'Roll with Hope: replace gained Hope with Slayer Die',
                           [step('gain', 'slayer-dice', 1)], {'hope': 1}),
                    action('clear', 'Clear unspent Slayer Dice', [step('clear', 'slayer-dice', 'all')])],
        'rollOptions': [option('slayer-attack', 'Slayer Die', 'attack', dice='1d6', cost={'slayer-dice': 1}),
                        option('slayer-damage', 'Slayer Die', 'damage', dice='1d6', cost={'slayer-dice': 1})],
        'manual': ['Choose one store/replace button only after a roll with Hope; store does not award Hope, '
                   'replace spends Hope already awarded. '
                   'For multiple dice, roll additional spent dice manually. At session end, gain one Hope per '
                   'unspent die before clearing the pool. Training, rerolls and shared dice are manual.']},
    'Know the Tide': {
        'resources': [pool('tide', 'Tide', '@level')],
        'actions': [action('gain', 'Roll with Fear: store Tide', [step('gain', 'tide', 1)]),
                    action('clear', 'End session: clear Tide', [step('clear', 'tide', 'all')])],
        'rollOptions': [option('tide', 'Spend Tide token', 'action', value=1, cost={'tide': 1})],
        'manual': ['For multiple tokens, spend and add the remaining bonus manually.']},
    'Toxic Concoctions': {
        'resources': [pool('poison', 'Poison tokens')],
        'actions': [action('brew', 'Brew toxic concoctions', [step('gain', 'poison', '1d4+1')], {'stress': 1}),
                    action('poison', 'Afflict with poison (manual)', cost={'poison': 1}),
                    action('clear', 'Long rest: clear poison', [step('clear', 'poison', 'all')])]},
    'Ghost Petal': {'rollOptions': [option('leech-weed', 'Leech Weed', 'damage', dice='1d6', cost={'poison': 1},
                                          description='Successful weapon attack; choose Leech Weed poison.')]},
    'Courage': {'actions': [action('gain', 'Failed roll with Fear: gain Hope', [step('gain', 'hope', 1)])]},
    'Battle Ritual': {'uses': {'max': 1, 'per': 'longRest'},
                      'actions': [action('use', 'Perform battle ritual',
                                         [step('clear', 'stress', 2), step('gain', 'hope', 2)])]},
    'Limit Breaker': {'uses': {'max': 1, 'per': 'rest'},
                      'actions': [action('use', 'Perform athletic feat',
                                         [step('gain', 'hope', 1), step('clear', 'stress', 1)])]},
    'Deadly Determination': {'actions': [action('use', 'Clear 2 Stress', [step('clear', 'stress', 2)], {'hope': 3})]},
    'Frontline Tank': {'actions': [action('use', 'Clear 2 Armor Slots', [step('clear', 'armor', 2)], {'hope': 3})]},
    'No Mercy': {'rollOptions': [option('no-mercy', 'No Mercy', 'attack', value=1, cost={'hope': 3},
                                        description='+1 to attack rolls until your next rest.',
                                        effect={'name': 'No Mercy', 'kind': 'buff', 'until': 'rest',
                                                'text': '+1 bonus to attack rolls.'})]},
    'Rogue’s Dodge': {'actions': [effect_action('Rogue’s Dodge', [{'stat': 'evasion', 'value': 2}], cost={'hope': 3})],
                      'manual': ['Remove the effect early when an attack succeeds against you.']},
    'Tusks': {'rollOptions': [option('tusks', 'Tusks', 'damage', dice='1d6', cost={'hope': 1},
                                     description='Successful attack against a target within Melee range.')]},
    'Kick': {'rollOptions': [option('kick', 'Kick', 'damage', dice='2d6', cost={'stress': 1},
                                    description='Successful Melee attack; resolve knockback manually.')]},
    'Elementalist': {'rollOptions': [
        option('elemental-action', 'Elemental aid: action', 'action', value=2, cost={'hope': 1}),
        option('elemental-damage', 'Elemental aid: damage', 'damage', value=3, cost={'hope': 1})],
        'manual': ['Describe your element’s help; choose either the action bonus or its damage bonus, not both.']},
    'Heart of a Poet': {'rollOptions': [option('poet', 'Heart of a Poet', 'action', dice='1d4', cost={'hope': 1},
                                               description='Only to impress, persuade, or offend someone.')]},
    'Wings of Light': {'rollOptions': [option('wings', 'Wings of Light', 'damage', dice='1d8', cost={'hope': 1},
                                              description='Successful attack while flying; Power of the Gods upgrades this manually.')],
                       'actions': [action('carry', 'Carry willing creature while flying', cost={'stress': 1})]},
    'Sneak Attack': {'rollOptions': [option('sneak', 'Sneak Attack', 'damage', dice='1d6',
                                            diceByLevel={'2': '2d6', '5': '3d6', '8': '4d6'},
                                            description='Successful attack while Cloaked or an ally is in Melee of target.')]},
    'Combat Training': {'rollOptions': [option('training', 'Combat Training', 'damage', value='@level',
                                               description='Physical damage only; ignoring weapon burden is manual.')]},
    'Adrenaline': {'rollOptions': [option('adrenaline', 'Adrenaline', 'damage', value='@level',
                                          description='Only while Vulnerable.')]},
    'Face Your Fear': {'rollOptions': [option('fear', 'Face Your Fear', 'damage', dice='1d10',
                                              description='Successful attack with Fear; upgrades to 2d10/3d10 are manual.')]},
}

# A roll option that carries an effect (Patron's Fury, No Mercy) also gets a feature button that pays the cost and
# starts the effect up front; the roll dialog then shows the option pre-selected and free.
for _feature in CURATED_FEATURES.values():
    for _option in _feature.get('rollOptions', []):
        if _option.get('effect'):
            _feature.setdefault('actions', []).append(
                action('activate', _option['label'], [{'op': 'effect', 'effect': _option['effect']}], _option.get('cost')))

CURATED_CARDS = {
    'Rise Up': {'modifiers': [{'stat': 'severeThreshold', 'value': '@proficiency'}],
                'actions': [action('clear', 'After attack marks HP: clear Stress', [step('clear', 'stress', 1)])]},
    'Notorious': {'modifiers': [{'stat': 'loadoutMax', 'value': 1}],
                  'rollOptions': [option('notoriety', 'Leverage notoriety', 'action', value=10, cost={'stress': 1},
                                          description='Only when leveraging your notoriety; prices/vault restriction are manual.')]},
    'Bold Presence': {'rollOptions': [option('presence', 'Bold Presence', 'trait:Presence', value='@strength',
                                             cost={'hope': 1})],
                      'actions': [action('avoid', 'Once per rest: avoid condition (manual)')],
                      'manual': ['Track the separate once-per-rest condition avoidance manually.']},
    'Body Basher': {'rollOptions': [option('basher', 'Body Basher', 'damage', value='@strength',
                                            description='Successful attack with a Melee weapon.')]},
    'Cruel Precision': {'rollOptions': [
        option('finesse', 'Cruel Precision: Finesse', 'damage', value='@finesse'),
        option('agility', 'Cruel Precision: Agility', 'damage', value='@agility')],
        'manual': ['Successful weapon attack only; choose Finesse OR Agility, never both.']},
    'Nature’s Tongue': {'rollOptions': [option('nature', 'Nature’s Tongue', 'spellcast', value=2,
                                               cost={'hope': 1}, description='Only within a natural environment.')]},
    'Boost': {'rollOptions': [option('boost', 'Boost', 'damage', dice='1d10', cost={'stress': 1},
                                      description='Aerial attack boosted off a willing ally in Close range; advantage/movement manual.')]},
    'Forceful Push': {'rollOptions': [option('push', 'Forceful Push', 'damage', dice='1d6',
                                              description='Primary weapon Melee attack succeeds with Hope; knockback manual.')],
                      'actions': [action('vulnerable', 'Make target Vulnerable (manual)', cost={'hope': 1})]},
    'Natural Familiar': {'rollOptions': [option('familiar', 'Natural Familiar', 'damage', dice='1d6',
                                                description='Adversary is in Melee of your active familiar.')],
                         'actions': [action('summon', 'Summon familiar', cost={'hope': 1}),
                                     action('flying', 'Summon flying familiar', cost={'hope': 2}),
                                     action('eyes', 'See through familiar’s eyes', cost={'stress': 1})]},
    'Runic Adrenaline': {'rollOptions': [
        option('runic-' + t.lower(), 'Runic Adrenaline', 'trait:' + t, dice='1d8', cost={'hp': 1})
        for t in ['Strength', 'Agility', 'Finesse']]},
    'Full Surge': {'uses': {'max': 1, 'per': 'longRest'},
                   'actions': [effect_action('Full Surge', [{'stat': 'traits', 'value': 2}], cost={'stress': 3})]},
    'Second Wind': {'uses': {'max': 1, 'per': 'rest'},
                    'actions': [action('stress', 'Clear 3 Stress', [step('clear', 'stress', 3)]),
                                action('hp', 'Clear 1 HP', [step('clear', 'hp', 1)])]},
    'Vampiric Strike': {'actions': [
        action('hp', 'Clear own HP', [step('clear', 'hp', 1)], {'hope': 1}),
        action('stress', 'Clear own Stress', [step('clear', 'stress', 1)], {'hope': 1})]},
    'Battle-Hardened': {'uses': {'max': 1, 'per': 'longRest'},
                        'actions': [action('use', 'Instead of death move: clear HP',
                                           [step('clear', 'hp', 1)], {'hope': 1})]},
    'Unbreakable': {'actions': [action('use', 'At last HP: clear HP', [step('clear', 'hp', '1d6')])],
                    'manual': ['After use, place this card in your vault.']},
    'Savor the Anguish': {'actions': [action('use', 'Nearby adversary takes Severe damage: clear Stress',
                                            [step('clear', 'stress', 1)])]},
    'Gore and Glory': {'actions': [
        action('hope', 'Qualifying critical or defeat: gain Hope', [step('gain', 'hope', 1)]),
        action('stress', 'Qualifying critical or defeat: clear Stress', [step('clear', 'stress', 1)])]},
    'Eldritch Flesh': {'actions': [action('clear', 'Roll with Fear: clear Armor', [step('clear', 'armor', 1)],
                                         {'hope': 2})]},
    'Swift Step': {'actions': [
        action('stress', 'Attack fails: clear Stress', [step('clear', 'stress', 1)]),
        action('hope', 'Attack fails and no Stress to clear: gain Hope', [step('gain', 'hope', 1)])]},
    'Spellcharge': {
        'resources': [pool('spellcharge', 'Spellcharge', '@spellcast')],
        'actions': [action('gain', 'Magic damage: store one token per marked HP', [step('gain', 'spellcharge', 1)])],
        'rollOptions': [option('charge', 'Spellcharge token', 'damage', dice='1d6', cost={'spellcharge': 1})],
        'manual': ['Repeat the gain button once per marked HP from magic damage. Spend extra tokens/dice manually.']},
    'Inspirational Words': {
        'resources': [pool('inspiration', 'Inspiration tokens')],
        'actions': [action('clear', 'Long rest: clear old tokens', [step('clear', 'inspiration', 'all')]),
                    action('prepare', 'After long rest: prepare tokens', [step('gain', 'inspiration', '@presence')]),
                    action('give', 'Give ally a benefit (manual)', cost={'inspiration': 1})]},
    'Restoration': {
        'resources': [pool('restoration', 'Restoration tokens')],
        'actions': [action('clear', 'Long rest: clear old tokens', [step('clear', 'restoration', 'all')]),
                    action('prepare', 'After long rest: prepare tokens', [step('gain', 'restoration', '@spellcast')]),
                    action('hp', 'Clear 2 own HP', [step('clear', 'hp', 2)], {'restoration': 1}),
                    action('stress', 'Clear 2 own Stress', [step('clear', 'stress', 2)], {'restoration': 1}),
                    action('other', 'Touch creature: heal or clear condition (manual)', cost={'restoration': 1})]},
    'Thorn Skin': {
        'resources': [pool('thorns', 'Thorn Skin tokens')],
        'actions': [action('cast', 'Once per rest: sprout thorns', [step('gain', 'thorns', '@spellcast')], {'hope': 1}),
                    action('spend', 'Spend token: roll reduction/retaliation manually', cost={'thorns': 1}),
                    action('clear', 'Rest: clear thorns', [step('clear', 'thorns', 'all')])],
        'manual': ['Track casting once per rest; roll damage reduction and retaliatory damage manually.']},
}

CURATED_ITEMS = {
    'Charging Quiver': {'rollOptions': [option('quiver', 'Charging Quiver', 'damage', value='@tier',
                                               description='Only a successful attack with an arrow stored in this quiver.')]},
    'Piercing Arrows': {'rollOptions': [option('piercing', 'Piercing Arrows', 'damage', value='@proficiency',
                                               uses={'max': 3, 'per': 'rest'},
                                               description='Only a successful attack with one of these arrows.')]},
    'Lorekeeper': {'rollOptions': [option('lorekeeper', 'Lorekeeper', 'action', value=1,
                                           description='Only against one of up to three hostile creatures recorded in the book.')]},
    'Songbook of cursed carols': {'rollOptions': [option('carol', 'Cursed carol', 'action', value=2, cost={'hope': 1},
        description='Only using fire or influencing a mind. For Codex effects roll refund d6 manually (4+ regains Hope).')]},
    'Alconite Crystal': {
        'resources': [pool('alconite', 'Alconite charge', maximum=1, initial=1)],
        'rollOptions': [option('crystal', 'Alconite Crystal', 'spellcast', dice='1d4', cost={'alconite': 1})],
        'actions': [action('recharge', 'Downtime in salt water: recharge', [step('gain', 'alconite', 1)])]},
    'Superior Alconite Crystal': {'rollOptions': [option('crystal', 'Superior Alconite Crystal', 'spellcast', dice='1d4')],
                                  'manual': ['On a 4 the crystal is expended; track charge/recharge manually.']},
    'Premium Bedroll': {'actions': [action('downtime', 'Downtime: clear Stress', [step('clear', 'stress', 1)])]},
}
for _name, _trait in [('Stride', 'Agility'), ('Bolster', 'Strength'), ('Control', 'Finesse'),
                      ('Attune', 'Instinct'), ('Charm', 'Presence'), ('Enlighten', 'Knowledge')]:
    CURATED_ITEMS[_name + ' Relic'] = passive('trait.' + _trait, 1)

# Explicitly tracked simple counters; rolled-die pools (Prayer Dice, Rally Dice)
# are not interchangeable numeric tokens and are deliberately not flattened.
CURATED_CARDS.update({
    'Dark Army': {
        'resources': [pool('fiends', 'Dark Army fiends', maximum=8)],
        'actions': [action('summon', 'Successful Spellcast: summon 8 fiends', [step('gain', 'fiends', 8)]),
                    action('defend', 'Spend fiend: roll 1d8 reduction manually', cost={'fiends': 1}),
                    action('clear', 'Rest: dismiss fiends', [step('clear', 'fiends', 'all')])],
        'rollOptions': [option('fiend', 'Dark Army fiend', 'damage', dice='1d8', cost={'fiends': 1},
                                description='Only a target within Very Close range; spend extra fiends/dice manually.')],
        'manual': ['Summon once per long rest after a successful Spellcast (14), not automatically on resting.']},
    'Twilight Toll': {
        'resources': [pool('twilight', 'Twilight Toll tokens')],
        'actions': [action('gain', 'Successful non-damaging action against chosen target: gain token',
                           [step('gain', 'twilight', 1)]),
                    action('clear', 'New target or rest: clear tokens', [step('clear', 'twilight', 'all')])],
        'rollOptions': [option('toll', 'Twilight Toll token', 'damage', dice='1d12', cost={'twilight': 1},
                                description='Only damage to the chosen target; spend extra tokens/dice manually.')]},
    'Unleash Chaos': {
        'resources': [pool('chaos', 'Unleash Chaos tokens', '@spellcast')],
        'actions': [action('session', 'Start session: prepare tokens', [step('gain', 'chaos', '@spellcast')]),
                    action('replenish', 'Replenish chaos', [step('gain', 'chaos', '@spellcast')], {'stress': 1}),
                    action('spend', 'Spend token: roll 1d10 spell damage manually', cost={'chaos': 1}),
                    action('clear', 'End session: clear tokens', [step('clear', 'chaos', 'all')])],
        'manual': ['Resolve the Spellcast and standalone spell damage manually; tokens are spent even on a failed cast.']},
})
for _name, _rid, _label in [('Invisibility', 'invisibility', 'Invisibility'),
                           ('Uncanny Disguise', 'disguise', 'Disguise')]:
    CURATED_CARDS[_name] = {
        'resources': [pool(_rid, _label + ' tokens')],
        'actions': [action('cast', 'Successful cast: prepare tokens', [step('gain', _rid, '@spellcast')], {'stress': 1}),
                    action('tick', 'Affected creature acts: spend token', cost={_rid: 1}),
                    action('end', 'Effect ends: clear tokens', [step('clear', _rid, 'all')])],
        'manual': ['Check casting prerequisites and end the effect after the last token’s action. '
                   'Clear existing tokens before casting again; target effects are manual.']}



def spell_damage(name, label, dice, when=None, fixed=False, extra_dice=None, cost=None, steps=None, uses=None, tid='damage'):
    dmg = {'dice': dice}
    if fixed:
        dmg['fixed'] = True
    if extra_dice:
        dmg['extraDice'] = extra_dice
    extra = {'damage': dmg}
    if cost:
        extra['cost'] = cost
    if steps:
        extra['steps'] = steps
    if uses:
        extra['uses'] = uses
    return trigger(tid, label, 'card:' + name, when, **extra)


def spell_card(name, *triggers, **extra):
    auto = {'rollTriggers': list(triggers)}
    auto.update(extra)
    return auto


CURATED_CARDS.update({
    'Blighting Strike': spell_card(
        'Blighting Strike',
        spell_damage('Blighting Strike', 'Blighting Strike: roll d6+1 damage (Hope)', 'd6+1', 'hope', tid='hope-damage'),
        spell_damage('Blighting Strike', 'Blighting Strike: roll d10+1 damage (Fear)', 'd10+1', 'fear', tid='fear-damage'),
        trigger('fail-hope', 'Blighting Strike failed: spend a Hope', 'card:Blighting Strike', cost={'hope': 1}),
        trigger('fail-stress', 'Blighting Strike failed: mark a Stress', 'card:Blighting Strike',
                steps=[step('mark', 'stress', 1)])),
    'Blood Spike': spell_card(
        'Blood Spike',
        spell_damage('Blood Spike', 'Blood Spike: mark a Stress, roll d10 damage', 'd10', cost={'stress': 1}),
        trigger('fear', 'Blood Spike (Fear): mark a Stress', 'card:Blood Spike', 'fear', steps=[step('mark', 'stress', 1)])),
    'Bolt Beacon': spell_card(
        'Bolt Beacon', spell_damage('Bolt Beacon', 'Bolt Beacon: spend a Hope, roll d8+2 damage', 'd8+2', cost={'hope': 1})),
    'Corrosive Projectile': spell_card(
        'Corrosive Projectile', spell_damage('Corrosive Projectile', 'Corrosive Projectile: roll d6+4 damage', 'd6+4')),
    'Siphon Essence': spell_card(
        'Siphon Essence',
        spell_damage('Siphon Essence', 'Siphon Essence: roll d12+4 damage (once per long rest)', 'd12+4', 'hope',
                     uses={'max': 1, 'per': 'longRest'}, tid='hope-damage'),
        spell_damage('Siphon Essence', 'Siphon Essence: roll d12+4 damage with +1 Proficiency (Fear)', 'd12+4', 'fear',
                     extra_dice=1, uses={'max': 1, 'per': 'longRest'}, tid='fear-damage')),
    'Preservation Blast': spell_card(
        'Preservation Blast', spell_damage('Preservation Blast', 'Preservation Blast: roll d8+3 damage', 'd8+3')),
    'Rain of Blades': spell_card(
        'Rain of Blades', spell_damage('Rain of Blades', 'Rain of Blades: spend a Hope, roll d8+2 damage', 'd8+2', cost={'hope': 1}),
        manual=['Add the extra 1d8 against Vulnerable targets manually.']),
    'Towering Stalk': spell_card(
        'Towering Stalk', spell_damage('Towering Stalk', 'Towering Stalk: mark a Stress, roll d8 damage', 'd8', cost={'stress': 1})),
    'Cinder Grasp': spell_card(
        'Cinder Grasp', spell_damage('Cinder Grasp', 'Cinder Grasp: roll 1d20+3 damage', '1d20+3', fixed=True)),
    'Vicious Entangle': spell_card(
        'Vicious Entangle', spell_damage('Vicious Entangle', 'Vicious Entangle: roll 1d8+1 damage', '1d8+1', fixed=True)),
    'Conjure Swarm': spell_card(
        'Conjure Swarm', spell_damage('Conjure Swarm', 'Fire Flies: spend a Hope, roll 2d8+3 damage', '2d8+3', fixed=True, cost={'hope': 1})),
    'Grisly Harpoon': spell_card(
        'Grisly Harpoon', spell_damage('Grisly Harpoon', 'Grisly Harpoon: mark a Stress, roll 3d8 damage', '3d8', fixed=True, cost={'stress': 1})),
    'Delerium Grasp': spell_card(
        'Delerium Grasp', spell_damage('Delerium Grasp', 'Delerium Grasp: give the GM a Fear, roll d12+3 damage', 'd12+3')),
    'I See It Coming': {
        'actions': [{'id': 'use', 'label': 'Roll d4 against this attack', 'cost': {'stress': 1},
                     'sendRoll': {'dice': '1d4', 'base': 'evasion', 'label': 'I See It Coming - Evasion against this attack'}}]},
    'Umbral Veil': {
        'resources': [pool('veil', 'Umbral Veil tokens')],
        'actions': [action('cast', 'Encase in shadow (tokens = Fear in the GM pool)', [step('gain', 'veil', '@fear')], {'stress': 1}) | {'uses': {'max': 1, 'per': 'rest'}},
                    action('spend', 'Spend token: -1 to an attack roll', cost={'veil': 1}),
                    action('clear', 'Scene ends: clear tokens', [step('clear', 'veil', 'all')])]},
    'Flight': {
        'resources': [pool('flight', 'Flight tokens')],
        'actions': [action('cast', 'Successful cast: place tokens', [step('gain', 'flight', '@agility')]),
                    action('spend', 'Action roll while flying: spend token', cost={'flight': 1}),
                    action('land', 'Land: clear tokens', [step('clear', 'flight', 'all')])]},
    'Fane of the Wilds': {
        'resources': [pool('fane', 'Fane of the Wilds tokens')],
        'actions': [action('rest', 'Long rest: place tokens (Sage cards in loadout and vault)', [step('clear', 'fane', 'all'), step('gain', 'fane', '@cards_sage')]),
                    action('crit', 'Critical Sage spell: gain a token', [step('gain', 'fane', 1)]),
                    action('clear', 'Long rest: clear tokens', [step('clear', 'fane', 'all')])],
        'rollOptions': [option('fane', 'Fane of the Wilds token', 'spellcast', value=1, cost={'fane': 1})]},
    'Never Upstaged': {
        'resources': [pool('upstaged', 'Never Upstaged tokens')],
        'actions': [action('mark', 'HP marked from an attack: place tokens (set the count above)', cost={'stress': 1}),
                    action('clear', 'Clear tokens', [step('clear', 'upstaged', 'all')])],
        'rollOptions': [option('upstaged-%d' % n, 'Never Upstaged: %d token%s (+%d)' % (n, '' if n == 1 else 's', 5 * n),
                               'damage', value=5 * n, cost={'upstaged': n}) for n in (1, 2, 3, 4)]},
    'Strategic Approach': {
        'resources': [pool('strategic', 'Strategic Approach tokens')],
        'actions': [action('rest', 'Long rest: place tokens', [step('gain', 'strategic', '@knowledge')]),
                    action('spend', 'Spend token: advantage or clear an ally Stress', cost={'strategic': 1}),
                    action('clear', 'Long rest: clear tokens', [step('clear', 'strategic', 'all')])],
        'rollOptions': [option('strategic-d8', 'Strategic Approach: +d8 damage', 'damage', dice='1d8', cost={'strategic': 1})]},
})

CURATED_CONSUMABLES = {
    'Godling’s Pomelo': {'actions': [action('eat', 'Eat', [step('clear', 'hp', 'all'),
                                                         step('clear', 'stress', 'all')])]},
    'Sweet Moss': {'actions': [action('hp', 'During rest: clear HP', [step('clear', 'hp', '1d10')]),
                               action('stress', 'During rest: clear Stress', [step('clear', 'stress', '1d10')])]},
    'Self-Sewing Thread': {'actions': [action('hp', 'Clear HP', [step('clear', 'hp', 1)]),
                                      action('armor', 'Clear Armor Slots', [step('clear', 'armor', 2)])]},
    'Nursewood Sap': {'actions': [action('hp', 'Apply to self: clear HP', [step('clear', 'hp', '1d4')]),
                                 action('stress', 'Apply to self: clear Stress', [step('clear', 'stress', '1d4')]),
                                 action('other', 'Apply: other creature or condition (manual)')]},
    'Votive Bloom Nectar': {'actions': [action('use', 'Drink', [step('clear', 'stress', '1d4')])]},
    'Tincture of Fortified Thoughts': {
        'actions': [action('drink', 'Drink: clear Stress', [step('clear', 'stress', '1d4')])],
        'manual': ['After drinking, mark 2 Stress manually to turn a failed mental reaction into a success '
                   'and end the effect; this does not consume another tincture.']},
    'Steelskin Salve': {'actions': [effect_action('Steelskin Salve', [{'stat': 'thresholds', 'value': '@tier'}], 'scene')]},
    'Featherstep Potion': {'actions': [effect_action('Featherstep Potion', [{'stat': 'evasion', 'value': '@tier'}])]},
    'Drakemantle': {'actions': [effect_action('Drakemantle', [
        {'stat': 'thresholds', 'value': 5}, {'stat': 'proficiency', 'value': 1}], 'scene')]},
    'Potion of Vigilance': {'actions': [effect_action('Potion of Vigilance', [{'stat': 'evasion', 'value': 1}], 'manual')],
                            'manual': ['Remove the effect when you mark a Hit Point.']},
    'Lionheart Tonic': {'actions': [effect_action('Lionheart Tonic', [{'stat': 'proficiency', 'value': 1}], 'manual')],
                        'manual': ['Remove the effect when you roll with Fear.']},
    'Demiurge’s Draught': {'actions': [effect_action('Demiurge’s Draught', [{'stat': 'proficiency', 'value': 1}], 'manual')],
                          'manual': ['Remove the effect after your next successful attack roll.']},
    'Needle Key': {
        'actions': [effect_action('Needle Key', [{'stat': 'trait.Finesse', 'value': -1}], 'longRest', aid='embed')],
        'manual': ['Once embedded, mark a Stress manually when pressing into a lock to morph a key; '
                   'this does not consume another Needle Key.']},
    'Griefroot Powder': {'rollOptions': [option('powder', 'Griefroot Powder', 'action', dice='1d8', consumeItem=True,
        description='Inhale before this action. Resolve scene-long stun reaction checks manually.')]},
    'Toxilily Bulb': {'rollOptions': [option(
        'cloves', 'Toxilily clove', 'damage', value='@proficiency', consumeItem=True,
        description='Smear on a weapon: adds your Proficiency to its damage until your next rest. One bulb gives 1d4 cloves; '
                    'only the first use consumes the bulb.',
        effect={'name': 'Toxilily Bulb', 'kind': 'buff', 'until': 'rest',
                'text': 'Proficiency is added to the coated weapon\'s damage rolls.'})]},
}
for _name, _die in [('Grindletooth Venom', '1d6'), ('Improved Grindletooth Venom', '1d8'),
                    ('Redthorn Saliva', '1d12'), ('Mythic Dust', '1d12'),
                    ('Red Ooze Oil', '1d8'), ('Green Ooze Oil', '1d8')]:
    CURATED_CONSUMABLES[_name] = {
        'rollOptions': [option('coating', _name, 'damage', dice=_die, consumeItem=True,
                               description='Next damage roll with the coated weapon only; apply coating before the attack.')],
        'manual': ['Coating applicability, damage type, any target condition and ongoing damage are manual. '
                   'Selecting the option consumes one inventory item; do not select again after the coating is spent.']}


def curated_auto(category, name, text, fallback):
    overrides = {'features': CURATED_FEATURES, 'domain-cards': CURATED_CARDS, 'items': CURATED_ITEMS,
                 'consumables': CURATED_CONSUMABLES}
    auto = deepcopy(overrides.get(category, {}).get(name, fallback))
    t = plain(text)
    if category == 'domain-cards' and name.startswith('Book of '):
        auto = {}
    if category == 'features' and name in ('Devout', 'Virtuoso', 'Reliable Backup', 'Martial Preparation'):
        auto = {}
    if category == 'weapons':
        if name in ('Powerful', 'Massive'):
            auto['damage'] = {'extraDice': 1, 'dropLowest': 1}
        if name in ('Paired', 'Double Duty', 'Focused'):
            m = re.search(r'\+(\d+) to primary weapon damage', t)
            if m:
                auto['rollOptions'] = [option('primary-damage', name, 'damage', value=int(m.group(1)),
                    weapon='primary', description='Primary weapon only; ' + t)]
        if name == 'Persuasive':
            auto = {'rollOptions': [option('persuasive', 'Persuasive', 'trait:Presence', value=2, cost={'stress': 1})]}
        if name == 'Trusty':
            auto = {'rollOptions': [option('trusty', 'Trusty', 'attack', value=1,
                                            description='Only attacks made with your primary weapon.')]}
    if category == 'armor':
        if name == 'Conduit':
            auto = {'modifiers': [{'stat': 'armorScore', 'value': '@spellcast'},
                                  {'stat': 'thresholds', 'value': '@spellcast'}]}
        if name == 'Attuned':
            auto = {'modifiers': [{'stat': 'thresholds', 'value': '@tier'}, {'stat': 'loadoutMax', 'value': -1}]}
        if name == 'Sharp':
            auto = {'rollOptions': [option('sharp', 'Sharp', 'damage', dice='1d4',
                                            description='Successful attack against a target within Melee range.')]}
        if name == 'Quiet':
            auto = {'rollOptions': [option('quiet', 'Move silently', 'action', value=2)]}
        if name == 'Punishing Weight':
            auto = {'rollOptions': [option('grudge', 'Punishing Weight', 'damage', value=2,
                                            description='Successful Melee attack after marking Armor this scene.')]}
    # Every audited source keeps its prose and explicit boundaries. These annotations
    # describe unsupported work, never grant bonuses inferred from conditional prose.
    return damage_automation(category, name, rest_automation(category, name, t, auto))


def damage_automation(category, name, auto):
    """Attach authored incoming-damage mitigation options (see damage-automation.json)."""
    config = DAMAGE_AUTOMATION.get(category, {}).get(name)
    if config:
        DAMAGE_USED.add((category, name))
        auto['mitigation'] = deepcopy(config['mitigation'])
    return auto


def check_damage_automation():
    unknown = [f'{cat}/{name}' for cat, entries in DAMAGE_AUTOMATION.items() for name in entries
               if (cat, name) not in DAMAGE_USED]
    if unknown:
        raise ValueError('damage-automation.json names no converted entry: ' + ', '.join(sorted(unknown)))


def rest_automation(category, name, text, auto):
    """Export authored rest metadata; the browser never infers mechanics from prose."""
    config = deepcopy(REST_AUTOMATION.get(category, {}).get(name, {}))
    for key, value in config.items():
        if key != 'actions':
            auto[key] = value
    for patch in config.get('actions', []):
        actions = auto.setdefault('actions', [])
        existing = next((a for a in actions if a['id'] == patch['id']), None)
        if existing is None:
            if not patch.get('label'):
                raise ValueError(f'{category}/{name}: action patch {patch["id"]} has no target')
            actions.append(patch)
        else:
            existing.update(patch)
    narrative = re.search(r'(as (an? )?(additional )?downtime (move|project)|use a downtime move|downtime move to|downtime project)', text, re.I)
    if narrative and not auto.get('rest') and not any(a.get('timing') for a in auto.get('actions', [])):
        auto.setdefault('actions', []).append({
            'id': 'downtime-narrative', 'label': 'Narrative downtime move', 'timing': 'downtime'
        })
    return auto


def tally(kind, auto):
    auto.pop('manual', None)
    stats[(kind, 'total')] += 1
    if any(key != 'manual' for key in auto):
        stats[(kind, 'automated')] += 1
    if auto.get('manual'):
        stats[(kind, 'manual')] += 1


# ------------------------------------------------------------------ categories

def feature(name, text, ftype=None):
    f = {'name': name, 'text': markdown(text)}
    if ftype:
        f['type'] = ftype.lower()
    auto = curated_auto('features', name, text, automation_for(name, text, extra_mods=False))
    if auto:
        f['automation'] = auto
    tally('features', auto)
    return f


def features_of(r, typed=False):
    out = []
    for i in range(1, int(r.get('Feature Count') or 0) + 1):
        out.append(feature(r.get('Feature %d Name' % i) or 'Feature', r.get('Feature %d Description' % i),
                           r.get('Feature %d Type' % i) if typed else None))
    return out


def simple_features(path, category):
    entries = []
    for r in records(path):
        entries.append({'id': slug(r['Name']), 'name': r['Name'], 'text': markdown(r.get('Short Description')),
                        'features': features_of(r)})
    return entries


# Basic tier 1 gear offered at character creation ("starter": true). Everything else is loot.
STARTER_ARMOR = {'Gambeson Armor', 'Leather Armor', 'Chainmail Armor', 'Full Plate Armor'}
NON_STARTER_WEAPONS = {
    'Katana', 'Brass Knuckles', 'Scimitar', 'Twisted Dagger', 'Whipsword', 'Rope Dart', 'Brightsword', 'Shadowblade',
    'Enchanted Chakram', 'Casting Dagger', 'Runelock Pistol', 'Arcane Rifle', 'Hatchet', 'Offhand Brass Knuckles',
    'Throwing Knives', 'Fighting Cloak', 'Rune Shield', 'Focus Runes',
}


def armor():
    entries = []
    for r in records(os.path.join(SRC, 'armor.txt')):
        major, severe = [int(x) for x in r['Thresholds'].split('/')]
        e = {'id': slug(r['Name']), 'name': r['Name'], 'tier': int(r['Tier']),
             'armor': {'score': int(r['Score']), 'major': major, 'severe': severe}}
        feat = r.get('Feature')
        if feat:
            fname, _, ftext = feat.partition(': ')
            e['feature'] = {'name': fname, 'text': ftext}
            auto = curated_auto('armor', fname, ftext, automation_for(fname, ftext))
            if auto:
                e['automation'] = auto
            tally('armor', auto)
        else:
            tally('armor', {})
        if int(r['Tier']) == 1 and r['Name'] in STARTER_ARMOR:
            e['starter'] = True
        entries.append(e)
    return entries


def weapons():
    entries = []
    for r in records(os.path.join(SRC, 'weapons.txt')):
        m = re.match(r'^(\d*)d(\d+)(?:\+(\d+))?', r['Damage Formula'])
        dmg = {'dice': '%sd%s' % (m.group(1) or '1', m.group(2)), 'bonus': int(m.group(3) or 0),
               'type': 'magic' if r['Damage Type'] == 'Magic' else 'physical'}
        e = {'id': slug(r['Name']) + '-t%s' % r['Tier'] if False else slug(r['Name']), 'name': r['Name'],
             'tier': int(r['Tier']),
             'weapon': {'slot': r['Category'].lower(), 'trait': r['Trait'], 'range': r['Range'], 'damage': dmg,
                        'burden': r['Burden']}}
        feat = r.get('Feature')
        if feat:
            fname, _, ftext = feat.partition(': ')
            e['feature'] = {'name': fname, 'text': ftext}
            auto = curated_auto('weapons', fname, ftext, automation_for(fname, ftext))
            if auto:
                e['automation'] = auto
            tally('weapons', auto)
        else:
            tally('weapons', {})
        if int(r['Tier']) == 1 and r['Name'] not in NON_STARTER_WEAPONS:
            e['starter'] = True
        entries.append(e)
    return entries


SPELL_HEAD = re.compile(r'^\*{1,3}\s*([^*:]+?)\s*:[\s*]*(.*)$', re.S)


def spell_body(body):
    """Repairs the unbalanced bold markers of the export ('Spend a Hope** to', 'spend a **Hope**')."""
    body = body.replace('**spend a **Hope', '**spend a Hope**')
    if body.count('**') % 2:
        body = '**' + body
    return body.strip()


def grimoire_spells(text):
    """One entry per spell of a grimoire: every paragraph starts with the spell name in bold italics."""
    spells = []
    for para in re.split(r'\n\s*\n', text.strip()):
        m = SPELL_HEAD.match(para.strip())
        if not m:
            print('WARNING unparsed grimoire paragraph', para[:60])
            return []
        body = spell_body(m.group(2))
        if body.count('**') % 2:
            print('WARNING unbalanced spell text', m.group(1))
        spells.append({'name': m.group(1), 'text': body})
    return spells


def domain_cards():
    entries = []
    for r in records(os.path.join(SRC, 'domain_cards.txt')):
        text = markdown(r.get('Description'))
        spells = grimoire_spells(text) if r['Type'] == 'Grimoire' else []
        if spells:
            text = '\n\n'.join('***%s:*** %s' % (s['name'], s['text']) for s in spells)
        e = {'id': slug(r['Name']), 'name': r['Name'], 'cardType': r['Type'], 'domain': r['Domain'],
             'level': int(r['Level']), 'recall': int(r['Recall Cost'] or 0), 'text': text}
        auto = curated_auto('domain-cards', r['Name'], text, automation_for(r['Name'], text, extra_mods=False))
        if spells:
            # A grimoire holds several independent spells: each one carries its own cost/usage rules.
            for s in spells:
                sa = automation_for(s['name'], s['text'], extra_mods=False)
                if sa:
                    s['automation'] = sa
                tally('grimoire-spells', sa)
            e['spells'] = spells
        if auto:
            e['automation'] = auto
        tally('domain-cards', auto)
        entries.append(e)
    return entries


def classes():
    entries = []
    seen = Counter()
    for r in records(os.path.join(SRC, 'classes.txt')):
        stage = r['Type']
        base = stage == 'Base'
        sid = slug(r['Name']) + ('' if base else '-' + stage.lower())
        e = {'id': sid, 'name': r['Name'], 'kind': 'class' if base else 'subclass',
             'domains': (r.get('Domains') or '').split(',') if r.get('Domains') else [],
             'text': markdown(r.get('Short Description'))}
        if base:
            e['startingHp'] = int(r['Starting HP']) if r.get('Starting HP') else None
            e['startingEvasion'] = int(r['Starting Evasion']) if r.get('Starting Evasion') else None
        else:
            e['parent'] = r.get('Parent Class')
            e['stage'] = stage.lower()
            if r.get('Spellcast Trait'):
                e['spellcastTrait'] = r['Spellcast Trait']
        e['features'] = features_of(r, typed=True)
        seen[sid] += 1
        entries.append(e)
    dup = [k for k, v in seen.items() if v > 1]
    if dup:
        print('WARNING duplicate class ids', dup)
    return entries


# ------------------------------------------------------------------ consumables / items

def potion_effect(name, mods, until, text):
    return {'name': name, 'kind': 'buff', 'until': until, 'text': text, 'modifiers': mods}


def hp_stress_action(text):
    """'Clear 1d4+1 HP.' style healing potions."""
    m = re.match(r'^Clear (\d+d\d+(?:\+\d+)?|\d+) (HP|Stress)\.?$', text)
    if not m:
        return None
    res = 'hp' if m.group(2) == 'HP' else 'stress'
    return {'id': 'use', 'label': 'Drink', 'steps': [{'op': 'clear', 'resource': res, 'amount': m.group(1)}]}


def consumable_auto(name, text):
    t = plain(text)
    m = re.match(r'^You gain a \+1 bonus to your next (\w+) Roll\.$', t)
    if m:
        return {'actions': [{'id': 'use', 'label': 'Drink', 'steps': [{'op': 'effect', 'effect': potion_effect(
            name, [{'roll': 'trait:' + m.group(1), 'value': 1, 'consume': True}], 'manual',
            'Next %s Roll gets +1.' % m.group(1))}]}],
                'rollOptions': [option('potion', 'Drink', 'trait:' + m.group(1), value=1, consumeItem=True,
                                       description='Drink it as part of this roll.')]}
    m = re.match(r'^You gain a \+1 bonus to your (\w+) until your next rest\.$', t)
    if m:
        return {'actions': [{'id': 'use', 'label': 'Drink', 'steps': [{'op': 'effect', 'effect': potion_effect(
            name, [{'stat': 'trait.' + m.group(1), 'value': 1}], 'rest', '+1 %s until your next rest.' % m.group(1))}]}]}
    a = hp_stress_action(t)
    if a:
        return {'actions': [a]}
    m = re.search(r'add a (d\d+) to your next damage roll with that weapon', t)
    if m:
        return {'actions': [{'id': 'use', 'label': 'Apply', 'steps': [{'op': 'effect', 'effect': potion_effect(
            name, [{'roll': 'damage', 'dice': m.group(1), 'consume': True}], 'manual',
            'Add a %s to your next damage roll with the coated weapon.' % m.group(1))}]}]}
    m = re.match(r'^You can eat these paired leaves to immediately gain (\d+) Hope\.$', t)
    if m:
        return {'actions': [{'id': 'use', 'label': 'Eat', 'steps': [{'op': 'gain', 'resource': 'hope', 'amount': int(m.group(1))}]}]}
    if t == 'Mark a Stress and clear a HP.':
        return {'actions': [{'id': 'use', 'label': 'Use', 'cost': {'stress': 1},
                             'steps': [{'op': 'clear', 'resource': 'hp', 'amount': 1}]}]}
    if t.startswith('You can eat this meal to clear all HP and Stress'):
        return {'actions': [{'id': 'use', 'label': 'Eat', 'steps': [
            {'op': 'clear', 'resource': 'hp', 'amount': 'all'}, {'op': 'clear', 'resource': 'stress', 'amount': 'all'},
            {'op': 'gain', 'resource': 'hope', 'amount': '1d4'}]}]}
    if t.startswith('You can drink this potion to halve your size'):
        return {'actions': [{'id': 'use', 'label': 'Drink', 'steps': [{'op': 'effect', 'effect': potion_effect(
            name, [{'stat': 'trait.Agility', 'value': 2}, {'stat': 'proficiency', 'value': -1}], 'rest',
            'Halved size: +2 Agility, -1 Proficiency until you drop the form or rest.')}]}]}
    if t.startswith('You can drink this potion to double your size'):
        return {'actions': [{'id': 'use', 'label': 'Drink', 'steps': [{'op': 'effect', 'effect': potion_effect(
            name, [{'stat': 'trait.Strength', 'value': 2}, {'stat': 'proficiency', 'value': 1}], 'rest',
            'Doubled size: +2 Strength, +1 Proficiency until you drop the form or rest.')}]}]}
    if t.startswith('You gain magic wings') or t.startswith('For this scene, you have advantage on all Instinct rolls'):
        return None
    m = re.match(r'^After drinking this potion, the next successful attack you make critically succeeds\.$', t)
    if m:
        return {'actions': [{'id': 'use', 'label': 'Drink', 'steps': [{'op': 'effect', 'effect': potion_effect(
            name, [], 'manual', 'Your next successful attack critically succeeds.')}]}]}
    m = re.search(r'\buntil your next rest\b', t)
    if m and re.match(r'^(When you|You can|After you)', t) and 'advantage' in t:
        return {'actions': [{'id': 'use', 'label': 'Use', 'steps': [{'op': 'effect', 'effect': potion_effect(
            name, [], 'rest', t)}]}]}
    auto = automation_for('Use', t, extra_mods=False)
    return auto or None


def consumables():
    entries = []
    for r in records(os.path.join(SRC, 'consumables.txt')):
        text = markdown(r.get('Description'))
        e = {'id': slug(r['Name']), 'name': r['Name'], 'rarity': r.get('Rarity'), 'text': text}
        auto = curated_auto('consumables', r['Name'], text, consumable_auto(r['Name'], text) or {})
        if auto:
            e['automation'] = auto
        tally('consumables', auto)
        entries.append(e)
    return entries


def items():
    entries = []
    for r in records(os.path.join(SRC, 'items.txt')):
        text = markdown(r.get('Description'))
        e = {'id': slug(r['Name']), 'name': r['Name'], 'rarity': r.get('Rarity'), 'text': text}
        auto = automation_for('Use', text, extra_mods=False)
        m = re.match(r'^(?:You (?:gain|have) )?an? \+(\d)\s*(?:bonus )?to (?:your )?(\w+(?: \w+)?)\.?$', plain(text))
        if m and m.group(2).lower() in STAT_NAMES:
            auto['modifiers'] = [{'stat': STAT_NAMES[m.group(2).lower()], 'value': int(m.group(1))}]
        auto = curated_auto('items', r['Name'], text, auto)
        if auto:
            e['automation'] = auto
        tally('items', auto)
        entries.append(e)
    return entries


# ------------------------------------------------------------------ main

def write(category, label, entries):
    ids = Counter(e['id'] for e in entries)
    for e in entries:
        if ids[e['id']] > 1:
            raise SystemExit('duplicate id %s in %s' % (e['id'], category))
    doc = {'format': 'dmmap-daggerheart', 'version': 1, 'category': category, 'label': label, 'entries': entries}
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, category + '.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print('%-14s %4d entries' % (category, len(entries)))


def audit_counts(entries):
    sources = [s for e in entries for s in (e.get('features') or [e])]
    functional = lambda a: any(key != 'manual' for key in a)
    return {
        'entries': len(entries), 'sources': len(sources),
        'functional': sum(functional(s.get('automation', {})) for s in sources),
        'manual': sum(bool(s.get('automation', {}).get('manual')) for s in sources),
        'manualOnly': sum(bool(s.get('automation', {}).get('manual')) and
                          not functional(s['automation']) for s in sources),
        'plainGear': sum(not s.get('automation') for s in sources),
    }


def main():
    stats.clear()
    categories = [
        ('classes', 'Classes', classes()),
        ('ancestries', 'Ancestries', simple_features(os.path.join(SRC, 'ancestries.txt'), 'ancestries')),
        ('communities', 'Communities', simple_features(os.path.join(SRC, 'communities.txt'), 'communities')),
        ('transformations', 'Transformations', simple_features(os.path.join(SRC, 'transformations.txt'), 'transformations')),
        ('domain-cards', 'Domain cards', domain_cards()),
        ('armor', 'Armor', armor()),
        ('weapons', 'Weapons', weapons()),
        ('consumables', 'Consumables', consumables()),
        ('items', 'Items', items()),
    ]
    for category, label, entries in categories:
        write(category, label, entries)
        print('  audit ' + json.dumps(audit_counts(entries), sort_keys=True))
    check_damage_automation()
    kinds = sorted({k for k, _ in stats})
    for k in kinds:
        print('automation %-13s %4d / %4d; manual boundaries %4d' %
              (k, stats[(k, 'automated')], stats[(k, 'total')], stats[(k, 'manual')]))


if __name__ == '__main__':
    main()
