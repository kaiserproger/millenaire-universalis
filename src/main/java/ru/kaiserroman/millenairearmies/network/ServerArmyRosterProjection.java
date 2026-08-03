package ru.kaiserroman.millenairearmies.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;

/** Cold command-screen projection; invoked only for an explicit client state request/action. */
final class ServerArmyRosterProjection {
    private final ArmySavedData data;
    private final MillenaireVillageIndex villages;
    private final MillenaireVillageIndex.Cursor cursor;
    private final FactionProjectionService factions;
    private final MillenaireRecruitmentService recruitment;
    private final RealmGovernanceSavedData.AssignmentView governanceAssignment =
            new RealmGovernanceSavedData.AssignmentView();
    private long snapshotRevision;

    ServerArmyRosterProjection(
            ArmySavedData data,
            MillenaireVillageIndex villages,
            FactionProjectionService factions,
            MillenaireRecruitmentService recruitment) {
        this.data = data;
        this.villages = villages;
        this.cursor = villages.newCursor();
        this.factions = factions;
        this.recruitment = recruitment;
    }

    ArmyRosterSnapshotPayload snapshot(
            ServerPlayer player,
            int actionId,
            byte action,
            int result,
            int affected) {
        RealmGovernanceSavedData governance = RealmGovernanceSavedData.get(player.server);
        boolean assigned = governance.readPlayer(player.getUUID(), governanceAssignment);
        long assignedVillageMost = assigned ? governanceAssignment.villageMost() : 0L;
        long assignedVillageLeast = assigned ? governanceAssignment.villageLeast() : 0L;
        int settlementCount = 0;
        for (cursor.reset(); cursor.advance()
                && settlementCount < ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS; ) {
            Village village = cursor.village();
            if (!eligibleVillage(village)
                    || !village.isControlledBy(player.getUUID())
                    || !assignedSettlement(village, assigned, assignedVillageMost, assignedVillageLeast)
                    || factionRow(village) < 0) {
                continue;
            }
            settlementCount++;
        }

        int[] settlementInts = new int[settlementCount * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS];
        long[] settlementLongs = new long[settlementCount * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS];
        String[] settlementStrings = new String[settlementCount * ArmyRosterSnapshotPayload.SETTLEMENT_STRING_COLUMNS];
        int[] recruitInts = new int[
                ArmiesProtocol.MAX_AVAILABLE_RECRUITS * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS];
        long[] recruitLongs = new long[
                ArmiesProtocol.MAX_AVAILABLE_RECRUITS * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS];
        String[] recruitStrings = new String[
                ArmiesProtocol.MAX_AVAILABLE_RECRUITS * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS];

        int settlementRow = 0;
        for (cursor.reset(); cursor.advance() && settlementRow < settlementCount; ) {
            Village village = cursor.village();
            int factionRow = factionRow(village);
            if (!eligibleVillage(village)
                    || !village.isControlledBy(player.getUUID())
                    || !assignedSettlement(village, assigned, assignedVillageMost, assignedVillageLeast)
                    || factionRow < 0) {
                continue;
            }
            int si = settlementRow * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS;
            int sl = settlementRow * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS;
            int ss = settlementRow * ArmyRosterSnapshotPayload.SETTLEMENT_STRING_COLUMNS;
            settlementInts[si + ArmyRosterSnapshotPayload.SETTLEMENT_FACTION] = factions.factionId(factionRow);
            settlementInts[si + ArmyRosterSnapshotPayload.SETTLEMENT_POPULATION] = livingPopulation(village);
            settlementInts[si + ArmyRosterSnapshotPayload.SETTLEMENT_AVAILABLE] = 0;
            UUID villageId = village.getId().uuid();
            settlementLongs[sl + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_MOST] =
                    villageId.getMostSignificantBits();
            settlementLongs[sl + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_LEAST] =
                    villageId.getLeastSignificantBits();
            settlementLongs[sl + ArmyRosterSnapshotPayload.SETTLEMENT_POSITION] = village.getCenter().asLong();
            String name = village.getVillageName();
            settlementStrings[ss + ArmyRosterSnapshotPayload.SETTLEMENT_NAME] = bounded(
                    name == null || name.isBlank() ? village.getVillageTypeId().getPath() : name);
            settlementStrings[ss + ArmyRosterSnapshotPayload.SETTLEMENT_CULTURE] = bounded(
                    village.getCultureId().toString());
            settlementRow++;
        }

        int[] recruitCount = {0};
        final int projectedSettlementCount = settlementCount;
        if (recruitment != null) {
            recruitment.visitEligible(
                    ArmyCommandAuthority.player(
                            player.getUUID(), player.hasPermissions(2)),
                    player.serverLevel(),
                    player.blockPosition(),
                    (villager, villageName, villageMost, villageLeast, distance) -> {
                        int row = recruitCount[0];
                        if (row == ArmiesProtocol.MAX_AVAILABLE_RECRUITS) {
                            return;
                        }
                        UUID uuid = villager.getUUID();
                        int ri = row * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS;
                        int rl = row * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS;
                        int rs = row * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS;
                        recruitInts[ri + ArmyRosterSnapshotPayload.RECRUIT_STRENGTH] =
                                (int) Math.max(0.0D, villager.getAttackStrength());
                        recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_UUID_MOST] =
                                uuid.getMostSignificantBits();
                        recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_UUID_LEAST] =
                                uuid.getLeastSignificantBits();
                        recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_VILLAGE_MOST] = villageMost;
                        recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_VILLAGE_LEAST] = villageLeast;
                        recruitStrings[rs + ArmyRosterSnapshotPayload.RECRUIT_NAME] = bounded(
                                villager.getVillagerDisplayName());
                        recruitStrings[rs + ArmyRosterSnapshotPayload.RECRUIT_ROLE] = bounded(
                                villager.getNativeRoleName());
                        incrementAvailable(
                                settlementInts,
                                settlementLongs,
                                projectedSettlementCount,
                                villageMost,
                                villageLeast);
                        recruitCount[0] = row + 1;
                    });
        }

        int recruits = recruitCount[0];

        return new ArmyRosterSnapshotPayload(
                ++snapshotRevision,
                actionId,
                action,
                result,
                affected,
                settlementCount,
                recruits,
                settlementInts,
                settlementLongs,
                settlementStrings,
                Arrays.copyOf(
                        recruitInts, recruits * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS),
                Arrays.copyOf(
                        recruitLongs, recruits * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS),
                Arrays.copyOf(
                        recruitStrings, recruits * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS));
    }

    private int factionRow(Village village) {
        return village.getCultureId() == null ? -1 : factions.findCultureRow(village.getCultureId());
    }

    private static void incrementAvailable(
            int[] settlementInts,
            long[] settlementLongs,
            int settlementCount,
            long villageMost,
            long villageLeast) {
        for (int row = 0; row < settlementCount; row++) {
            int longs = row * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS;
            if (settlementLongs[longs + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_MOST] == villageMost
                    && settlementLongs[longs + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_LEAST]
                            == villageLeast) {
                int ints = row * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS;
                settlementInts[ints + ArmyRosterSnapshotPayload.SETTLEMENT_AVAILABLE]++;
                return;
            }
        }
    }

    private static int livingPopulation(Village village) {
        int count = 0;
        for (VillagerRecord record : village.getVillagerRecords().values()) {
            if (record != null && !record.isKilled()) {
                count++;
            }
        }
        return count;
    }

    private static boolean eligibleVillage(Village village) {
        return village != null
                && village.getId() != null
                && village.getId().uuid() != null
                && village.getCenter() != null
                && village.getCultureId() != null
                && village.getVillageTypeId() != null;
    }

    private static boolean assignedSettlement(
            Village village, boolean assigned, long assignedMost, long assignedLeast) {
        if (!assigned) {
            return true;
        }
        UUID villageId = village.getId().uuid();
        return villageId.getMostSignificantBits() == assignedMost
                && villageId.getLeastSignificantBits() == assignedLeast;
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= ArmyRosterSnapshotPayload.MAX_STRING_UTF8_BYTES) {
            return value;
        }
        int end = ArmyRosterSnapshotPayload.MAX_STRING_UTF8_BYTES;
        while (end > 0 && (encoded[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }
}
