# ModernSlimefunPet

Pets for a Paper 26.3 Slimefun server: equipped pets follow their owner, give random stats,
have to be fed, evolve, and pay AFK XP. Ported from ModernMineGens and rebuilt on Paper
26.3 / Java 25.

## Requirements

- Paper `26.3` (`26.3.build.249-stable` or newer), Java 25.
- **PacketEvents 2.14.0** — required (`depend`), the pet models are drawn with it.
- Slimefun — optional (`softdepend`), only for the item guards against Slimefun machines.
- Vault + an economy plugin — optional, only when a config cost is not `0`.
- MDLib, HikariCP and Crunch are shaded and relocated into the jar, nothing extra to install.

## Install

1. Build the jar (see *Building*) or drop a released one in `plugins/`.
2. Start the server once. `plugins/ModernSlimefunPet/` is created with `config.yml`, `pets.yml`
   and `gui/`.
3. Put your own Blockbench models in `plugins/ModernSlimefunPet/models/` (`<pet id>.bbmodel` or
   any skin file) and run `/slimefunpet reload`.

## How a player uses it

**Getting pets.** Pets are given by an admin, not crafted:
`/slimefunpet givepet <player> <pet_id> [level]`. The pet lands in the player's pet list, they
equip it from `/pets`. The pet items (food, candy, stones, tokens, totems) are handed out with
`/slimefunpet givepetitem <player> <type> <id> [amount]` and are used by right clicking them.

**The pet menu.** `/pet` or `/pets` opens the pet selection menu: one page holds 45 pets, arrows
page through the rest, the lead at the bottom toggles whether other players can see your pets.

- **Left-click** a pet opens its options menu.
- **Right-click** an equipped pet puts it away.

**Pet options** holds skins, equip/unequip, Auto-Feed and Stay/Follow. One pet may be out at a
time (`equip.max-equipped`), and a dead pet cannot be taken out.

**Food and levels.** A pet has a food bar (`food.max`, 300 by default) and loses
`food.decay-per-hour` (5) every hour it is equipped — also while you are offline, capped at
`food.max-offline-hours` (72). Burning food is what levels a pet up: `food.exp-per-food` (150)
EXP per food burned against the pet's `level-cost` formula. Feeding refills the bar only; the EXP
comes from the pet digesting it while it is out.

**Feeding.** Right-click a pet food item (Pet Treat `+25`, Pet Feast `+150`) — the pet menu opens
in feeding mode and the chat tells the player what to do. Click a pet to feed it; the item is
taken only if the pet actually ate it.

**Death and revive.** A pet whose food bar hits 0 starves and dies. It stays in the list, cannot
be equipped and has to be revived: right-click a Pet Revival Totem, then click the dead pet. It
wakes up with `food: 50`.

**Candy.** Right-click Pet Candy for `+1` level, Super Pet Candy for `+5`, then click the pet.
Levels stop at the pet's `max-level`.

**Evolution.** Every pet can name what it becomes (`evolution.into`). Right-click an Evolution
Stone (or a Blessed Evolution Stone, `+25%` success rate), click the pet in the evolution select
menu; the confirmation menu then shows the real odds and every requirement marked met or unmet
(level, item, money). Confirm to roll the chance. On a failure the stone is lost and, unless
`keep-level: true`, the pet drops back to level 1. A successful evolution can roll `bonus-stats`
extra stats on top.

**Stats.** Each pet rolls `starting-stats` (3) random stats when obtained, keeps them through
evolution and can hold up to `max-stats` (8). A stat only works while its pet is equipped and
alive. The pool ships 15 stats: max health, armor, attack damage, movement speed, knockback
resistance, luck, attack speed, plus haste (while mining), regeneration, resistance (below 30%
health), jump boost, night vision, fire resistance, water breathing and speed (out of combat).
Values scale with level through `stats.level-scaling`.

Ways to change them:

- **Stat Reroll Token** — right-click, click the pet, then confirm. 50/50: reroll every stat, or
  burn one stat away.
- **Blessed Stat Reroll Token** — same, 90/10.
- **Stat Shard** — adds one extra stat, keeping the ones it has.
- **Stat Eraser** — burns one random stat. It ships with a `whitelist-pets` list, so it only works
  on the pets named there (`legendary_pet`, `mythic_pet`, `celestial_pet` by default) until you
  edit or remove that list.
- **Stat Lock Charm** — in the reroll confirmation screen, open the Stat Locks menu and pick the
  stats to protect. One charm is spent per locked stat (`stats.stat-lock-cost`, `2 ^ ({locked}-1)`),
  at most `stats.max-stat-locks` (4), and at least one stat always stays unlocked. Closing the menu
  spends nothing.

**Auto-Feed.** Click the locked Auto-Feed button in a pet's options with an Auto-Feed Unlocker in
hand to unlock it, then open the Auto-Feed menu: store pet food in its slots and the pet eats on
its own when the bar drops to the tier's threshold, offline catch-up included. Tiers ship as
50% / 3 slots, 65% / 4 slots, 80% / 5 slots; each Auto-Feed Upgrade climbs one. On/off is a button
in the same menu, and stored food is kept when it is switched off. A food worth more than the room
left on the bar fills it and the rest is lost, exactly as when feeding by hand. Food comes back to
the owner when a pet is deleted from the list.

