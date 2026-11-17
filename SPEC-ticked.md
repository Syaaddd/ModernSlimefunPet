# ModernSlimefunPet — my understanding (please check)

How to use: tick `[x]` if right. If wrong, write in `Note:`.
Each item is my reading plus the default I will use if you tick it.

Sources: `ModernMineGens-Pets-Handoff/PETS-HANDOFF.md` and your spec.
Nothing is built yet. This is only the question list.

## Findings you should know first

- **`PlayerIdleEvent` not found.** I searched the Paper API jars on this machine, 1.21.5 (MMG's target) and 1.21.11. Neither has it. I could NOT check 26.3: `_local-test-server-26.3/paper-26.3-249.jar` is a bundler without the API jar inside. Default: I hand-roll the AFK tracker. If you know a Paper version that has the event, tell me which.
- **"Mood" and "Stay mode" do not exist in MMG.** I grepped the handoff. MMG pets have food, death, level and skin only. Both are new features (section D).
- **MMG stats work like this:** each pet rolls N random stats from a config pool (`stats.pool`), each with a min-max value, weight, GOOD/BAD type. Values scale with pet level. Reroll items and stat locks exist. Evolution adds `bonus-stats`. Stat kinds include `POTION_EFFECT` (applied every 3s, 10s duration). Your new list becomes the pool. Section B asks how.
- **Vanilla Regeneration I heals about 1 HP per 2.5s**, not 1 HP per 8s. So "1 HP / 8s" cannot be the vanilla effect (section C).

## A. Carried over from MMG as-is

- [x] A1. Pets are records in a list, not items. Owned pets are opened through `/pet` and `/pets` in a paged menu.
  Note:
- [x] A2. Equip and unequip from an options menu. Default limit 1 equipped pet (`equip.max-equipped: 1`). Whether a slot permission can raise it: see B5.
  Note:
- [x] A3. Pet is drawn by packets (PacketEvents): floating head or Blockbench model with name tag. Skins by permission. Hide toggle.
  Note:
- [x] A4. Food bar. Equipped pet burns food over time. Burned food becomes pet EXP. Pet EXP gives pet levels (`level-cost` formula).
  Note:
- [x] A5. Food at 0 means the pet dies, is unequipped and gives nothing. A revive item (plus optional money) brings it back unequipped.
  Note:
- [x] A6. Food items refill the bar. Candy gives instant levels. Right-click opens a pet picker.
  Note:
- [x] A7. Auto-Feed: pantry per pet, eats food by itself at a threshold, upgrades add slots.
  Note:
- [x] A8. Evolution: pet type chain, evolution stone, required level, success chance roll, stone lost on failure, level reset (all per config as in MMG).
  Note:
- [x] A9. Evolution **boosters** (lucky charm etc.) are left out, same as the handoff.
  Note: It cannot be duped by Slimefun.
- [x] A10. Pet items are recognised only by PDC tags, never by name or material.
  Note: It cannot be duped by Slimefun.
- [x] A11. Config in `pets.yml` plus `gui/*.yml` menu files, same layout as MMG.
  Note:
- [x] A12. I fix the known MMG bugs from handoff section 10 in the port. Examples: thread safety of pet state, starving pet not saved as unequipped, EXP rounding to 0 per tick, missing message keys, pet items edible or craftable.
  Note:
- [x] A13. Pets of offline players keep burning food (MMG default), with the existing `offline-decay` and `max-offline-hours` caps.
  Note:

## B. Stats (MMG mechanism, new list)

I read: `PetStatKind`, the `stats:` pool in `pets.yml`, `PetPlayer` stat/reroll/lock code, `PetService.getBoosts`, and `PetEffectTask`.
I did not read `PetStatRegistry.roll` line by line.

Everything below defaults to **MMG behaviour**. The alternative is in the note line.

- [x] B1. Stats live in the config pool (`stats.pool`). A pet rolls stats from it, the values scale with pet level, and they apply while the pet is equipped and alive. Only the pool content changes to your list.
  Note:
- [x] B2. The pool entries are your passive attributes: max_health, armor, attack_damage, movement_speed, knockback_resistance, luck, attack_speed. I add a new stat kind `ATTRIBUTE` (MMG has none). Swim speed and reach are skipped.
  Note:
- [x] B3. Each number you listed (+2 HP, +1 armor, +0.5 damage, +2% speed, +5% knockback res., +1 luck, +5% attack speed) is the **roll at pet level 1**, and the roll is the max of that stat (`max:`). What `min:` for each? Default: min = max, so the number is always what you wrote.
  Note:
- [x] B4. **Level scaling stays on** (MMG formula `1 + (level-1) * 0.05`, +5% per level). Max level = per `pets.yml`. Alternative: off, flat numbers at every level. Do the capped attributes need a limit (for example max_health)?
  Note:
- [x] B5. "cap 1 pet aktif": only **1 pet is equipped** (`max-equipped: 1`), so stats never stack. This means the MMG `petslots` permission is dropped. Alternative: several equipped, but only one pet's stats count.
  Note:
- [x] B6. Rolled stats: a pet rolls `starting-stats` stats from its pool when obtained, holds at most `max-stats` (MMG: global 8). The pool has 7 attribute stats, plus the 8 effects of section C if C1 stays at default (15 in total). How many does a pet start with and hold? Default: starts with 3, max 8 (MMG's global max). Alternative: every pet starts with all of the attribute stats.
  Note:
- [x] B7. **Reroll items and stat-lock items are kept** (MMG: reroll gives `reroll-amount` stats, one BAD stat per reroll, locks cost charms). Alternative: removed.
  Note:
- [x] B8. Evolution `bonus-stats` (extra stats rolled on success) is **kept**, per-pet `stat-pool` and `guaranteed-stats` are kept, and **BAD stats are kept**. What BAD stat would you put in the pool (for example -armor)? Default: none, so rerolls roll no BAD stat. Alternative: drop all of these.
  Note:
- [x] B9. **How do pet tiers differ?** For each pet type (evolution tier) tell me `starting-stats`, `max-stats`, which stats are in its `stat-pool`, and any `guaranteed-stats`. Example: common 2 / 3, rare 3 / 5, legendary 4 / 8. Default: all tiers use the same pool and caps (starting 3, max 8).
  Note:
- [x] B10. Operations (so the numbers mean what you expect):
  max_health +2 flat; armor +1 flat; attack_damage +0.5 flat; luck +1 flat.
  movement_speed +2% and attack_speed +5% as `ADD_SCALAR` (a multiplier on the base value; the enum exists, I verified it).
  **knockback_resistance +5%: flat +0.05**, because its base value is 0 so a multiplier would give nothing (1.0 = full immunity). I did not verify that base value here; it is from my knowledge of the attribute.
  Note:
- [x] B11. One fixed modifier key per attribute. Removed on unequip, death, logout and plugin disable. Re-applied on join and respawn. Dead or starving pet gives nothing (same as MMG).
  Note:
- [x] B12. **Luck** is the attribute only (it affects loot tables with quality, such as fishing and chests). I do **not** inject mob drops. Want drop injection as an option?
  Note:

## C. Status effects (new)

- [x] C1. These are **new stat kinds** with conditions (HP, combat, mining), which MMG's `POTION_EFFECT` kind does not have. Are they pool stats rolled per pet like the attributes (MMG way), or do all pets always have all of them? Default: **pool stats**, in the same pool as section B, so B6 to B9 apply to them too. Effect levels do not scale with pet level (as in MMG, level II stays level II). Below, C3 to C8 mean "while the pet holds this stat". If you answer "all pets always have them", it means "always".
  Note:
- [x] C2. They are applied by a repeating task (default every 2s) with a short duration, never permanent. Removed right away on unequip or death. Each effect has an on/off switch in config.
  Note:
- [x] C3. Haste I while mining: "mining" means the player broke a block in the last 5 seconds. Effect lasts 5s and is refreshed.
  Note:
- [x] C4. Regeneration: vanilla Regen I heals 1 HP about every 2.5s, not 1 HP / 8s. So I use a **custom heal task** (1 HP every 8s, only when HP is below 70%), not the potion effect. Or do you prefer the real Regen I potion?
  Note:
- [x] C5. Resistance I only when HP is below 30%.
  Note:
- [x] C6. Jump Boost I, Night Vision, Water Breathing: on while the pet holds that stat. Night Vision keeps a duration above 10s, or the screen flickers.
  Note:
- [x] C7. Fire Resistance with 3s refresh, on while the pet holds that stat.
  Note:
- [x] C8. Speed I only outside combat.
  Note:
- [x] C9. "Combat" = the player hit or was hit by a mob or player in the last **10 seconds** (config `combat-seconds`).
  Note:
- [x] C10. Existing effects from other sources are not overwritten by a weaker or shorter one.
  Note:

## D. AFK player XP (new)

- [x] D0. Is AFK XP a **perk of every summoned pet**, or a **rollable stat** that only some pets hold (in the pool like section B)? Default: every summoned pet has it.
  Note:

Pet EXP (from food, A4) and player XP here are **two separate things**.

- [x] D1. Reward is `player.giveExp(points)`, never `giveExpLevels`.
  Note:
- [x] D2. Payout every 60 minutes of AFK: 30 XP points. Config: `interval-minutes: 60`, `xp-per-interval: 30`.
  Note:
- [x] D3. Daily cap 240 XP points per player account, reset at 00:00 server time. Config: `daily-cap: 240`. If one payout would pass the cap, I pay only the remainder.
  Note:
- [x] D4. The daily total is saved in storage (not memory), so a restart does not reset it. Storage is my own table keyed by player UUID (see section F).
  Note:
- [x] D5. XP is paid only when: pet is summoned, alive, and **mood is Happy**. Config: `required-mood`.
  Note:
- [x] D6. **Mood (new, not in MMG).** My proposal: mood comes from the food bar. Happy at 60% food or more, Neutral 25–60%, Sad under 25%. Is this right, or should mood come from something else (petting, time since last feed)?
  Note:
- [x] D7. **Stay mode (new, not in MMG).** A toggle button in the options menu. In Stay the pet stays where it was left and does not follow. "Stay far from player" means more than **16 blocks** away (config), or in another world. Is this the meaning?
  Note:
- [x] D8. No XP while: in combat (C9), or the player is in water or lava, or the pet is in Stay mode far from the player.
  Note:
- [x] D9. The partial hour (AFK progress toward the next payout) is **saved** across relog and restart. It resets when the player stops being AFK. (Or should it be lost on relog?)
  Note:
- [x] D10. AFK detection, since I found no `PlayerIdleEvent`: my own tracker. Idle = position moved less than 0.1 block **and** camera not turned for 3 minutes (config). Reset on move, rotate, chat, click, interact, command, block break and place.
  Note:
- [x] D11. I do not use EssentialsX AFK.
  Note:
- [x] D12. The 1-hour timer starts counting only after the 3 minutes of idle are reached.
  Note:
- [x] D13. "XP does not run if the chunk is not loaded": an online player's chunk is always loaded, so I read this as: **the player or the pet is not in a loaded chunk** (for example the pet in Stay mode in an unloaded chunk). I check `world.isChunkLoaded` for both. Is that what you mean?
  Note: I believe there's an async one.
- [x] D14. Action bar while AFK: `XP AFK berikutnya: 42m`, updated every 1s or 1 minute (config). Text from messages config.
  Note:
- [x] D15. Each payout is logged to `plugins/ModernSlimefunPet/afk-payouts.log`: time, player name and UUID, amount, daily total, world and coordinates, IP. Is that enough for alt audit, or do you want it in the database too?
  Note: Can be in log but perhaps write it in async or if you have anything better then go ahead

## E. Slimefun

- [x] E1. The server only runs Slimefun. ModernSlimefunPet is a **standalone Paper plugin** (soft-depends on Slimefun), not a Slimefun addon.
  Note:
- [x] E2. Pet items (food, candy, revive, evolution stone) are normal PDC items, not registered Slimefun items. Is that fine, or should they be Slimefun items (craftable in Slimefun machines, shown in the Slimefun guide)?
  Note: As long slimefun cannot dupe it
- [x] E3. Should Slimefun be required for anything else (for example "mining" counts Slimefun machines, or items given from a Slimefun recipe)?
  Note: None

## F. Platform and technical

- [x] F1. Build target Paper **1.21.5** API (MMG's target; 1.21.11 is also in the local Maven repo). Java 21. The test server folder says 26.3. Which version does your live server run? I could not verify 26.3 API here.
  Note: Target is 26.3
- [x] F2. Libraries: MDLib 2.4.9 (menus, items), PacketEvents 2.13.0 (drawing), Vault optional (revive and evolution money), Crunch for formulas. Same as MMG.
  Note: MDLib must be 3.0.1
- [x] F3. Storage: own **SQLite** file in the plugin folder (tables for pets, equipped set, hide and stay, AFK daily cap and progress). MySQL is not needed. Right?
  Note: Use HikariCP and has MySQL support aswell and Redis for future cross server support. And migration
- [x] F4. Plugin name `ModernSlimefunPet`, package `id.alwination.slimefunpet` (my pick, change if wanted), commands `/pet`, `/pets`, admin `/slimefunpet`.
  Note:
- [x] F5. Permissions: `modernslimefunpet.*` (skins `modernslimefunpet.skin.<model>`, admin). The slot permission `modernslimefunpet.petslots.<n>` only exists if B5 says several pets can be equipped.
  Note:
- [x] F6. How players get pets: admin command and console (crates or shops run it), same as MMG. No Slimefun recipe. Right?
  Note: Yes
- [x] F7. Own `/slimefunpet reload` that also restarts the timers.
  Note:
- [x] F8. Messages in Indonesian by default (your action bar text is Indonesian). English as second file?
  Note: Everything in English
- [x] F9. Bedrock handshake (`alwination:has_pack`) from MMG: keep or drop? Default: **drop**, Bedrock players see heads.
  Note:
- [x] F10. Build follows the `mc-plugin-kit` lint gate and cold review before delivery.
  Note:

## Anything else I missed
Note:
