"""Converts testdata/*.txt (Daggerheart exports) into dmmap-daggerheart/*.json.

Run from the repository root:  python tools/daggerheart/convert.py
See docs/DAGGERHEART_DATA.md for the output format.
"""
import json
import os
import re
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(__file__))
from parse import records, markdown  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = os.path.join(ROOT, 'testdata')
OUT = os.path.join(ROOT, 'dmmap-daggerheart')

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
    for seg in re.split(r';|\.(?=\s)', text):
        seg = seg.strip()
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
        elif what == 'attack rolls made with your primary weapon':
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
        if re.search(pat, text, re.I):
            mods.extend(m)
    m = re.search(r': \+(\d+) Evasion;', text)
    if m and not any(x.get('stat') == 'evasion' for x in mods):
        mods.append({'stat': 'evasion', 'value': int(m.group(1))})
    m = re.match(r'^[A-Za-z\' -]+: -(\d) Evasion;', text)
    if m and not any(x.get('stat') == 'evasion' for x in mods):
        mods.append({'stat': 'evasion', 'value': -int(m.group(1))})
        m2 = re.search(r'; -(\d) Agility', text)
        if m2:
            mods.append({'stat': 'trait.Agility', 'value': -int(m2.group(1))})
    return mods


# ------------------------------------------------------------------ uses / actions

NUMWORDS = {'a': 1, 'an': 1, 'one': 1, 'two': 2, 'three': 3, 'four': 4}


def count(word):
    word = word.lower()
    return NUMWORDS.get(word, int(word) if word.isdigit() else None)


def uses_from(text):
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
    return cost


def action_from(label, text):
    """A 'Use' button that spends what the text says it costs and/or consumes a use."""
    text = plain(text)
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


def tally(kind, auto):
    stats[(kind, 'total')] += 1
    if auto:
        stats[(kind, 'automated')] += 1


# ------------------------------------------------------------------ categories

def feature(name, text, ftype=None):
    f = {'name': name, 'text': markdown(text)}
    if ftype:
        f['type'] = ftype.lower()
    auto = automation_for(name, text, extra_mods=False)
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
            auto = automation_for(fname, ftext)
            if auto:
                e['automation'] = auto
            tally('armor', auto)
        else:
            tally('armor', {'x': 1})
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
            auto = automation_for(fname, ftext)
            if auto:
                e['automation'] = auto
            tally('weapons', auto)
        else:
            tally('weapons', {'x': 1})
        if int(r['Tier']) == 1 and r['Name'] not in NON_STARTER_WEAPONS:
            e['starter'] = True
        entries.append(e)
    return entries


def domain_cards():
    entries = []
    for r in records(os.path.join(SRC, 'domain_cards.txt')):
        text = markdown(r.get('Description'))
        e = {'id': slug(r['Name']), 'name': r['Name'], 'cardType': r['Type'], 'domain': r['Domain'],
             'level': int(r['Level']), 'recall': int(r['Recall Cost'] or 0), 'text': text}
        auto = automation_for(r['Name'], text, extra_mods=False)
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
            'Next %s Roll gets +1.' % m.group(1))}]}]}
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
        auto = consumable_auto(r['Name'], text)
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


def main():
    write('classes', 'Classes', classes())
    write('ancestries', 'Ancestries', simple_features(os.path.join(SRC, 'ancestries.txt'), 'ancestries'))
    write('communities', 'Communities', simple_features(os.path.join(SRC, 'communities.txt'), 'communities'))
    write('transformations', 'Transformations', simple_features(os.path.join(SRC, 'transformations.txt'), 'transformations'))
    write('domain-cards', 'Domain cards', domain_cards())
    write('armor', 'Armor', armor())
    write('weapons', 'Weapons', weapons())
    write('consumables', 'Consumables', consumables())
    write('items', 'Items', items())
    kinds = sorted({k for k, _ in stats})
    for k in kinds:
        print('automation %-13s %4d / %4d' % (k, stats[(k, 'automated')], stats[(k, 'total')]))


if __name__ == '__main__':
    main()
