# Millenaire War Council direction

Millenaire Armies is an addon to Millenaire, not a generic RTS layer. Military systems must begin
with a culture, a settlement, residents, reputation, local stores and a named captain. UI language
uses war council, banner, muster, settlement and supplies rather than abstract unit-production
terminology.

## Interface contract

- `K` raises or lowers the captain's command banner without pausing the world.
- The banner HUD shows at most nine named warbands, their ready strength and supplies.
- `Alt+1` … `Alt+9` select a warband.
- `Alt+H/M/R/A/L` issue hold, march, rally, attack and supply orders.
- `Alt+G` places or moves the selected warband's muster banner inside a controlled settlement.
- `Alt+F` cycles the current formation.
- `J` opens the compact war-council ledger for recruitment, realm administration and detailed
  inspection; `K` lowers the command banner.
- Targeted orders still use the existing authoritative in-world block selection. The HUD never
  fabricates coordinates or bypasses server authorization.

The ledger is deliberately bounded to a compact 640×370 maximum, uses a narrow book-like section
rail, and avoids dashboard cards. Field command remains visible in the world where the army and its
terrain can be seen.

## Ancient Warfare ideas translated into Millenaire

### Adopt

1. **Command groups → named warbands.** AW2's baton remembers arbitrary NPC lists. Here the stable
   group is an army raised from Millenaire residents and attached to a faction/realm.
2. **Home point → muster banner or village garrison post.** The implemented garrison order is
   anchored to a real controlled settlement, validates the banner near its center and bounds every
   defensive target and formation slot to the configured guard radius.
3. **Upkeep point → Millenaire stores and supply routes.** Implemented upkeep debits only the bound
   settlement's reserve-protected food and ranged-ammunition ledger. No remote same-faction village
   or parallel warehouse can silently pay the cost.
4. **Combat professions → culture-defined roles.** Archer, shield bearer, scout, captain and support
   roles should come from datapacks and the resident's culture/loadout. A medic or engineer exists
   only where a culture and actual equipment justify it.
5. **Commander bonus → morale and cohesion.** Captains may improve rally speed, formation cohesion
   or morale recovery. Avoid unexplained magical strength auras.

### Do not adopt here

- AW2's research tree, power system, automation network and structure-generation framework.
- Generic player-owned clone NPCs detached from Millenaire families and settlements.
- A permanent full-screen RTS camera or a second economy alongside Millenaire.
- Siege engines until settlement construction, operators, ammunition and counterplay are integrated
  into Millenaire rather than bolted on as standalone vehicles.

## Implemented garrison and muster contract

- One controllable warband can serve as a settlement garrison.
- The persistent binding contains the settlement UUID, dimension, muster position, guard radius,
  upkeep schedule, supply, readiness and morale.
- Residents outside the radius physically return through Millenaire navigation; no teleport or
  forced chunk loading is used.
- Defensive Millenaire targets and tactical approaches are bounded to the same radius.
- Upkeep is atomic, settlement-local and reserve-protected. Missing food/ammunition degrades the
  garrison gradually; restored stores recover it gradually.
- Disband, settlement-control loss, invalid dimensions, stale handles and malformed persisted rows
  fail closed and cannot resurrect a binding.
- The Captain's Banner exposes `Alt+G` and a compact settlement/radius marker. The War Council shows
  the exact settlement, muster coordinates, radius, upkeep condition and readiness, and can move or
  clear the post.

This reuses the existing army order, settlement economy, formation, battle, morale and persistence
systems instead of introducing a foreign subsystem.

## Next coherent slice

Add culture-defined garrison roles and equipment accounting: derive actual ranged/support counts
from persistent Millenaire resident roles, consume exact ammunition/tool kits, and let settlement
culture determine which captain, scout, shield and support duties are available. This must remain a
datapack/Millenaire extension rather than a generic unit-production tree.
