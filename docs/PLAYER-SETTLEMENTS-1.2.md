# Player settlements in 1.2.0

## Goal

Millenaire Universalis adds a canonical player-settlement layer above the public Millenaire 9.0.0 beta.2 API. A player can found or adopt one capital, develop it from a hamlet into a city-state, select a development profile, queue a broader set of buildings, expand protected territory over time, and annex physical settlements after reaching city-state status.

The implementation does not copy decompiled 1.12 code. The supplied `millenaire-8.1.2.jar` was inspected only to recover gameplay contracts that remain useful:

- `VillageType.playerControlled` distinguished player settlements;
- `Building.controlledBy` and `controlledByName` persisted the owner;
- projects were grouped as `PLAYER`, `CORE`, `SECONDARY`, `EXTRA`, custom and wall projects;
- the controlled-project UI could create projects, permit/forbid upgrades and cancel buildings;
- controlled military exposed relations, raid planning and active-raid state;
- the village type owned a fixed radius and separate player/core/secondary/extra building lists.

Universalis preserves those concepts while making Realm and Simulation the authoritative strategic state.

## Persistence and authority

Two bounded SavedData stores are used:

- `millenaire_player_settlements` owns the capital UUID, canonical Realm ID, dimension, foundation time, development tier and monotonic territory radius;
- `millenaire_player_settlement_customization` owns the display name, village type, development profile, automatic-development flag and controlled-project queue limit.

Millenaire's physical `Village` remains authoritative for buildings, residents, project placement, pathing, inventories and construction. `RealmSavedData` remains authoritative for membership, war and conquest. `SimulationSavedData` supplies strategic population and receives ownership changes.

## Foundation

`/millarmies settlement types` lists loaded non-marvel player-controlled village types.

`/millarmies settlement create <village_type> <name>` searches at most 32 deterministic candidates within 96 already-loaded blocks, from the player's position outward, and validates each complete site with Millenaire. It never force-loads or generates chunks. After finding a valid site it preflights capacity for the capital and every generated hamlet, creates the physical village complex, founds a canonical Realm, attaches child settlements as governor-led regions, then registers both player-settlement stores. If no candidate is valid, the command tells the player to move to broad, level, dry terrain or select another village type. If canonical foundation is unexpectedly rejected after physical registration, every newly created village registration is removed and the index is reconciled.

`/millarmies settlement adopt <capital_uuid> <name>` adopts an already player-controlled root village after checking owner, canonical and compatibility capacity. A child hamlet cannot become a capital; all controlled descendants of the selected root are discovered first and adopted into the same Realm as governor-led regions in one preflighted transaction.

On the first 1.2 startup, existing player-founded canonical Realms from 1.1 are reconciled into the two new settlement stores. Migration is two-phase: owner/capital conflicts are checked in both stores before either missing row is written, and physical ownership must still match the canonical controller.

## Development and territory

Development is recalculated every 200 server ticks from:

- physical building count;
- Simulation population, with physical villager records as fallback;
- settlement age;
- captured-settlement count.

Tiers are monotonic:

1. `HAMLET`
2. `VILLAGE`
3. `TOWN`
4. `CITY_STATE`

Territory begins at at least 96 blocks and can grow to 512 blocks. It never shrinks after temporary population or economic decline. The same radius is injected into a synthetic Millenaire layout slot, while a narrow beta.2 compatibility bridge runs Millenaire's own terrain, collision, reachability, elevation, clear-margin and tag checks with the developed per-village radius.

Expanded placement never force-loads chunks. Manual validation, automatic search and deferred queue launch are capped to the largest continuous square of chunks that is already loaded around the settlement. A distant queued project remains in the queue until its area is loaded again.

Expanded territory is also included in settlement block protection. The owner and members of the canonical Realm remain authorized; foreign players receive the existing peacetime/siege protection policy.

## Expanded construction catalog

`/millarmies settlement catalog [limit]` lists all same-culture plans available at the current tier. Existing village-type plans are kept, while additional same-culture plans are unlocked by policy. Town halls, sub-buildings, wall segments and gifts cannot be injected as arbitrary extra projects, and the village type's `neverBuildings` exclusions remain mandatory in manual, automatic and catalog paths.

Profiles influence automatic selection:

- `balanced`
- `food`
- `trade`
- `industry`
- `military`
- `civic`

Commands:

- `/millarmies settlement profile <profile>`
- `/millarmies settlement auto <true|false>`
- `/millarmies settlement queue-limit <1..5>`
- `/millarmies settlement queue <plan> [variant]`
- `/millarmies settlement build <plan> <position> [rotation] [variant]`
- `/millarmies settlement clear`

Automatic development is bounded to four settlement rows per 200-tick interval, scans at most 128 same-culture candidates and attempts physical placement for at most the 12 highest-scoring candidates. Built and already queued instances both count against `maxCount`.

## City-state and annexation

A city-state can annex physical settlements inside its strategic frontier. The frontier is four times the developed territory radius, bounded to 512..2048 blocks.

`/millarmies settlement members [limit]` lists all physically controlled settlements in the player's Realm.

Captured settlements can be managed explicitly, including settlements of another culture:

- `/millarmies settlement catalog-in <settlement_uuid> [limit]`
- `/millarmies settlement queue-in <settlement_uuid> <plan> [variant]`
- `/millarmies settlement build-in <settlement_uuid> <plan> <position> [rotation] [variant]`

`/millarmies settlement capture <target_uuid>` applies fail-closed rules:

- the actor must own a `CITY_STATE` profile;
- the target must be near the player and inside the frontier;
- another player's settlement cannot be captured by this operation;
- a Realm-owned target requires canonical `WAR` and an active physical attack;
- a multi-settlement or player Realm capital requires a future siege/peace-treaty resolution instead of one-click transfer;
- an isolated NPC capital may be dissolved and absorbed;
- physical ownership, canonical Realm membership, Simulation ownership and capture counters are updated together.

## Verification

The `check` lifecycle includes dedicated self-tests for:

- tier progression, territory growth and construction unlocks;
- profile persistence, queue bounds and cyclic automatic-development iteration;
- player-settlement identity, duplicate Realm rejection, monotonic territory/tier and malformed SavedData rejection;
- the bounded, unique near-to-far foundation search envelope and queue clearing through Millenaire's mutable API.

A full 44-mod dedicated-server QA in peaceful mode additionally covers non-OP Carpet foundation, physical growth, automatic development, clean restart persistence, city-state capture through the production command, captured-settlement Realm/owner/parent transfer, and a second clean restart verification.
