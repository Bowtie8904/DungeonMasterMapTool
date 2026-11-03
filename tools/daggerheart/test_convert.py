"""Export-backed regression tests. Run: python -m unittest discover -s tools\\daggerheart."""
import json
import os
import unittest

import convert


def catalogs():
    return {
        'classes': convert.classes(),
        'ancestries': convert.simple_features(os.path.join(convert.SRC, 'ancestries.txt'), 'ancestries'),
        'communities': convert.simple_features(os.path.join(convert.SRC, 'communities.txt'), 'communities'),
        'transformations': convert.simple_features(os.path.join(convert.SRC, 'transformations.txt'), 'transformations'),
        'domain-cards': convert.domain_cards(),
        'armor': convert.armor(),
        'weapons': convert.weapons(),
        'consumables': convert.consumables(),
        'items': convert.items(),
    }


class CatalogAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = catalogs()

    def entry(self, category, name):
        return next(e for e in self.data[category] if e['name'] == name)

    def feature(self, name):
        return next(f for e in self.data['classes'] + self.data['ancestries'] + self.data['communities']
                    + self.data['transformations'] for f in e.get('features', []) if f['name'] == name)

    def sources(self):
        return [s for entries in self.data.values() for e in entries for s in [e] + e.get('features', [])]

    def test_all_export_entries_are_preserved(self):
        self.assertEqual({k: len(v) for k, v in self.data.items()}, {
            'classes': 101, 'ancestries': 27, 'communities': 15, 'transformations': 6,
            'domain-cards': 236, 'armor': 110, 'weapons': 336, 'consumables': 182, 'items': 174})
        for entries in self.data.values():
            self.assertEqual(len(entries), len({e['id'] for e in entries}))

    def test_documented_audit_totals(self):
        counts = [convert.audit_counts(entries) for entries in self.data.values()]
        self.assertEqual({key: sum(c[key] for c in counts) for key in counts[0]}, {
            'entries': 1187, 'sources': 1294, 'functional': 684,
            'manual': 0, 'manualOnly': 0, 'plainGear': 610})

    def test_generated_files_match_converter_and_are_deterministic(self):
        self.assertEqual(self.data, catalogs())
        for category, entries in self.data.items():
            path = os.path.join(convert.OUT, category + '.json')
            with open(path, encoding='utf-8') as f:
                document = json.load(f)
            self.assertEqual(document['entries'], entries, category)
            self.assertEqual(document['format'], 'dmmap-daggerheart')
            self.assertEqual(document['version'], 1)
            self.assertEqual(document['category'], category)
            with open(path, 'rb') as f:
                actual = f.read()
            expected = (json.dumps(document, ensure_ascii=False, indent=1) + '\n').encode('utf-8')
            self.assertEqual(actual, expected, category)

    def test_damage_mitigation_metadata_is_exported_for_every_config_entry(self):
        valid = {'id', 'label', 'description', 'passive', 'defaultOn', 'when', 'cost', 'uses', 'consumeItem', 'quantity',
                 'halve', 'reduce', 'negate', 'thresholds', 'severity', 'hp', 'replaceHp', 'armorSlots', 'armorSeverity',
                 'armorCost', 'armorBlocked', 'saveArmor', 'roll', 'followUp'}
        for category, definitions in convert.DAMAGE_AUTOMATION.items():
            if category == 'features':
                candidates = [f for entries in self.data.values() for e in entries for f in e.get('features', [])]
            elif category in ('weapons', 'armor'):
                candidates = [{'name': e.get('feature', {}).get('name'), 'automation': e.get('automation', {})}
                              for e in self.data[category]]
            else:
                candidates = self.data[category]
            for name, config in definitions.items():
                matches = [s for s in candidates if s['name'] == name]
                self.assertTrue(matches, f'Unused damage config: {category}/{name}')
                for option in config['mitigation']:
                    self.assertLessEqual(set(option), valid, f'{category}/{name}')
                    self.assertTrue(option['id'] and option['label'])
                for source in matches:
                    self.assertEqual(source['automation']['mitigation'], config['mitigation'])
        claws = self.entry('weapons', 'Claws of Yifang')['automation']['mitigation'][0]
        self.assertEqual((claws['when'], claws['halve']), ({'type': 'magic'}, True))
        self.assertNotIn('mitigation', next(e for e in self.data['armor'] if e.get('feature', {}).get('name') == 'Channeling')['automation'])
        with self.assertRaises(ValueError):
            saved = set(convert.DAMAGE_USED)
            try:
                convert.DAMAGE_USED.clear()
                convert.check_damage_automation()
            finally:
                convert.DAMAGE_USED.update(saved)

    def test_authored_rest_metadata_is_exported_without_unused_config_entries(self):
        for category, definitions in convert.REST_AUTOMATION.items():
            if category == 'features':
                candidates = [f for entries in self.data.values() for e in entries for f in e.get('features', [])]
                candidates += self.data['communities']
            elif category in ('weapons', 'armor'):
                candidates = [{'name': e.get('feature', {}).get('name'), 'automation': e.get('automation', {})}
                              for e in self.data[category]]
            else:
                candidates = self.data[category]
            for name, config in definitions.items():
                matches = [s for s in candidates if s['name'] == name]
                self.assertTrue(matches, f'Unused rest config: {category}/{name}')
                for source in matches:
                    auto = source['automation']
                    for key, value in config.items():
                        if key != 'actions':
                            self.assertEqual(auto[key], value)
                    for patch in config.get('actions', []):
                        action = next(a for a in auto['actions'] if a['id'] == patch['id'])
                        for key, value in patch.items():
                            self.assertEqual(action[key], value)

    def test_rest_classification_is_authored_not_inferred_from_action_labels(self):
        auto = convert.rest_automation('items', 'Homebrew', '', {
            'actions': [{'id': 'tribute', 'label': 'Downtime in the label'}]
        })
        self.assertNotIn('timing', auto['actions'][0])
        auto = convert.rest_automation('items', 'Homebrew', 'As a downtime move, describe a ritual.', {})
        self.assertEqual(auto['actions'][0]['timing'], 'downtime')

    def test_additional_options_do_not_grant_slots_but_explicit_grants_do(self):
        for category, name in [('domain-cards', 'Forager'), ('items', 'Potioncraft Kit')]:
            auto = self.entry(category, name)['automation']
            self.assertNotIn('extraMoves', auto.get('rest', {}))
            self.assertTrue(any(a.get('timing') == 'downtime' for a in auto['actions']))
            for action in auto['actions']:
                self.assertNotIn('additional', action.get('downtime', {}))
        self.assertEqual(self.feature('Celestial Trance')['automation']['rest']['extraMoves'], 1)
        self.assertEqual(self.entry('items', 'Timekeeper\u2019s Pendant')['automation']['rest']['extraMoves'], 1)
        for category, name in [('consumables', 'Potion of Stability'), ('domain-cards', 'Safe Haven')]:
            self.assertEqual(self.entry(category, name)['automation']['rest']['passives'][0]['extraMoves'], 1)

    def test_all_resources_and_option_references_are_valid(self):
        autos = [s.get('automation', {}) for s in self.sources()]
        declared = {r['id'] for a in autos for r in a.get('resources', [])}
        known = declared | {'hope', 'hp', 'stress', 'armor'}
        for auto in autos:
            ids = [r['id'] for r in auto.get('resources', [])]
            self.assertEqual(len(ids), len(set(ids)))
            for resource in auto.get('resources', []):
                self.assertTrue(resource['label'])
                self.assertRegex(resource['id'], r'^[a-z][a-z0-9-]*$')
                self.assertIsInstance(resource['initial'], (int, str))
                if 'max' in resource:
                    self.assertIsInstance(resource['max'], (int, str))
            for group in ('actions', 'rollOptions'):
                nodes = auto.get(group, [])
                self.assertEqual(len(nodes), len({n['id'] for n in nodes}))
                for node in nodes:
                    self.assertTrue(node['label'])
                    for rid, amount in node.get('cost', {}).items():
                        self.assertIn(rid, known)
                        self.assertIsInstance(amount, (int, str))
                        if isinstance(amount, int):
                            self.assertGreater(amount, 0)
                    for step in node.get('steps', []):
                        if step['op'] in ('gain', 'clear', 'mark'):
                            self.assertIn(step['resource'], known)
                            self.assertIsInstance(step['amount'], (int, str))
                    if group == 'rollOptions':
                        self.assertRegex(node['roll'], r'^(damage|attack|spellcast|action|trait:(Agility|Strength|Finesse|Instinct|Presence|Knowledge))$')
                        if 'weapon' in node:
                            self.assertIn(node['weapon'], ('source', 'primary', 'secondary', 'any'))
                        self.assertTrue('value' in node or 'dice' in node or 'effect' in node)
                        for level, dice in node.get('diceByLevel', {}).items():
                            self.assertIn(int(level), range(1, 11))
                            self.assertRegex(dice, r'^\d+d\d+$')
                    if 'uses' in node:
                        self.assertGreater(node['uses']['max'], 0)
                        self.assertIn(node['uses']['per'], convert.UNTIL.values())

    def test_formulas_use_supported_named_values(self):
        def walk(value):
            if isinstance(value, dict):
                for child in value.values():
                    walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)
            elif isinstance(value, str) and value.startswith(('@', '-@')):
                self.assertRegex(value, r'^-?@(tier|level|proficiency|spellcast|fear|cards_sage|cards_splendor|hp|armorScore|agility|strength|finesse|instinct|presence|knowledge)$')
        for source in self.sources():
            walk(source.get('automation', {}))

    def test_favor_is_initially_three_and_uncapped(self):
        auto = self.feature('Favor')['automation']
        self.assertEqual(auto['resources'], [{'id': 'favor', 'label': 'Favor', 'initial': 3}])
        tribute, gain, replacement = auto['actions']
        self.assertEqual(tribute['steps'], [convert.step('gain', 'favor', '@spellcast')])
        self.assertNotIn('cost', gain)
        self.assertIn('Successful Hope roll', gain['label'])
        self.assertEqual(gain['steps'], [convert.step('gain', 'favor', 1)])
        self.assertEqual(replacement['cost'], {'hope': 1})
        self.assertIn('Successful Hope roll', replacement['label'])
        self.assertEqual(replacement['steps'], [convert.step('gain', 'favor', 1)])

    def test_patron_die_is_optional_and_upgrades_at_five(self):
        auto = self.feature('Patron’s Pact')['automation']
        opt, = auto['rollOptions']
        self.assertNotIn('modifiers', auto)
        self.assertNotIn('actions', auto)
        self.assertEqual(opt['roll'], 'action')
        self.assertEqual(opt['dice'], '1d6')
        self.assertEqual(opt['diceByLevel'], {'5': '1d8'})
        self.assertEqual(opt['cost'], {'favor': 1})
        self.assertIn('sphere', opt['description'])

    def test_warlock_subclasses_spend_existing_favor(self):
        for name in ('Patron’s Mantle', 'Damage Sink', 'Dark Aegis',
                     'Harrowing Invocation', 'Draining Bane', 'Fearsome Attack', 'Menacing Reach'):
            auto = self.feature(name)['automation']
            self.assertEqual(auto['actions'][0]['cost'], {'favor': 1}, name)
            self.assertNotIn('resources', auto, name)
        self.assertEqual(self.feature('Damage Sink')['automation']['uses'], {'max': 1, 'per': 'rest'})

    def test_charging_quiver_is_situational_tier_bonus(self):
        auto = self.entry('items', 'Charging Quiver')['automation']
        opt, = auto['rollOptions']
        self.assertEqual(opt['roll'], 'damage')
        self.assertEqual(opt['value'], '@tier')
        self.assertIn('arrow', opt['description'])
        self.assertNotIn('modifiers', auto)
        self.assertNotIn('cost', opt)

    def test_piercing_arrows_track_independent_uses(self):
        auto = self.entry('items', 'Piercing Arrows')['automation']
        opt, = auto['rollOptions']
        self.assertNotIn('uses', auto)
        self.assertEqual(opt['uses'], {'max': 3, 'per': 'rest'})
        self.assertEqual(opt['value'], '@proficiency')

    def test_patrons_fury_has_a_button_and_a_roll_option_sharing_one_effect(self):
        auto = self.feature('Patron’s Fury')['automation']
        opt, = auto['rollOptions']
        self.assertEqual(opt['roll'], 'damage')
        self.assertEqual(opt['cost'], {'favor': 1})
        self.assertEqual(opt['dice'], '1d6')
        self.assertEqual(opt['diceByLevel'], {'2': '2d6', '5': '3d8', '8': '4d8'})
        self.assertEqual(opt['effect']['until'], 'scene')
        self.assertNotIn('modifiers', opt['effect'])
        button, = auto['actions']
        self.assertEqual(button['cost'], {'favor': 1})
        self.assertEqual(button['steps'], [{'op': 'effect', 'effect': opt['effect']}])
        no_mercy = self.feature('No Mercy')['automation']
        self.assertEqual(no_mercy['actions'][0]['cost'], {'hope': 3})
        self.assertEqual(no_mercy['rollOptions'][0]['effect']['name'], 'No Mercy')

    def test_toxilily_bulb_is_a_damage_option_that_lasts_until_rest(self):
        opt, = self.entry('consumables', 'Toxilily Bulb')['automation']['rollOptions']
        self.assertEqual((opt['roll'], opt['value'], opt['consumeItem']), ('damage', '@proficiency', True))
        self.assertEqual(opt['effect']['until'], 'rest')

    def test_next_roll_potions_are_offered_in_the_roll_dialog(self):
        for name, trait in [('Stride Potion', 'Agility'), ('Bolster Potion', 'Strength'), ('Control Potion', 'Finesse')]:
            auto = self.entry('consumables', name)['automation']
            opt, = auto['rollOptions']
            self.assertEqual((opt['roll'], opt['value'], opt['consumeItem']), ('trait:' + trait, 1, True), name)
            self.assertTrue(auto['actions'][0]['steps'][0]['effect']['modifiers'][0]['consume'], name)

    def test_powerful_only_modifies_its_source_weapon_damage(self):
        powerful = [e for e in self.data['weapons'] if e.get('feature', {}).get('name') in ('Powerful', 'Massive')]
        self.assertEqual(len(powerful), 14)
        for e in powerful:
            self.assertEqual(e['automation']['damage'], {'extraDice': 1, 'dropLowest': 1})
            self.assertFalse(any(m.get('dice') for m in e['automation'].get('modifiers', [])))
            if e['feature']['name'] == 'Massive':
                self.assertIn({'stat': 'evasion', 'value': -1}, e['automation']['modifiers'])
        self.assertNotIn('damage', self.entry('items', 'Greatstone').get('automation', {}))

    def test_unconditional_gear_modifiers_remain(self):
        for name, modifier in [('Full Plate Armor', {'stat': 'evasion', 'value': -2}),
                                ('Full Plate Armor', {'stat': 'trait.Agility', 'value': -1}),
                                ('Granminster’s Finery', {'stat': 'armorScore', 'value': '@presence'}),
                                ('Mage Robes', {'stat': 'thresholds', 'value': '@spellcast'}),
                                ('Rune-Forged Exosuit', {'stat': 'loadoutMax', 'value': -1})]:
            self.assertIn(modifier, self.entry('armor', name)['automation']['modifiers'])
        self.assertIn({'roll': 'attack', 'value': 1},
                      self.entry('weapons', 'Broadsword')['automation']['modifiers'])

    def test_conditional_bonuses_do_not_become_passives(self):
        texts = [
            'While in your Wolf Form, you gain a bonus to your Damage Thresholds equal to your tier.',
            'When you deal physical damage, gain a bonus to your damage rolls equal to your level.',
            'While flying: +2 to Evasion; +1 to Agility',
            'If you have 3 Hope, gain a bonus to your Armor Score equal to your Presence.',
        ]
        for text in texts:
            self.assertEqual(convert.modifiers_from(text), [], text)
        for name in ('Irontree Breastplate Armor', 'Vowkeeper’s Panoply', 'Heartsteel Armor', 'Defiant Bulwark'):
            self.assertNotIn('modifiers', self.entry('armor', name).get('automation', {}), name)
        self.assertNotIn('modifiers', self.feature('Adrenaline').get('automation', {}))
        self.assertNotIn('modifiers', self.feature('Feral Hide').get('automation', {}))
        for name in ('Arcana-Touched', 'Blade-Touched', 'Bone-Touched', 'Splendor-Touched', 'Valor-Touched'):
            self.assertNotIn('modifiers', self.entry('domain-cards', name).get('automation', {}), name)

    def test_creation_grants_are_not_passive_duplicates(self):
        expected = {'Endurance': {'hp': 1}, 'High Stamina': {'stress': 1}, 'Nimble': {'evasion': 1},
                    'Prepared': {'domainCards': 1}, 'Battlemage': {'hp': 1}, 'At Ease': {'stress': 1}}
        for name, grant in expected.items():
            auto = self.feature(name)['automation']
            self.assertEqual(auto['creation'], grant, name)
            self.assertNotIn('modifiers', auto)
        self.assertEqual(self.feature('Stoneskin')['automation']['modifiers'],
                         [{'stat': 'armorScore', 'value': 1}, {'stat': 'thresholds', 'value': 1}])
        self.assertNotIn('creation', self.feature('Adept').get('automation', {}))

    def test_new_relic_bonuses_and_optional_melee_damage(self):
        for name, trait in [('Stride', 'Agility'), ('Bolster', 'Strength'), ('Control', 'Finesse'),
                            ('Attune', 'Instinct'), ('Charm', 'Presence'), ('Enlighten', 'Knowledge')]:
            self.assertEqual(self.entry('items', name + ' Relic')['automation']['modifiers'],
                             [{'stat': 'trait.' + trait, 'value': 1}])
        for name in ('Shortsword', 'Spiked Shield', 'Focus Runes'):
            auto = self.entry('weapons', name)['automation']
            self.assertEqual(auto['rollOptions'][0]['roll'], 'damage')
            self.assertEqual(auto['rollOptions'][0]['weapon'], 'primary')
            self.assertIn('Primary weapon', auto['rollOptions'][0]['description'])
        for entry in self.data['weapons']:
            for opt in entry.get('automation', {}).get('rollOptions', []):
                if opt['id'] == 'primary-damage':
                    self.assertEqual(opt['weapon'], 'primary', entry['name'])
                else:
                    self.assertNotIn('weapon', opt, entry['name'])

    def test_choice_actions_do_not_apply_both_healing_choices(self):
        for name in ('Sweet Moss', 'Self-Sewing Thread', 'Nursewood Sap'):
            actions = self.entry('consumables', name)['automation']['actions']
            self.assertGreaterEqual(len(actions), 2)
            for a in actions:
                self.assertLessEqual(len(a.get('steps', [])), 1)
        self.assertEqual(len(self.entry('consumables', 'Godling’s Pomelo')['automation']['actions'][0]['steps']), 2)

    def test_coatings_are_optional_weapon_scoped_and_consumed_explicitly(self):
        for name in ('Grindletooth Venom', 'Improved Grindletooth Venom', 'Redthorn Saliva',
                     'Mythic Dust', 'Red Ooze Oil', 'Green Ooze Oil'):
            auto = self.entry('consumables', name)['automation']
            opt, = auto['rollOptions']
            self.assertTrue(opt['consumeItem'])
            self.assertIn('coated weapon', opt['description'])
            self.assertNotIn('actions', auto)

    def test_existing_healing_and_one_shot_potions_remain(self):
        self.assertEqual(self.entry('consumables', 'Health Potion')['automation']['actions'][0]['steps'],
                         [convert.step('clear', 'hp', '1d4+1')])
        effect = self.entry('consumables', 'Control Potion')['automation']['actions'][0]['steps'][0]['effect']
        self.assertEqual(effect['modifiers'], [{'roll': 'trait:Finesse', 'value': 1, 'consume': True}])
        self.assertEqual(self.entry('consumables', 'Snap Powder')['automation']['actions'][0]['cost'], {'stress': 1})

    def test_multiple_subfeatures_do_not_share_inferred_cost_or_usage(self):
        self.assertEqual(convert.cost_from('Spend a Hope to cast. Mark a Stress to sustain.'), {})
        self.assertIsNone(convert.uses_from('You can use this twice instead of once per long rest.'))
        for name in ('Book of Ava', 'Book of Grynn', 'Book of Homet', 'Book of Illiat'):
            self.assertEqual(self.entry('domain-cards', name).get('automation', {}), {}, name)
        for name in ('Devout', 'Virtuoso', 'Reliable Backup'):
            self.assertEqual(self.feature(name).get('automation', {}), {}, name)
        self.assertNotIn('uses', self.entry('domain-cards', 'Bold Presence').get('automation', {}))

    def test_grimoire_spells_are_independent_features(self):
        ava = self.entry('domain-cards', 'Book of Ava')
        self.assertEqual([s['name'] for s in ava['spells']], ['Power Push', 'Tava\u2019s Armor', 'Ice Spike'])
        self.assertNotIn('automation', ava['spells'][0])
        self.assertEqual(ava['spells'][1]['automation']['actions'][0]['cost'], {'hope': 1})
        grynn = {s['name']: s for s in self.entry('domain-cards', 'Book of Grynn')['spells']}
        self.assertEqual(grynn['Arcane Deflection']['automation']['uses'], {'max': 1, 'per': 'longRest'})
        self.assertNotIn('automation', grynn['Wall of Flame'])
        for e in self.data['domain-cards']:
            for s in e.get('spells', []):
                self.assertEqual(s['text'].count('**') % 2, 0, s['name'])
                self.assertFalse(s['text'].startswith('*') and not s['text'].startswith('**'), s['name'])

    def test_manual_notes_are_only_specific_curated_boundaries(self):
        for s in self.sources():
            self.assertNotIn('manual', s.get('automation', {}))

    def test_simple_pools_preserve_caps_and_explicit_replenishment(self):
        for name, rid, cap in [('Slayer', 'slayer-dice', '@proficiency'),
                               ('Know the Tide', 'tide', '@level'),
                               ('Spellcharge', 'spellcharge', '@spellcast')]:
            source = (self.entry('domain-cards', name) if name == 'Spellcharge' else self.feature(name))
            resource, = source['automation']['resources']
            self.assertEqual(resource['id'], rid)
            self.assertEqual(resource['initial'], 0)
            self.assertEqual(resource['max'], cap)
        crystal = self.entry('items', 'Alconite Crystal')['automation']
        self.assertEqual(crystal['resources'][0]['initial'], 1)
        self.assertNotIn('uses', crystal)


if __name__ == '__main__':
    unittest.main()
