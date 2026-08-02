# Millenaire army order execution

`orderExecutionEnabled` defaults to `true` and is asserted by the production-candidate assembler.
Setting it to `false` remains an emergency fallback that keeps commands, persistence, networking
and UI state-only without constructing `ArmyOrderExecutionBridge`.

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

MOVE/RALLY/LOGISTICS yield on public combat signals (`getAttackTarget()` and
`Village.isUnderAttack()`). ATTACK is a separate retained task: it selects only real loaded
MillVillager targets, uses Millenaire navigation, `setAttackTarget` and `performAttack`, and records
damage/death only from NeoForge events after Minecraft applies them. It never calculates a
parallel hit-point or casualty simulation.

## Target dimension and bounds

Army orders persist a stable target-dimension dictionary id. ATTACK also persists the exact target
village UUID after server-authoritative resolution, while every army persists its home-village
UUID. Execution rejects a different entity dimension, coordinates outside build height/world
border, a non-village ATTACK target, the attacker's own settlement and non-operator conquest before
the player has founded a realm.

## Physical capture

Village ownership changes only when a real attacker reaches the target centre and no hostile
loaded Millenaire combatant remains. The target is marked `underAttack` so native defenders can
react. Death cleanup removes only the dead entity's exact army membership. A successful capture
updates Millenaire's owner, clears `underAttack`, dirties VillageSavedData and increments the
player-realm conquest ledger. Without a founded realm, physical capture is fail-closed.

## Cancellation

A new order never calls `GoalScheduler.forceStop`. The currently retained army task is marked
finished/cancelled, and the successor revision is published as pending first. On the next normal
entity tick Millenaire completes and releases the old task through its ordinary `COMPLETED` path,
then the bounded bridge applies the successor. This avoids per-unit abandonment events and INFO
logging, while exact army/revision guards prevent the cancelled task from retrying stale state.
