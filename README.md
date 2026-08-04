# Millenaire Armies

NeoForge 1.21.1 addon that turns real Millenaire villagers and settlements into a persistent military layer. Armies owns physical movement, individual combat, garrisons, sieges, occupation and player command; political state and historical settlement dynamics are supplied by two independently versioned pure-Java kernels.

## Standalone repositories and dependency boundary

- [`millenaire-armies`](https://github.com/kaiserproger/millenaire-armies) — Minecraft/NeoForge/Millenaire integration and physical gameplay.
- [`millenaire-realm`](https://github.com/kaiserproger/millenaire-realm) — `ru.kaiserroman.millenaire:millenaire-realm:1.0.0`; mixed player/NPC Realms, government, diplomacy, dependencies, war outcomes and historical state lifecycle.
- [`millenaire-simulation`](https://github.com/kaiserproger/millenaire-simulation) — `ru.kaiserroman.millenaire:millenaire-simulation:1.0.0`; inactive-settlement history, population, productivity, regional shocks, commodities and dynamic prices.

Realm and Simulation do not depend on Minecraft, NeoForge, Millenaire, Armies or each other. Armies resolves their pinned binary coordinates from `local-maven/` and embeds the kernel classes into the final mod JAR, so a fresh Armies checkout does not require sibling source directories.

## Build

```bash
python3 scripts/materialize-dependencies.py
./gradlew --offline clean compileJava check build
```

`materialize-dependencies.py` verifies the exact SHA-256 of the local Millenaire beta.2 API/runtime JAR; it is not committed. The release artifact is `build/libs/millenaire_armies-<version>.jar`.

To refresh the bundled kernels from local Realm/Simulation checkouts:

```bash
../millenaire-realm/gradlew -p ../millenaire-realm clean check build publish \
  -PintegrationRepositoryUrl="$PWD/local-maven"
../millenaire-simulation/gradlew -p ../millenaire-simulation clean check build publish \
  -PintegrationRepositoryUrl="$PWD/local-maven"
./gradlew --offline clean check build
```

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
