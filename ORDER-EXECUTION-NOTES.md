# Millenaire army order execution (experimental opt-in)

`orderExecutionEnabled` defaults to `false`. With the production default the lifecycle never
constructs `ArmyOrderExecutionBridge`, never installs its order listener, never creates a task, and
never calls a Millenaire AI/navigation API. Commands, persistence, networking and UI remain
state-only. The server operator must explicitly set `orderExecutionEnabled=true` to exercise the
experimental entity-side bridge described below.

The execution bridge uses Millenaire 9.0.0-beta.2 public APIs only. `MOVE`, `RALLY`, and
`LOGISTICS` install a retained `VillagerTask` through `GoalScheduler.forceTask`; the task delegates
the complete route to `VillagerNavDriver`. It owns that route until arrival, Millenaire reports it
abandoned, a concrete combat signal appears, or a newer committed army order cancels it.

There is deliberately no periodic route reset. In beta.2 the navigation driver owns the active
destination, `WaypointNavigator`, local/long-distance stuck counters, and teleport recovery.
Stopping and recreating the task on a timer would discard that state and repeat long-route work.

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

Phase2 persisted orders contain a packed block position but no dimension id. Consequently the
experimental bridge cannot prove that an issuer selected the target in the unit's dimension. It
interprets the coordinates in each loaded unit's current dimension and rejects targets outside
that level's build height or world border before touching navigation. Cross-dimension execution is
not production-safe and is another reason the entity bridge is disabled by default.

## Cancellation

A new order never calls `GoalScheduler.forceStop`. The currently retained army task is marked
finished/cancelled, and the successor revision is published as pending first. On the next normal
entity tick Millenaire completes and releases the old task through its ordinary `COMPLETED` path,
then the bounded bridge applies the successor. This avoids per-unit abandonment events and INFO
logging, while exact army/revision guards prevent the cancelled task from retrying stale state.
