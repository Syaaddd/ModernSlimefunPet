# Slimefun / addon vectors for ModernSlimefunPet items

Scope: read-only review of Slimefun5-AE, Networks5-AE, InfinityExpansion5-AE, FastMachines5-AE, Supreme5-AE.
Paths below are relative to /home/sr/work/. SF = Slimefun5-AE/core/src/main/java/io/github/thebusybiscuit/slimefun5.
Unverified items are marked UNVERIFIED. "No event" means no Bukkit event fires on that code path.

## 0. Baseline (verified)
- SF/utils/SlimefunUtils.java:368 isItemSimilar: type mismatch -> false (:372). Candidate with ItemMeta vs Slimefun-item
  ingredient without Slimefun id -> false (:440-443). Candidate with meta vs metaless vanilla ingredient -> falls to
  `else return false` (:476-478 area). Candidate with meta vs ingredient with meta, both non-Slimefun -> equalsItemMeta
  (:491/:517): display name, lore (if checkLore), CustomModelData, potion type. PDC is never compared.
- SF/utils/SlimefunUtils.java:107/:120 hasNoPickupFlag/markAsNoPickup: Bukkit metadata key "no_pickup" (:68) on the Item
  entity, plus pickup delay Short.MAX_VALUE.

## 1. Material/type-only and RecipeChoice matching

### 1.1 Vanilla Auto Crafter (Slimefun) - HIGH
- SF/implementation/items/autocrafters/VanillaRecipe.java:53-62 turns each Shaped/Shapeless recipe slot into the raw
  Bukkit RecipeChoice used as a Predicate. VanillaAutoCrafter.java:100-105 `matches()` only rejects Slimefun items
  (`sfItem == null || isUseableInWorkbench`), then AbstractAutoCrafter.java:370 calls `predicate.test(item)`.
  MaterialChoice.test is type-only (javap of paper-api 26.3: iterates choices, `getType()` == only), so ANY
  non-Slimefun stack of the right Material, with any
  name/lore/PDC, is an ingredient. craft() at AbstractAutoCrafter.java:409 consumes from the vanilla chest it sits on.
- Only Shaped/ShapelessRecipe are offered (VanillaAutoCrafter.java:184-187); complex recipes (shulker dyeing, banner,
  firework) are not.
- Event: none fired in craft(). Cannot be cancelled.
- Mitigation: Material must not appear in any vanilla Shaped/Shapeless recipe input (see section 6).

### 1.2 Electric Furnace / furnace bridge - LOW (safe as long as item has meta)
- SF/implementation/items/electric/machines/ElectricFurnace.java:37-46 registers every vanilla FurnaceRecipe whose input
  is a MaterialChoice as `new ItemStack(input)` (metaless). AContainer.findNextRecipe uses isItemSimilar
  (me/mrCookieSlime/Slimefun/Objects/SlimefunItem/abstractItems/AContainer.java:427), and our meta-carrying item vs a
  metaless ingredient returns false (SlimefunUtils.java:479). Safe.
- SF/core/services/MinecraftRecipeService.java:120-137 getFurnaceOutput/isSmeltable delegate to dough RecipeSnapshot
  (external lib, UNVERIFIED in code, upstream uses RecipeChoice.test = type-only). Used by SmeltersPickaxe:61 (block
  drops, not our item), EnhancedFurnaceListener:88 (vanilla furnace smelting, vanilla behaviour anyway) and
  CargoUtils.java:404 (only slot routing, lazy path is Tag.LOGS at :402).
- Mitigation: Material without a vanilla smelting recipe (vanilla furnace would smelt it regardless of Slimefun).

### 1.3 Same pattern, also safe: Freezer, Food Fabricator, Electric Ingot Pulverizer, Auto Drier, Electric Smeltery
- All register metaless `new ItemStack(Material)` ingredients and match via AContainer isItemSimilar
  (Freezer.java:44-56, FoodFabricator.java:24-34, ElectricIngotPulverizer.java:59-62, AutoDrier.java:92-104,
  ElectricSmeltery.java:77). Our item never matches. LOW.
- Electric Gold Pan / Gold Pan: GoldPan.java:208-214 isValidInput = material in list AND isItemSimilar(item, metaless
  stack) -> false for us. LOW.

### 1.4 Type-only machines with no recipe table - MEDIUM
- Auto Anvil: AutoAnvil.java:51 repairs ANY item with maxDurability > 0 and damage > 0 (plus Duct Tape). No event.
  Mitigation: non-damageable Material (maxDurability 0).
