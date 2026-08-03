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
2. **Home point → muster banner or village garrison post.** A future persistent guard/muster order
   should be anchored to a controlled settlement, army camp or banner rather than a free-floating
   RTS waypoint.
3. **Upkeep point → Millenaire stores and supply routes.** Food, arrows, iron and leather should be
   withdrawn through the existing settlement economy and logistics ledger. No parallel warehouse
   simulation is needed.
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

## Next mechanics slice

The next coherent addition is a **garrison and muster contract**:

- set a persistent village guard post;
- assign one warband as the settlement garrison;
- define a bounded guard radius and return-to-post behaviour;
- draw upkeep from that settlement's real stores;
- lose readiness and morale when food/ammunition are unavailable;
- expose the state through the same banner HUD and war-council ledger.

This reuses the existing army order, settlement economy, logistics, morale and persistence systems
instead of introducing a foreign subsystem.
