# ModernSlimefunPet — pet ideas that work with the plugin as it is

Feasibility first: a pet is **config only**. Beside the keys below there is no code hook per pet —
no pet can shoot, mine, or talk on its own. Everything a pet *is* comes from its stats, its
upkeep, its evolution gate and how it looks. Ideas that need a unique ability are marked
**needs code**.

## What one pet can be (every key, all read from `pets.yml`)

- `max-level`, `level-cost` (formula on `{level}`), `exp-per-food` (number or formula)
- `food-decay-multiplier` — 0.5 = cheap to keep, 2.0 = a hungry sink
- `starting-stats`, `max-stats` (hard ceiling), `stat-pool` (list, or weights per stat)
- `guaranteed-stats` — identity stats on top of the rolled ones, never rerolled or burned
- `evolution` — `into`, `required-level`, `item` (one or a list), `chance`, `bonus-stats`,
  `keep-level`, `lose-item-on-fail`, `money-cost`, `item-requirement(s)`
- `skull`, `item-display-name`, `item-lore` (placeholders: `{level}` `{max_level}` `{exp}`
  `{exp_needed}` `{progress_bar}` `{food_bar}` `{food_percent}` `{status}` `{stats}`
  `{stat_scaling}` `{evolution}`)
- `model` — `models/<pet id>.bbmodel`, or a `model:` section (`file`, `scale`, `y-offset`,
  `facing`, `detail`, `blocks`, `particles`)

A pet without a model of its own can instead wear any file in `models/` through the skins menu
(`modernslimefunpet.skin.<file>`), so a generic-looking pet line needs no artwork at all.

---

## Line A — Ember: the combat line

- `ember_pup` (10) — cheap starter, hurts a little
- `ash_hound` (15) — faster attacks on top
- `magma_beast` (20) — hungry (`food-decay-multiplier: 2.0`) but the best damage of the three, and
  the chain ends there

```yaml
  ember_pup:
    max-level: 10
    level-cost: '1000 * 1.15 ^ ({level} - 1)'
    food-decay-multiplier: 1.0
    exp-per-food: 30
    starting-stats: 3
    max-stats: 8
    stat-pool:
      attack_damage: 30
      max_health: 25
      armor: 20
      movement_speed: 15
      haste: 10
    guaranteed-stats:
      attack_damage: 0.5
    item-display-name: '&#FF6B3D&l[I] &7Ember Pup'
    evolution:
      into: ash_hound
      display-name: '&#FF6B3D&l[II] Ash Hound'
      required-level: 10
      item: [evolution_stone, blessed_evolution_stone]
      chance: 50.0
      bonus-stats: 1
      keep-level: false
      lose-item-on-fail: true
      money-cost: 50000
```

`ash_hound` → `magma_beast` follows the same shape with `max-level: 15` / `20`, `chance: 40.0` /
`35.0`, and `magma_beast` ending without an `evolution:` block (chain over). Nothing here needs a
model: three skins from one `.bbmodel`, or the head.

## Line B — Royal: the money line

`coin_fox` (10) → `gilded_lynx` (15) → `aurum_dragon` (20). Pool leans `luck` + `attack_speed`,
`guaranteed-stats: {luck: 1}` on every tier, and the evolutions are the expensive ones
(`money-cost: 250000`) so they are a real coin sink.

## Line C — Terra: the mining line

`stone_golem` (12) → `deepslate_golem` (18) → `bedrock_titan` (25). Pool: `haste` (MINING
condition), `luck`, `armor`, `max_health`. The evolutions ask for a handed-in item as well, which
is how the plugin makes a pet feel earned:

```yaml
    evolution:
      into: deepslate_golem
      display-name: '&#D08A4E&l[II] Deepslate Golem'
      required-level: 12
      item: [evolution_stone, blessed_evolution_stone]
      chance: 45.0
      bonus-stats: 1
      lose-item-on-fail: true
      item-requirement:
        material-name: COBBLED_DEEPSLATE
        display-name: '&#F2CFA0&lGolem Core'
        amount: 64
```

