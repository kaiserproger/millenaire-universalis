# Millenaire army order execution

`orderExecutionEnabled` defaults to `true` after the revision/rebind/dimension/auth/persistence
self-tests and the 1,000-unit allocation smoke passed. Setting it to `false` remains a fail-closed
state-only mode: the lifecycle constructs no `ArmyOrderExecutionBridge`, installs no execution
listener, creates no execution task, and calls no Millenaire AI/navigation API.

The execution bridge uses Millenaire 9.0.0-beta.2 public APIs only. `MOVE`, `RALLY`, and
`LOGISTICS` install a retained `VillagerTask` through `GoalScheduler.forceTask`; the task delegates
the complete route to `VillagerNavDriver`. It owns that route until arrival, Millenaire reports it
abandoned, a concrete combat signal appears, or a newer committed army order cancels it.

There is deliberately no periodic route reset. In beta.2 the navigation driver owns the active
destination, `WaypointNavigator`, local/long-distance stuck counters, and teleport recovery.
Stopping and recreating the task on a timer would discard that state and repeat long-route work.
If another subsystem clears or replaces the exact destination, the retained task may re-delegate
the committed endpoint at most once per 20 ticks; an unchanged route is allocation-free.

## Beta.2 scheduler constraint

Bytecode inspection of `GoalScheduler.forceTask(VillagerTask, GoalContext)` shows that it stores
the passed task and sets `currentGoal` to `null`. `GoalScheduler.tickInternal` evaluates its generic
urgent-goal preemption loop only when both `currentTask` and `currentGoal` are non-null. Therefore a
forced task cannot honestly claim full generic combat preemption.

`Millenaire.getGoalRegistry()` and `GoalRegistry.register(VillagerGoal)` are public, but registry
membership alone does not put a goal into any villager scheduler. Bytecode inspection of
`MillVillager.initGoals` shows that it constructs a private `GoalScheduler` from
`GoalRegistry.resolve(villagerType.goals())`, plus a fixed set of Millenaire-owned conditional
goals. There is no public `GoalScheduler` API to add an owning goal or force a goal/task pair.
Making an addon goal visible would require mutating every loaded `VillagerType.goals()` list and
destructively reinitialising every villager scheduler. That relies on an incidental mutable
`ArrayList`, drops current task ownership, and is not a production-safe public extension point.

The current bridge yields only on public, allocation-free signals it can observe without
implementing target-finding: `MillVillager.getAttackTarget() != null` and
`Village.isUnderAttack()`. A newly eligible urgent goal without either signal is delayed until the
route finishes or Millenaire abandons it. This is a known limitation of the beta.2 public API.

## Target dimension and bounds

Orders persist a stable dimension-dictionary id together with the packed block position. The
bridge requires an exact match with the loaded unit's `ServerLevel`, then validates build height
and world border before touching navigation. Schema-1 rows migrate to `UNKNOWN_DIMENSION` and stay
blocked until a controller reissues the target; cross-dimension and invalid targets fail closed.

## Cancellation

A new order never calls `GoalScheduler.forceStop`. The currently retained army task is marked
finished/cancelled, and the successor revision is published as pending first. On the next normal
entity tick Millenaire completes and releases the old task through its ordinary `COMPLETED` path,
then the bounded bridge applies the successor. This avoids per-unit abandonment events and INFO
logging, while exact army/revision guards prevent the cancelled task from retrying stale state.