**Skins.** A pet without a model of its own can wear any model from `models/` the player has the
permission for: `modernslimefunpet.skin.<file name>` (for example
`modernslimefunpet.skin.redpanda`). Picked in options → Skins, with a button to go back to its own
look. A pet that already has its own look says so instead.

**AFK XP.** Stand still (no movement, camera turn, chat, command, click or block change) for
`afk-xp.idle-minutes` (3) and a pet that is out, alive, at least `afk-xp.required-mood` (Happy),
not in combat, not in water or lava, and not on Stay further than `afk-xp.stay-max-distance` (16)
blocks pays `afk-xp.xp-per-interval` (30) XP **points** every `afk-xp.interval-minutes` (60) of
AFK, at most `afk-xp.daily-cap` (240) a day, resetting at 00:00 server time. The action bar shows
the countdown, why it is paused, or that the daily limit is reached. Every payout is appended to
`plugins/ModernSlimefunPet/afk-payouts.log`.

**Mood** comes from the food bar: Happy at `mood.happy-at` (60%) or more, Neutral from
`mood.neutral-at` (25%), Sad below.

**Models.** Drop any Blockbench `.bbmodel` in `plugins/ModernSlimefunPet/models/` and reload — no
resource pack needed. Every cube is drawn as one solid block picked from its texture colours, so
shape and animation show but not fine texture detail. Animations used: `idle`, `walk`, `fly`
(falls back to `idle`). A pet uses `models/<pet id>.bbmodel` on its own, or a file you name under
its `model:` key; `model: none` keeps the head. Bedrock players see the head instead (Geyser
cannot draw block displays), unless the AlwiDisplayEntity extension is installed. Heavy models
warn in console: every piece is an entity sent per nearby viewer.

## Commands

- `/pet`, `/pets` — open the pet list.
- `/pets <player>` — open someone else's list (needs `modernslimefunpet.admin`).
- `/slimefunpet` (alias `/sfpet`) — admin command, all subcommands need
  `modernslimefunpet.admin`:
  - `givepet <player> <pet_id> [level]` — give a pet, optionally at a level (capped at its
    `max-level`).
  - `givepetitem <player> <type> <id> [amount]` — give a pet item. Types: `food`, `candy`,
    `evolution`, `reroll`, `stat_lock`, `auto_feed_unlocker`, `auto_feed_upgrade`, `revive`.
  - `petmenu <player> [type] [id]` — open the pet list for them, or straight into one item's menu.
    The player still needs the item to actually use it.
  - `reload` — reload every config, the pet types and the timers.

## Permissions

- `modernslimefunpet.admin` — admin commands and `/pets <player>`. Default `op`.
- `modernslimefunpet.skin.<file name>` — wear that skin, one node per file in `models/`.

## Files it creates

- `config.yml` — database: `sqlite` (default, `data.db`) or `mysql`. Switching to MySQL fills the
  tables from an existing `data.db` once at startup and renames it to `data.db.migrated`; if that
  copy fails the plugin refuses to start and leaves `data.db` untouched.
- `pets.yml` — food, Auto-Feed tiers, equip limit, mood, AFK XP, display and models, the stat pool,
  every pet item, the item menu texts, messages and placeholders, and the pets themselves. The
  file is commented line by line; read it before changing anything.
- `gui/*.yml` — one menu per file: `pet_gui.yml` (the list), then `pet_options_gui.yml`,
  `pet_auto_feed_gui.yml`, `pet_skin_gui.yml`, `pet_evolution_gui.yml`,
  `pet_evolution_select_gui.yml`, `pet_food_gui.yml`, `pet_candy_gui.yml`, `pet_reroll_gui.yml`,
  `pet_reroll_confirm_gui.yml`, `pet_stat_lock_gui.yml`, `pet_revive_gui.yml`.
- `models/` — your `.bbmodel` files.
- `afk-payouts.log` — the AFK XP payout log.

## Slimefun guards

Pet items are ordinary-looking vanilla items on purpose (`ShippedConfigTest` fails if a crafting
ingredient comes back): they cannot be crafted, eaten, placed, used from the off hand, brewed, or
handed to a Slimefun machine, and Slimefun cannot pick up dropped ones. They may be stored in
chests, and another plugin's chest menu without a holder blocks them while it is open. A hopper
cannot collect a dropped pet item. Cargo nodes, the Networks Vanilla Grabber and the Vanilla Auto
Crafter read vanilla chests with no event, so a pet item can still be moved into Slimefun storage
or voided from there — the materials stop it from being *used* as an ingredient, nothing more.

## Known limits

- Every milestone is finished and covered by the gate (64 tests), but gameplay has not been played
  through on a live server yet; MySQL storage is code-complete but was never run against a MySQL
  server.
- Bedrock (Geyser) players only see the head, not the model.
- A pet type removed from `pets.yml` stays in the database and still counts as equipped.
- `PlaceholderAPI` is listed as a soft dependency, but no placeholder expansion is implemented
  yet.

## Building

Maven, Java 25 (`maven.compiler.release` 25):

```bash
export JAVA_HOME=$HOME/jdks/jdk25      # or any JDK 25
mvn clean package
```

The jar lands in `target/`. Slimefun4 comes from JitPack (events only, `provided`), so nothing has
to be installed by hand.
