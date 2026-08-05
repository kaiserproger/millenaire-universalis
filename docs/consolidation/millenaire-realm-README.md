# Millenaire Realm

Deterministic state, government, diplomacy, dependency, war and historical lifecycle kernel for Millenaire-compatible worlds.

This repository is a pure Java 21 kernel. It intentionally has no dependency on
Minecraft, NeoForge, Millenaire, Millenaire Armies, or the other simulation kernel.

## Build and verify

```bash
./gradlew --offline clean check build
```

The `check` lifecycle runs `runRealmCoreSelfTest`. The produced Maven coordinate is
`ru.kaiserroman.millenaire:millenaire-realm:1.0.0`.

For local integration with Millenaire Armies:

```bash
./gradlew publishMavenJavaPublicationToLocalIntegrationRepository
```

The artifact is written below `build/integration-repository` and can be copied into
the Armies repository's bounded local integration repository.

## Scope

Deterministic state, government, diplomacy, dependency, war and historical lifecycle kernel for Millenaire-compatible worlds.

License: MIT.
