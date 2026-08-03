# Millenaire Armies

Standalone NeoForge 1.21.1 project extracted from the former Bannerok monorepository with component history preserved.

## Build

```bash
python3 scripts/materialize-dependencies.py
./gradlew clean check build
```

Binary build dependencies are materialized by exact SHA-256 and are never committed. The release artifact is `build/libs/millenaire_armies-<version>.jar`.
