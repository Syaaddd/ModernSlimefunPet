# Live checks (in game, on a TEST server)

Nothing below has been run. The build server has no game client. Do these on a test server with
Paper 26.3, Java 25, PacketEvents and (for money costs) Vault plus an economy plugin.
Do NOT put this jar on the live server before the Slimefun guards (M7) exist.

## Setup
- `/slimefunpet givepet <you> common_pet`
- `/slimefunpet givepetitem <you> food small_treat 16` (and candy, evolution, revive, reroll, stat_lock, auto_feed_unlocker)

## Pets and display (M2)
1. `/pets` opens the list; the pet shows level, food, stats and its evolution.
2. Equip it: a floating head (or the model) follows you with the name tag.
3. Try to equip a second pet: refused, only 1 can be out.
4. Hide toggle hides your pet; relog keeps it hidden.
5. Change world, die and respawn: the pet comes back.
6. Skins: with `modernslimefunpet.skin.redpanda` the Skins menu offers Red Panda; remove the permission and the pet goes back to its own look on the next redraw.

## Food, death, revive, candy, Auto-Feed (M3)
7. Right-click food, pick the pet: food rises, one item is taken.
8. Candy gives levels. A pet at max level refuses candy and keeps the item.
9. Set `food.decay-per-hour` very high, wait for a food tick: the pet starves, is unequipped, and stays unequipped after a restart.
10. Revive totem brings it back unequipped.
11. Auto-Feed: unlock it from the options menu, store food, let the bar drop to the threshold: it eats by itself.

## Evolution (M4)
12. Level the pet to 10 (candy), right-click an evolution stone: the menu shows 50% (+25% with the blessed stone). No booster buttons.
13. Shift-click inside your own inventory while the evolution menu is open: refused.

## Stats (M5)
14. Equip a pet with max_health: one extra heart (+2 health points). Unequip: gone at once, and your health is not above the new max.
15. movement_speed +2% and attack_speed +5%: compare `/attribute @s minecraft:movement_speed get` before and after.
16. knockback_resistance shows +0.05 in `/attribute @s minecraft:knockback_resistance get`.
17. Haste only while breaking blocks (gone about 5 s after you stop).
18. Resistance only under 30% health; regeneration heals 1 HP every 8 s only under 70%.
19. Speed disappears when you hit or get hit by a mob, and returns 10 s later.
20. Night Vision does not flicker.
21. Die and respawn with the pet equipped: the attributes come back within 2 s.
22. A beacon's Haste II is not replaced by the pet's Haste I.

## Saving
23. Feed a pet, then kill the server process (not /stop) within a second: after restart the feed and the taken food both stuck, or neither did.
