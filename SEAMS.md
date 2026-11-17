# ModernSlimefunPet test seams

Seam ids come from the mc-plugin-kit seam catalog. No MockBukkit. Glue stays thin and delegates to S1 logic; the rest is a headless boot on `_local-test-server-26.3` plus the in-game checks in LIVE-CHECKS.md.

| Area | Seam | Driven by | Status |
|---|---|---|---|
| Whole codebase | S9 Architecture | `ArchitectureTest` (`MinecraftRules.all()`; PetDisplay and the pets.gui menus may hold a Player, reasons in the test) | M0 |
| Boot (`onEnable`, MDLib, PacketEvents, registries) | S11 | headless boot on `_local-test-server-26.3`: 15 stats, 14 items, 3 pets, model load | M0-M5 |
| Storage: pet and profile round trip, upsert, delete in the same batch, import twice | S1 | `PetStorageTest` (SQLite) | M1 |
| SQLite to MySQL startup import (`SqliteImporter`) | S1 | `PetStorageTest`, SQLite to SQLite only | M1 |
| MySQL upsert SQL text (`DatabaseType.upsert`) | S1 | `PetStorageTest`, string only; never run on MySQL | M1 |
| Save queue ordering (`DatabaseQueueHandler`) | none | no test (static, scheduler-bound); covered by the restart round trip below | M1 |
| Restart round trip: load, offline food decay, EXP, save on stop | S11 | seeded row on the test server, checked with sqlite3 | M3 |
| Pet display packet code | S1 | `BlockOverridesTest`, `PetSkinsTest` (ported) | M2 |
| Pagination (last page bug) | S1 | `PetPaginationTest` | M2 |
| Food burn, Auto-Feed, EXP fraction | S1 | `PetFoodBurnTest`, `AutoFeedTest` (ported, plus the fraction case) | M3 |
| Skin field | S1 | `PetPlayerSkinTest` (ported) | M2 |
| Effect conditions, attribute percent, effect replace rule, heal timing | S1 | `PetEffectTaskTest` | M5 |
| Shipped pets.yml and menus | S6 | `ShippedConfigTest` | M2-M5 |
| Every `messages.*` key sent from code exists in pets.yml | S6 | `MessageKeysTest` | M2-M5 |
| AFK idle rule, daily rollover and cap, payout amount, time left, mood | S1 | `AfkXpTaskTest` | M6 |
| Menus, clicks, equip, feeding, evolution, reroll, attributes on a real player | in-game check | LIVE-CHECKS.md, not run | M2-M5 |
