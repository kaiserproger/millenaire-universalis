# Millenaire Simulation

Deterministic inactive-settlement, productivity, population, regional shock, commodity and dynamic-market simulation kernel.

This repository is a pure Java 21 kernel. It intentionally has no dependency on
Minecraft, NeoForge, Millenaire, Millenaire Armies, or the other simulation kernel.

## Build and verify

```bash
./gradlew --offline clean check build
```

The `check` lifecycle runs `runWorldSimulationSelfTest`. The produced Maven coordinate is
`ru.kaiserroman.millenaire:millenaire-simulation:1.0.0`.

For local integration with Millenaire Armies:

```bash
./gradlew publishMavenJavaPublicationToLocalIntegrationRepository
```

The artifact is written below `build/integration-repository` and can be copied into
the Armies repository's bounded local integration repository.

## Scope

Deterministic inactive-settlement, productivity, population, regional shock, commodity and dynamic-market simulation kernel.

License: MIT.