- Auto Brewer: AutoBrewer.java:76-87 treats the potion slot by Material only (POTION/SPLASH/LINGERING) and rewrites its
  PotionMeta; the ingredient slot rejects any item with meta (:80-82). No event. Mitigation: no potion Materials.
- Auto Enchanter: AutoEnchanter.java:160-164 accepts any non-air, non-ENCHANTED_BOOK, non-Slimefun item; applies any
  book enchant whose `canEnchantItem(target)` is true. Fires AutoEnchantEvent (:72) and
  AsyncAutoEnchanterProcessEvent (:93), both cancellable.
- Auto Disenchanter: AutoDisenchanter.java:159-162 accepts any non-air, non-BOOK, non-Slimefun item and moves ALL real
  enchantments into a book (:94-112). If our item uses a real enchantment for glint, that is a free enchanted book per
  item = HIGH. Fires AutoDisenchantEvent (:73), cancellable.
  Mitigation: never put real enchantments on our items (use ItemMeta#setEnchantmentGlintOverride(true)); also cancel
  AutoEnchantEvent/AutoDisenchantEvent when getItem() carries our PDC.
- Server-side extra: AbstractEnchantmentMachine.java:44-45,74-90 has opt-in `use-ignored-lores`/`ignored-lores` item
  settings (default off). A Slimefun config change, not ours.

### 1.5 Multiblocks - MEDIUM, event-cancellable
- Table Saw: TableSaw.java:111 picks output by held item Material only (Tag.LOGS / Tag.PLANKS, :134-142).
- Automated Panning Machine: AutomatedPanningMachine.java:73-80 by held item Material only (gravel/soul sand list).
- Enhanced Crafting Table / Magic Workbench / Armor Forge: `sameWoodMatch` (AbstractCraftingTable.java:88-114) lets
  any NON-Slimefun stack whose Material ends in _PLANKS/_LOG/_WOOD satisfy a wood cell, ignoring meta
  (EnhancedCraftingTable.java:104, MagicWorkbench.java:150, ArmorForge.java:75).
- All of these fire MultiBlockCraftEvent (TableSaw.java:118, AutomatedPanningMachine.java:85,
  EnhancedCraftingTable.java:53, MagicWorkbench.java:61, ArmorForge.java:50, Smeltery/AbstractSmeltery.java:53,
  GrindStone.java:133, Compressor.java:73, Juicer.java:69, PressureChamber.java:58, OreCrusher.java:199,
  OreWasher.java:125). getInput() returns ItemStack[] (api/events/MultiBlockCraftEvent.java:75).
  MultiBlockInteractEvent (MultiBlockListener.java:73) is also cancellable, has the player.
- Mitigation: cancel MultiBlockCraftEvent if any input carries our PDC; avoid wood/gravel/soul-sand Materials.

### 1.6 InfinityExpansion via infinitylib - HIGH
- infinitylib is only present as compiled classes (InfinityExpansion5-AE/build/tmp/.cache/expanded/.../infinitylib),
  read with javap. `StackUtils.getIdOrType` returns the Slimefun id, else `getType().name()` for ANY non-Slimefun
  stack, meta ignored. `MachineBlock.getOutput` keys inputs by `getId` else `Material.name()`, and
  `MachineBlockRecipe` keys recipe inputs the same way. So every infinitylib MachineBlock matches our item by
  Material only.
- MachineBlock instances: Infinity Machines.java:220 (Extreme Freezer: ICE, MAGMA_BLOCK), :247 (Concrete Molding),
  :272/:294 (Dust Extractors: COBBLESTONE/ANDESITE/STONE/DIORITE/GRANITE), :308 (Cobble Press), :319/:334 (Ingot
  Formers: IRON/GOLD/DIAMOND/... ingots and blocks), :352 (Uranium Extractor), :363 (Decompressor: *_BLOCK).
- Singularity Constructor: SingularityConstructor.java:50 (recipe key) and :115 (input key) both use getIdOrType.
  Vanilla inputs: GOLD_INGOT, IRON_INGOT, DIAMOND, EMERALD, NETHERITE_INGOT, COAL, REDSTONE, LAPIS_LAZULI, QUARTZ.
  Consumes inputs into progress (:128-132).
- Stoneworks Factory: StoneworksFactory.java:114 `item.getType() == c.inputs[check]` (COBBLESTONE, STONE, SAND,
  GRAVEL, GRANITE/DIORITE/ANDESITE and polished, STONE_BRICKS, SANDSTONE, ...).
- Growing machines: GrowingMachine.java:59-64 keyed by `input.getType()` (plant Materials, Machines.java:155+,
  exact list UNVERIFIED); the
  input is not consumed, it keeps producing output while it sits there.
- Gear Transformer: GearTransformer.java:135,151,166 type-only for tools/armor (and :215 rejects Slimefun items only).
- Advanced Anvil: AdvancedAnvil.java:116 pairs any two same-Material items or item + ENCHANTED_BOOK; returns no output
  when neither has enchantments (:158-160). Only a risk if our item has real enchantments.
- Safe in Infinity: infinitylib `StackUtils.isSimilar` (CraftingBlock / Infinity Workbench recipes, javap) requires
  both or neither stack to have meta, then Slimefun id or type + display name. Our meta item never matches a
  metaless ingredient. ResourceSynthesizer, MobSimulationChamber, InfinityReactor key by Slimefun id only.
  VoidHarvester and MaterialGenerator take no input.
- Events: grep of `callEvent` in Infinity/FastMachines/Supreme finds only VeinMinerRune (needs a damageable item).
  Mitigation: Material choice (section 6) plus the click guard (section 5).

### 1.7 Networks - LOW for matching
- Networks matching is strict: StackUtils.itemsMatch (Networks5-AE/.../utils/StackUtils.java:102-188) compares type,
  meta class, CMD, Slimefun id, PDC (`containersEqual` :163/:445), enchants, flags, lore, name. No RecipeChoice use.
- Network crafting only knows Slimefun ENHANCED_CRAFTING_TABLE recipes (network/SupportedRecipes.java:19-34, matched
  with itemsMatch :48). Quantum Workbench uses isItemSimilar (NetworkQuantumWorkbench.java:167).
  Our item can never be a Networks crafting ingredient.

### 1.8 FastMachines - LOW
- Vanilla MaterialChoice is converted to a metaless ItemWrapper (FastMachines5-AE/.../utils/items/RecipeExt.java:30-38)
  and matched with ItemExt.isSimilarTo (utils/items/ItemExt.java:38-66): meta present on one side only -> false; the
  optional `fmUseBukkitItemComparison` path uses ItemStack#isSimilar (:54-55), also strict. Consumption goes through
  the same check (utils/MachineUtils.java consumeChoice, isSimilarTo at :64). Our item never matches.

### 1.9 Supreme - MEDIUM
- Type-only, no event: VirtualAquarium.java:144 (FISHING_ROD, TRIDENT, GOLDEN_HOE inputs; damages or consumes them,
  :147-153), VirtualGarden.java:137 (input list from config, UNVERIFIED; not consumed), MobCollector.java:391
  (GLASS_BOTTLE consumed :396, tools
  such as SHEARS / IRON_SWORD damaged). GenericMachine (:367,:503,:513) and the multiblock fabricators use
  isItemSimilar (safe).
- Mitigation: Material choice; do not use bottles, shears, swords, hoes, rods, tridents.

### 1.10 Generators / fuel - LOW
- MachineFuel.test uses isItemSimilar (me/mrCookieSlime/.../abstractItems/MachineFuel.java:69-70). Safe.

## 2. Inventory moves without Bukkit events

- Slimefun Cargo input node pulls from any vanilla container: CargoUtils.java:232-250 (only the node's own
  whitelist/blacklist filter, :241, ItemFilter uses isItemSimilar against player-set filter items). Output nodes
  insert into any Slimefun BlockMenu slot the preset exposes (CargoUtils.java:251-285), including Trash Can. HIGH.
  No event. A node with an empty blacklist moves everything.
- Networks Vanilla Grabber pulls any item from any InventoryHolder block next to it (NetworkVanillaGrabber.java:93-135),
  only a protection check (:87). Network Grabber pulls from Slimefun menus (NetworkGrabber.java:51-63), Network
  Import pushes its input slots into the network (NetworkImport.java:84), Vanilla Pusher writes into vanilla blocks
  (NetworkVanillaPusher.java:93-115). HIGH. No event.
- Vanilla Auto Crafter consumes from the chest it sits on (section 1.1). No event.
- Dropped Item entities: Network Vacuum NetworkVacuum.java:99, Infused Magnet InfusedMagnetTask.java:50, Infused
  Hopper InfusedHopper.java:113, Butcher Android ButcherAndroidListener.java:55. All skip items with the "no_pickup"
  metadata (SlimefunUtils.hasNoPickupFlag :107, key-only `hasMetadata("no_pickup")`, any plugin may set it); the first
  three also require pickupDelay <= 0. Only Network Vacuum and Butcher Android put the item into Slimefun storage;
  magnet/hopper just teleport it (vanilla pickup then fires EntityPickupItemEvent / InventoryPickupItemEvent).
  COST of using the marker: Slimefun's own ItemPickupListener.java:28/:43 then cancels player AND hopper pickup of
  that entity, and Bukkit metadata is memory-only (lost on chunk unload/restart). Severity MEDIUM, no event.
  Mitigation options: (a) on ItemSpawnEvent for our stacks set "no_pickup" and re-allow player pickup by un-cancelling
  EntityPickupItemEvent at HIGHEST (runs after ItemPickupListener's NORMAL handler); re-apply on EntitiesLoadEvent;
  (b) PlayerDropItemEvent cancel only covers manual drops (not death, container break, inventory overflow).
- Ancient Pedestal: right-click with ANY held item (AncientAltarListener.java:163-171) consumes one and spawns a
  display copy renamed `ITEM_PREFIX + nanoTime` (AncientPedestal.java:163-186, marked no_pickup); on removal the name
  is rebuilt from the entity custom name (:148-160). PDC/lore survive the clone; legacy-string round trip of the
  display name may lose component styling (UNVERIFIED). Altar recipes use isItemSimilar (:353-369), so it never
  crafts. MEDIUM, cancellable through PlayerRightClickEvent#setUseBlock(DENY) (listener returns at :108).
- Markers/blacklists that exist (none is a public "isAllowed" API):
  SlimefunBackpack.isItemAllowed (SlimefunBackpack.java:65-72: shulker boxes, backpacks), Networks Quantum
  isBlacklisted (NetworkQuantumStorage.java:562-570: AIR, maxDurability<0, *SHULKER_BOX, non-Slimefun BUNDLE,
  quantum storages), NetworkCard isBlacklisted (NetworkCard.java:79-83: *SHULKER_BOX). Network Cells
  (NetworkRoot.java:760-790), Infinity Storage Units and Cargo have no blacklist at all.

## 3. Storage that clones / rebuilds stacks

- Networks Quantum Storage: template + counter. setItem clones the cursor item as template (NetworkQuantumStorage.java
  :234-249), deposits only if StackUtils.itemsMatch incl. PDC (:540-552), withdraw = template.clone() + setAmount
  (QuantumCache.java:79-86). PDC is kept (Base64 Bukkit serialisation, SerializationUtils.java:26-32). Every
  withdrawn copy carries the template's PDC. MEDIUM, no event; mitigation: ledger below + section 5 click guard.
- Infinity Storage Unit: Material + full ItemMeta template (StorageCache.java:402-413), matches by `meta.equals`
  (:421-425, includes PDC), rebuilds with `new ItemStack(material, n)` + setItemMeta (:427-433). PDC kept. MEDIUM, no
  event, same mitigation. Networks bridges it (network/barrel/InfinityBarrel.java:34-36).
- Network Cells and Cargo move the real stacks. Backpacks persist via Bukkit YAML item serialisation
  (PlayerBackpack.java:79, keeps PDC). BlockMenu slot save/load not opened (UNVERIFIED, Bukkit serialisation
  expected). Slimefun ItemStackWrapper is comparison-only and immutable (ItemStackWrapper.java:26-102, clone()
  throws :81-82). LOW.
- Mitigation: unique id per high-value item in PDC + server-side ledger (issued / redeemed). A cloned copy then dies
  on second redemption. Unique ids also stop identical-looking items from merging in quantum/Infinity storage.

## 4. Machines that consume or void any item

- Trash Can: TrashCan.java:69-85 async ticker sets every input slot to null, any item, fed by players or Cargo.
  MEDIUM (player loses it), no event; mitigation: click guard; Cargo path not preventable.
- Portable Dustbin: PortableDustbin.java:42 opens `createInventory(null, 27, "Delete Items")`, contents discarded.
  LOW, no event; click guard (holder null) covers it.
- Network Purger: NetworkPurger.java:100-117 deletes network items matching the test-slot item (itemsMatch, strict).
  MEDIUM, no event; click guard keeps our item out of the test slot.
- Void excess: Quantum QuantumCache.java:41-52 (`voidExcess`), Infinity StorageCache.java:292-297 and :502-507.
  MEDIUM, no event; click guard + Material choice.
- Type-only consumers: Vanilla Auto Crafter (1.1), infinitylib MachineBlocks, Singularity Constructor, Stoneworks (1.6),
  Supreme MobCollector/VirtualAquarium (1.9), Auto Anvil/Brewer/Disenchanter (1.4). HIGH; Material choice.
- Rune Anvil: RuneAnvil.java:210-224 turns ANY single item (no Slimefun check in the soulbound branch) into a soulbound
  clone shown in the output slot; whether the input is then consumed is in the :92 output handler (not read,
  UNVERIFIED). Enchant rune only if the Material is enchantable (:242-263). MEDIUM, no event; click guard.
- None of these check anything we can set except the Auto(Dis)Enchanter events and MultiBlockCraftEvent.

## 5. Recognising Slimefun inventories from InventoryClickEvent

- Holder class recognition is impossible: every Slimefun GUI is `Bukkit.createInventory(null, size, title)`:
  ChestMenu (CSCoreLibPlugin/general/Inventory/ChestMenu.java:226,:236; BlockMenu extends DirtyChestMenu extends
  ChestMenu), backpacks (api/player/PlayerBackpack.java:110,:309,:344, title "Backpack [N Slots]"), Portable Dustbin
  (PortableDustbin.java:42). Networks, Infinity (infinitylib MenuBlockPreset extends BlockMenuPreset, javap),
  FastMachines and Supreme create no inventories of their own. So a Slimefun top inventory is: holder == null,
  InventoryType.CHEST. Use Paper `Inventory#getHolder(false)` (exists in paper-api 26.3) to skip the BlockState
  snapshot vanilla containers would build.
- The only exact registry is `MenuListener.menus` (MenuListener.java:33), a package-private static
  Map<UUID, ChestMenu> filled in ChestMenu.open (:265). Reflection only; not recommended. BlockStorage.getInventory
  (Block/Location) (BlockStorage.java:1357,:1443) needs a location, which a click event does not give.
  `StorageCacheUtils` and `getDatabaseManager` do not exist in this fork (grep: no hits).
- PRIORITY TRAP (verified): MenuListener.onClick (:60-61) is a plain @EventHandler (NORMAL, ignoreCancelled=false) and
  overwrites the flag (:80, :82, :84, :87) with the handler's answer. A LOWEST/LOW cancel is undone, and slot click
  handlers with side effects run regardless (e.g. Quantum Storage set-item clones the cursor as template,
  NetworkQuantumStorage.java:234-249; quick-deposit then pulls every matching stack straight from the player
  inventory, :257-285). BackpackListener.onClick (BackpackListener.java:94) is ignoreCancelled=true.
- Therefore: cancel at EventPriority.HIGHEST (ignoreCancelled=false) and make sure our item can never reach the cursor
  while a foreign holder-null top inventory is open. Cursor is empty when a container opens (vanilla behaviour,
  UNVERIFIED here), so blocking pickup,
  NUMBER_KEY (getHotbarButton), SWAP_OFFHAND, shift-click and InventoryDragEvent while such a view is open means no
  handler ever sees our item on the cursor. No addon registers a side-effect player-inventory handler
  (grep addPlayerInventoryClickHandler: none; only setPlayerInventoryClickable).
- Suggested rule: top = view.getTopInventory(); foreign = top.getType() == InventoryType.CHEST &&
  top.getHolder(false) == null. Our own GUIs must therefore use a non-null custom InventoryHolder.
  If foreign and the click/drag involves our item in any role -> cancel, except moving our item OUT of the top
  inventory (so a stack Cargo pushed into a machine can be recovered). This also covers backpacks, Dustbin, Trash Can,
  Rune Anvil, Networks grid/quantum/cells, Infinity units, and every other plugin's GUI (accept that, or whitelist).
- Severity HIGH, fully preventable for the player-click path.

## 6. Material choice

Method (verified): vanilla recipe data read from /tmp/dmserver/versions/26.3/paper-26.3.jar (data/minecraft/recipe/*,
2044 files, all ingredient-bearing keys incl. smithing, brewing reagent, dye, transmute, decorated pot, firework,
tags expanded via data/minecraft/tags/item). Material list from paper-api 26.3 (javap org.bukkit.Material).
"Referenced" = token match of the Material name (or minecraft:id) anywhere in the 5 source trees incl. resources and
comments (conservative). Then dropped: anything with a block loot table (placeable), damageable/enchantable tags,
food, ammo, spawn eggs, buckets, boats/minecarts. Dynamic name checks in the trees (`endsWith("SHULKER_BOX")`,
`_SIGN`, `_PLANKS/_LOG/_WOOD`, armour/tool name building in GearTransformer) do not hit any pick below.
Candidates (none is a recipe ingredient, none appears in any tree):
- SPYGLASS: in no item tag; right-click only zooms (harmless). Stack 1 (UNVERIFIED, from vanilla knowledge).
- RECOVERY_COMPASS: only tag `compasses`; no recipe uses it; inert in hand. Stack 64 (UNVERIFIED). Best for stackables.
- GLOBE_BANNER_PATTERN (or FLOW_/GUSTER_/PIGLIN_/MOJANG_...): tag `loom_patterns`; only use is the loom, which does not
  consume the pattern (guard the loom click if wanted). Stack 1 (UNVERIFIED).
- MUSIC_DISC_RELIC (or other discs listed above): no tag; side-use is inserting into a jukebox (hoppers can then pull
  it out). Cancel PlayerInteractEvent on JUKEBOX if used. Stack 1 (UNVERIFIED).
- Not recommended although they pass: DEBUG_STICK (anti-illegal-item plugins strip it), TRIAL_KEY/OMINOUS_TRIAL_KEY
  (opens vaults), OMINOUS_BOTTLE (drinkable), NETHERITE_HORSE_ARMOR (equippable).
- Paper 26.3 ItemMeta has setItemModel, setMaxStackSize, setEnchantmentGlintOverride, setRarity (javap verified).
  So pick an inert Material for identity and give it any look with setItemModel (vanilla model id or resource
  pack model) and any stack size with setMaxStackSize. Matching code only ever sees getType().
PLAYER_HEAD (used by most Slimefun items): Slimefun-item ingredients refuse non-Slimefun stacks
(SlimefunUtils.java:434-443), so heads are not a recipe risk by themselves. But PLAYER_HEAD is placeable (PDC lost
on place: must cancel BlockPlaceEvent, and Slimefun's Block Placer places any non-Slimefun block Material,
BlockPlacer.java:91,:187 with a cancellable BlockPlacerPlaceEvent), it is in tag `skulls` (vanilla firework star
shape in a normal crafting table) and it makes our items look like Slimefun items to players. Avoid it.

## Recommended item form and guard set

Item form:
1. Material RECOVERY_COMPASS (stackables) or SPYGLASS / a banner pattern (singles), never a block, tool, food,
   potion, bottle, wood, ore/ingot or anything in section 1 lists. Keep the Material in config but validate it
   against a small allow-list at load.
2. Look via setItemModel; glint via setEnchantmentGlintOverride(true), NEVER real enchantments (Auto Disenchanter).
3. Always carry ItemMeta (display name + PDC); that alone defeats every isItemSimilar/FastMachines/infinitylib
   isSimilar path against metaless vanilla ingredients.
4. High-value items (revive totem, evolution stones, reroll, stat-lock, Auto-Feed unlocker/upgrade): unique id in
   PDC + DB ledger (issued, redeemed). Refuse unknown or already-redeemed ids. This is the only answer to clones
   from quantum/Infinity storage or any future dupe.
Guards (all cheap, all in our plugin):
- InventoryClickEvent + InventoryDragEvent at HIGHEST: section 5 rule.
- MultiBlockCraftEvent: cancel if any getInput() stack is ours. MultiBlockInteractEvent: cancel if main hand is ours.
- AutoEnchantEvent, AutoDisenchantEvent, AsyncAutoEnchanterProcessEvent: cancel if getItem() is ours (machine
  tickers may run async; only read PDC there).
- PlayerRightClickEvent (Slimefun): if getItem() is ours AND getSlimefunBlock().isPresent(), setUseBlock(DENY) only.
  Slimefun copies the result back onto the Bukkit event (SlimefunItemInteractListener.java:91-96), so denying
  unconditionally would block chests/doors and our own use. Covers Ancient Pedestal, Crucible, Composter.
- BlockPlaceEvent / BlockPlacerPlaceEvent: cancel for ours (moot with a non-block Material).
- Dropped entities: ItemSpawnEvent marker approach from section 2 (no_pickup + HIGHEST un-cancel for players), or
  accept the Network Vacuum / Butcher Android residual (listed below).
- Vanilla: PrepareItemCraftEvent/CraftItemEvent, PrepareSmithingEvent, PrepareAnvilEvent, FurnaceSmeltEvent,
  BrewEvent etc. are outside Slimefun but vanilla recipe matching is also type-only.

## Cannot be prevented from our plugin
- Event-less container readers once the player puts our item into ANY vanilla container: Slimefun Cargo nodes
  (CargoUtils.java:232), Networks Vanilla Grabber (NetworkVanillaGrabber.java:93-135), Vanilla Auto Crafter
  (AbstractAutoCrafter.java:409). Only Material choice limits what they can DO with it; they still move/store it.
  Fully closing this means forbidding our items in every vanilla container too (InventoryClickEvent/Drag/
  InventoryMoveItemEvent/hopper pickup), which is a product decision.
- Anything a Slimefun ticker does to an item that already sits in a Slimefun slot (Trash Can, Purger, void-excess).
- Template cloning and PDC copy inside Networks Quantum / Infinity Storage (ledger makes clones worthless, nothing
  stops them existing).
- Server config of other plugins (Auto Enchanter ignored-lores, cargo filters) and future addon updates; re-run this
  review when the Slimefun forks change.
- Dropped entities picked up by Network Vacuum (NetworkVacuum.java:99) or Butcher Android (ButcherAndroidListener
  .java:55) unless the no_pickup marker is applied to every spawn of our item.
- Admin/creative duplication (middle-click, /give). Ledger again.

## 7. Shipped item materials (owner answers)

Owner decisions: items may sit in vanilla chests (so Cargo, the Vanilla Grabber and the Vanilla Auto Crafter reach
them), no id ledger, no resource-pack models. Material is the only defence on the event-less paths.
How each material was checked (all read-only):
- R: role as an ingredient in the 2044 vanilla 26.3 recipe JSONs, tags expanded. The Vanilla Auto Crafter only takes
  crafting_shaped/shapeless (VanillaAutoCrafter.java:184-187).
- P: vanilla item properties from javap of net.minecraft.world.item.Items in paper-26.3.jar (food, compostable,
  BLOCK = placeable, durability, cookingFuel/brewingFuel, DEATH_PROTECTION).
- T: every mention in the 5 trees, read by role. Type-only inputs: Infinity Machines.java:220-363, Singularity,
  Stoneworks, growers Machines.java:141+; Supreme Aquarium/Garden/MobCollector first argument; Auto Anvil (damage),
  Auto Brewer potion slot, Auto Disenchanter BOOK slot, Book Binder and Auto Enchanter ENCHANTED_BOOK slot.
- V: vanilla blocks Cargo can fill without an event (furnace, brewing stand, Crafter, which is an
  org.bukkit.block.Container). Their processing does fire cancellable events (FurnaceSmeltEvent/FurnaceBurnEvent,
  BrewEvent, CrafterCraftEvent). The composter is not an InventoryHolder, so only hoppers/players reach it
  (CompostItemEvent, EntityCompostItemEvent and InventoryMoveItemEvent exist; which of them a hopper fires is UNVERIFIED).
Shared mitigation for the residual vanilla uses: Paper 26.3 has ItemStack#unsetData and DataComponentTypes FOOD,
CONSUMABLE and DEATH_PROTECTION, so edibility and totem resurrection can be stripped from our stacks (runtime
effect UNVERIFIED). Compostable is not exposed (no COMPOSTABLE type), so composting needs the events above.

SAFE (no type-only consumer; only event-guardable vanilla uses):
- food/small_treat COOKIE. R none. T only Slimefun recipes matched by isItemSimilar (SlimefunItemSetup.java:258,263,
  1452). Networks/FastMachines are strict. P food, compostable (MEDIUM_HIGH), not placeable.
- food/large_feast PUMPKIN_PIE. R none. T only Slimefun's own item definitions. P food, compostable, not placeable.
- stat_lock/stat_lock_charm NAME_TAG. R none (recipe result only). T GUI icons only (HologramProjector.java:105,
  ElevatorPlate.java:210, AbstractGrid.java:74, TechMutation.java:277) and android loot. Vanilla: renames a mob on
  right-click and is consumed (PlayerInteractEntityEvent).
- auto_feed_upgrade GOLDEN_CARROT. R brewing reagent only (3 night-vision recipes). The Auto Brewer ingredient slot
  rejects items with meta (AutoBrewer.java:80-82). Cargo can drop it into a vanilla brewing stand
  (CargoUtils.java:91-101), and BrewEvent can cancel that. P food. Horse food.
- revive/revive_totem TOTEM_OF_UNDYING. R none. T only Supreme MobCollector OUTPUTS (MobCollector.java:222,291,
  second argument). P DEATH_PROTECTION, so it saves the player on death from hand or off-hand: cancel
  EntityResurrectEvent or unset DEATH_PROTECTION. Stacks to 1.

UNSAFE (Vanilla Auto Crafter ingredient; no event can stop it), with replacements:
- candy/pet_candy SUGAR: cake (shaped), pumpkin_pie and fermented_spider_eye (shapeless), plus 6 brewing recipes.
  -> GLOW_BERRIES. R none. T only Juicer recipe SlimefunItemSetup.java:2677 (isItemSimilar) and BioGenerator.java:77
  fuel (MachineFuel isItemSimilar). P food, compostable, fox food; placeable as cave vines (vanilla knowledge).
  -> or FIREWORK_STAR (non-edible). R only special firework recipes the auto crafter ignores. P none. T only
  Slimefun's own ColoredFireworkStar items. Tint with FireworkEffectMeta.
- candy/super_candy HONEYCOMB: beehive, candle, honeycomb_block (shaped) and 60 waxed_*_from_honeycomb (shapeless).
  -> GOLDEN_APPLE. R none. T Juicer recipe SlimefunItemSetup.java:1310 and a Supreme icon. P food. Tags horse_food,
  piglin_loved (piglins pick it up: EntityPickupItemEvent). Not ENCHANTED_GOLDEN_APPLE, which is an ingredient
  of mojang_banner_pattern (shapeless). -> or FIREWORK_STAR with glint.
- evolution/evolution_stone HEART_OF_THE_SEA: conduit (shaped).
  -> AMETHYST_CLUSTER. R none, no tags, T none. P BLOCK: cancel BlockPlaceEvent and BlockPlacerPlaceEvent.
  -> or FIREWORK_STAR tinted.
- evolution/blessed_evolution_stone NETHER_STAR: beacon (shaped). The NetherStarReactor fuel check is isItemSimilar,
  which is safe on its own.
  -> FIREWORK_STAR + setEnchantmentGlintOverride. -> or CONDUIT: R none; T only FastMachines' own item materials
  (FMItems.java:40,103); P BLOCK, so guard placing.
- reroll/reroll_token PAPER: book (shapeless); map, cartography_table, name_tag (shaped); banner-pattern and
  firework_rocket_simple (shapeless).
  -> GLOBE_BANNER_PATTERN or FLOWER_BANNER_PATTERN (paper-sheet texture). R none. Only tag loom_patterns. T none.
  P not food, not a block. Stacks to 1 (raise it with setMaxStackSize). The loom does not consume patterns
  (vanilla knowledge, UNVERIFIED).
- reroll/blessed_reroll_token BOOK: bookshelf, enchanting_table (shaped), writable_book (shapeless). Also the Auto
  Disenchanter takes any BOOK by type (AutoDisenchanter.java:81) while AutoDisenchantEvent only exposes the other item.
  -> KNOWLEDGE_BOOK. R none. T GUI icons only (AddonCatalog.java:136, SurvivalSlimefunGuide.java:668). Tag
  bookshelf_books (storage only). Vanilla right-click may consume it (UNVERIFIED): cancel PlayerInteractEvent.
  -> or WRITTEN_BOOK: R only crafting_special_bookcloning; T only a Cargo GUI icon (CargoInputNode.java:75).
  Avoid ENCHANTED_BOOK: matched by type in AutoEnchanter.java:80, BookBinder.java:106, AdvancedAnvil.java:116.
- reroll/stat_shard AMETHYST_SHARD: amethyst_block, spyglass, tinted_glass, calibrated_sculk_sensor (shaped).
  -> PRIZE_POTTERY_SHERD (any sherd). R only crafting_decorated_pot (special, ignored by the auto crafter; a vanilla
  table/Crafter fires CraftItemEvent/CrafterCraftEvent). T none. P not food, not a block.
  -> or LARGE_AMETHYST_BUD (R/T none, BLOCK). Avoid ECHO_SHARD (recovery_compass, shaped).
- reroll/stat_eraser FLINT: arrow, fletching_table (shaped), flint_and_steel (shapeless).
  -> WIND_CHARGE. R none, no tags. T only ExplosionsListener.java:64-102 (the explosion entity). Thrown on
  right-click or from a dispenser: PlayerInteractEvent / BlockDispenseEvent.
  -> or PHANTOM_MEMBRANE: R brewing only (BrewEvent). T AutoBrewer.java:53 (meta rejected) and a Supreme output.
- auto_feed_unlocker HAY_BLOCK: wheat (shapeless), target and straw_bed (shaped). Also placeable and compostable.
  -> BEETROOT_SOUP. R none, no tags, T none. P food, stacks to 1, returns a bowl; not compostable, not placeable.
  -> or RABBIT_STEW (tag wolf_food). Avoid MUSHROOM_STEW (ProduceCollector.java:80,131).
Residual: nothing fitting the "food" and "candy" themes is both non-edible and SAFE except FIREWORK_STAR. Edible picks
rely on the FOOD/CONSUMABLE strip or PlayerItemConsumeEvent. Placeable picks rely on BlockPlaceEvent. A vanilla
Crafter filled by Cargo with sherds or written books relies on CrafterCraftEvent.