Note `COBBLED_DEEPSLATE` is a Slimefun/crafting material: it must be *handed over*, not used as
an ingredient, which is exactly what `item-requirement` does. It also means the requirement can be
farmed or bought, so it is a sink, not a wall.

## Line D — Verdant: the AFK line

`lazy_sloth` (10) → `dozy_panda` (15) → `dream_ox` (20). These are built for the AFK XP feature:

- `food-decay-multiplier: 0.5` and `max-offline-hours` untouched, so a sloth kept out overnight
  loses far less than a hound.
- `exp-per-food: '30 + ({level} * 5)'` — it digests better as it grows, so it levels while you sleep.
- Pool: `regeneration`, `haste`, `max_health`, `armor` shipped as they are. The two kinds no pool
  entry uses yet are `PET_EXP` (more exp per food burned) and `FOOD_SAVER` (slower hunger, `100` =
  never hungry) — a lazy line is the natural home for them. Add one pool entry and any pet can roll
  it:
  ```yaml
      food_saver:
        kind: FOOD_SAVER
        display: ' &8- &#9EE6C1+{value}% &7Food Saved'
        min: 10
        max: 25
        decimals: 0
        weight: 6
  ```

## Line E — Abyss: the utility line

`void_wisp` (10) → `abyss_specter` (15) → `void_sovereign` (22). Pool forces `night_vision`,
`fire_resistance`, `water_breathing`, `speed` (OUT_OF_COMBAT) — the pool choice is the pet's
identity, no code needed.

## Line F — Bloom: the support line

`bloom_sprite` (10) → `verdant_druid` (16) → `worldtree_guardian` (24). Pool: `regeneration`
(HEAL below 70%), `resistance` (below 30%), `max_health`, `armor`. `guaranteed-stats:
{regeneration: 1}` on the last tier only, so the payoff is visible.

## The endgame trio (fills the `stat_eraser` whitelist)

`stat_eraser` already ships `whitelist-pets: [legendary_pet, mythic_pet, celestial_pet]`, and
those three ids do not exist yet. Give them to the last line of line A (or their own line):

- `legendary_pet` — level 35, `chance: 30.0`, `money-cost: 500000`, `item-requirements` with two
  different handed-in items, `max-stats: 10`
- `mythic_pet` — level 50, `chance: 25.0`, `keep-level: false` so failing really hurts,
  `max-stats: 11`, `starting-stats: 4`
- `celestial_pet` — level 75, `chance: 20.0`, `guaranteed-stats: {luck: 2, armor: 2}`,
  `max-stats: 12`, no `evolution:`, `level-cost: '50000 * 1.25 ^ ({level} - 1)'`

Raising `max-stats` per tier is the cleanest progression lever the plugin has: a reroll can never
push a pet past its own ceiling.

## Ideas that need code

- A pet that fights, mines, farms or picks items up for you — needs a new task per pet.
- A new stat kind (for example "double ore drops") — needs a `PetStatKind` implementation.
- A pet that only works at night, or in one world — closest config-only version is a
  `stat-pool` full of conditional POTION_EFFECT stats.
- Per-pet chat lines or animations beyond `idle`/`walk`/`fly` — needs code (animations come from
  the `.bbmodel`).

## Checklist to turn an idea into a real pet

The YAML snippets here show the structural keys only: copy `item-lore` and `skull` (or add a
`model:`) from the shipped `common_pet` when you write a real entry.

1. Pick the id (it is the `models/<id>.bbmodel` name) and decide chain order.
2. Add the entry under `pets:` in `src/main/resources/pets.yml` (or the live
   `plugins/ModernSlimefunPet/pets.yml`), one block per pet.
3. Drop the model in `plugins/ModernSlimefunPet/models/`, or leave it out and let the skull show.
4. `/slimefunpet reload`, then `/slimefunpet givepet <player> <id> [level]`.
5. If the edit is in `src/main/resources`, run the gate: `ShippedConfigTest` fails when a stat in a
   pool has no known kind, and when a pet item material becomes a crafting ingredient again.
