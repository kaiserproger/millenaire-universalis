# Millenaire Universalis

Unified NeoForge 1.21.1 addon for Millenaire world simulation, political Realms, diplomacy, armies, logistics, sieges and player command. Realm, Simulation and the Minecraft integration now ship as one client/server mod and one release artifact.

## Unified repository and compatibility boundary

The project is built as a single Gradle source set. Realm, Simulation and the NeoForge integration are stored as ordinary Java sources in this repository and compile together into one artifact. The final JAR contains ordinary classes and no nested Realm/Simulation JARs. Historical README and license files from the former kernel repositories are retained under `docs/consolidation/`.

The public addon identity is `millenaire_universalis`. Existing SavedData filenames, `/millarmies` commands, `millenaire-armies.properties`, translation keys and the `millenaire_armies` content namespace remain compatibility contracts for existing worlds and operator tooling.

## Build

```bash
python3 scripts/materialize-dependencies.py
./gradlew --offline clean check build
```

`materialize-dependencies.py` verifies the exact SHA-256 of the local Millenaire beta.2 API/runtime JAR; it is not committed. The release artifact is `build/libs/millenaire_universalis-<version>.jar`.

## Gameplay loop

- A player reaches exactly 4096 Millenaire reputation and hires a resident through the normal one-day denier contract. Direct settlement levy recruitment remains distinct.
- Every physical fighter remains an individual Millenaire entity with its own target, pathing, survival and fallback state. Orders include hold, move, rally, follow, attack, garrison, logistics, guard and physical siege.
- A successful physical siege requires attackers and defenders to fight around the real settlement objective before Realm capture/subjugation effects are accepted.
- Foreign settlement blocks are server-authoritatively protected. Ordinary foreign blocks break at a severe configurable slowdown; town halls and storage infrastructure are denied. A fresh active physical siege opens only a narrow, still-slow perimeter breach rather than making the settlement freely mineable.
- Player, mixed and NPC Realms can form, evolve, tax, receive tribute, wage high-level wars, subordinate other Realms and lose provinces through secession or rebellion.
- Raised player armies have recurring Millenaire-denier upkeep. Regular hired soldiers cost materially more than local levies; noble leaders cost more again. Missed payment causes warning, forced demobilization and then persisted desertion.
- Real loaded Millenaire village chiefs can receive a feudal projection: increased health, damage, attack speed and movement, superior armour and weapon, NOBLE upkeep class, and a safe mount only when a nearby tamed, saddled and unused horse already exists.
- A suzerain can call a feudal settlement's own levy. Loyalty is deterministic from legitimacy, centralization, noble power, land concentration, militarization and settlement population. A lord may answer, refuse, or rebel; rebellion transfers local control to the feudal controller, raises a defensive levy where resources allow, and enters the physical hostility path.
- Settlement population, productivity, market prices and lifecycle continue advancing in historical time while chunks are inactive. The simulation never force-loads chunks to produce resources.

## Player controls

- `K` raises or lowers the command banner without pausing the world.
- `Alt+1` … `Alt+9` select a named warband.
- `Alt+H/M/R/A/L` issue hold, move, rally, attack and logistics orders.
- `Alt+G` places or moves a settlement muster banner.
- `Alt+F` changes formation.
- `J` opens the military ledger and Realm council.

## Server commands

Diagnostics and administration:

```text
/millarmies realm status|list|show|relations|dependencies
/millarmies realm war <source> <target> <goal>
/millarmies realm truce <source> <target>
/millarmies realm subject <subject> <overlord> <autonomy> <tribute> <levy>
/millarmies realm release <subject>
/millarmies simulation status|events|settlement
/millarmies simulation shock <settlement> <type> <magnitude> <cycles>
```

Gameplay feudal call, validated against the invoking player's suzerainty:

```text
/millarmies realm levy call <village-uuid> <units> <order> <target-pos>
```

The resulting army remains controlled and financed by the local feudal lord. The command reports loyalty, separatism, the auditable reason mask and whether the lord answered, refused or rebelled.

## Verification

The Gradle `check` lifecycle currently runs deterministic coverage for persistence/migration, recruitment, per-unit roles, upkeep, block protection, physical siege state, target policy, garrisons, Realm formation/evolution/secession/dissolution, diplomacy/capture/subjugation/liberation, Simulation history, dynamic trade and regional shocks.

A real dedicated-server cycle is available at:

```bash
BANNEROK_SERVER_REPO=/path/to/bannerok-server \
BANNEROK_QA_RUNTIME_ROOT=/path/to/stopped/neoforge-runtime \
qa/full-cycle/bin/run.sh release-candidate
```

It boots with the real Millenaire JAR, creates and funds a settlement, uses controlled non-OP players, recruits physical units, exercises orders/combat/chunk unload, saves, restarts the same world and checks persistence. Machine-readable release evidence and a readiness matrix live under `qa/release-evidence/`.

See [ARCHITECTURE-REALM-SIMULATION.md](ARCHITECTURE-REALM-SIMULATION.md), [MILLENAIRE-WAR-COUNCIL.md](MILLENAIRE-WAR-COUNCIL.md) and [qa/full-cycle/README-RU.md](qa/full-cycle/README-RU.md) for detailed boundaries and safeguards.

License: MIT for this repository's original code. Millenaire and other dependencies retain their own licenses and distribution terms.
