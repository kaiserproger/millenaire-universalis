# NPC settlement economy

This subsystem makes Millenaire settlements the economic drivers without making them fake
players. Players still create/control armies and decide diplomacy and orders; settlement code does
not declare wars, pick targets or issue commands.

## Stock contract

The four bounded army commodities are bread, iron ingots, leather and arrows. A revision-driven
bridge scans the real Millenaire `BuildingInventory` sources two villages per tick by default. It
never force-loads a chunk. All four items are captured together after one cache invalidation per
building/revision; an unloaded source keeps its last sound observation.

After the initial capture, `PackedSettlementEconomyState` is the strategic source of truth. New
physical observations are applied as deltas, so a strategic debit is not undone simply because the
visual chest stack has not changed. Coarse production, consumption, recruitment kits, army supply
and inter-settlement trade mutate this persisted ledger.

This release deliberately does **not** claim to move a physical courier entity or atomically mutate
block entities with Overworld `SavedData`; those saves have no common transaction boundary. If
Millenaire physically consumes beyond the already-debited logical balance, stock clamps to zero and
the persisted `physical_reconciliation_shortfall` metric records the conflict. Shutdown logs expose
that value under `BANNEROK_SETTLEMENT_ECONOMY_METRICS`.

## Scheduling and transactions

- There is no player-count gate. Due settlements advance by elapsed-cycle arithmetic even while
  unloaded; no entity or chunk is required.
- Production/consumption, route searches, inventory capture and shipment completions have separate
  fixed per-tick budgets.
- Trade is same-faction and same-dimension only, preserving player-owned political decisions.
- A shipment debits its origin before entering the persisted `IN_TRANSIT` WAL state. Delivery and
  rollback are one-way idempotent transitions. Terminal WAL rows are safely recycled with a fresh
  monotonic identity at the configured cap.
- Local reserves are excluded from `StrategicSupplyPublisher`; army dispatch goes through the same
  commit boundary and cannot reserve settlement emergency stock.

Recruitment atomically consumes one kit (8 bread, 1 iron ingot, 2 leather, 8 arrows) above local
reserves. A shortage returns `SUPPLY_SHORTAGE` and the existing command UI reports it. The army
screen's supply percentage is no longer a hard-coded 100%; it is derived from faction settlement
surplus relative to protected reserves.

Run `./gradlew :armies:runSettlementEconomySelfTest` for conservation/no-dupe, restart while in
transit, unloaded catch-up/delivery, equilibrium, WAL recycling, shortage gates, logistics debit and
the 100-settlement memory/allocation budget.
