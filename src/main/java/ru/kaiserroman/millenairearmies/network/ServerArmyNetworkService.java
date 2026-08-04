package ru.kaiserroman.millenairearmies.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import org.millenaire.ReputationConstants;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.model.ArmyFormation;
import ru.kaiserroman.millenairearmies.model.ArmyTacticalState;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedFactionState;
import ru.kaiserroman.millenairearmies.persistence.PackedGarrisonState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;
import ru.kaiserroman.millenairearmies.server.economy.SettlementEconomyEngine;
import ru.kaiserroman.millenairearmies.server.execution.ArmyOrderExecutionBridge;
import ru.kaiserroman.millenairearmies.server.garrison.GarrisonService;
import ru.kaiserroman.millenairearmies.server.realm.RealmAdministrationService;
import ru.kaiserroman.millenairearmies.server.unit.UnitLoadoutDescriptor;
import ru.kaiserroman.millenairearmies.server.unit.UnitRoleService;

/**
 * Minimal authoritative networking vertical slice. It projects only armies controlled by the
 * authenticated player (or all armies for an operator), and delegates every mutation to the one
 * lifecycle-owned command service.
 */
public final class ServerArmyNetworkService implements ServerIntentSink {
    private final ArmySavedData data;
    private final ArmyCommandService commands;
    private final UnitRoleService unitRoleService;
    private final VisibleArmies visible = new VisibleArmies();
    private final PackedArmyEcs.UnitCursor unitCursor;
    private final PackedFactionState.Cursor relationCursor;
    private final PackedLogisticsState.Cursor logisticsCursor;
    private final PackedUnitMembership.UuidBits unitUuid;
    private final int[] visibleFactions = new int[ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT];
    private FactionProjectionService factionProjection;
    private final MillenaireRecruitmentService recruitment;
    private final MillenaireVillageIndex villageIndex;
    private final ServerArmyRosterProjection rosterProjection;
    private final ArmyOrderExecutionBridge execution;
    private final SettlementEconomyEngine settlementEconomy;
    private final GarrisonService garrisonService;
    private final PackedGarrisonState garrisons;
    private final RealmSavedData canonicalRealms;
    private final SimulationSavedData simulation;
    private final RealmAdministrationService realmAdministration;
    private int visibleFactionCount;

    public ServerArmyNetworkService(
            ArmySavedData data,
            ArmyCommandService commands,
            UnitRoleService unitRoleService,
            FactionProjectionService factionProjection,
            MillenaireVillageIndex villageIndex,
            MillenaireRecruitmentService recruitment,
            ArmyOrderExecutionBridge execution,
            SettlementEconomyEngine settlementEconomy,
            GarrisonService garrisonService,
            RealmSavedData canonicalRealms,
            SimulationSavedData simulation,
            RealmAdministrationService realmAdministration) {
        this.data = data;
        this.commands = commands;
        this.unitRoleService = unitRoleService;
        this.factionProjection = factionProjection;
        this.recruitment = recruitment;
        this.villageIndex = villageIndex;
        this.rosterProjection = new ServerArmyRosterProjection(
                data, villageIndex, factionProjection, recruitment);
        this.execution = execution;
        this.settlementEconomy = settlementEconomy;
        this.garrisonService = garrisonService;
        if (canonicalRealms == null || realmAdministration == null) {
            throw new NullPointerException("canonical Realm network dependency");
        }
        this.canonicalRealms = canonicalRealms;
        this.simulation = simulation;
        this.realmAdministration = realmAdministration;
        this.garrisons = data.garrisons();
        this.unitCursor = data.ecs().newUnitCursor();
        this.relationCursor = data.factions().newCursor();
        this.logisticsCursor = data.logistics().newCursor();
        this.unitUuid = data.memberships().newUuidBits();
    }

    /** Cold lifecycle hook; projection data never changes command authority or visibility. */
    public void factionProjection(FactionProjectionService replacement) {
        factionProjection = replacement;
    }

    @Override
    public void open(ServerPlayer player, OpenCommandIntent intent) {
        byte sections = switch (intent.view()) {
            case ArmiesProtocol.VIEW_FACTION -> (byte) (ArmiesProtocol.SECTION_FACTIONS
                    | ArmiesProtocol.SECTION_RELATIONS
                    | ArmiesProtocol.SECTION_ARMIES
                    | ArmiesProtocol.SECTION_UNITS);
            case ArmiesProtocol.VIEW_ARMY -> (byte) (ArmiesProtocol.SECTION_ARMIES
                    | ArmiesProtocol.SECTION_UNITS
                    | ArmiesProtocol.SECTION_ORDERS
                    | ArmiesProtocol.SECTION_LOGISTICS);
            case ArmiesProtocol.VIEW_LOGISTICS -> (byte) (ArmiesProtocol.SECTION_LOGISTICS
                    | ArmiesProtocol.SECTION_ARMIES);
            default -> ArmiesProtocol.SECTION_ALL;
        };
        byte scope = intent.view() == ArmiesProtocol.VIEW_ARMY
                ? ArmiesProtocol.SCOPE_ARMY
                : intent.view() == ArmiesProtocol.VIEW_FACTION
                        ? ArmiesProtocol.SCOPE_FACTION
                        : ArmiesProtocol.SCOPE_GLOBAL;
        sendSnapshot(player, sections, scope, intent.contextHandle());
        sendRealm(player, 0, (byte) 0, ArmiesProtocol.RESULT_NONE);
    }

    @Override
    public void requestState(ServerPlayer player, RequestStateIntent intent) {
        // Cursor zero is the complete bounded projection implemented by this first vertical slice.
        // Non-zero cursors are reserved for the later paged dictionary/content protocol.
        if (intent.cursor() != 0) {
            return;
        }
        sendSnapshot(player, intent.sectionMask(), intent.scope(), intent.scopeHandle());
        sendRealm(player, 0, (byte) 0, ArmiesProtocol.RESULT_NONE);
    }

