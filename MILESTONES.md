# ModernSlimefunPet milestones

Spec of record: `SPEC-ticked.md` (owner ticked everything, notes included).
Reference only, never edited: `../ModernMineGens-Pets-Handoff` and `../ModernMineGens-Alwination/modernminegens`.
Target: Paper 26.3 (`26.3.build.249-stable`), Java 25, MDLib 3.0.1, PacketEvents 2.14.0.
Build and gate need `MCKIT_JAVA_HOME=$HOME/jdks/jdk25`. The MCP `gate` tool picks the wrong JDK; run `scripts/gate.py` instead.

| # | Milestone | Status |
|---|---|---|
| M0 | Skeleton: pom, plugin.yml, gate PASS, boots on 26.3 | done |
| M1 | Storage: HikariCP, SQLite + MySQL, owner profile. Migration = one-time import of this plugin's own data.db into an empty MySQL at startup, then data.db is renamed to data.db.migrated. No Redis. Pet state on the main thread, DB I/O async through an ordered write-behind queue (DatabaseQueueHandler) | done for SQLite; MySQL code not run against a MySQL server (none on this machine) |
| M1a | Slimefun investigation: SLIMEFUN-VECTORS.md (agent report, three key claims spot-checked) | done |
| M2 | Core pets ported from ModernMineGens-Alwination: data, equip (1 pet, no slot permission), /pet menus, packet display (main thread) | done, untested in game |
| M3 | Food, death, revive, candy, Auto-Feed (ported) | done, untested in game |
| M4 | Evolution without boosters (handoff section 9 applied) | done, untested in game |
| M5 | Stats: owner's list as the pool; new kinds ATTRIBUTE (transient modifiers) and HEAL; POTION_EFFECT conditions ALWAYS/MINING/HEALTH_BELOW/OUT_OF_COMBAT; reroll and stat lock kept | done, untested in game |
| M6 | AFK XP (hand-rolled idle tracker, 30 XP points/hour, daily cap 240 saved per profile, async payout log, action bar), mood from food (Happy >= 60%), Stay (options menu button; spot in memory, ends on quit) | done, untested in game |
| M7 | Slimefun guards per the owner's answers: holder-less chest menu click guard (HIGHEST), no_pickup marker on dropped pet items (players can still pick up), Slimefun event hooks (multiblock, auto (dis)enchanter, right-click block, block placer), vanilla guards (craft, eat, place, off-hand use, entity interact, totem resurrect, brew), 9 item materials swapped to non-ingredients | done, untested in game; cold review not run yet |

## Owner answers for M7 (2026-11-17)

1. Pet items MAY go into chests (no container lock).
2. No unique-id ledger.
3. No materials that need a resource-pack item model: items keep vanilla-looking materials.
4. The click guard may block pet items in every holder-less chest menu (any plugin): accepted.
5. Players may drop pet items, but Slimefun must not be able to take dropped ones.
6. Local commits per milestone; push to Alwi-Studio/ModernSlimefunPet (the owner creates the repo).

## Verified this round

- Gate PASS, 64 tests: storage round trip, ordered delete, SQLite to MySQL import (SQLite to SQLite), pagination (bug fixed: 46 pets on 45 slots lost page 2), effect conditions, heal timing, shipped pets.yml (every pool stat has a known kind; knockback is a flat 0.05), ported MMG tests (food burn, Auto-Feed, skin), pet-display tests, architecture rules, every sent message key shipped (MessageKeysTest).
- Boot on Paper 26.3-249: 15 stats load (every attribute and effect name resolves against the real registries), 14 items, 3 pets, redpanda model, no errors.
- Restart round trip on the test server: a pet seeded as equipped 2 hours ago loaded, burned 10 food (5/h), gained 300 pet EXP (30/food), and was saved on shutdown.

## Handoff section 10 bugs

