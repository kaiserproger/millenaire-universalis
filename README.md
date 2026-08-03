# Millenaire Armies

Standalone NeoForge 1.21.1 project extracted from the former Bannerok monorepository with component history preserved.

## Build

```bash
python3 scripts/materialize-dependencies.py
./gradlew clean check build
```

Binary build dependencies are materialized by exact SHA-256 and are never committed. The release artifact is `build/libs/millenaire_armies-<version>.jar`.

## In-game command interface

- `K` raises the captain's command banner without pausing the world.
- `Alt+1` … `Alt+9` select a named warband.
- `Alt+H/M/R/A/L` issue hold, march, rally, attack and supply orders.
- `Alt+F` changes formation.
- `J` opens the compact war council for recruitment, realm administration and detailed state; `K` lowers the banner.

See [MILLENAIRE-WAR-COUNCIL.md](MILLENAIRE-WAR-COUNCIL.md) for the addon boundaries and the
Ancient Warfare ideas translated into Millenaire mechanics.
