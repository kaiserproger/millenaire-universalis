package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.millenaire.ReputationConstants;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedCommandState;
import ru.kaiserroman.millenairearmies.persistence.PackedFactionState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.persistence.StableItemTable;
import ru.kaiserroman.millenairearmies.model.ArmyOrderType;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;

/** Deterministic coverage of the authoritative recruitment/store lifecycle. */
public final class RecruitmentGameplaySelfTest {
    private RecruitmentGameplaySelfTest() {}

    public static void main(String[] arguments) {
        successPersistsAcrossReload();
        foreignSettlementAndUnavailableNpcFailClosed();
        duplicateAndCapacityAreRejected();
        disbandIsIdempotentAndReleasesExactlyOnce();
        System.out.println("RecruitmentGameplaySelfTest: all checks passed");
    }

    private static void successPersistsAcrossReload() {
        Fixture fixture = new Fixture(4);
        int army = fixture.createVerifiedArmy(7, PackedArmyEcs.packBlockPos(4, 64, 4));
        long unit = fixture.roster.recruit(fixture.owner, army, 11L, 22L, PackedArmyEcs.packBlockPos(5, 64, 5));
        check(unit >= 0L, "living loaded fighter recruited");
        check(fixture.controllers.matches(army, fixture.owner.uuidMost(), fixture.owner.uuidLeast()),
                "controller persisted in packed store");
        check(fixture.memberships.unitHandleForUuid(11L, 22L) == (int) unit,
                "membership persisted in packed store");

        StableDimensionTable dimensions = new StableDimensionTable();
        dimensions.intern(Level.OVERWORLD.location());
        ArmySavedData saved = new ArmySavedData(
                dimensions,
                new StableItemTable(),
                new PackedFactionState(),
                fixture.ecs,
                fixture.memberships,
                fixture.controllers,
                fixture.dirty[0],
                new PackedCommandState(),
                new PackedLogisticsState());
        ArmySavedData restored = ArmySavedData.load(saved.save(new CompoundTag(), null), null);
        int restoredArmy = onlyArmy(restored.ecs());
        check(restoredArmy != 0, "army survives save/reload");
        check(restored.controllers().matches(
                        restoredArmy, fixture.owner.uuidMost(), fixture.owner.uuidLeast()),
                "controller survives handle remap");
        int restoredUnit = restored.memberships().unitHandleForUuid(11L, 22L);
        check(restoredUnit != 0 && restored.ecs().unitArmy(restoredUnit) == restoredArmy,
                "membership survives handle remap");
    }

    private static void foreignSettlementAndUnavailableNpcFailClosed() {
        check(RecruitmentRules.settlementAccess(
                        true, false, ReputationConstants.HIRE_REPUTATION_THRESHOLD)
                        == MillenaireRecruitmentService.SETTLEMENT_NOT_CONTROLLED,
                "foreign settlement rejected even with reputation");
        check(RecruitmentRules.settlementAccess(true, true, 0)
                        == MillenaireRecruitmentService.REPUTATION_TOO_LOW,
                "controlled settlement still checks reputation");
        check(RecruitmentRules.candidate(false, true, true, true, false, false)
                        == MillenaireRecruitmentService.VILLAGER_NOT_LOADED,
                "unloaded NPC rejected");
        check(RecruitmentRules.candidate(true, false, true, true, false, false)
                        == MillenaireRecruitmentService.VILLAGER_UNAVAILABLE,
                "dead NPC rejected");
    }

