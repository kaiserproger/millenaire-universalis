# Millenaire Universalis 1.2.0

## Highlights

- Player-founded and adopted Millenaire settlements with canonical Realm ownership.
- Monotonic progression from `HAMLET` to `CITY_STATE` and protected territory growth from 96 to 512 blocks.
- Expanded same-culture construction catalog, manual placement, bounded automatic development, development profiles and queue limits.
- Management of captured settlements through `catalog-in`, `queue-in` and `build-in`.
- City-state annexation with frontier, war, physical occupation, player-ownership and major-capital safeguards.
- Migration of existing player-founded 1.1 Realm capitals into the new settlement persistence stores.
- Per-settlement Millenaire growth-radius integration without global culture mutation or forced chunk loading.

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.247
- Millenaire 9.0.0-beta.2
- Client and dedicated server
- Existing legacy namespace, commands, config filename and SavedData identifiers remain compatible.

## Important fixes

- Controlled queues are cleared through Millenaire's mutable queue API instead of the unmodifiable public view.
- Queued and pending project corner signs are removed during cancellation.
- Foundation checks search a deterministic bounded set of loaded nearby sites and provide actionable terrain guidance.
- Missing-plan and missing-settlement feedback now directs players to the relevant discovery commands.

## Verification

- Clean Gradle `check build` with all Army, Realm, Simulation and player-settlement self-tests.
- Dedicated-server bootstrap against the real Millenaire 9.0.0-beta.2 JAR.
- Full 44-mod Sarvar peaceful-mode QA using non-OP Carpet players.
- Physical foundation, project growth, automatic development, clean restart persistence and foreign access denial.
- Production city-state capture command, physical/canonical ownership transfer, capture counter and second clean restart persistence.
- No runtime crash, watchdog, hard Mixin failure, duplicate UUID or POI corruption during the final capture run.
