package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Arrays;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.millenaire.culture.Culture;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.model.FactionAllegiance;
import ru.kaiserroman.millenairearmies.persistence.FactionIdentitySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedFactionState;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;

/**
 * Read-only strategic projection over Millenaire's public village/culture API.
 *
 * <p>One faction is synthesized per culture. Village AI and background ticking remain entirely
 * owned by Millenaire: this service never changes activity, chunks, villagers, raids, combat,
 * targets, or paths. Its hot representation is primitive SoA and is rebuilt only after the
 * existing low-frequency village-index reconciliation.</p>
 */
public final class FactionProjectionService
        implements RecruitmentFactionPolicy, ArmyCommandService.FactionValidator {
    private static final String UNNAMED_CAPITAL = "—";

    private final int maxFactions;
    private final ResourceLocation[] cultureScratch;
    private final MillenaireVillageIndex villageIndex;
    private final MillenaireVillageIndex.Cursor villageCursor;

    private ResourceLocation[] cultureIds;
    private String[] displayNames;
    private int[] factionIds;
    private int[] settlementCounts;
    private int[] populations;
    private int[] influences;
    private long[] capitalVillageMost;
    private long[] capitalVillageLeast;
    private long[] capitalPositions;
    private ResourceLocation[] capitalDimensions;
    private String[] capitalNames;
    private int[] capitalScores;
    private byte[] capitalKinds;

    private long[] relationSums = new long[0];
    private int[] relationSamples = new int[0];
    private int factionCount;
    private long revision;
    private long fingerprint;
    private FactionIdentitySavedData identities;
    private PackedFactionState relations;
    private Runnable dirtyCallback;

    public FactionProjectionService(MillenaireVillageIndex villageIndex) {
        this(villageIndex, ArmiesConfig.MAX_FACTIONS);
    }

    FactionProjectionService(MillenaireVillageIndex villageIndex, int maxFactions) {
        if (villageIndex == null) {
            throw new NullPointerException("villageIndex");
        }
        if (maxFactions <= 0) {
            throw new IllegalArgumentException("maxFactions must be positive");
        }
        this.maxFactions = maxFactions;
        this.cultureScratch = new ResourceLocation[maxFactions];
        this.villageIndex = villageIndex;
        this.villageCursor = villageIndex.newCursor();
        int initial = Math.min(8, maxFactions);
        cultureIds = new ResourceLocation[initial];
        displayNames = new String[initial];
        factionIds = new int[initial];
        settlementCounts = new int[initial];
        populations = new int[initial];
        influences = new int[initial];
        capitalVillageMost = new long[initial];
        capitalVillageLeast = new long[initial];
        capitalPositions = new long[initial];
        capitalDimensions = new ResourceLocation[initial];
        capitalNames = new String[initial];
        capitalScores = new int[initial];
        capitalKinds = new byte[initial];
    }

    /** Binds persistent stores and performs the initial projection. */
    public int start(
            MinecraftServer server,
            PackedFactionState packedRelations,
            Runnable onPersistentChange) {
        if (identities != null) {
            return 0;
        }
        if (server == null || packedRelations == null || onPersistentChange == null) {
            throw new NullPointerException("Faction projection dependency");
        }
        identities = FactionIdentitySavedData.get(server);
        relations = packedRelations;
        dirtyCallback = onPersistentChange;
        return reconcile();
    }

    /** Rebuilds aggregate values from the already reconciled village index. */
    public int reconcile() {
        requireStarted();
        int discoveredCultures = collectCultures(villageIndex);
        if (discoveredCultures > maxFactions) {
            throw new IllegalStateException(
                    "Millenaire cultures exceed configured maxFactions=" + maxFactions);
        }
        long directedRelations = (long) discoveredCultures * Math.max(0, discoveredCultures - 1);
        if (directedRelations > 4_000_000L) {
            throw new IllegalStateException(
                    "Projected faction relation graph exceeds persistence safety limit: "
                            + directedRelations);
        }
        sortCultures(discoveredCultures);
        ensureFactionCapacity(discoveredCultures);
        clearActiveColumns(Math.max(factionCount, discoveredCultures));
        factionCount = discoveredCultures;

        for (int row = 0; row < factionCount; row++) {
            ResourceLocation cultureId = cultureScratch[row];
            cultureIds[row] = cultureId;
            factionIds[row] = identities.resolve(cultureId);
            Culture culture = ModCultures.getCulture(cultureId);
            String displayName = culture == null ? null : culture.displayName();
            displayNames[row] = displayName == null || displayName.isBlank() ? cultureId.toString() : displayName;
        }

        aggregateSettlements(villageIndex);
        aggregateRelations(villageIndex);

        int persistentChanges = relations.removeRelationsOutside(factionIds, factionCount);
        for (int source = 0; source < factionCount; source++) {
            for (int target = 0; target < factionCount; target++) {
                if (source == target) {
                    continue;
                }
                int cell = source * factionCount + target;
                int sampleCount = relationSamples[cell];
                int reputation = sampleCount == 0 ? 0 : roundedAverage(relationSums[cell], sampleCount);
                reputation = Math.max(-100, Math.min(100, reputation));
                if (relations.put(
                        factionIds[source],
                        factionIds[target],
                        allegiance(reputation),
                        (short) reputation)) {
                    persistentChanges++;
                }
            }
        }

        long nextFingerprint = fingerprint();
        int projectionChanges = nextFingerprint == fingerprint ? 0 : 1;
        if (projectionChanges != 0 || persistentChanges != 0) {
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("Faction projection revision space exhausted");
            }
            revision++;
            fingerprint = nextFingerprint;
        }
        if (persistentChanges != 0) {
            dirtyCallback.run();
        }
        return persistentChanges + projectionChanges;
    }

    /** Drops all server/SavedData references on shutdown. */
    public void stop() {
        identities = null;
        relations = null;
        dirtyCallback = null;
        clearActiveColumns(factionCount);
        Arrays.fill(cultureScratch, null);
        factionCount = 0;
        fingerprint = 0L;
    }

    public boolean isStarted() {
        return identities != null;
    }

    public int size() {
        return factionCount;
    }

    public long revision() {
        return revision;
    }

    public int factionId(int row) {
        checkRow(row);
        return factionIds[row];
    }

    public ResourceLocation cultureId(int row) {
        checkRow(row);
        return cultureIds[row];
    }

    public String displayName(int row) {
        checkRow(row);
        return displayNames[row];
    }

    public int settlementCount(int row) {
        checkRow(row);
        return settlementCounts[row];
    }

    public int population(int row) {
        checkRow(row);
        return populations[row];
    }

    public int influence(int row) {
        checkRow(row);
        return influences[row];
    }

    public long capitalVillageMost(int row) {
        checkRow(row);
        return capitalVillageMost[row];
    }

    public long capitalVillageLeast(int row) {
        checkRow(row);
        return capitalVillageLeast[row];
    }

    public long capitalPosition(int row) {
        checkRow(row);
        return capitalPositions[row];
    }

    public ResourceLocation capitalDimension(int row) {
        checkRow(row);
        return capitalDimensions[row];
    }

    public String capitalName(int row) {
        checkRow(row);
        return capitalNames[row];
    }

    public int findFactionRow(int factionId) {
        for (int row = 0; row < factionCount; row++) {
            if (factionIds[row] == factionId) {
                return row;
            }
        }
        return -1;
    }

    public int findCultureRow(ResourceLocation cultureId) {
        for (int row = 0; row < factionCount; row++) {
            if (cultureIds[row].equals(cultureId)) {
                return row;
            }
        }
        return -1;
    }

    @Override
    public boolean isValid(int factionId) {
        return findFactionRow(factionId) >= 0;
    }

    /** Allocation-free recruitment policy backed by the already reconciled village index. */
    @Override
    public boolean villageBelongsToFaction(int armyFactionId, long villageUuidMost, long villageUuidLeast) {
        return factionForVillage(villageUuidMost, villageUuidLeast) == armyFactionId;
    }

    @Override
    public int factionForVillage(long villageUuidMost, long villageUuidLeast) {
        Village village = villageIndex.find(villageUuidMost, villageUuidLeast);
        if (village == null || village.getCultureId() == null) {
            return -1;
        }
        int row = findCultureRow(village.getCultureId());
        return row < 0 ? -1 : factionIds[row];
    }

    private int collectCultures(MillenaireVillageIndex villageIndex) {
        int count = 0;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            ResourceLocation culture = village == null ? null : village.getCultureId();
            if (culture == null || containsCulture(cultureScratch, count, culture)) {
                continue;
            }
            if (count == cultureScratch.length) {
                return count + 1;
            }
            cultureScratch[count++] = culture;
        }
        return count;
    }

    private void aggregateSettlements(MillenaireVillageIndex villageIndex) {
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            if (village == null || village.getCultureId() == null || village.getId() == null
                    || village.getId().uuid() == null || village.getCenter() == null) {
                continue;
            }
            int row = findCultureRow(village.getCultureId());
            if (row < 0) {
                continue;
            }

            int population = 0;
            int militaryStrength = 0;
            for (VillagerRecord record : village.getVillagerRecords().values()) {
                if (!record.isKilled()) {
                    population++;
                    militaryStrength = saturatedAdd(militaryStrength, Math.max(0, record.getMilitaryStrength()));
                }
            }
            int buildings = village.getBuildings().size();
            int settlementInfluence = saturatedInfluence(population, buildings, militaryStrength);
            settlementCounts[row]++;
            populations[row] = saturatedAdd(populations[row], population);
            influences[row] = saturatedAdd(influences[row], settlementInfluence);

            boolean loneBuilding = village.isLoneBuilding();
            if (isBetterCapital(row, village, settlementInfluence, loneBuilding)) {
                VillageId id = village.getId();
                capitalVillageMost[row] = id.uuid().getMostSignificantBits();
                capitalVillageLeast[row] = id.uuid().getLeastSignificantBits();
                capitalPositions[row] = village.getCenter().asLong();
                capitalDimensions[row] = villageCursor.level().dimension().location();
                String name = village.getVillageName();
                capitalNames[row] = name == null || name.isBlank() ? UNNAMED_CAPITAL : name;
                capitalScores[row] = settlementInfluence;
                capitalKinds[row] = loneBuilding ? (byte) 1 : (byte) 2;
            }
        }
    }

    private void aggregateRelations(MillenaireVillageIndex villageIndex) {
        int cells = factionCount * factionCount;
        ensureRelationCapacity(cells);
        Arrays.fill(relationSums, 0, cells, 0L);
        Arrays.fill(relationSamples, 0, cells, 0);

        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village sourceVillage = villageCursor.village();
            int source = findCultureRow(sourceVillage.getCultureId());
            if (source < 0) {
                continue;
            }
            // Millenaire returns a read-only wrapper here. This cold 200-tick scan accepts one
            // short-lived wrapper/iterator per village instead of probing every village pair.
            for (Map.Entry<VillageId, Integer> relation : sourceVillage.getRelations().entrySet()) {
                Village targetVillage = villageIndex.find(relation.getKey());
                if (targetVillage == null) {
                    continue;
                }
                int target = findCultureRow(targetVillage.getCultureId());
                if (target < 0 || target == source) {
                    continue;
                }
                int cell = source * factionCount + target;
                relationSums[cell] += Math.max(-100, Math.min(100, relation.getValue()));
                relationSamples[cell]++;
            }
        }
    }

    private boolean isBetterCapital(int row, Village village, int score, boolean loneBuilding) {
        byte kind = loneBuilding ? (byte) 1 : (byte) 2;
        if (kind != capitalKinds[row]) {
            return kind > capitalKinds[row];
        }
        if (score != capitalScores[row]) {
            return score > capitalScores[row];
        }
        long most = village.getId().uuid().getMostSignificantBits();
        int high = Long.compareUnsigned(most, capitalVillageMost[row]);
        if (high != 0) {
            return high < 0;
        }
        return Long.compareUnsigned(
                        village.getId().uuid().getLeastSignificantBits(), capitalVillageLeast[row])
                < 0;
    }

    private long fingerprint() {
        long value = 0xcbf29ce484222325L;
        value = mix(value, factionCount);
        for (int row = 0; row < factionCount; row++) {
            value = mix(value, factionIds[row]);
            value = mix(value, cultureIds[row].hashCode());
            value = mix(value, displayNames[row].hashCode());
            value = mix(value, settlementCounts[row]);
            value = mix(value, populations[row]);
            value = mix(value, influences[row]);
            value = mix(value, capitalVillageMost[row]);
            value = mix(value, capitalVillageLeast[row]);
            value = mix(value, capitalPositions[row]);
            ResourceLocation dimension = capitalDimensions[row];
            value = mix(value, dimension == null ? 0 : dimension.hashCode());
            String capitalName = capitalNames[row];
            value = mix(value, capitalName == null ? 0 : capitalName.hashCode());
        }
        return value;
    }

    private void ensureFactionCapacity(int required) {
        if (required <= cultureIds.length) {
            return;
        }
        int capacity = Math.min(maxFactions, Math.max(required, cultureIds.length + (cultureIds.length >>> 1)));
        cultureIds = Arrays.copyOf(cultureIds, capacity);
        displayNames = Arrays.copyOf(displayNames, capacity);
        factionIds = Arrays.copyOf(factionIds, capacity);
        settlementCounts = Arrays.copyOf(settlementCounts, capacity);
        populations = Arrays.copyOf(populations, capacity);
        influences = Arrays.copyOf(influences, capacity);
        capitalVillageMost = Arrays.copyOf(capitalVillageMost, capacity);
        capitalVillageLeast = Arrays.copyOf(capitalVillageLeast, capacity);
        capitalPositions = Arrays.copyOf(capitalPositions, capacity);
        capitalDimensions = Arrays.copyOf(capitalDimensions, capacity);
        capitalNames = Arrays.copyOf(capitalNames, capacity);
        capitalScores = Arrays.copyOf(capitalScores, capacity);
        capitalKinds = Arrays.copyOf(capitalKinds, capacity);
    }

    private void ensureRelationCapacity(int required) {
        if (required <= relationSums.length) {
            return;
        }
        relationSums = new long[required];
        relationSamples = new int[required];
    }

    private void clearActiveColumns(int count) {
        Arrays.fill(cultureIds, 0, Math.min(count, cultureIds.length), null);
        Arrays.fill(displayNames, 0, Math.min(count, displayNames.length), null);
        Arrays.fill(factionIds, 0, Math.min(count, factionIds.length), 0);
        Arrays.fill(settlementCounts, 0, Math.min(count, settlementCounts.length), 0);
        Arrays.fill(populations, 0, Math.min(count, populations.length), 0);
        Arrays.fill(influences, 0, Math.min(count, influences.length), 0);
        Arrays.fill(capitalVillageMost, 0, Math.min(count, capitalVillageMost.length), 0L);
        Arrays.fill(capitalVillageLeast, 0, Math.min(count, capitalVillageLeast.length), 0L);
        Arrays.fill(capitalPositions, 0, Math.min(count, capitalPositions.length), 0L);
        Arrays.fill(capitalDimensions, 0, Math.min(count, capitalDimensions.length), null);
        Arrays.fill(capitalNames, 0, Math.min(count, capitalNames.length), null);
        Arrays.fill(capitalScores, 0, Math.min(count, capitalScores.length), 0);
        Arrays.fill(capitalKinds, 0, Math.min(count, capitalKinds.length), (byte) 0);
    }

    private static boolean containsCulture(ResourceLocation[] values, int count, ResourceLocation requested) {
        for (int row = 0; row < count; row++) {
            if (values[row].equals(requested)) {
                return true;
            }
        }
        return false;
    }

    private void sortCultures(int count) {
        for (int index = 1; index < count; index++) {
            ResourceLocation value = cultureScratch[index];
            int destination = index;
            while (destination > 0 && compareCulture(value, cultureScratch[destination - 1]) < 0) {
                cultureScratch[destination] = cultureScratch[destination - 1];
                destination--;
            }
            cultureScratch[destination] = value;
        }
        Arrays.fill(cultureScratch, count, cultureScratch.length, null);
    }

    private static int compareCulture(ResourceLocation left, ResourceLocation right) {
        int namespace = left.getNamespace().compareTo(right.getNamespace());
        return namespace != 0 ? namespace : left.getPath().compareTo(right.getPath());
    }

    private static byte allegiance(int reputation) {
        if (reputation <= -30) {
            return FactionAllegiance.HOSTILE.code();
        }
        if (reputation >= 70) {
            return FactionAllegiance.ALLIED.code();
        }
        if (reputation >= 30) {
            return FactionAllegiance.FRIENDLY.code();
        }
        return FactionAllegiance.NEUTRAL.code();
    }

    private static int roundedAverage(long sum, int count) {
        long half = count >>> 1;
        return (int) (sum >= 0 ? (sum + half) / count : (sum - half) / count);
    }

    private static int saturatedInfluence(int population, int buildings, int militaryStrength) {
        long value = (long) population * 10L + (long) buildings * 25L + militaryStrength;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int saturatedAdd(int left, int right) {
        long value = (long) left + right;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private void requireStarted() {
        if (identities == null) {
            throw new IllegalStateException("Faction projection is not started");
        }
    }

    private void checkRow(int row) {
        if (row < 0 || row >= factionCount) {
            throw new IllegalArgumentException("Unknown faction row " + row);
        }
    }
}