    private static void duplicateAndCapacityAreRejected() {
        Fixture fixture = new Fixture(1);
        int army = fixture.createVerifiedArmy(3, 0L);
        ArmyCommandAuthority foreignOperator = new ArmyCommandAuthority(301L, 401L, true, true);
        check(fixture.roster.recruit(foreignOperator, army, 100L, 200L, 0L)
                        == MillenaireRecruitmentService.PERMISSION_DENIED,
                "operator status does not replace exact settlement-derived controller ownership");
        long first = fixture.roster.recruit(fixture.owner, army, 101L, 201L, 1L);
        check(first >= 0L, "first recruit succeeds");
        check(fixture.roster.recruit(fixture.owner, army, 101L, 201L, 1L)
                        == MillenaireRecruitmentService.ALREADY_RECRUITED,
                "double recruit rejected without duplicate row");
        check(fixture.roster.recruit(fixture.owner, army, 102L, 202L, 2L)
                        == MillenaireRecruitmentService.ARMY_FULL,
                "full capacity rejected");
        check(fixture.memberships.size() == 1 && fixture.ecs.unitSize() == 1,
                "rejections do not mutate membership");
    }

    private static void disbandIsIdempotentAndReleasesExactlyOnce() {
        Fixture fixture = new Fixture(4);
        int army = fixture.createVerifiedArmy(9, 0L);
        fixture.roster.recruit(fixture.owner, army, 1L, 2L, 3L);
        fixture.roster.recruit(fixture.owner, army, 4L, 5L, 6L);
        int staleUnit = fixture.ecs.createUnit(army, 0, 0, 7L);
        fixture.memberships.bind(staleUnit, 7L, 8L);
        fixture.ecs.removeUnit(staleUnit);
        fixture.commands.add(
                army, 9, ArmyOrderType.HOLD.code(), 0, 0L, 0L, 0L, 0L, 1L, (byte) 0);
        fixture.logistics.add(9, army, 0, 1, 0, 0L, 1L, (byte) 0);
        long first = fixture.roster.disband(fixture.owner, army);
        check(first == 3L, "disband reports two released fighters");
        check(fixture.released[0] == 2, "each recruited NPC released once");
        check(fixture.ecs.armySize() == 0 && fixture.ecs.unitSize() == 0
                        && fixture.memberships.size() == 0 && fixture.controllers.size() == 0,
                "disband removes controller, units and stale memberships without orphans");
        check(fixture.commands.size() == 0 && fixture.logistics.size() == 0,
                "disband removes persisted command/logistics references to the dead handle");
        StableDimensionTable dimensions = new StableDimensionTable();
        dimensions.intern(Level.OVERWORLD.location());
        new ArmySavedData(
                        dimensions,
                        new StableItemTable(),
                        new PackedFactionState(),
                        fixture.ecs,
                        fixture.memberships,
                        fixture.controllers,
                        fixture.dirty[0],
                        fixture.commands,
                        fixture.logistics)
                .save(new CompoundTag(), null);
        long second = fixture.roster.disband(fixture.owner, army);
        check(second == 1L && fixture.released[0] == 2,
                "second disband is a no-op success and does not release twice");
    }

    private static int onlyArmy(PackedArmyEcs ecs) {
        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        return cursor.advance() ? cursor.handle() : 0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture {
        private final PackedArmyEcs ecs = new PackedArmyEcs(2, 4);
        private final PackedUnitMembership memberships = new PackedUnitMembership();
        private final PackedArmyControllers controllers = new PackedArmyControllers();
        private final PackedCommandState commands = new PackedCommandState();
        private final PackedLogisticsState logistics = new PackedLogisticsState();
        private final int[] dirty = {0};
        private final int[] released = {0};
        private final ArmyCommandAuthority owner = ArmyCommandAuthority.player(
                UUID.fromString("8135b3ac-d331-48ff-86a1-26c67f17372f"), false);
        private final RecruitmentRoster roster;

        private Fixture(int capacity) {
            roster = new RecruitmentRoster(
                    ecs,
                    memberships,
                    controllers,
                    commands,
                    logistics,
                    capacity,
                    () -> dirty[0]++,
                    (unit, most, least) -> released[0]++);
        }

        private int createVerifiedArmy(int faction, long position) {
            int army = ecs.createArmy(faction, 0, 0, 0, position);
            controllers.put(army, owner.uuidMost(), owner.uuidLeast(), true);
            dirty[0]++;
            return army;
        }
    }
}