    @Override
    public void createArmy(ServerPlayer player, CreateArmyIntent intent) {
        if (intent.expectedRevision() != data.armyRevision() || intent.flags() != 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_CREATE_ARMY,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        if (recruitment == null || intent.templateKeyId() != 0) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_CREATE_ARMY,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        // The UUID is the player's explicit ledger selection. Position/faction are consistency
        // hints only; the server resolves the UUID and re-verifies every authority and proximity
        // condition before charging or mutating anything.
        long result = recruitment.formArmyAtVillage(
                authority(player),
                player.serverLevel(),
                player.blockPosition(),
                intent.homeVillageUuidMost(),
                intent.homeVillageUuidLeast(),
                intent.factionId(),
                intent.homeVillagePosition(),
                intent.desiredUnits());
        if (result >= 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_CREATE_ARMY,
                result >= 0 ? ArmiesProtocol.RESULT_ACCEPTED : recruitmentResult(result),
                result >= 0 ? intent.desiredUnits() : 0);
    }

    @Override
    public void recruitUnits(ServerPlayer player, RecruitUnitsIntent intent) {
        if (intent.expectedRevision() != data.armyRevision()) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_RECRUIT,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        if (recruitment == null) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_RECRUIT,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }

        ArmyCommandAuthority authority = authority(player);
        int affected = 0;
        long failure = 0L;
        long[] bits = intent.villagerUuidBits();
        for (int index = 0; index < intent.count(); index++) {
            long result = recruitment.recruitSelected(
                    authority,
                    intent.armyHandle(),
                    player.serverLevel(),
                    player.blockPosition(),
                    intent.villageUuidMost(),
                    intent.villageUuidLeast(),
                    bits[index * 2],
                    bits[index * 2 + 1]);
            if (result < 0L) {
                failure = result;
                break;
            }
            affected++;
        }
        if (affected > 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        int result = affected == intent.count()
                ? ArmiesProtocol.RESULT_ACCEPTED
                : affected > 0 ? ArmiesProtocol.RESULT_PARTIAL : recruitmentResult(failure);
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_RECRUIT, result, affected);
    }

