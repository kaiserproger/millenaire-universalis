package ru.kaiserroman.millenairearmies.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;

/** Cold command-screen projection; invoked only for an explicit client state request/action. */
final class ServerArmyRosterProjection {
    private final FactionProjectionService factions;
    private final MillenaireRecruitmentService recruitment;
    private long snapshotRevision;

    ServerArmyRosterProjection(
            ArmySavedData ignoredData,
            MillenaireVillageIndex ignoredVillages,
            FactionProjectionService factions,
            MillenaireRecruitmentService recruitment) {
        this.factions = factions;
        this.recruitment = recruitment;
    }

    ArmyRosterSnapshotPayload snapshot(
            ServerPlayer player,
            int actionId,
            byte action,
            int result,
            int affected) {
        int[] settlementInts = new int[
                ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS];
        long[] settlementLongs = new long[
                ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS];
        String[] settlementStrings = new String[
                ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS * ArmyRosterSnapshotPayload.SETTLEMENT_STRING_COLUMNS];
        int[] recruitInts = new int[
                ArmiesProtocol.MAX_AVAILABLE_RECRUITS * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS];
        long[] recruitLongs = new long[
                ArmiesProtocol.MAX_AVAILABLE_RECRUITS * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS];
        String[] recruitStrings = new String[
                ArmiesProtocol.MAX_AVAILABLE_RECRUITS * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS];
        int[] settlementCount = {0};
        int[] recruitCount = {0};

        if (recruitment != null) {
            recruitment.visitRecruitmentOptions(player, new MillenaireRecruitmentService.RecruitmentOptionSink() {
                @Override
                public void settlement(Village village, int faction, boolean controlled) {
                    if (settlementCount[0] >= ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS
                            || village == null
                            || village.getId() == null
                            || village.getId().uuid() == null
                            || village.getCenter() == null
                            || findSettlement(settlementLongs, settlementCount[0], village.getId().uuid()) >= 0) {
                        return;
                    }
                    int row = settlementCount[0]++;
                    int si = row * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS;
                    int sl = row * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS;
                    int ss = row * ArmyRosterSnapshotPayload.SETTLEMENT_STRING_COLUMNS;
                    UUID villageId = village.getId().uuid();
                    settlementInts[si + ArmyRosterSnapshotPayload.SETTLEMENT_FACTION] = faction;
                    settlementInts[si + ArmyRosterSnapshotPayload.SETTLEMENT_POPULATION] = livingPopulation(village);
                    settlementInts[si + ArmyRosterSnapshotPayload.SETTLEMENT_AVAILABLE] = 0;
                    settlementInts[si + ArmyRosterSnapshotPayload.SETTLEMENT_ACCESS] = controlled
                            ? ArmiesProtocol.SETTLEMENT_ACCESS_CONTROLLED
                            : ArmiesProtocol.SETTLEMENT_ACCESS_HIRE;
                    settlementLongs[sl + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_MOST] =
                            villageId.getMostSignificantBits();
                    settlementLongs[sl + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_LEAST] =
                            villageId.getLeastSignificantBits();
                    settlementLongs[sl + ArmyRosterSnapshotPayload.SETTLEMENT_POSITION] =
                            village.getCenter().asLong();
                    String name = village.getVillageName();
                    settlementStrings[ss + ArmyRosterSnapshotPayload.SETTLEMENT_NAME] = bounded(
                            name == null || name.isBlank()
                                    ? village.getVillageTypeId().getPath()
                                    : name);
                    settlementStrings[ss + ArmyRosterSnapshotPayload.SETTLEMENT_CULTURE] = bounded(
                            village.getCultureId().toString());
                }

                @Override
                public void recruit(
                        MillVillager villager,
                        Village village,
                        int option,
                        int cost,
                        int reputation,
                        int requiredReputation) {
                    if (recruitCount[0] >= ArmiesProtocol.MAX_AVAILABLE_RECRUITS
                            || villager == null
                            || villager.getUUID() == null
                            || village == null
                            || village.getId() == null
                            || village.getId().uuid() == null) {
                        return;
                    }
                    int settlementRow = findSettlement(
                            settlementLongs, settlementCount[0], village.getId().uuid());
                    if (settlementRow < 0) {
                        return;
                    }
                    int row = recruitCount[0]++;
                    int ri = row * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS;
                    int rl = row * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS;
                    int rs = row * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS;
                    UUID uuid = villager.getUUID();
                    UUID villageId = village.getId().uuid();
                    recruitInts[ri + ArmyRosterSnapshotPayload.RECRUIT_STRENGTH] =
                            (int) Math.max(0.0D, villager.getAttackStrength());
                    recruitInts[ri + ArmyRosterSnapshotPayload.RECRUIT_OPTION] = option;
                    recruitInts[ri + ArmyRosterSnapshotPayload.RECRUIT_COST] = Math.max(0, cost);
                    recruitInts[ri + ArmyRosterSnapshotPayload.RECRUIT_REPUTATION] = reputation;
                    recruitInts[ri + ArmyRosterSnapshotPayload.RECRUIT_REQUIRED_REPUTATION] =
                            Math.max(0, requiredReputation);
                    recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_UUID_MOST] =
                            uuid.getMostSignificantBits();
                    recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_UUID_LEAST] =
                            uuid.getLeastSignificantBits();
                    recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_VILLAGE_MOST] =
                            villageId.getMostSignificantBits();
                    recruitLongs[rl + ArmyRosterSnapshotPayload.RECRUIT_VILLAGE_LEAST] =
                            villageId.getLeastSignificantBits();
                    recruitStrings[rs + ArmyRosterSnapshotPayload.RECRUIT_NAME] = bounded(
                            villager.getVillagerDisplayName());
                    recruitStrings[rs + ArmyRosterSnapshotPayload.RECRUIT_ROLE] = bounded(
                            villager.getNativeRoleName());
                    int settlementOffset = settlementRow * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS;
                    settlementInts[settlementOffset + ArmyRosterSnapshotPayload.SETTLEMENT_AVAILABLE]++;
                }
            });
        }

        int settlements = settlementCount[0];
        int recruits = recruitCount[0];
        return new ArmyRosterSnapshotPayload(
                ++snapshotRevision,
                actionId,
                action,
                result,
                affected,
                settlements,
                recruits,
                Arrays.copyOf(
                        settlementInts, settlements * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS),
                Arrays.copyOf(
                        settlementLongs, settlements * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS),
                Arrays.copyOf(
                        settlementStrings, settlements * ArmyRosterSnapshotPayload.SETTLEMENT_STRING_COLUMNS),
                Arrays.copyOf(recruitInts, recruits * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS),
                Arrays.copyOf(recruitLongs, recruits * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS),
                Arrays.copyOf(recruitStrings, recruits * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS));
    }

    private static int findSettlement(long[] settlementLongs, int count, UUID villageId) {
        long most = villageId.getMostSignificantBits();
        long least = villageId.getLeastSignificantBits();
        for (int row = 0; row < count; row++) {
            int offset = row * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS;
            if (settlementLongs[offset + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_MOST] == most
                    && settlementLongs[offset + ArmyRosterSnapshotPayload.SETTLEMENT_UUID_LEAST] == least) {
                return row;
            }
        }
        return -1;
    }

    private static int livingPopulation(Village village) {
        int count = 0;
        for (VillagerRecord record : village.getVillagerRecords().values()) {
            if (record != null && !record.isKilled() && count != Integer.MAX_VALUE) {
                count++;
            }
        }
        return count;
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