Fixed: missing message keys shipped; a starved pet's unequip is saved; a display that throws IllegalState/IllegalArgument/IndexOutOfBounds no longer stops the other owners' pets (other exceptions still end that pass); a revoked skin is no longer drawn; EXP keeps its fraction; pagination last page; givepet level capped and saved; pet state on the main thread (food, display, effects; DB I/O async through an ordered queue); saves right after an item or money is taken; reload restarts the timers; money costs refuse when Vault or an economy is missing (was free); read from MDLib bytecode, not run (no Vault on the test server, every shipped cost is 0).
Still open: pet items usable vanilla-style from the off hand and in crafting (M7 guards); pets of types removed from pets.yml stay in the DB and count as equipped; the evolution money and stone money are checked separately, not as a sum; Bedrock players only see heads (handshake dropped, F9).

## Defaults I chose (owner may change)

- Three sample pets: common_pet (max 10) -> rare_pet (max 15) -> epic_pet (max 20), 50% evolution, every pet starts with 3 stats, max 8.
- `stats.reroll-amount: 3` (ModernMineGens shipped 6), revive totem `money-cost: 0` (ModernMineGens: 1,000,000).
- C2 switch: `enabled: false` on a pool entry turns that stat off.

## NullAway ratchet

New code is checked at ERROR. The 30 classes ported unchanged from ModernMineGens are listed in `mckit.nullaway.unannotated` (pom, mckit-lint profile) and are not checked; at ERROR without that list javac stopped at 100 errors in them. Fix a class, take it off the list, keep the gate green. SpotBugs' NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE is excluded for pets.gui, pets.items, pets.managers, pets.stats and packet.display for the same reason; drop those matches as the list shrinks.

## Gate hygiene

- Run `rm -rf target` before the gate after any `-Dmckit.skip` build: otherwise Maven treats the classes as up to date and Error Prone/NullAway never look at them (this hid the ported code's findings once).
- `MessageKeysTest` fails when code sends a `messages.*` key that pets.yml does not ship (MDLib skips missing keys silently).

## M6 defaults I chose

- A failed condition (combat, water/lava, pet far on Stay, unhappy pet, no pet) pauses the hour; only the player's own activity resets it.
- A join is not activity, so the saved partial hour carries on after a relog once the player idles again.
- Action bar every second while AFK: countdown, "paused", or "daily limit reached".
- Mood is not shown on the pet item yet.

## M7 material swaps (SLIMEFUN-VECTORS.md section 7)

pet_candy SUGAR -> GLOW_BERRIES; super_candy HONEYCOMB -> GOLDEN_APPLE; evolution_stone HEART_OF_THE_SEA -> AMETHYST_CLUSTER; blessed_evolution_stone NETHER_STAR -> FIREWORK_STAR (glint); reroll_token PAPER -> GLOBE_BANNER_PATTERN; blessed_reroll_token BOOK -> KNOWLEDGE_BOOK; stat_shard AMETHYST_SHARD -> LARGE_AMETHYST_BUD; stat_eraser FLINT -> WIND_CHARGE; auto_feed_unlocker HAY_BLOCK -> BEETROOT_SOUP. Kept: COOKIE, PUMPKIN_PIE, NAME_TAG, GOLDEN_CARROT, TOTEM_OF_UNDYING. `ShippedConfigTest` fails if an unsafe material comes back.

## Residual risk (cannot be closed from this plugin, owner chose to allow chests)

- Cargo nodes, the Networks Vanilla Grabber and the Vanilla Auto Crafter read vanilla chests with no event: they can move a pet item into Slimefun storage (Networks quantum, Infinity storage units) or a Trash Can can void it. The materials stop any machine from using it as an ingredient.
- Quantum/Infinity storage keep one template and hand out clones (PDC kept); with no ledger a clone is a real pet item.
- Hoppers cannot collect dropped pet items (Slimefun cancels hopper pickup of no_pickup items).
- Any other plugin's chest menu without a holder blocks pet items while open (accepted).

## Building

Slimefun4 (2024.3.1, events only, `provided`) comes from JitPack, so no manual install is needed. The server runs a Slimefun4 fork with the same `io.github.thebusybiscuit.slimefun4.api.events` classes.
Build and gate with `MCKIT_JAVA_HOME=$HOME/jdks/jdk25`.

## Open (small)

- `reloadPets()` builds new mode-menu `Config` objects on every reload on top of `Config.reload()`; harmless but wasteful.
