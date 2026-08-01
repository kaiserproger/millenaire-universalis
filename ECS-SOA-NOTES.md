# Strategic army ECS / SoA performance notes

## Scope

This work covers only the authoritative strategic stores owned by the armies addon:
armies, units, orders, logistics references, and the persistent MillVillager UUID membership
association. It does not replace NMS entities, MillVillager physics, combat, networking, block
pathfinding, or the Minecraft tick loop.

## Baseline finding: the main SoA already exists

The release-base `PackedArmyEcs` is already a dense primitive SoA with:

- generation-checked opaque army and unit handles;
- packed `BlockPos` values in `long` fields;
- stable dimension-table IDs rather than runtime dimension objects;
- swap-remove packed rows and slot-to-row tables;
- allocation-free reusable cursors/snapshots;
- deterministic, versioned SavedData serialization.

A same-process 1,000-army / 10,000-unit model measured the existing ECS plus linear
`PackedUnitMembership` at about **604,600 bytes (0.577 MiB)** under a conservative compressed-oops
model. A complete 10,000-unit cursor pass allocated 0 bytes and took about **0.017--0.022 ms** in the
observed warmed runs. An empty cursor allocated 0 bytes and completed in roughly 18--23 ns. These
are isolated Java kernels, not server MSPT/RSS claims.

Because the baseline is already far below the 5 MiB target and dense scans are far below 0.5 ms,
segmenting the unit arrays would add pointer chasing, more array headers, and more swap/remove
surface without solving an observed problem. No segmentation rewrite is proposed.

## Measured scaling defect: membership lookup

`PackedUnitMembership` retained the correct primitive row format (`int handle`, UUID as two
`long`s), but all handle/UUID reads and removals scanned every membership row. At 10,000 rows,
last-row UUID hits and misses measured approximately 1.5--2.5 microseconds per operation.
Recruitment validation, entity join/leave projection, release, and order execution can invoke this
seam repeatedly.

The experimental `membershipPrimitiveIndex` mode adds two project-owned primitive open-addressed
indices:

- unit handle -> packed membership row;
- UUID most/least -> packed membership row.

The authoritative packed rows remain unchanged. Cursor order, swap-remove behavior, UUID encoding,
unit handles, and the NBT schema are identical in linear and indexed modes. Cluster deletion shifts
only primitive index slots; when a packed row is swap-moved, both indices are updated to its new row.
The store is hard-bounded to the ECS 20-bit unit-handle space, and indexed NBT restore reserves its
known unit count once to avoid repeated rehashing. No `UUID`, map entry, iterator, boxed key, or
external entity reference is retained.

## A/B results

Representative warmed results from `runArmyEcsSoaBenchmark`:

| 1k armies / 10k units | linear fallback | primitive index |
|---|---:|---:|
| modeled total retained heap | 0.577 MiB | 1.452 MiB |
| UUID last-row hit | 1,695--2,537 ns | 16--25 ns |
| UUID miss | 1,527--1,923 ns | 10--11 ns |
| lookup allocation | 0 B/op | 0 B/op |

The index trades about 0.88 MiB of bounded primitive arrays for roughly two orders of magnitude
lower worst-case lookup latency, while remaining below the 5 MiB attributable-ECS target. The heap
numbers are a conservative object/array model, not measured process RSS or heap high-water.

`PackedUnitMembershipIndexSelfTest` first fills 512 rows, then executes 50,000 deterministic mixed
operations against both implementations: insert, rebind, duplicate rejection, handle removal, UUID
removal, lookup, read, cursor traversal, swap-remove, and open-address cluster shifting. Row order and
all public results must match after every verification interval. Persistence and recruitment tests
also run in separate JVMs with the experimental flag enabled.

## Activation and fallback

The release default remains:

```properties
membershipPrimitiveIndex=false
```

`PackedUnitMembership()` therefore uses the original linear behavior. The indexed implementation is
selected only by `-Dmillenairearmies.membershipPrimitiveIndex=true` or the equivalent config value
after restart. This preserves a direct fallback while server-level evidence is absent.

Production activation still requires an equal-world A/B or BA/ABBA test with identical JVM,
affinity, warmup, world, commands, and online-player conditions. Required evidence includes
recruit/release/order workloads, save/stop/restart parity, p50/p95/p99/max tick distributions,
allocation rate, heap/RSS/native high-water, and zero-feature overhead. The isolated kernels do not
establish those results.

## Native boundary decision

FFM/off-heap and Rust/JNI are intentionally rejected for this table. The complete indexed store is
about 1.45 MiB at the target scale, lookup is tens of nanoseconds, and mutations need immediate
server-thread consistency with Java SavedData. Packing, native crossing, lifetime management, and a
Java fallback would dominate this operation and create additional correctness risks. Native work
remains disabled by default and is not part of this candidate.

## Verification

```text
./gradlew :armies:runArmyEcsSoaBenchmark \
  :armies:runPackedUnitMembershipIndexSelfTest \
  :armies:runArmyPersistenceSelfTest \
  :armies:runArmyPersistenceIndexedSelfTest \
  :armies:runRecruitmentGameplaySelfTest \
  :armies:runRecruitmentGameplayIndexedSelfTest \
  :armies:build --no-configuration-cache
```

No Minecraft fixture, stress/load/soak run, live server, production world, production port, or
secret was touched while preparing this candidate.
