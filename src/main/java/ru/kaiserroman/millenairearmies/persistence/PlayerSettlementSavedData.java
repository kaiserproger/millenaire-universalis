package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementPolicy;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementTier;

/** Persistent player-settlement identity, territorial radius and development tier. */
public final class PlayerSettlementSavedData extends SavedData {
    public static final String FILE_ID = "millenaire_player_settlements";
    public static final int MAX_SETTLEMENTS = PlayerRealmSavedData.MAX_REALMS;
    private static final int SCHEMA_VERSION = 1;
    private static final SavedData.Factory<PlayerSettlementSavedData> FACTORY =
            new SavedData.Factory<>(PlayerSettlementSavedData::new, PlayerSettlementSavedData::load);

    private int size;
    private long revision;
    private long territoryRevision;
    private long[] ownerMost = new long[8];
    private long[] ownerLeast = new long[8];
    private long[] capitalMost = new long[8];
    private long[] capitalLeast = new long[8];
    private long[] realmIds = new long[8];
    private String[] dimensions = new String[8];
    private int[] baseRadii = new int[8];
    private int[] territoryRadii = new int[8];
    private int[] development = new int[8];
    private byte[] tiers = new byte[8];
    private long[] foundedTicks = new long[8];
    private long[] lastAssessmentTicks = new long[8];

    public static PlayerSettlementSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public int size() { return size; }
    public long revision() { return revision; }
    public long territoryRevision() { return territoryRevision; }

    public boolean register(
            UUID owner,
            UUID capital,
            long realmId,
            ResourceLocation dimension,
            int baseRadius,
            long foundedTick) {
        if (owner == null || capital == null || realmId <= 0L || dimension == null
                || baseRadius < 0 || baseRadius > PlayerSettlementPolicy.MAXIMUM_RADIUS
                || foundedTick < 0L) {
            throw new IllegalArgumentException("Invalid player settlement registration");
        }
        if (find(owner) >= 0 || findCapital(capital) >= 0 || findRealm(realmId) >= 0
                || size == MAX_SETTLEMENTS) {
            return false;
        }
        ensureCapacity(size + 1);
        int row = size++;
        ownerMost[row] = owner.getMostSignificantBits();
        ownerLeast[row] = owner.getLeastSignificantBits();
        capitalMost[row] = capital.getMostSignificantBits();
        capitalLeast[row] = capital.getLeastSignificantBits();
        realmIds[row] = realmId;
        dimensions[row] = dimension.toString();
        baseRadii[row] = baseRadius;
        territoryRadii[row] = Math.max(96, baseRadius);
        development[row] = 0;
        tiers[row] = (byte) PlayerSettlementTier.HAMLET.ordinal();
        foundedTicks[row] = foundedTick;
        lastAssessmentTicks[row] = foundedTick;
        changed(true);
        return true;
    }

    public boolean updateAssessment(
            UUID owner,
            PlayerSettlementTier tier,
            int territoryRadius,
            int developmentValue,
            long assessmentTick) {
        if (owner == null || tier == null
                || territoryRadius < PlayerSettlementPolicy.MINIMUM_RADIUS
                || territoryRadius > PlayerSettlementPolicy.MAXIMUM_RADIUS
                || developmentValue < 0 || developmentValue > 1000 || assessmentTick < 0L) {
            throw new IllegalArgumentException("Invalid player settlement assessment update");
        }
        int row = find(owner);
        if (row < 0) return false;
        if (assessmentTick < foundedTicks[row]) {
            throw new IllegalArgumentException("Assessment predates settlement foundation");
        }
        byte encoded = (byte) Math.max(Byte.toUnsignedInt(tiers[row]), tier.ordinal());
        int monotonicRadius = Math.max(territoryRadii[row], territoryRadius);
        boolean radiusChanged = territoryRadii[row] != monotonicRadius;
        boolean changed = tiers[row] != encoded || radiusChanged
                || development[row] != developmentValue;
        tiers[row] = encoded;
        territoryRadii[row] = monotonicRadius;
        development[row] = developmentValue;
        if (changed) {
            lastAssessmentTicks[row] = assessmentTick;
            changed(radiusChanged);
        }
        return true;
    }

    public boolean view(UUID owner, View view) {
        if (view == null) throw new NullPointerException("view");
        int row = find(owner);
        if (row < 0) return false;
        fill(row, view);
        return true;
    }

    public boolean viewCapital(UUID capital, View view) {
        if (view == null) throw new NullPointerException("view");
        int row = findCapital(capital);
        if (row < 0) return false;
        fill(row, view);
        return true;
    }

    public boolean viewRealm(long realmId, View view) {
        if (view == null) throw new NullPointerException("view");
        int row = findRealm(realmId);
        if (row < 0) return false;
        fill(row, view);
        return true;
    }

    public void visit(Visitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        View view = new View();
        for (int row = 0; row < size; row++) {
            fill(row, view);
            visitor.accept(view);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putLong("Revision", revision);
        tag.putLong("TerritoryRevision", territoryRevision);
        ListTag rows = new ListTag();
        for (int row = 0; row < size; row++) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Owner", new UUID(ownerMost[row], ownerLeast[row]));
            value.putUUID("Capital", new UUID(capitalMost[row], capitalLeast[row]));
            value.putLong("RealmId", realmIds[row]);
            value.putString("Dimension", dimensions[row]);
            value.putInt("BaseRadius", baseRadii[row]);
            value.putInt("TerritoryRadius", territoryRadii[row]);
            value.putInt("Development", development[row]);
            value.putByte("Tier", tiers[row]);
            value.putLong("FoundedTick", foundedTicks[row]);
            value.putLong("LastAssessmentTick", lastAssessmentTicks[row]);
            rows.add(value);
        }
        tag.put("Settlements", rows);
        return tag;
    }

