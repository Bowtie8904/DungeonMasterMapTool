# Making Online Play automation configurable

**Assessment: yes for the shipped Daggerheart mechanics, but not by moving strings out of TypeScript.** The catalog must describe what makes a control available, what happens when it is used, what state survives it, and what people see. The Java host must understand the same rules: it already owns important parts of Deathrun, rewards, claims and cross-character effects. A browser-only refactor would let a copy look right while the host still executes the original rule or rejects it.

**Agreed direction: a full rewrite of the automation subsystem, not two systems maintained side by side.** Give authors short, editable JSON rules and reusable JSON recipes, backed by one generic browser renderer and one authoritative host interpreter. Replace all feature-specific automation paths and convert all shipped content before release. No legacy engine, runtime adapter, mixed-format execution or fallback. Existing generic infrastructure such as the dice roller, persistence journal and accessible controls can be reused; the rest of the application need not be rewritten.

Prove that a differently named and mechanically different Deathrun works alongside the original **with both expressed in the new format and executed by the new engine**. Build in stages, but release as one coordinated replacement. Existing saved games need a backed-up, one-time data conversion, not permanent support for executing their old rules. This is the approved target design; the live application does not yet accept the replacement format.

Start with the [Deathrun and homebrew example](#example-2-deathrun-and-a-mechanically-different-copy) for the author experience, the [migration map](#coverage-and-naming) for scope, or the [release gates](#delivery-plan-and-release-gates) for implementation order.

## Implementation status

**Started, not cut over.** The new implementation is an isolated development foundation. It is not connected to `OnlineSessionServer`, the live sheet/chat/roll UI, campaign persistence or the catalog editor. All nine production catalogs remain at format 1. No compatibility version has changed because no new network contract is exposed. There is no live second-engine selector or legacy adapter; complete the remaining work before replacing the running engine.

| Implemented building block | Scope |
|---|---|
| `dmmt.online.automation.RuleCompiler` / `RuleCatalog` | One replacement format (`version: 2`); strict JSON, known operation/input fields, stable IDs, bounded recipe expansion, state/input/result references and templates. Recursively find nested automation outside editor metadata; require stable IDs for automated array members rather than deriving them from names or positions. Candidate-pack validation requires nine matching category envelopes and rejects old/mixed versions. It does **not** prove content coverage or conversion parity. |
| `RuleExpression` / `RuleTemplate`, `web/src/automation/values.ts` | Shared pure arithmetic, comparisons, booleans, conditionals, collection-size/membership, explicit reads and text formatting. No eval, numeric coercion or HTML. Shared fixtures cover host/browser results and invalid values. |
| `StructuredRoll`, `web/src/automation/roll.ts` | Identified dice/pools, signed dice, fixed bonuses and dependency-based multipliers; original kept/dropped/maximized provenance and cumulative saved-roll derivation. Host plan resolution supports mixed counts/sides, keep-highest and maximization. Renaming a label cannot change arithmetic. |
| `RuleRuntime` | Pure resolution on copies, descriptors, revision/input/eligibility checks, cumulative costs, inventory consumption, capped recovery, named use limits, numeric pools, individually identified stored-die pools, state operations, bounded rolls and configured reports/previews. Pools can be source-scoped or actor-scoped in the isolated context; dice are rolled, kept by rule, independently enumerated, spent by stable ID, added with provenance to derived roll results, and reset by declared events. Failure leaves the caller's snapshot unchanged. Random previews fail explicitly rather than sampling. The caller still must authorize, dispatch and atomically journal the entire gameplay transaction; actor-shared revision and concurrent transactions are not integrated. |
| `web/src/automation/controls.ts` | Standalone button descriptors with text-safe labels, themed integer/die-choice dialogs, validation, cancellation and pending-click protection. Retries preserve the same request ID and inputs; IDs work over ordinary HTTP LAN origins. Per-die descriptors include the stable item ID, not its face or array index. Option bindings cannot be independently submitted or paid. This renderer is not yet attached to live surfaces. |

Executable candidate configurations, rather than a partial replacement of the shipped pack:

- [recipes.json](../src/test/resources/dmmt/automation/rewrite/recipes.json): saved-weapon-roll sequence composed exclusively from generic rules and operations, including JSON-defined exhaustion cleanup.
- [domain-cards.json](../src/test/resources/dmmt/automation/rewrite/domain-cards.json): Deathrun (3 Hope, +1 first Proficiency, remove one die) and Splinter Trail (1 Stress, +2, remove two, different labels).
- [consumables.json](../src/test/resources/dmmt/automation/rewrite/consumables.json): Minor Health Potion consumes stock, rolls d4, clears actual marked HP and formats the actual result.
- [pools.json](../src/test/resources/dmmt/automation/rewrite/pools.json): a Prayer-Dice-shaped source pool rolls three, keeps two, generates a separate templated action for each die, and demonstrates consuming a stored die into a derived roll result; a numeric Favor pool is actor-scoped, so two independently compiled source programs can share it through the same host snapshot. These fixtures test generic mechanics only; real Prayer Dice ownership, audience, roll/transaction integration, damage-reduction and Hope interactions are not implemented.

The sequence fixtures supply a synthetic host damage plan and source state; they are not production effect instances or committed chat messages. The isolated resolver demonstrates `46 -> 40 -> 36` with the original dice and no rerolls. Browser/Java tests consume the same expression, template and saved-roll fixtures. Browser control tests additionally exercise cancellation, disabled reasons, exact selections, integer boundaries and uncertain retries.

Current supported automation fields are `state`, `limits`, `pools` and `rules`; pool kinds are `number` and `dice`, with `source` or `actor` scope. Supported pool operations are `pool.gain`, cost-phase `pool.spend`, `pool.roll` with keep-highest, identity-checked `pool.removeDie`, and `roll.addPoolDie`, which consumes a selected stored die and appends it with explicit provenance to a derived roll result. Bindings may use `forEach` over declared dice pools. Input kinds are `integer` and `die.select`; binding modes are `button`, `option` and `automatic`. The compiler performs programmatic validation; a published JSON Schema and fully typed actor/event/recipient/state contracts are still pending. Unused recipe bodies do not yet receive complete semantic linking. Other constructs below describe the **target**, not available runtime capabilities.

Run the focused checks from the repository root with `mvn -q "-Dtest=RuleConformanceTest,StructuredRollTest,RuleRuntimeTest,RuleCatalogTest,PoolRuntimeTest" test`; run `npm test -- --run src\automation` from `web`. `RuleCatalog.main` also accepts `<catalog.json> <recipes.json>` with the project's Java dependency classpath for read-only candidate validation. It does not convert or overwrite files.

**Still required:** full behavior-level baseline/parity coverage; all catalog families and shared ruleset; authoritative HTTP intents, deduplication/journal integration, multi-actor transactions and protected sheet writes; event dispatch, effects, passive rules, general roll calculation/edit phases, shared actor-pool persistence/concurrency, claims and remaining inputs/operations; all UI/editor surfaces; one-time backed-up catalog/saved-game conversion; compatibility negotiation and coordinated removal of old handlers. The foundation does not satisfy the final release gates.

## What exists today

A recursive count of `automation` objects in the nine maintained catalogs gives **1,123 blocks across 1,216 entries** (1,122 nonempty blocks; some entries have none and others have several). This includes nested features, grimoire spells, companion options, attachments and beastform features; it excludes `_editorOriginal` and other internal metadata. There are **58 distinct keys directly inside** these blocks. The direct lists alone contain 620 actions, 210 roll options, 103 result triggers and 79 mitigation options; these counts do **not** include conditional lists or controls created at runtime. A migration based only on the `actions` list would miss most kinds of behavior.

| What the implementation shows | Why it matters for authoring |
|---|---|
| [Deathrun's catalog entry](../dmmap-daggerheart/domain-cards.json#L1843-L1865) specifies the price and some labels, repeats the same effect under the sheet action and attack option, and sets `deathrun` / `deathrunResult`. | Copying it copies a switch into a built-in algorithm. The author cannot choose a different initial bonus, continuation policy, or continuation label there. |
| [Result buttons](../web/src/daggerheart/rollbuttons.ts#L542-L559) construct **Continue Deathrun**. The [damage dialog](../web/src/daggerheart/view.ts#L2434-L2446) builds the die selector; its [preview](../web/src/daggerheart/view.ts#L2533-L2562) recognizes multiplier rows by the display names `Smite`, `Invoke Torment` and `First Strike`. | This is not just a translation problem. Changing the label of an unrelated bonus can change the preview and the math. Buttons, previews and execution need structured roll provenance. |
| [OnlineSessionServer](../src/main/java/dmmt/online/OnlineSessionServer.java#L3237-L3248) checks the active effect and counter. [BoneValorRules](../src/main/java/dmmt/online/BoneValorRules.java#L78-L155) saves mixed-pool dice, handles maximized faces and recomputes damage; [the server](../src/main/java/dmmt/online/OnlineSessionServer.java#L3358-L3378) stamps chat and commits the sheet. | The original dice, activation identity, costs, retries and resulting chat cannot be left to client configuration or reconstructed from text. |
| [Prayer Dice](../dmmap-daggerheart/classes.json#L3879-L3887) declares a pool, but [the browser](../web/src/daggerheart/engine.ts#L622-L645) invents its per-die actions and labels, [sheet loading](../web/src/daggerheart/persist.ts#L131-L132) rewrites labels, and [the host](../src/main/java/dmmt/online/OnlineSessionServer.java#L4225-L4239) synthesizes the spend trigger. | A second rolled pool needs its **own** controls and lifetime. Two equal faces must remain different spendable dice, not two indistinguishable `+3` actions. |
| [`ActionDef`](../web/src/daggerheart/engine.ts#L144-L249) mixes costs and general steps with `splendorAction`, `witchAction`, `patronDice`, `copycat` and similar fields. [SpecialCardActions](../src/main/java/dmmt/online/SpecialCardActions.java#L14-L76) dispatches on named workflows. Some [spell controls](../web/src/daggerheart/view.ts#L2041) still depend on prose. | Neither adding more flags nor renaming them to `handler: "deathrun"` achieves the goal. Explicit events, operations and placement must replace all three forms of dispatch. |
| [Result matching and grouping](../web/src/daggerheart/engine.ts#L1825-L1889) interpret comma-separated source tags and sometimes names. [HostCatalog](../src/main/java/dmmt/online/HostCatalog.java#L96-L142) indexes names; [the editor and sheet links](DAGGERHEART_DATA.md#guided-dm-editor-and-live-catalog-links) already maintain entry IDs, feature identities, revisions and override baselines. | Build on existing identities, revision checks and the guided editor. Don't make visible names, button order, array positions or localized text into foreign keys. |

### What “fully configurable” should mean

An author can add or duplicate a feature, change its costs, triggers, available choices, calculations, state, expiry, placements and **all feature-specific text** by editing the configured catalog. No browser build, Java change, capability named after the feature, or database migration for a new combination of supported operations. One declarative definition drives sheet, roll dialog, chat, quick access, resource chips, effect rows and the DM's read-only chat action summary where applicable.

The engine still owns *how* to roll and identify dice, render an accessible picker, validate ownership, apply a transaction and synchronize it. Put **game policy** (including base reward, rest and death-move definitions when migrating those systems) in ruleset JSON; do not make persistence, visibility/authorization, or dice limits editable game rules. An entirely new kind of input, die operation, or world fact may require one new generic capability in code. Narrative prerequisites such as range, a defeated adversary, or an unknown hit are not facts the engine can invent. Model them as a visible instruction, an explicit choice, or an assertion on the existing action, without an extra confirmation by default.

## Proposed authoring format

Keep the nine category files and keep rules next to the entry, feature or spell that owns them. **Remove per-block `schemaVersion`: there is only one supported automation format.** Use one format version for the complete catalog/rules pack, declared in its file headers: the rewritten catalogs use the existing envelope's `version: 2`, and shared rule files declare that same format version. This is a validation/migration marker, not a choice between runtime parsers. The normal loader rejects older or mixed-format packs with instructions to convert them; only a separate one-time converter understands the old data. Changing a label or price does not change the format version.

Add a reserved `rules/` subdirectory under `online.daggerheartDirectory` for the shared `ruleset.json` (default rules, text, tracks, event phases) and `recipes.json` (optional compositions). These are not new compendium categories or executable plugins. Update the loader, editor, revision/invalidation and packaging explicitly before using them. Ordinary entries should need only the category file and inline labels. Provide an **Expand recipe** view so nobody has to guess what a preset does.

| Authoring concept | Meaning |
|---|---|
| `pools`, `limits`, `effects` | Declare state: numeric resources or collections of individually identified dice; a use budget with an owner and reset events; an effect definition with stacking and expiry. Separate from current values, which belong to instances on the host. |
| `rules` | Each has a stable local `id`, one or more `bindings`, optional `when`, `inputs`, `costs`, `limitRef`, ordered `do` operations and `ui`. A passive constant modifier can use a short form; the compiler expands it into a contribution in a defined phase. |
| `bindings` | Each says **when**, **where** and **how**: typed `event`, `surface`, `mode` (`button`, `option`, `automatic`, `passive`), optional selection default, actor/audience filter and chat `group`. A sheet button and a paid attack option can bind to the same rule; only the selected binding executes. |
| `when` / `inputs` / `do` | `when` reads facts without side effects. An input declares a themed picker: enum, quantity, eligible actor, allocation, trait, eligible die(s), catalog entry or effect instance. `do` changes state using registered, typed operations; it never runs client-supplied steps. Eligibility is checked again after selection. |
| `ui` | Plain-text templates for labels, descriptions, tooltips, accessible names, disabled reasons, input labels, state badges, previews and chat/reports. Binding-specific overrides take precedence over rule text, then ruleset defaults. Text is never an ID, a dice expression, a trigger or an input value. |
| `useRecipe`, `args` | Compile-time composition of the same fields, with typed required/default arguments and local IDs scoped to the use site. Recipes can be opened, copied or inlined. No Java/TS switch on recipe ID; no implicit deep merges of action arrays. |

**Identity.** A definition reference is `(category, entryId, nested feature/spell ID, local ID)`; `self`, `owner` and local references are scoped shortcuts. Promote/reuse `_catalogFeatureId` where present; mint and persist IDs once for missing features and spells. A duplicate receives a fresh definition namespace, with local references rebound and explicit external references left intact. Resources intended to be shared, such as Warlock Favor, use explicit shared IDs. A *source instance* (equipped item, learned or borrowed card, etc.), an *activation* of its effect, a *roll*, and a *die* each have their own identities. Two casters or a copied card must not share state merely because they share a definition.

**Expressions and templates.** Use literals or typed JSON expressions, e.g. `{"read":"actor.derived.proficiency"}`, `{"add":[1,{"read":"input.amount"}]}`, `{"gte":[{"read":"event.finalHpMarked"},1]}`. Allow bounded arithmetic, min/max/clamp, explicit rounding, comparisons, boolean combinations and typed collection queries. Distinguish base/derived, marked/free, original/current roll, before/after and requested/applied values. Use a dice spec for random values, sampled at the declared resolution phase, never while rendering. No JavaScript, reflection, arbitrary sheet paths, remote `$ref`s or script escape hatch.

For text, use **one** syntax: `Continue {source.name} ({state.remainingCount} dice left)` or `{source.name}: {die.face|signed}`. Resolve only declared, audience-visible context (`source`, `actor`, `recipient`, `event`, `state`, `input`, `die`, `result`), plus a small formatter list (`signed`, number, singular/plural forms). Literal braces escape as `{{` / `}}`. Unknown tokens, wrong types and tokens unavailable on a surface are authoring errors, not `undefined` on a button. Optional values need an explicit fallback or separate template for that case. Render as text on browser and desktop; never interpret the result as HTML or mechanics. Snapshot the rendered report with its original audience at commit; derive live button labels from current state. Generic error codes and a safe default message remain available if a feature doesn't supply its own disabled reason.

### Example 1: a simple action needs no workflow

Proposed complete automation block for a one-Hope action clearing up to two marked Stress on the acting character. It has an independent limit, not a feature-wide one. `result.cleared` is the **actual** amount after capping.

```json
{
  "limits": { "recover": { "max": 1, "resetOn": ["rest.short", "rest.long"] } },
  "rules": [{
    "id": "steady",
    "bindings": [{ "id": "sheet", "event": "action.request", "surface": "sheet", "mode": "button" }],
    "when": { "gte": [{ "read": "actor.stress.marked" }, 1] },
    "limitRef": "recover",
    "costs": [{ "op": "resource.spend", "pool": "actor.hope", "amount": 1 }],
    "do": [{ "op": "track.clear", "track": "actor.stress", "amount": 2, "as": "cleared" }],
    "ui": { "label": "Steady {actor.name}", "report": "{actor.name} cleared {result.cleared} Stress." }
  }]
}
```

### Example 2: Deathrun and a mechanically different copy

What a catalog author should write for the original card (the repeating sequence is a **JSON recipe**, specified below):

```json
{
  "state": {
    "phase": "inactive", "activation": 0, "uses": 0, "weaponId": null,
    "firstDamage": null, "originalEligibleDice": [], "remainingDice": []
  },
  "rules": [{
    "id": "run",
    "useRecipe": "core:repeat-saved-weapon-damage",
    "args": {
      "startCosts": [{ "op": "resource.spend", "pool": "actor.hope", "amount": 3 }],
      "firstProficiencyBonus": 1,
      "removeEachTime": 1,
      "dieRole": "weaponBase",
      "minDiceLeftToDealDamage": 1
    },
    "ui": {
      "start": "{source.name}",
      "damage": "{source.name} damage",
      "continue": "Continue {source.name}",
      "end": "End {source.name}",
      "chooseDice": "Choose a weapon die to remove",
      "dieChoice": "d{die.sides}: {die.face}",
      "preview": "Remaining damage: {result.damage.total}. No new dice are rolled."
    }
  }]
}
```

For a homebrew **Splinter Trail**, duplicate the card with a new entry ID, keep the same recipe, and edit these fields:

| Path relative to the rule | Deathrun | Splinter Trail |
|---|---|---|
| `args.startCosts` | Spend 3 Hope | `[{"op":"track.mark","track":"actor.stress","amount":1}]` |
| `args.firstProficiencyBonus` | `1` | `2` |
| `args.removeEachTime` | `1` | `2` distinct original kept dice |
| `ui.continue` / `ui.chooseDice` | `Continue {source.name}` / choose one die | `Strike again ({state.uses} hits so far)` / `Choose two dice to remove` |

The new rule must **not** create an effect named Deathrun, look for its card ID, modify its counter, or remove its dice. Both may be learned or active at once. If several sequences qualify for the same damage roll, offer a mutually exclusive choice for that roll, not “first matching effect wins.” A continuation opened from chat is already bound to the exact activation.

**Production recipe expansion contract** (the names in the first column are recipe-local IDs, not engine operations). The executable fixture currently uses source-local slots and a supplied damage plan to exercise the calculations; effect instances, the ordinary-roll pipeline and transaction integration remain to be implemented:

| Rule | Bindings and guards | Ordered behavior |
|---|---|---|
| `start` | Sheet button, **or** optional weapon attack roll option. No live instance for this rule on this source. | Pay `startCosts` once, then `effect.start` on self, with manual expiry. Sheet action commits immediately; selecting the attack option commits with the attack, not when ticking the box. Both reference one effect definition. |
| `firstDamage` | Default-selected option on weapon damage while this instance has no snapshot; not spell damage. | `roll.contribute` the configured Proficiency delta to the ordinary weapon calculation (per proficiency-scaled pool; respect fixed dice). Resolve normal modifiers once. `roll.capture` final breakdown, weapon/profile identity, original kept die IDs matching `dieRole`, and dependency links. Start the sequence revision and publish a bound result. |
| `nextDamage` | Result-group button on the roller's latest matching result, **also** reachable via the active option in the ordinary damage dialog. Instance/revision and weapon must match. Require `remainingCount - removeEachTime >= minDiceLeftToDealDamage`. | Show `die.select` on remaining original IDs (Deathrun defaults to a lowest-valued die). No new bonuses or rolls. Apply `roll.derive` from the saved roll minus the **cumulative** selected IDs; publish a *new* result, keeping earlier messages immutable. Advance state once in the same commit. |
| `end` | Effect button or feature-group action on a result from this activation. | `effect.end` the referenced instance only. Stop offering Continue on exhaustion; release the spent instance before the next ordinary weapon damage dialog. A historical End action must never end a new activation. When targets run out first, End remains a player decision. |

For clarity, this is the proposed JSON *inside* the `nextDamage` rule, after binding `state` to the activation and `args` to the recipe arguments. `roll.derive` has **no knowledge of sequences**: it derives a roll from a snapshot with named dice omitted. It also works without effects or chat.

```json
{
  "inputs": [{
    "id": "remove", "kind": "die.select", "from": { "read": "state.firstDamage" },
    "eligible": { "read": "state.remainingDice" },
    "count": { "read": "args.removeEachTime" }, "default": "lowest",
    "label": { "$ui": "chooseDice" }, "choiceLabel": { "$ui": "dieChoice" }
  }],
  "do": [
    { "op": "set.subtract", "from": { "read": "state.remainingDice" }, "items": { "read": "input.remove" }, "as": "kept" },
    { "op": "roll.derive", "from": { "read": "state.firstDamage" }, "keepSelectedWithin": { "read": "state.originalEligibleDice" }, "keep": { "read": "result.kept" }, "as": "damage" },
    { "op": "state.set", "slot": "remainingDice", "value": { "read": "result.kept" } },
    { "op": "state.increment", "slot": "uses", "by": 1 },
    { "op": "roll.publish", "roll": { "read": "result.damage" } }
  ]
}
```

The preview evaluates `set.subtract` and `roll.derive`; it reads `result.damage.total` without publishing or advancing state. Each contribution records its stable ID, role, source, pool, sides, original face, kept/dropped/maximized status and whether a multiplier applies to a pool or the whole total. Recompute dependent contributions only; keep independent bonus dice and flats. Do not regex the displayed expression or search for a word like “Smite.” Example parity fixture: kept d8 faces `8,3`, d6 faces `6,2`, flat `+4`, whole-roll x2 gives `46`. Removing the d8 showing `3` gives `40`, regardless of how the multiplier row is labeled.

### Example 3: generated controls and claims

Prayer Dice is a different test: **one rule per stored die**, not one counter and not a special `prayer` widget. Declare a face pool with session lifetime, a once-per-session roll rule, independent samples equal to the configured count plus extras and `keepHighest` equal to the base count. Devout modifies the draw count by stable feature reference. Instantiate the following binding for each kept die; this fragment shows the chat use:

```json
{
  "id": "aid-roll",
  "bindings": [{ "id": "result", "event": "roll.finished", "surface": "chat", "mode": "button", "forEach": "pool.dice", "group": "otherFeatures", "visibleTo": "messageAudience", "invoker": "dieOwner" }],
  "ui": { "label": "Prayer die: {die.face|signed}", "description": "Spend this die to aid a roll within Far range." },
  "do": [
    { "op": "roll.addFlat", "roll": { "read": "event.rollId" }, "amount": { "read": "die.face" } },
    { "op": "pool.consumeDie", "die": { "read": "die.id" } }
  ]
}
```

The binding intersects the message audience with ownership of the die (self or ally's roll, including plain rolls). The header's **gain Hope** and **remove**, plus the incoming-damage **reduce by {die.face}** option, are other rules consuming the *same instance* after a successful operation. IDs disambiguate equal faces; indices need not appear on the buttons. Keep the roll and spend controls confirmation-free. A homebrew pool could instead expire on rest and offer only a damage reduction, with no Prayer text left over.

For an alternative Hope reward such as Ethereal Visage, bind to the owner's normal Presence roll with Hope, exclude reactions and criticals, require success if known and available GM Fear. Use a **shared claim group** with the base reward, `campaign.fear.spend` and a configured report. The button itself asserts success if the difficulty is hidden; it does not ask again. A party invitation is different: snapshot eligible recipients, configure one claim per player/actor as appropriate and bind to the source activation if required. Neither is the same thing as a once-per-rest use.

## Coverage and naming

This is the migration map, not a claim that the proposed operations have already been implemented. Use the existing [catalog field guide](DAGGERHEART_DATA.md#automation-block) and [living specification](../plan.md), but resolve differences against code, shipped data and characterization fixtures. Keep a field- and behavior-level ledger; top-level key coverage cannot prove runtime parity.

| Family to migrate | Reusable concepts needed | Cases that must survive |
|---|---|---|
| Source eligibility and passives | Source activation policies, filtered contributions, calculated stats, overrides and dependency order | Learned vs merely granted, equipped vs carried, positive stock, active loadout vs vault, nested spell identity, four-domain conditions, Bare Bones unarmored base; do not apply creation grants every load. |
| Actions, costs and limits | Explicit button/roll bindings; mark/clear tracks vs spend/gain pools; atomic aggregate cost; independent or shared use budgets and recharge rules | Simple potions, item quantities/portions, source use vs action/option use, deferred use on successful cast, Hope/Stress plus Experience and options, alternative costs. Cancel or no legal benefit must not spend. |
| Rolls and result edits | Typed attack/reaction/Spellcast/weapon/spell-damage contexts; optional/required/default contributions; explicit result origin; die selection, keep/drop, matching, exploding, reroll, replace, multiply and result claims | Powerful, mixed Brawler pools/Combo chain, Sacred Resonance, rerolling Duality, saved-roll continuation, extra target damage, preselected form maintenance. Distinguish changing a historic roll from publishing a new one. |
| Pools, equipment and effects | Numeric, rolled-face and stored-roll state; scoped instances, stacking, replace/refresh; link holder/caster/recipient; inventory grant, consume, attach and transfer | Prayer Dice, Favor/Patron Die, tokens, reloading, armor thresholds, charged items, transferable talismans, end-on-zero and manual expiry. Copy/transfer definitions and state under the correct ownership policy. |
| Incoming damage, reactions and rewards | Ordered calculation stages and “after commit” events; claimant and target filters, exclusivity and no-benefit rejection | Preserve thresholds → multiplication/resistance → reduction → severity/negation → armor → HP adjustment/replacement; distinguish would-mark from actually marked HP. Cover mitigation, armor wear, shared shields, target healing, alternative Hope claims, GM Fear and hidden rolls. |
| Lifecycle, rest and downtime | Explicit acquisition, equipment, scene/session/rest and source-removal events; expiry/reset policies; bounded tables, crafting, rest slots/moves and extra/alternative moves | Once per scene vs session, rest-granted effects not immediately expiring, short vs long rest, Recovery, Forager, ally repairs, permanent vault and death moves. A reconnect is not a new session. |
| Cross-actor and stateful workflows | Recipient selectors, bounded allocations, mirrored pools/activation links, borrowed-source namespaces, structured choices, roll/commit-or-cancel flows | Offline vs connected allies, per-player claims across tabs/actors, Copycat and nested spells, Zone of Protection, Salvation Beam, exclusivity on recast; never run one client write per target. |
| Class subsystems and custom content | Declarative selection lists/requirements, acquisition grants, form/profile replacement, companion entity/track operations, conditional actions and UI descriptors | Druid forms/elements, Brawler stances, ranger training/interception, lunar phases, Warlock outcomes, transformation choices. Mancer/import, DM-authored effects and customized equipment must resolve the same definitions without losing player or DM choices. Keep unsupported custom-effect presets explicitly manual. |

**Index of the 58 shipped top-level fields.** This is where each existing field enters the conversion, not a proposal for 58 new operations. Most of the per-card switches are *below* this level.

| First migration destination | Current fields |
|---|---|
| Rules, bindings and plain instructions | `actions`, `conditionalActions`, `rollOptions`, `conditionalRollOptions`, `rollTriggers`, `conditionalRollTriggers`, `manual`, `hideSpellcast`, `spellcastDifficulty`, `spellcastSuccessSteps` |
| Contributions, eligibility and roll calculations | `modifiers`, `conditionalModifiers`, `requirement`, `damage`, `damageTypeChoice`, `alternateProfiles`, `channelBenefit`, `ignoreWeaponBurden`, `unarmoredBase` |
| Named pools, budgets and display | `resources`, `dicePool`, `effectCounters`, `classResourceOnly`, `uses`, `usesOverrides`, `experienceRefund` |
| Damage, claims and linked effects | `mitigation`, `conditionalMitigation`, `hopeReward`, `conditionalDamageRewards`, `actionBenefits`, `criticalMelee`, `overflowHope`, `equipEffect`, `armorFullEffect`, `armorHalfEffect`, `armorFullHeldEffect`, `zeroHopeClearArmor` |
| Rest, inventory and lifecycle events | `rest`, `restReroll`, `automaticRestUse`, `automaticRestUseOnAttempt`, `partyDowntime`, `attach`, `reloading`, `deathMove`, `banishDifficulty` |
| Acquisition, forms and companion recipes | `creation`, `grantsFeatures`, `groupGrantedFeatures`, `elementChoice`, `unarmedWeapon`, `stances`, `lunarPhases`, `beastforms`, `beastformFree`, `companion`, `companionTraining` |

**Rename concepts, not just keys:**

| Ambiguous current vocabulary | Proposed distinction |
|---|---|
| `on`, `roll`, `when`, `timing`, `chatOnly`, `automatic` | `bindings.event` = what happened; `when` = predicates on facts; `bindings.surface` = where offered; `bindings.mode` = player choice or automatic. Explicit binding replaces text-derived Spellcast. |
| `hostOwned`, `hostRollAction`, `*Automation` | Not author switches. Compiler infers required capabilities and execution/commit phase from the operations and binding. |
| `uses`, `usesSource`, `deferSourceUse`, `expendOn`, `repeatable` | Named `limitRef` plus explicit spend event; claim policy separately defines per-roll/per-player exclusivity. Reset events are neither costs nor effect expiry. |
| `target`, `audience`, `otherPlayerOnly`, `allowSourceOwner`, `allActors`, `party` | Separate **who sees the message**, **who may invoke**, **which actors are eligible**, and **who receives the change**. Give selectors explicit cardinality and scope: one selected saved actor, connected players' actors, or all saved player actors. |
| `requiresEffect`, `excludesEffect`, `endEffect`, `unique`, `exclusiveRollGroup` | Typed instance predicates, `effect.end`, a stacking key/policy, and a separate claim-group key. Explicit source/recipient/activation scope; never match display names. |
| `resource`, `counter`, `dicePool`, `@hp`, `@proficiency` | Distinguish `track.mark` from `resource.spend`, use budgets from counters, numeric pools from face collections; use phase-qualified and base/derived reads. |
| `actionRoll`, `sendRoll`, `resourceRoll`, `damage`, `damageDice`, `rollTriggers` | Specify roll kind and calculation, a binding to a finished result, and whether to add a contribution, edit that result, or publish a derived one. Use result groups `result`, `sourceFeature`, `otherFeatures` independently of the mechanic. |

## Runtime, persistence and compatibility

1. **Compile once per catalog revision.** Strict JSON parsing (including duplicate keys), JSON Schema for tagged unions, then a semantic linker: unique IDs, reference existence/types, allowed inputs/outputs by event, valid cost directions and roll phases, template variables, private facts, cycles and bounds. Compile shorthand and JSON recipes to one typed rule representation. Reject unknown executable fields with `category / entry / rule / JSON pointer` and a fix; preserve unrelated metadata. Never silently run half a feature. Index by source, event and surface; don't walk every catalog entry on each roll. Retain the last good revision on a failed live edit; if none exists, explain why the ruleset cannot be activated.
2. **Interpret once for authority.** Java owns eligibility, spend/claim checks, rolling for authoritative commands, and multi-sheet, campaign and chat mutations. Extract generic primitives from existing `DiceRoller`, feature steps, mitigation/rest logic and campaign journal instead of implementing nine independent engines. TypeScript renders action descriptors and inputs and evaluates only pure previews for responsiveness; desktop renders the same audience-appropriate text/sections. Use shared schemas, generated bindings and identical cross-language fixtures for the pure subset. Do not ship host secrets (e.g. hidden Difficulty) in a preview. Replace locally resolved automation flows too, explicitly testing the authority change against captured baseline behavior. An unconverted flow blocks release rather than falling back to the old engine.
3. **Define deterministic dispatch.** One event has typed origin, owner, phase and visible facts. Collect candidates from active sources only. Resolve passives, optional selections, combined costs and mutually exclusive groups in documented phases; use explicit priority and stable ID as a tie-breaker within a phase. Fully resolve input/eligibility before mutation. Stage bounded follow-ups after commit; reject cycles, recursion without a bound, excessive dice, targets or steps. Never permit arbitrary sheet patches or network/file access from a rule.
4. **Send intent, not mechanics.** Example intent fields: `actorId`, `sourceInstanceId`, `ruleId`, `bindingId`, `activationId?`, `messageRef?`, `expectedStateRevision`, `ruleRevision`, `inputs`, `requestId`. Host looks up the definition from the trusted catalog or validated DM-owned snapshot, verifies ownership/active loadout, target scope, current session, message visibility, allowed die IDs, cost, claim group and revision; never trusts claimed faces, totals, labels or supplied operations. Use one journaled transaction for cost, use, roll, effect, recipients, campaign Fear and chat as applicable. Retry the *same* request idempotently; return the original result, or an actionable stale rejection with refreshed choices. Do not replay a possibly committed operation with a fresh ID. Ordinary sheet PUTs must not fabricate or overwrite protected state.
5. **Keep definitions separate from facts.** An activation stores its definition/source ID and rule revision, actor/recipient, instance ID, monotonic revision, selected inputs, counters and typed slots (including immutable roll snapshot + remaining die IDs). Retain revisioned definitions or a bounded validated snapshot until live activations/claims no longer need them. New activations use the current catalog. Cosmetic edits may refresh live labels; old chat keeps its text and audience. Reject stale actions, and never reinterpret an old die selection against a changed definition.
6. **Authoring is part of the feature.** Extend the existing loopback-only DM editor (`web/src/catalog-editor.ts`, `catalog-fields.ts`, Java `CatalogEditor`) rather than creating a second editor. Present *When / Where / Who / Cost & limits / What happens / What players see*. Offer field pickers, template autocomplete with visible sample values, expanded recipe, a “why unavailable?” preview and dry-run sheet/chat examples. Keep plain JSON round-trippable, immutable IDs, atomic save, optimistic revision, unknown non-rule fields, embedded-WebView close protection and `catalogChanged` cache invalidation. Do not infer rules from descriptions, labels or filenames.
7. **Make one coordinated cutover.** Ship the new engine, all converted catalogs, shared rules, editor and browser assets together. Delete the old execution paths, card-specific capability flags and incompatible command handlers; retain no legacy interpreter or down-conversion for old clients. Negotiate the new Online Play contract before loading editable sheets or accepting gameplay commands, and enforce it on every mutation endpoint so stale tabs cannot bypass the check. Reject mismatches with the supported and received versions and an update/reload instruction; a new browser must likewise refuse an old host rather than executing old automation. Increment the Online Play compatibility contract as required by the breaking rewrite. DM Link's `RemoteCompatibility.VERSION` is a separate assessment: increment it if its messages, snapshots, settings or asset-transfer semantics change incompatibly, never merely for new application content. A format version and a connection compatibility version protect different boundaries; neither implies two runnable engines.
8. **Convert data once, outside normal execution.** Stop hosting and back up the original catalog pack and affected campaign data before conversion. Map `feat:<name>`, `card:<book>/<spell>`, option/usage keys, custom resource IDs, `_catalogFeatureId`, effect-name links and legacy dice indices to IDs in a staged copy. Respect catalog-link baselines and edited gear, custom inventory, earned choices, spent limits and offline actors. Do not re-grant creation benefits. For active Deathruns preserve first roll, weapon, original indices, remaining dice and count; for Prayer Dice preserve IDs, values and spent session use. Convert executable effect/claim snapshots to the new format too. Use exact, unambiguous aliases; unresolved mechanics or references produce actionable diagnostics and block activation of the converted data, not a legacy fallback or silent loss. Validate the entire staged pack and affected campaigns before a recoverable, journaled cutover; record completion so retries never reapply grants or conversion. Existing chat stays readable and keeps order, whispers and claim status; never make a historic message newly executable by matching its label. Rollback restores the old application and the corresponding pre-conversion data together, not old code over new saves.

## Delivery plan and release gates

| Stage | Deliverable | Exit gate |
|---|---|---|
| 1. Baseline before designing more primitives | Inventory each live field **and** runtime-generated action/label. Record source path, binding/surface, authority, condition, inputs, cost timing, result, persistence and representative fixture. Classify as direct, recipe, new primitive, intentionally manual or rewrite pending. Include base rules, DM custom effects and saved-sheet-only behavior. | All nine categories and nested automation owners counted; every discovered dispatch/field assigned. Existing manual boundaries and known parity gaps called out, not promoted to supported. Capture current event traces and golden chat/sheet outcomes before replacing the implementation. |
| 2. Language and authoring prototype | Single-format identity model, schema, expression/template checker, compiler, host descriptor query, generic controls/preview and CLI/DM-editor validation. Start with one consumable and one independent-use feature. Build the one-time converter separately from the runtime loader. | Changing a label, cost, amount, placement or name updates the UI and execution without a build. Bad reference/token fails at the exact path; known non-rule fields survive editing. Runtime rejects unconverted or mixed-format data. |
| 3. Prove the hardest vertical slice early | Roll provenance, snapshot/derive operations, revisioned activation state, intent/commit path, first Deathrun recipe. Add Splinter Trail in JSON only. | Both work together from sheet, attack option, damage dialog, effect and result chat; checks include the numeric fixture above, fixed/mixed/kept/dropped/maximized dice, multipliers, spent bonuses, last die, wrong weapon, stale result and reconnect. Rename the multiplier row and every feature label; totals must not change. |
| 4. Prove the model is not a Deathrun engine | Migrate Prayer Dice/Devout and one alternative reward; then a grimoire spell, one mitigation/reaction and a cross-actor invitation or transfer. | One generic per-die renderer/command; equal faces consumed independently; no new confirmation. Verify one-claim exclusivity, real healing, canceled input, offline recipients, simultaneous tabs and transaction recovery. Add a second pool/claim from JSON without code. |
| 5. Complete every family before release | Passives, costs, resources, simple actions/options first; then results, effects, damage/rest, gear, acquisition/forms/companions, and linked or borrowed multi-actor workflows. Mechanically convert only unambiguous old data with a dry run and reviewable diff; resolve every outstanding conversion explicitly. Add a primitive only with a documented contract and a second independent use/fixture. | Each batch matches captured baseline outcomes, has author documentation and editor presets, and accounts for every field. All shipped executable behavior runs on the new engine. No legacy adapters or formerly supported mechanics downgraded to manual fallbacks. |
| 6. Release the complete replacement | Remove old feature-specific runtime handlers, label/name checks and compatibility fallbacks. Ship the complete rules/assets in local and CI packages; rehearse conversion on copied catalogs/sheets/history, mismatch rejection, interrupted cutover and coordinated rollback. | One runtime and one supported format. No shipped executable mechanic requires a feature/class name, text pattern or card-specific flag; old-format knowledge exists only in the one-time converter. All shipped behaviors have a passing parity fixture or an explicit pre-existing manual disposition. Publish the author guide, schema and conversion report. |

**Test the contract, not just JSON parsing.** Run each fixture through eligibility, displayed controls/placeholders and pure preview, then through the host with deterministic dice. Compare committed sheets, resources, uses, roll contributions, IDs, revisions, chat audience and message order against captured baseline fixtures, without retaining the old engine in the shipped application. Include unknown/hidden facts, stale or duplicate requests, cancel, insufficient combined costs, full tracks, recast/expiry, actor switching, cold load, catalog hot reload, rejection of old clients/hosts and restart after an interrupted journal. Cover conversion reruns, ambiguous references, preserved custom gear and active state, interrupted cutover and rollback. Test desktop and browser output, not merely that the rule appears in a list. Extend [the browser Deathrun tests](../web/src/daggerheart/bone-valor.test.ts), [result-button tests](../web/src/daggerheart/rollbuttons.test.ts), [Java dice tests](../src/test/java/dmmt/online/BoneValorRulesTest.java) and [server integration tests](../src/test/java/dmmt/online/OnlineSessionServerTest.java); use the existing catalog editor/cache, persistence, Seraph, damage, rest and class suites as characterization fixtures.

**Completion criterion:** stages are an implementation order, not permission to ship a partial rewrite. Simple one-shot rules should become shorter, not longer. The real cost is replacing stateful dice, event ordering and cross-actor transactions *without losing saved games*. If a card still needs a named handler, implement the missing generic operation before release. Do not hide the exception behind JSON, retain a fallback engine, or mark the rewrite complete with a supported feature still unconverted.
