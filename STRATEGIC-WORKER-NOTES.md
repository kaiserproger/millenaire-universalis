# Strategic worker and dormant-memory checkpoint

## Verdict

`bannerok.experimental.workerCount=0|1|2` is now consumed by the armies module,
but the active worker count is deliberately fixed at zero. Runs requesting one
or two workers are reported as `NOT_APPLICABLE`; they are not valid performance
comparisons and must not be advertised as speed-ups.

The current production call graph has no strategic phase that is simultaneously
active, expensive, and a sufficiently large pure primitive kernel:

- logistics has no production request/dispatch/delivery ingress and ticks an
  empty table;
- the inventory publisher is disabled by default, and its expensive work reads
  Millenaire villages/buildings/inventories on the server thread;
- diplomacy has no production scheduled-command ingress;
- trade and coarse army planning do not exist yet;
- faction projection is real, but the measured world has only 12 villages / 6
  factions and most of its cost is Millenaire API capture, not pure arithmetic.

Creating idle executors or synthetic production jobs would make the `w1/w2`
matrix look real without measuring useful work, so no executor, queue, double
buffer, FFM arena, JNI library, or Rust code is installed.

## Harness contract

At startup the module logs:

```text
[BANNEROK_ARMIES_WORKER_STATUS] requested=N active=0 status=BASELINE|NOT_APPLICABLE reason=no_profiled_pure_runtime_kernel
```

The harness must require `active == requested` before accepting a worker result.
Thus only `w0` is currently a valid baseline. `w1/w2` are `NOT_APPLICABLE` or
`INCOMPLETE`, even if their MSPT happens to be lower. The exact system property
is `bannerok.experimental.workerCount`; invalid values fail closed to zero.

`runStrategicWorkerGateSelfTest` starts with a requested count of two and proves
that active count remains zero and no `millarmies-strategy-*` thread exists.

## Implemented memory patch

The useful measured issue was dormant logistics storage. Previously startup
reserved the maximum 32,768 persisted requests plus three maximum-sized runtime
columns, a fixed supply hash table and event ring even though there is currently
no production logistics ingress. The combined primitive storage was estimated
at roughly 2.6 MiB.

Now:

- zero-ingress startup retains zero request columns, zero runtime request
  columns, zero event arrays and no supply table;
- the first request grows persisted/runtime request columns together to a
  64-row tier, then by bounded 1.5x tiers up to the configured hard maximum;
- the event ring is allocated only on the first event;
- the supply table is allocated only on the first absolute-supply event;
- loaded persisted rows allocate only the tier needed to hold those rows;
- request/event/supply limits and NBT schema remain unchanged.

One isolated ThreadMXBean run measured about 8,016 B for constructing/starting
the empty subsystem, 368 B total across 100,000 zero-ingress ticks (late JVM
bookkeeping, not per-tick growth), and a 64-row first tier. Logistics replay,
publisher behavior, cancellation/restart behavior, and NBT round-trip tests all
pass.

## Profiling seam

`StrategicPhaseTelemetry` keeps fixed primitive counters for supply publishing,
logistics, diplomacy, order execution, Millenaire capture, faction projection,
and entity reconciliation. Recording one million samples allocated 192 B total
in the isolated test and no amount proportional to call count.

The stop log exposes cumulative calls/total/max nanoseconds for the important
capture/projection/logistics phases. A future worker implementation is allowed
only after these counters and JFR show a substantial pure-compute portion. It
must then add immutable primitive double buffers, a queue capped at one or two,
revision/handle validation, stale-result drops, deterministic synchronous
parity, saturation metrics, and clean shutdown before `active` can become
non-zero.