    static PlayerSettlementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int schema = tag.getInt("SchemaVersion");
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported player settlement schema " + schema);
        }
        PlayerSettlementSavedData data = new PlayerSettlementSavedData();
        ListTag rows = tag.getList("Settlements", Tag.TAG_COMPOUND);
        if (rows.size() > MAX_SETTLEMENTS) {
            throw new IllegalArgumentException("Too many player settlements");
        }
        for (int index = 0; index < rows.size(); index++) {
            CompoundTag value = rows.getCompound(index);
            UUID owner = value.getUUID("Owner");
            UUID capital = value.getUUID("Capital");
            ResourceLocation dimension = ResourceLocation.tryParse(value.getString("Dimension"));
            int tierOrdinal = Byte.toUnsignedInt(value.getByte("Tier"));
            int baseRadius = value.getInt("BaseRadius");
            int territoryRadius = value.getInt("TerritoryRadius");
            int developmentValue = value.getInt("Development");
            long foundedTick = value.getLong("FoundedTick");
            long lastAssessmentTick = value.getLong("LastAssessmentTick");
            int minimumTerritory = Math.max(PlayerSettlementPolicy.MINIMUM_RADIUS, baseRadius);
            if (dimension == null || tierOrdinal >= PlayerSettlementTier.values().length
                    || baseRadius < 0 || baseRadius > PlayerSettlementPolicy.MAXIMUM_RADIUS
                    || territoryRadius < minimumTerritory
                    || territoryRadius > PlayerSettlementPolicy.MAXIMUM_RADIUS
                    || developmentValue < 0 || developmentValue > 1000
                    || foundedTick < 0L || lastAssessmentTick < foundedTick
                    || !data.register(owner, capital, value.getLong("RealmId"), dimension,
                            baseRadius, foundedTick)) {
                throw new IllegalArgumentException("Invalid or duplicate player settlement row");
            }
            int row = data.find(owner);
            data.territoryRadii[row] = territoryRadius;
            data.development[row] = developmentValue;
            data.tiers[row] = (byte) tierOrdinal;
            data.lastAssessmentTicks[row] = lastAssessmentTick;
        }
        long restoredRevision = tag.getLong("Revision");
        long restoredTerritoryRevision = tag.contains("TerritoryRevision", Tag.TAG_LONG)
                ? tag.getLong("TerritoryRevision")
                : restoredRevision;
        if (restoredRevision < 0L || restoredTerritoryRevision < 0L) {
            throw new IllegalArgumentException("Negative player settlement revision");
        }
        data.revision = restoredRevision;
        data.territoryRevision = restoredTerritoryRevision;
        data.setDirty(false);
        return data;
    }

    private void fill(int row, View view) {
        view.owner = new UUID(ownerMost[row], ownerLeast[row]);
        view.capital = new UUID(capitalMost[row], capitalLeast[row]);
        view.realmId = realmIds[row];
        view.dimension = ResourceLocation.parse(dimensions[row]);
        view.baseRadius = baseRadii[row];
        view.territoryRadius = territoryRadii[row];
        view.development = development[row];
        view.tier = PlayerSettlementTier.values()[Byte.toUnsignedInt(tiers[row])];
        view.foundedTick = foundedTicks[row];
        view.lastAssessmentTick = lastAssessmentTicks[row];
    }

    private int find(UUID owner) {
        if (owner == null) return -1;
        long most = owner.getMostSignificantBits();
        long least = owner.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (ownerMost[row] == most && ownerLeast[row] == least) return row;
        }
        return -1;
    }

    private int findCapital(UUID capital) {
        if (capital == null) return -1;
        long most = capital.getMostSignificantBits();
        long least = capital.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (capitalMost[row] == most && capitalLeast[row] == least) return row;
        }
        return -1;
    }

    private int findRealm(long realmId) {
        if (realmId <= 0L) return -1;
        for (int row = 0; row < size; row++) {
            if (realmIds[row] == realmId) return row;
        }
        return -1;
    }

    private void ensureCapacity(int required) {
        if (required <= ownerMost.length) return;
        int capacity = Math.min(MAX_SETTLEMENTS, Math.max(required, ownerMost.length << 1));
        ownerMost = Arrays.copyOf(ownerMost, capacity);
        ownerLeast = Arrays.copyOf(ownerLeast, capacity);
        capitalMost = Arrays.copyOf(capitalMost, capacity);
        capitalLeast = Arrays.copyOf(capitalLeast, capacity);
        realmIds = Arrays.copyOf(realmIds, capacity);
        dimensions = Arrays.copyOf(dimensions, capacity);
        baseRadii = Arrays.copyOf(baseRadii, capacity);
        territoryRadii = Arrays.copyOf(territoryRadii, capacity);
        development = Arrays.copyOf(development, capacity);
        tiers = Arrays.copyOf(tiers, capacity);
        foundedTicks = Arrays.copyOf(foundedTicks, capacity);
        lastAssessmentTicks = Arrays.copyOf(lastAssessmentTicks, capacity);
    }

    private void changed(boolean territoryChanged) {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Player settlement revision exhausted");
        if (territoryChanged && territoryRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Player settlement territory revision exhausted");
        }
        revision++;
        if (territoryChanged) territoryRevision++;
        setDirty();
    }

    public static final class View {
        public UUID owner;
        public UUID capital;
        public long realmId;
        public ResourceLocation dimension;
        public int baseRadius;
        public int territoryRadius;
        public int development;
        public PlayerSettlementTier tier;
        public long foundedTick;
        public long lastAssessmentTick;
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(View view);
    }
}