    @Override
    public void hireRecruit(ServerPlayer player, HireRecruitIntent intent) {
        if (intent.expectedRevision() != data.armyRevision()) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_HIRE_RECRUIT,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        if (recruitment == null) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_HIRE_RECRUIT,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        long result = recruitment.hireRecruit(
                player, intent.villagerUuidMost(), intent.villagerUuidLeast());
        if (result >= 0L) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_HIRE_RECRUIT,
                result >= 0L ? ArmiesProtocol.RESULT_ACCEPTED : recruitmentResult(result),
                result >= 0L ? 1 : 0);
    }

    @Override
    public void issueOrder(ServerPlayer player, IssueOrderIntent intent) {
        if (intent.expectedRevision() != data.armyRevision() || intent.flags() != 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        StrategicArmyOrder order = order(intent.orderType());
        if (order == null) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        if (order.requiresTarget()
                && !intent.targetDimension().equals(player.serverLevel().dimension().location())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        long result = commands.issueOrder(
                authority(player),
                intent.armyHandle(),
                order,
                order.requiresTarget() ? player.serverLevel().dimension().location() : null,
                intent.primaryPosition());
        if (result == ArmyCommandService.SUCCESS) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                commandResult(result), result == ArmyCommandService.SUCCESS ? 1 : 0);
    }

    @Override
    public void setFormation(ServerPlayer player, SetFormationIntent intent) {
        if (intent.expectedRevision() != data.armyRevision()) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_FORMATION,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        ArmyFormation formation;
        try {
            formation = ArmyFormation.fromCode(Byte.toUnsignedInt(intent.formationCode()));
        } catch (IllegalArgumentException invalid) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_FORMATION,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        long result = commands.setFormation(authority(player), intent.armyHandle(), formation);
        if (result == ArmyCommandService.SUCCESS) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_FORMATION,
                commandResult(result), result == ArmyCommandService.SUCCESS ? 1 : 0);
    }

    @Override
    public void setTactical(ServerPlayer player, SetTacticalIntent intent) {
        if (intent.expectedRevision() != data.armyRevision()) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_TACTICAL,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        int flag = switch (intent.tacticalCode()) {
            case ArmiesProtocol.TACTIC_SHIELD_WALL -> ArmyTacticalState.SHIELD_WALL;
            case ArmiesProtocol.TACTIC_FIRE_AT_WILL -> ArmyTacticalState.FIRE_AT_WILL;
            default -> 0;
        };
        if (flag == 0) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_TACTICAL,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        long result = commands.setTacticalFlag(
                authority(player), intent.armyHandle(), flag, intent.enabled());
        if (result == ArmyCommandService.SUCCESS) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_TACTICAL,
                commandResult(result), result == ArmyCommandService.SUCCESS ? 1 : 0);
    }

    @Override
    public void setSupplyChest(ServerPlayer player, SetSupplyChestIntent intent) {
        if (intent.expectedRevision() != data.armyRevision()) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_SUPPLY_CHEST,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        ArmyCommandAuthority authority = authority(player);
        if (!data.ecs().isArmyAlive(intent.armyHandle())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_SUPPLY_CHEST,
                    ArmiesProtocol.RESULT_NOT_FOUND, 0);
            return;
        }
        if (!commands.canControl(authority, intent.armyHandle())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_SUPPLY_CHEST,
                    ArmiesProtocol.RESULT_PERMISSION_DENIED, 0);
            return;
        }
        boolean changed;
        if (intent.operation() == SetSupplyChestIntent.OP_CLEAR) {
            changed = data.armySupplies().remove(intent.armyHandle());
        } else {
            if (!intent.dimension().equals(player.serverLevel().dimension().location())) {
                sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_SUPPLY_CHEST,
                        ArmiesProtocol.RESULT_INVALID, 0);
                return;
            }
            BlockPos chestPos = BlockPos.of(intent.chestPosition());
            if (player.distanceToSqr(
                            chestPos.getX() + 0.5D,
                            chestPos.getY() + 0.5D,
                            chestPos.getZ() + 0.5D) > 64.0D * 64.0D
                    || !(player.serverLevel().getBlockEntity(chestPos) instanceof Container)) {
                sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_SUPPLY_CHEST,
                        ArmiesProtocol.RESULT_INVALID, 0);
                return;
            }
            int dimensionId = data.dimensions().intern(intent.dimension());
            changed = data.armySupplies().assign(
                    intent.armyHandle(), dimensionId, intent.chestPosition());
        }
        if (changed) data.markArmyChanged();
        sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_SUPPLY_CHEST,
                ArmiesProtocol.RESULT_ACCEPTED, changed ? 1 : 0);
    }

    @Override
    public void setUnitLoadout(ServerPlayer player, SetUnitLoadoutIntent intent) {
        if (intent.expectedRevision() != data.armyRevision()) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }

        ArmyCommandAuthority authority = authority(player);
        if (!commands.canControl(authority, intent.armyHandle())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                    ArmiesProtocol.RESULT_PERMISSION_DENIED, 0);
            return;
        }
        if (!data.ecs().isArmyAlive(intent.armyHandle())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                    ArmiesProtocol.RESULT_NOT_FOUND, 0);
            return;
        }
        if (!data.ecs().isUnitAlive(intent.unitHandle())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                    ArmiesProtocol.RESULT_NOT_FOUND, 0);
            return;
        }
        if (data.ecs().unitArmy(intent.unitHandle()) != intent.armyHandle()) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        if (!data.memberships().read(intent.unitHandle(), unitUuid)
                || !unitRoleService.hasAssignment(intent.unitHandle())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                    ArmiesProtocol.RESULT_NOT_FOUND, 0);
            return;
        }

        int loadoutToken;
        if (intent.loadoutSelector() == SetUnitLoadoutIntent.LOADOUT_BY_KEY) {
            UnitLoadoutDescriptor loadout = unitRoleService.catalog().loadout(intent.loadoutKey());
            if (loadout == null) {
                sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                        ArmiesProtocol.RESULT_INVALID, 0);
                return;
            }
            loadoutToken = loadout.token();
        } else if (intent.loadoutSelector() == SetUnitLoadoutIntent.LOADOUT_BY_TOKEN) {
            if (intent.loadoutToken() != 0
                    && unitRoleService.catalog().loadout(intent.loadoutToken()) == null) {
                sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                        ArmiesProtocol.RESULT_INVALID, 0);
                return;
            }
            loadoutToken = intent.loadoutToken();
        } else if (intent.loadoutSelector() == SetUnitLoadoutIntent.LOADOUT_DEFAULT) {
            loadoutToken = 0;
        } else {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }

        int affected = 0;
        if (unitRoleService.assignLoadoutOnly(intent.unitHandle(), loadoutToken)) {
            data.markArmyChanged();
            unitRoleService.projectUnitLoadout(player.server, intent.unitHandle());
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            affected = 1;
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_SET_UNIT_LOADOUT,
                ArmiesProtocol.RESULT_ACCEPTED, affected);
    }

    @Override
    public void setGarrison(ServerPlayer player, SetGarrisonIntent intent) {
        int result = garrisonService == null
                ? ArmiesProtocol.RESULT_INVALID
                : garrisonService.apply(player, intent);
        if (result == ArmiesProtocol.RESULT_ACCEPTED || result == ArmiesProtocol.RESULT_STALE) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        byte action = intent.operation() == SetGarrisonIntent.OP_CLEAR
                ? ArmiesProtocol.ACTION_CLEAR_GARRISON
                : ArmiesProtocol.ACTION_SET_GARRISON;
        sendRoster(player, intent.actionId(), action, result,
                result == ArmiesProtocol.RESULT_ACCEPTED ? 1 : 0);
    }

    @Override
    public void realmAction(ServerPlayer player, RealmActionIntent intent) {
        long currentRevision = realmRevision(player.getUUID());
        if (intent.expectedRealmRevision() != currentRevision
                || intent.action() == RealmActionIntent.ACTION_FOUND
                        && intent.expectedArmyRevision() != data.armyRevision()) {
            sendRealm(player, intent.actionId(), intent.action(), ArmiesProtocol.RESULT_STALE);
            return;
        }

        int result;
        if (intent.action() == RealmActionIntent.ACTION_FOUND) {
            UUID capitalId = new UUID(intent.capitalVillageMost(), intent.capitalVillageLeast());
            Village capital = villageIndex.find(
                    intent.capitalVillageMost(), intent.capitalVillageLeast());
            boolean eligible = capital != null
                    && capital.getId() != null
                    && capital.getId().uuid() != null
                    && villageIndex.level(capital.getId()) == player.serverLevel()
                    && player.blockPosition().distSqr(capital.getCenter()) <= 64L * 64L
                    && capital.isControlledBy(player.getUUID())
                    && capital.getCombinedReputation(player.serverLevel(), player.getUUID())
                            >= ReputationConstants.ONE_OF_US
                    && canonicalRealms.realmForPlayer(player.getUUID()) == RealmRegistry.NO_REALM
                    && canonicalRealms.realmForSettlement(capitalId) == RealmRegistry.NO_REALM;
            if (!eligible) {
                result = ArmiesProtocol.RESULT_PERMISSION_DENIED;
            } else {
                long gameTime = player.serverLevel().getGameTime();
                long realmId = realmAdministration.foundPlayerRealm(
                        player.getUUID(),
                        capitalId,
                        villageName(capital),
                        player.serverLevel().dimension().location(),
                        gameTime,
                        gameTime / Math.max(1L, ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS));
                result = realmId == RealmRegistry.NO_REALM
                        ? ArmiesProtocol.RESULT_PERMISSION_DENIED
                        : ArmiesProtocol.RESULT_ACCEPTED;
            }
        } else if (intent.action() == RealmActionIntent.ACTION_SET_TAX) {
            if (intent.taxRate() < 0 || intent.taxRate() > 25) {
                result = ArmiesProtocol.RESULT_INVALID;
            } else {
                result = realmAdministration.setTaxRate(player.getUUID(), intent.taxRate())
                        ? ArmiesProtocol.RESULT_ACCEPTED
                        : ArmiesProtocol.RESULT_PERMISSION_DENIED;
            }
        } else {
            result = ArmiesProtocol.RESULT_INVALID;
        }
        sendRealm(player, intent.actionId(), intent.action(), result);
    }

    private void sendSnapshot(ServerPlayer player, byte sections, byte scope, int scopeHandle) {
        ArmyCommandAuthority authority = authority(player);
        visible.reset(scope, scopeHandle);
        commands.visitVisibleArmies(authority, visible);
        collectVisibleFactions();

        int factionRows = (sections & ArmiesProtocol.SECTION_FACTIONS) != 0 ? visibleFactionCount : 0;
        int armyRows = (sections & ArmiesProtocol.SECTION_ARMIES) != 0 ? visible.size : 0;
        int unitRows = (sections & ArmiesProtocol.SECTION_UNITS) != 0 ? countVisibleUnits() : 0;
        int relationRows = (sections & ArmiesProtocol.SECTION_RELATIONS) != 0 ? countVisibleRelations() : 0;
        int logisticsRows = (sections & ArmiesProtocol.SECTION_LOGISTICS) != 0 ? countVisibleLogistics() : 0;
        int orderRows = (sections & ArmiesProtocol.SECTION_ORDERS) != 0 ? visible.size : 0;
        int totalRows = factionRows + armyRows + unitRows + relationRows + logisticsRows + orderRows;
        int[] ints = new int[totalRows * ArmiesProtocol.INT_COLUMNS];
        long[] longs = new long[totalRows * ArmiesProtocol.LONG_COLUMNS];
        byte[] bytes = new byte[totalRows * ArmiesProtocol.BYTE_COLUMNS];

        int row = 0;
        if (factionRows != 0) {
            for (int index = 0; index < visibleFactionCount; index++) {
                int faction = visibleFactions[index];
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, faction);
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, faction);
                row++;
            }
        }
        if (armyRows != 0) {
            for (int index = 0; index < visible.size; index++) {
                writeArmy(ints, longs, bytes, row++, index);
            }
        }
        if (unitRows != 0) {
            int written = 0;
            unitCursor.reset();
            while (written < unitRows && unitCursor.advance()) {
                if (!visible.contains(unitCursor.army())) {
                    continue;
                }
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, unitCursor.handle());
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, unitCursor.army());
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, unitCursor.order());
                setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, unitCursor.state());
                if (data.memberships().read(unitCursor.handle(), unitUuid)) {
                    setLong(longs, row, 0, unitUuid.most());
                    setLong(longs, row, 1, unitUuid.least());
                }
                row++;
                written++;
            }
        }
        if (relationRows != 0) {
            int written = 0;
            relationCursor.reset();
            while (written < relationRows && relationCursor.advance()) {
                if (!relationVisible()) {
                    continue;
                }
                int source = relationCursor.sourceFactionId();
                int target = relationCursor.targetFactionId();
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, (source << 16) | (target & 0xffff));
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, source);
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, target);
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_0, relationCursor.reputation());
                setLong(longs, row, 0, relationCursor.revision());
                setByte(bytes, row, 0, relationCursor.allegianceCode());
                row++;
                written++;
            }
        }
        if (logisticsRows != 0) {
            int written = 0;
            logisticsCursor.reset();
            while (written < logisticsRows && logisticsCursor.advance()) {
                if (!logisticsVisible()) {
                    continue;
                }
                long requestId = logisticsCursor.requestId();
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, (int) requestId);
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, logisticsCursor.factionId());
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, logisticsCursor.itemKey());
                setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, logisticsCursor.requesterArmyHandle());
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_0, logisticsCursor.requiredAmount());
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_1, logisticsCursor.fulfilledAmount());
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_2, logisticsCursor.dimensionId());
                setLong(longs, row, 0, logisticsCursor.destination());
                setLong(longs, row, 1, logisticsCursor.createdGameTime());
                setByte(bytes, row, 0, logisticsCursor.statusCode());
                setByte(bytes, row, 1, logisticsCursor.priority());
                row++;
                written++;
            }
        }
        if (orderRows != 0) {
            for (int index = 0; index < visible.size; index++) {
                int handle = visible.handles[index];
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, handle);
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, handle);
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, visible.orders[index]);
                setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, visible.states[index]);
                setLong(longs, row, 0, Integer.toUnsignedLong(handle));
                setLong(longs, row, 1, visible.targets[index]);
                setByte(bytes, row, 0, (byte) visible.orders[index]);
                setByte(bytes, row, 1, execution == null
                        ? ArmiesProtocol.EXECUTION_BLOCKED
                        : execution.armyExecutionStatus(handle));
                row++;
            }
        }

        int playerFaction = visibleFactionCount == 0 ? -1 : visibleFactions[0];
        ArmiesNetwork.sendSnapshot(player, new ArmyStateSnapshotPayload(
                data.armyRevision(),
                playerFaction,
                sections,
                factionRows,
                armyRows,
                unitRows,
                relationRows,
                logisticsRows,
                orderRows,
                ints,
                longs,
                bytes));
        sendFactionMetadata(player);
        sendGarrisonState(player);
        sendRoster(player, 0, ArmiesProtocol.ACTION_NONE, ArmiesProtocol.RESULT_NONE, 0);
    }

    private void sendFactionMetadata(ServerPlayer player) {
        int count = visibleFactionCount;
        int[] ints = new int[count * FactionMetadataPayload.INT_COLUMNS];
        long[] positions = new long[count];
        String[] strings = new String[count * FactionMetadataPayload.STRING_COLUMNS];
        FactionProjectionService projection = factionProjection;
        for (int row = 0; row < count; row++) {
            int factionId = visibleFactions[row];
            int primitive = row * FactionMetadataPayload.INT_COLUMNS;
            int text = row * FactionMetadataPayload.STRING_COLUMNS;
            ints[primitive + FactionMetadataPayload.COLUMN_FACTION_ID] = factionId;
            int projectionRow = projection == null ? -1 : projection.findFactionRow(factionId);
            if (projectionRow < 0) {
                strings[text + FactionMetadataPayload.STRING_CULTURE_ID] = "";
                strings[text + FactionMetadataPayload.STRING_DISPLAY_NAME] = "";
                strings[text + FactionMetadataPayload.STRING_CAPITAL_NAME] = "";
                continue;
            }
            ints[primitive + FactionMetadataPayload.COLUMN_SETTLEMENTS] =
                    projection.settlementCount(projectionRow);
            ints[primitive + FactionMetadataPayload.COLUMN_POPULATION] = projection.population(projectionRow);
            ints[primitive + FactionMetadataPayload.COLUMN_INFLUENCE] = projection.influence(projectionRow);
            positions[row] = projection.capitalPosition(projectionRow);
            strings[text + FactionMetadataPayload.STRING_CULTURE_ID] = boundedUtf8(
                    projection.cultureId(projectionRow).toString());
            strings[text + FactionMetadataPayload.STRING_DISPLAY_NAME] = boundedUtf8(
                    projection.displayName(projectionRow));
            strings[text + FactionMetadataPayload.STRING_CAPITAL_NAME] = boundedUtf8(
                    projection.capitalName(projectionRow));
        }
        ArmiesNetwork.sendFactionMetadata(player, new FactionMetadataPayload(
                data.armyRevision(),
                projection == null ? 0L : projection.revision(),
                count,
                ints,
                positions,
                strings));
    }

    private static String boundedUtf8(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= FactionMetadataPayload.MAX_STRING_UTF8_BYTES) {
            return value;
        }
        int end = FactionMetadataPayload.MAX_STRING_UTF8_BYTES;
        while (end > 0 && (encoded[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }

    private void collectVisibleFactions() {
        visibleFactionCount = 0;
        for (int index = 0; index < visible.size; index++) {
            addFaction(visible.factions[index]);
        }
        // Relations disclose only factions directly connected to an already visible faction.
        int originalCount = visibleFactionCount;
        relationCursor.reset();
        while (relationCursor.advance() && visibleFactionCount < visibleFactions.length) {
            int source = relationCursor.sourceFactionId();
            int target = relationCursor.targetFactionId();
            if (containsFaction(source, originalCount) || containsFaction(target, originalCount)) {
                addFaction(source);
                addFaction(target);
            }
        }
    }

    private int countVisibleUnits() {
        int count = 0;
        unitCursor.reset();
        while (unitCursor.advance() && count < ArmiesProtocol.MAX_UNITS_PER_SNAPSHOT) {
            if (visible.contains(unitCursor.army())) {
                count++;
            }
        }
        return count;
    }

    private int countVisibleRelations() {
        int count = 0;
        relationCursor.reset();
        while (relationCursor.advance() && count < ArmiesProtocol.MAX_RELATIONS_PER_SNAPSHOT) {
            if (relationVisible()) {
                count++;
            }
        }
        return count;
    }

    private int countVisibleLogistics() {
        int count = 0;
        logisticsCursor.reset();
        while (logisticsCursor.advance() && count < ArmiesProtocol.MAX_LOGISTICS_PER_SNAPSHOT) {
            if (logisticsVisible()) {
                count++;
            }
        }
        return count;
    }

    private boolean relationVisible() {
        return containsFaction(relationCursor.sourceFactionId(), visibleFactionCount)
                && containsFaction(relationCursor.targetFactionId(), visibleFactionCount);
    }

    private boolean logisticsVisible() {
        return visible.contains(logisticsCursor.requesterArmyHandle())
                || containsFaction(logisticsCursor.factionId(), visibleFactionCount);
    }

    private void writeArmy(int[] ints, long[] longs, byte[] bytes, int row, int index) {
        int handle = visible.handles[index];
        int units = visible.units[index];
        int garrisonRow = garrisons.findArmy(handle);
        int supply = garrisonRow < 0
                ? settlementEconomy == null ? 100 : settlementEconomy.factionSupplyPercent(visible.factions[index])
                : garrisons.supplyPercentAt(garrisonRow);
        int readiness = garrisonRow < 0 ? 100 : garrisons.readinessPercentAt(garrisonRow);
        int morale = garrisonRow < 0 ? 100 : garrisons.moralePercentAt(garrisonRow);
        setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, handle);
        setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, visible.factions[index]);
        setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, visible.states[index]);
        setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, units * readiness / 100);
        setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_0, units);
        setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_1, supply);
        setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_2, readiness);
        setLong(longs, row, 0, visible.targets[index]);
        setLong(longs, row, 1, visible.targets[index]);
        setByte(bytes, row, 0, (byte) visible.orders[index]);
        setByte(bytes, row, 1, (byte) morale);
    }

    private void addFaction(int faction) {
        if (faction < 0 || containsFaction(faction, visibleFactionCount)
                || visibleFactionCount == visibleFactions.length) {
            return;
        }
        visibleFactions[visibleFactionCount++] = faction;
    }

    private boolean containsFaction(int faction, int limit) {
        for (int index = 0; index < limit; index++) {
            if (visibleFactions[index] == faction) {
                return true;
            }
        }
        return false;
    }

    private static ArmyCommandAuthority authority(ServerPlayer player) {
        return ArmyCommandAuthority.player(player.getUUID(), player.hasPermissions(2));
    }

    private static StrategicArmyOrder order(byte code) {
        return switch (code) {
            case ArmiesProtocol.ORDER_HOLD -> StrategicArmyOrder.HOLD;
            case ArmiesProtocol.ORDER_MOVE -> StrategicArmyOrder.MOVE;
            case ArmiesProtocol.ORDER_RALLY -> StrategicArmyOrder.RALLY;
            case ArmiesProtocol.ORDER_LOGISTICS -> StrategicArmyOrder.LOGISTICS;
            case ArmiesProtocol.ORDER_ATTACK -> StrategicArmyOrder.ATTACK;
            case ArmiesProtocol.ORDER_SIEGE -> StrategicArmyOrder.SIEGE;
            case ArmiesProtocol.ORDER_FOLLOW -> StrategicArmyOrder.FOLLOW;
            case ArmiesProtocol.ORDER_GUARD -> StrategicArmyOrder.GUARD;
            default -> null;
        };
    }

    private void sendGarrisonState(ServerPlayer player) {
        int count = 0;
        for (int row = 0; row < garrisons.size(); row++) {
            if (visible.contains(garrisons.armyHandleAt(row))) {
                count++;
            }
        }
        int[] ints = new int[count * GarrisonStatePayload.INT_COLUMNS];
        long[] longs = new long[count * GarrisonStatePayload.LONG_COLUMNS];
        byte[] statuses = new byte[count];
        String[] names = new String[count];
        int out = 0;
        for (int row = 0; row < garrisons.size(); row++) {
            int army = garrisons.armyHandleAt(row);
            if (!visible.contains(army)) {
                continue;
            }
            int ib = out * GarrisonStatePayload.INT_COLUMNS;
            int lb = out * GarrisonStatePayload.LONG_COLUMNS;
            ints[ib + GarrisonStatePayload.COLUMN_ARMY_HANDLE] = army;
            ints[ib + GarrisonStatePayload.COLUMN_DIMENSION_ID] = garrisons.dimensionIdAt(row);
            ints[ib + GarrisonStatePayload.COLUMN_GUARD_RADIUS] = garrisons.guardRadiusAt(row);
            ints[ib + GarrisonStatePayload.COLUMN_SUPPLY] = garrisons.supplyPercentAt(row);
            ints[ib + GarrisonStatePayload.COLUMN_READINESS] = garrisons.readinessPercentAt(row);
            ints[ib + GarrisonStatePayload.COLUMN_MORALE] = garrisons.moralePercentAt(row);
            longs[lb + GarrisonStatePayload.LONG_VILLAGE_MOST] = garrisons.villageMostAt(row);
            longs[lb + GarrisonStatePayload.LONG_VILLAGE_LEAST] = garrisons.villageLeastAt(row);
            longs[lb + GarrisonStatePayload.LONG_MUSTER_POSITION] = garrisons.musterPositionAt(row);
            longs[lb + GarrisonStatePayload.LONG_NEXT_UPKEEP_TICK] = garrisons.nextUpkeepTickAt(row);
            longs[lb + GarrisonStatePayload.LONG_REVISION] = garrisons.revisionAt(row);
            statuses[out] = garrisons.statusAt(row);
            names[out] = boundedUtf8(villageName(villageIndex.find(
                    garrisons.villageMostAt(row), garrisons.villageLeastAt(row))));
            out++;
        }
        ArmiesNetwork.sendGarrisonState(player, new GarrisonStatePayload(
                data.armyRevision(), count, ints, longs, statuses, names));
    }

    private void sendRealm(ServerPlayer player, int actionId, byte action, int result) {
        UUID playerId = player.getUUID();
        long revision = realmRevision(playerId);
        long playerSubject = canonicalRealms.keys().findPlayer(playerId);
        long realmId = playerSubject == 0L
                ? RealmRegistry.NO_REALM
                : canonicalRealms.registry().realmOfMember(playerSubject);
        if (realmId == RealmRegistry.NO_REALM || !canonicalRealms.registry().exists(realmId)) {
            sendEmptyRealm(player, revision, actionId, action, result);
            return;
        }

        long capitalSubject = canonicalRealms.registry().capitalMemberId(realmId);
        long controlledSubject = controlledSettlement(realmId, playerSubject, capitalSubject);
        GovernmentForm government = canonicalRealms.registry().government(realmId);
        Constitution constitution = canonicalRealms.institutions().constitution(realmId);
        byte role = canonicalRole(playerSubject, capitalSubject, controlledSubject, constitution);
        Village capital = villageForSubject(capitalSubject);
        Village controlled = controlledSubject == 0L ? null : villageForSubject(controlledSubject);
        int[] totals = canonicalRealmTotals(realmId);
        int settlementCount = canonicalRealms.registry().settlementCount(realmId);

        ArmiesNetwork.sendRealm(player, new RealmStatePayload(
                revision,
                actionId,
                action,
                result,
                true,
                role,
                government == null ? (byte) 0 : (byte) (government.ordinal() + 1),
                boundedUtf8(value(canonicalRealms.name(realmId))),
                boundedUtf8(villageName(capital)),
                boundedUtf8(villageName(controlled)),
                canonicalRealms.taxRate(realmId),
                canonicalRealms.treasury(realmId),
                settlementCount,
                Math.max(0, settlementCount - 1),
                totals[0],
                canonicalRealms.capturedSettlementCount(realmId),
                totals[1],
                totals[2],
                totals[3],
                totals[4]));
        sendRealmDiplomacy(player, realmId, revision);
    }

    private void sendEmptyRealm(
            ServerPlayer player,
            long revision,
            int actionId,
            byte action,
            int result) {
        ArmiesNetwork.sendRealm(player, new RealmStatePayload(
                revision,
                actionId,
                action,
                result,
                false,
                RealmGovernanceSavedData.ROLE_NONE,
                (byte) 0,
                "",
                "",
                "",
                0,
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0));
        ArmiesNetwork.sendRealmDiplomacy(player, new RealmDiplomacySnapshotPayload(
                revision,
                0L,
                0,
                BoundedCodecs.EMPTY_LONGS,
                BoundedCodecs.EMPTY_INTS,
                BoundedCodecs.EMPTY_BYTES,
                new String[0]));
    }

    private void sendRealmDiplomacy(ServerPlayer player, long realmId, long revision) {
        int maximum = ArmiesProtocol.MAX_REALM_RELATIONS_PER_SNAPSHOT;
        long[] otherRealms = new long[maximum];
        int[] ints = new int[maximum * RealmDiplomacySnapshotPayload.INT_COLUMNS];
        byte[] bytes = new byte[maximum * RealmDiplomacySnapshotPayload.BYTE_COLUMNS];
        String[] names = new String[maximum];
        int[] priorities = new int[maximum];
        int[] count = {0};
        long cycle = player.server.overworld().getGameTime()
                / ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS;

        canonicalRealms.diplomacy().visit((firstRealm, secondRealm, storedStatus,
                firstGoal, secondGoal, firstTrust, secondTrust,
                firstGrievances, secondGrievances, firstFear, secondFear,
                firstClaims, secondClaims, firstExhaustion, secondExhaustion,
                firstWarScore, secondWarScore, trade, border, ideology,
                commonThreat, truceUntil, lastEvaluation) -> {
            if (firstRealm != realmId && secondRealm != realmId) return;
            long otherRealm = firstRealm == realmId ? secondRealm : firstRealm;
            if (!canonicalRealms.registry().exists(otherRealm)) return;
            DiplomaticStatus status = canonicalRealms.diplomacy().status(
                    firstRealm, secondRealm, cycle);
            int warScore = firstRealm == realmId ? firstWarScore : secondWarScore;
            int exhaustion = firstRealm == realmId ? firstExhaustion : secondExhaustion;
            int grievances = firstRealm == realmId ? firstGrievances : secondGrievances;
            int trust = firstRealm == realmId ? firstTrust : secondTrust;
            int goal = (firstRealm == realmId ? firstGoal : secondGoal).ordinal();
            int priority = relationPriority(status, warScore, exhaustion, grievances);

            int row;
            if (count[0] < maximum) {
                row = count[0]++;
            } else {
                row = lowestPriorityRow(priorities, count[0]);
                if (priority <= priorities[row]) return;
            }
            otherRealms[row] = otherRealm;
            priorities[row] = priority;
            int intBase = row * RealmDiplomacySnapshotPayload.INT_COLUMNS;
            ints[intBase + RealmDiplomacySnapshotPayload.COLUMN_WAR_SCORE] = warScore;
            ints[intBase + RealmDiplomacySnapshotPayload.COLUMN_EXHAUSTION] = exhaustion;
            ints[intBase + RealmDiplomacySnapshotPayload.COLUMN_GRIEVANCES] = grievances;
            ints[intBase + RealmDiplomacySnapshotPayload.COLUMN_TRUST] = trust;
            int byteBase = row * RealmDiplomacySnapshotPayload.BYTE_COLUMNS;
            bytes[byteBase + RealmDiplomacySnapshotPayload.BYTE_STATUS] =
                    (byte) status.ordinal();
            bytes[byteBase + RealmDiplomacySnapshotPayload.BYTE_WAR_GOAL] = (byte) goal;
            String name = canonicalRealms.name(otherRealm);
            names[row] = boundedRealmName(name == null ? "Realm #" + otherRealm : name);
        });

        sortRealmRelations(priorities, otherRealms, ints, bytes, names, count[0]);
        int rows = count[0];
        ArmiesNetwork.sendRealmDiplomacy(player, new RealmDiplomacySnapshotPayload(
                revision,
                realmId,
                rows,
                Arrays.copyOf(otherRealms, rows),
                Arrays.copyOf(ints, rows * RealmDiplomacySnapshotPayload.INT_COLUMNS),
                Arrays.copyOf(bytes, rows * RealmDiplomacySnapshotPayload.BYTE_COLUMNS),
                Arrays.copyOf(names, rows)));
    }

    private static int relationPriority(
            DiplomaticStatus status,
            int warScore,
            int exhaustion,
            int grievances) {
        int statusPriority = switch (status) {
            case WAR -> 5;
            case TRUCE -> 4;
            case TENSION -> 3;
            case ALLIANCE -> 2;
            case PEACE -> 1;
        };
        long detail = Math.min(999_999L,
                Math.abs((long) warScore) * 10L + exhaustion * 4L + grievances * 3L);
        return statusPriority * 1_000_000 + (int) detail;
    }

    private static int lowestPriorityRow(int[] priorities, int count) {
        int row = 0;
        for (int index = 1; index < count; index++) {
            if (priorities[index] < priorities[row]) row = index;
        }
        return row;
    }

    private static void sortRealmRelations(
            int[] priorities,
            long[] otherRealms,
            int[] ints,
            byte[] bytes,
            String[] names,
            int count) {
        for (int index = 1; index < count; index++) {
            int cursor = index;
            while (cursor > 0 && priorities[cursor] > priorities[cursor - 1]) {
                swap(priorities, cursor, cursor - 1);
                swap(otherRealms, cursor, cursor - 1);
                swapRows(ints, RealmDiplomacySnapshotPayload.INT_COLUMNS, cursor, cursor - 1);
                swapRows(bytes, RealmDiplomacySnapshotPayload.BYTE_COLUMNS, cursor, cursor - 1);
                String name = names[cursor];
                names[cursor] = names[cursor - 1];
                names[cursor - 1] = name;
                cursor--;
            }
        }
    }

    private static void swap(int[] values, int first, int second) {
        int value = values[first];
        values[first] = values[second];
        values[second] = value;
    }

    private static void swap(long[] values, int first, int second) {
        long value = values[first];
        values[first] = values[second];
        values[second] = value;
    }

    private static void swapRows(int[] values, int stride, int first, int second) {
        int firstBase = first * stride;
        int secondBase = second * stride;
        for (int column = 0; column < stride; column++) {
            int value = values[firstBase + column];
            values[firstBase + column] = values[secondBase + column];
            values[secondBase + column] = value;
        }
    }

    private static void swapRows(byte[] values, int stride, int first, int second) {
        int firstBase = first * stride;
        int secondBase = second * stride;
        for (int column = 0; column < stride; column++) {
            byte value = values[firstBase + column];
            values[firstBase + column] = values[secondBase + column];
            values[secondBase + column] = value;
        }
    }

    private static String boundedRealmName(String value) {
        if (value == null || value.isEmpty()) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= RealmDiplomacySnapshotPayload.MAX_NAME_UTF8_BYTES) return value;
        int end = value.length();
        while (end > 0
                && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length
                        > RealmDiplomacySnapshotPayload.MAX_NAME_UTF8_BYTES) {
            end--;
        }
        return value.substring(0, end);
    }

    private long controlledSettlement(long realmId, long playerSubject, long capitalSubject) {
        if (canonicalRealms.registry().memberControllerId(capitalSubject) == playerSubject) {
            return capitalSubject;
        }
        long[] controlled = {0L};
        canonicalRealms.registry().visitMembers(realmId, (memberId, kind, controllerId, influence) -> {
            if (controlled[0] == 0L
                    && kind != RealmMemberKind.PLAYER
                    && controllerId == playerSubject) {
                controlled[0] = memberId;
            }
        });
        return controlled[0];
    }

    private static byte canonicalRole(
            long playerSubject,
            long capitalSubject,
            long controlledSubject,
            Constitution constitution) {
        if (playerSubject != 0L && controlledSubject == capitalSubject) {
            return RealmGovernanceSavedData.ROLE_HEAD;
        }
        if (controlledSubject != 0L
                && constitution != null
                && constitution.centralization() >= 550) {
            return RealmGovernanceSavedData.ROLE_GOVERNOR;
        }
        return RealmGovernanceSavedData.ROLE_FEUDAL;
    }

    private Village villageForSubject(long settlementSubject) {
        if (!canonicalRealms.keys().valid(settlementSubject)
                || canonicalRealms.keys().kind(settlementSubject)
                        != ru.kaiserroman.millenairearmies.persistence.RealmKeyTable.SETTLEMENT) {
            return null;
        }
        UUID uuid = canonicalRealms.keys().uuid(settlementSubject);
        return villageIndex.find(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    private int[] canonicalRealmTotals(long realmId) {
        int[] totals = new int[5];
        if (simulation != null) {
            for (int row = 0; row < simulation.state().size(); row++) {
                if (simulation.state().realmIdAt(row) == realmId
                        && simulation.state().statusAt(row) != SettlementStatus.RUINED) {
                    totals[0] = saturatedAdd(totals[0], simulation.state().populationAt(row));
                }
            }
        }
        canonicalRealms.registry().visitMembers(realmId, (memberId, kind, controllerId, influence) -> {
            if (kind == RealmMemberKind.PLAYER || !canonicalRealms.keys().valid(memberId)) return;
            UUID uuid = canonicalRealms.keys().uuid(memberId);
            if (simulation == null) {
                Village village = villageIndex.find(
                        uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
                if (village != null) totals[0] = saturatedAdd(totals[0], livingPopulation(village));
            }
            if (settlementEconomy != null) {
                totals[1] = saturatedAdd(totals[1], settlementEconomy.stock(
                        uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                        SettlementEconomyEngine.FOOD));
                totals[2] = saturatedAdd(totals[2], settlementEconomy.stock(
                        uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                        SettlementEconomyEngine.IRON));
                totals[3] = saturatedAdd(totals[3], settlementEconomy.stock(
                        uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                        SettlementEconomyEngine.LEATHER));
                totals[4] = saturatedAdd(totals[4], settlementEconomy.stock(
                        uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(),
                        SettlementEconomyEngine.ARROWS));
            }
        });
        return totals;
    }

    private long realmRevision(UUID player) {
        long revision = canonicalRealms.registry().revision();
        revision = saturatedRevisionAdd(revision, canonicalRealms.institutions().revision());
        revision = saturatedRevisionAdd(revision, canonicalRealms.lifecycle().revision());
        revision = saturatedRevisionAdd(revision, canonicalRealms.diplomacy().revision());
        revision = saturatedRevisionAdd(revision, canonicalRealms.dependencies().revision());
        return saturatedRevisionAdd(revision, canonicalRealms.metadataRevision());
    }

    private static long saturatedRevisionAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static int livingPopulation(Village village) {
        int count = 0;
        if (village != null) {
            for (VillagerRecord record : village.getVillagerRecords().values()) {
                if (record != null && !record.isKilled() && count != Integer.MAX_VALUE) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String villageName(Village village) {
        if (village == null) {
            return "";
        }
        String name = village.getVillageName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return village.getVillageTypeId() == null ? "Settlement" : village.getVillageTypeId().getPath();
    }

    private static int saturatedAdd(int left, int right) {
        return right > Integer.MAX_VALUE - left ? Integer.MAX_VALUE : left + Math.max(0, right);
    }

    private static int saturatedAdd(int left, long right) {
        if (right <= 0L) return left;
        return right >= Integer.MAX_VALUE - (long) left ? Integer.MAX_VALUE : left + (int) right;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private void sendRoster(ServerPlayer player, int actionId, byte action, int result, int affected) {
        ArmiesNetwork.sendRoster(player, rosterProjection.snapshot(
                player, actionId, action, result, affected));
    }

    private static int commandResult(long result) {
        if (result >= 0 || result == ArmyCommandService.SUCCESS) {
            return ArmiesProtocol.RESULT_ACCEPTED;
        }
        return switch ((int) result) {
            case (int) ArmyCommandService.PERMISSION_DENIED -> ArmiesProtocol.RESULT_PERMISSION_DENIED;
            case (int) ArmyCommandService.ARMY_NOT_FOUND -> ArmiesProtocol.RESULT_NOT_FOUND;
            case (int) ArmyCommandService.LIMIT_REACHED -> ArmiesProtocol.RESULT_LIMIT_REACHED;
            default -> ArmiesProtocol.RESULT_INVALID;
        };
    }

    private static int recruitmentResult(long result) {
        return switch ((int) result) {
            case (int) MillenaireRecruitmentService.PERMISSION_DENIED,
                    (int) MillenaireRecruitmentService.WRONG_FACTION,
                    (int) MillenaireRecruitmentService.SETTLEMENT_NOT_CONTROLLED,
                    (int) MillenaireRecruitmentService.REPUTATION_TOO_LOW,
                    (int) MillenaireRecruitmentService.INSUFFICIENT_FUNDS,
                    (int) MillenaireRecruitmentService.HIRED_BY_OTHER ->
                    ArmiesProtocol.RESULT_PERMISSION_DENIED;
            case (int) MillenaireRecruitmentService.ARMY_NOT_FOUND,
                    (int) MillenaireRecruitmentService.VILLAGE_NOT_FOUND,
                    (int) MillenaireRecruitmentService.VILLAGER_NOT_IN_VILLAGE,
                    (int) MillenaireRecruitmentService.VILLAGER_NOT_LOADED -> ArmiesProtocol.RESULT_NOT_FOUND;
            case (int) MillenaireRecruitmentService.UNIT_LIMIT_REACHED,
                    (int) MillenaireRecruitmentService.ARMY_LIMIT_REACHED,
                    (int) MillenaireRecruitmentService.ARMY_FULL -> ArmiesProtocol.RESULT_LIMIT_REACHED;
            default -> ArmiesProtocol.RESULT_INVALID;
        };
    }

    private static void setInt(int[] columns, int row, int column, int value) {
        columns[row * ArmiesProtocol.INT_COLUMNS + column] = value;
    }

    private static void setLong(long[] columns, int row, int column, long value) {
        columns[row * ArmiesProtocol.LONG_COLUMNS + column] = value;
    }

    private static void setByte(byte[] columns, int row, int column, byte value) {
        columns[row * ArmiesProtocol.BYTE_COLUMNS + column] = value;
    }

    private static final class VisibleArmies implements ArmyCommandService.ArmyViewSink {
        private final int[] handles = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] factions = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] orders = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] states = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] units = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final long[] targets = new long[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private int size;
        private byte scope;
        private int scopeHandle;

        void reset(byte scope, int scopeHandle) {
            this.size = 0;
            this.scope = scope;
            this.scopeHandle = scopeHandle;
        }

        @Override
        public void accept(int handle, int faction, int order, int state, int units, long packedTargetPosition) {
            if (size == handles.length
                    || scope == ArmiesProtocol.SCOPE_ARMY && handle != scopeHandle
                    || scope == ArmiesProtocol.SCOPE_FACTION && faction != scopeHandle) {
                return;
            }
            handles[size] = handle;
            factions[size] = faction;
            orders[size] = order;
            states[size] = state;
            this.units[size] = units;
            targets[size] = packedTargetPosition;
            size++;
        }

        boolean contains(int handle) {
            for (int index = 0; index < size; index++) {
                if (handles[index] == handle) {
                    return true;
                }
            }
            return false;
        }
    }
}
