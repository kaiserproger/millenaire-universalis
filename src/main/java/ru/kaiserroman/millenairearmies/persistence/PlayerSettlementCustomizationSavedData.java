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
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementProfile;

/** Player-selected identity and bounded development settings for an owned physical settlement. */
public final class PlayerSettlementCustomizationSavedData extends SavedData {
    public static final String FILE_ID = "millenaire_armies_player_settlement_customization";
    public static final int MAX_SETTLEMENTS = PlayerSettlementSavedData.MAX_SETTLEMENTS;
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MIN_QUEUE_LIMIT = 1;
    public static final int MAX_QUEUE_LIMIT = 5;
    public static final int DEFAULT_QUEUE_LIMIT = 3;
    private static final int SCHEMA_VERSION = 1;
    private static final SavedData.Factory<PlayerSettlementCustomizationSavedData> FACTORY =
            new SavedData.Factory<>(
                    PlayerSettlementCustomizationSavedData::new,
                    PlayerSettlementCustomizationSavedData::load);

    private int size;
    private long nextRevision = 1L;
    private long[] ownerMost = new long[8];
    private long[] ownerLeast = new long[8];
    private long[] settlementMost = new long[8];
    private long[] settlementLeast = new long[8];
    private String[] names = new String[8];
    private String[] villageTypes = new String[8];
    private byte[] profiles = new byte[8];
    private boolean[] automatic = new boolean[8];
    private byte[] queueLimits = new byte[8];
    private long[] revisions = new long[8];

    public static PlayerSettlementCustomizationSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public int size() { return size; }

    public boolean exists(UUID owner) { return findOwner(owner) >= 0; }

    public boolean found(
            UUID owner,
            UUID settlement,
            ResourceLocation villageType,
            String name) {
        if (owner == null || settlement == null || villageType == null) throw new NullPointerException();
        String validatedName = normalizeName(name);
        if (findOwner(owner) >= 0 || findSettlement(settlement) >= 0) return false;
        if (size == MAX_SETTLEMENTS) return false;
        ensureCapacity(size + 1);
        int row = size++;
        ownerMost[row] = owner.getMostSignificantBits();
        ownerLeast[row] = owner.getLeastSignificantBits();
        settlementMost[row] = settlement.getMostSignificantBits();
        settlementLeast[row] = settlement.getLeastSignificantBits();
        names[row] = validatedName;
        villageTypes[row] = villageType.toString();
        profiles[row] = (byte) PlayerSettlementProfile.BALANCED.ordinal();
        automatic[row] = true;
        queueLimits[row] = (byte) DEFAULT_QUEUE_LIMIT;
        revisions[row] = claimRevision();
        setDirty();
        return true;
    }

    public boolean rename(UUID owner, String name) {
        int row = findOwner(owner);
        if (row < 0) return false;
        String validated = normalizeName(name);
        if (validated.equals(names[row])) return true;
        names[row] = validated;
        changed(row);
        return true;
    }

    public boolean setProfile(UUID owner, PlayerSettlementProfile profile) {
        if (profile == null) throw new NullPointerException("profile");
        int row = findOwner(owner);
        if (row < 0) return false;
        if (Byte.toUnsignedInt(profiles[row]) == profile.ordinal()) return true;
        profiles[row] = (byte) profile.ordinal();
        changed(row);
        return true;
    }

    public boolean setAutomatic(UUID owner, boolean enabled) {
        int row = findOwner(owner);
        if (row < 0) return false;
        if (automatic[row] == enabled) return true;
        automatic[row] = enabled;
        changed(row);
        return true;
    }

    public boolean setQueueLimit(UUID owner, int limit) {
        validateQueueLimit(limit);
        int row = findOwner(owner);
        if (row < 0) return false;
        if (Byte.toUnsignedInt(queueLimits[row]) == limit) return true;
        queueLimits[row] = (byte) limit;
        changed(row);
        return true;
    }

    public boolean read(UUID owner, View destination) {
        if (destination == null) throw new NullPointerException("destination");
        int row = findOwner(owner);
        if (row < 0) return false;
        copy(row, destination);
        return true;
    }

    public boolean readSettlement(UUID settlement, View destination) {
        if (destination == null) throw new NullPointerException("destination");
        int row = findSettlement(settlement);
        if (row < 0) return false;
        copy(row, destination);
        return true;
    }

    /** Visits at most {@code limit} physical rows and returns the next cyclic cursor. */
    public int visitAutomatic(int startRow, int limit, Visitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        if (limit <= 0) throw new IllegalArgumentException("Non-positive visit limit");
        if (size == 0) return 0;
        int row = Math.floorMod(startRow, size);
        int inspected = 0;
        while (inspected < Math.min(limit, size)) {
            if (automatic[row]) {
                visitor.accept(
                        ownerMost[row], ownerLeast[row],
                        settlementMost[row], settlementLeast[row],
                        PlayerSettlementProfile.values()[Byte.toUnsignedInt(profiles[row])],
                        Byte.toUnsignedInt(queueLimits[row]));
            }
            row = row + 1 == size ? 0 : row + 1;
            inspected++;
        }
        return row;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putLong("NextRevision", nextRevision);
        ListTag settlements = new ListTag();
        for (int row = 0; row < size; row++) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Owner", new UUID(ownerMost[row], ownerLeast[row]));
            entry.putUUID("Settlement", new UUID(settlementMost[row], settlementLeast[row]));
            entry.putString("Name", names[row]);
            entry.putString("VillageType", villageTypes[row]);
            entry.putByte("Profile", profiles[row]);
            entry.putBoolean("Automatic", automatic[row]);
            entry.putByte("QueueLimit", queueLimits[row]);
            entry.putLong("Revision", revisions[row]);
            settlements.add(entry);
        }
        tag.put("Settlements", settlements);
        return tag;
    }

    static PlayerSettlementCustomizationSavedData load(
            CompoundTag tag,
            HolderLookup.Provider registries) {
        if (tag.getInt("SchemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported player settlement customization schema "
                            + tag.getInt("SchemaVersion"));
        }
        ListTag settlements = tag.getList("Settlements", Tag.TAG_COMPOUND);
        if (settlements.size() > MAX_SETTLEMENTS) {
            throw new IllegalArgumentException("Too many player settlement customizations");
        }
        PlayerSettlementCustomizationSavedData data =
                new PlayerSettlementCustomizationSavedData();
        data.ensureCapacity(settlements.size());
        long maximumRevision = 0L;
        for (int row = 0; row < settlements.size(); row++) {
            CompoundTag entry = settlements.getCompound(row);
            UUID owner = entry.getUUID("Owner");
            UUID settlement = entry.getUUID("Settlement");
            ResourceLocation villageType = ResourceLocation.tryParse(entry.getString("VillageType"));
            int profile = Byte.toUnsignedInt(entry.getByte("Profile"));
            int queueLimit = Byte.toUnsignedInt(entry.getByte("QueueLimit"));
            long revision = entry.getLong("Revision");
            if (data.findOwner(owner) >= 0 || data.findSettlement(settlement) >= 0
                    || villageType == null
                    || profile >= PlayerSettlementProfile.values().length
                    || queueLimit < MIN_QUEUE_LIMIT || queueLimit > MAX_QUEUE_LIMIT
                    || revision <= 0L) {
                throw new IllegalArgumentException(
                        "Invalid player settlement customization row " + row);
            }
            data.ownerMost[row] = owner.getMostSignificantBits();
            data.ownerLeast[row] = owner.getLeastSignificantBits();
            data.settlementMost[row] = settlement.getMostSignificantBits();
            data.settlementLeast[row] = settlement.getLeastSignificantBits();
            data.names[row] = normalizeName(entry.getString("Name"));
            data.villageTypes[row] = villageType.toString();
            data.profiles[row] = (byte) profile;
            data.automatic[row] = entry.getBoolean("Automatic");
            data.queueLimits[row] = (byte) queueLimit;
            data.revisions[row] = revision;
            data.size++;
            maximumRevision = Math.max(maximumRevision, revision);
        }
        long next = tag.getLong("NextRevision");
        data.nextRevision = Math.max(maximumRevision + 1L, next <= 0L ? 1L : next);
        data.setDirty(false);
        return data;
    }

    public static String normalizeName(String value) {
        if (value == null) throw new NullPointerException("name");
        String name = value.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Settlement name length outside 1.." + MAX_NAME_LENGTH);
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new IllegalArgumentException(
                        "Settlement name contains control characters");
            }
        }
        return name;
    }

    private void copy(int row, View destination) {
        destination.owner = new UUID(ownerMost[row], ownerLeast[row]);
        destination.settlement = new UUID(settlementMost[row], settlementLeast[row]);
        destination.name = names[row];
        destination.villageType = ResourceLocation.parse(villageTypes[row]);
        destination.profile = PlayerSettlementProfile.values()[Byte.toUnsignedInt(profiles[row])];
        destination.automatic = automatic[row];
        destination.queueLimit = Byte.toUnsignedInt(queueLimits[row]);
        destination.revision = revisions[row];
    }

    private int findOwner(UUID owner) {
        if (owner == null) return -1;
        long most = owner.getMostSignificantBits();
        long least = owner.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (ownerMost[row] == most && ownerLeast[row] == least) return row;
        }
        return -1;
    }

    private int findSettlement(UUID settlement) {
        if (settlement == null) return -1;
        long most = settlement.getMostSignificantBits();
        long least = settlement.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (settlementMost[row] == most && settlementLeast[row] == least) return row;
        }
        return -1;
    }

    private void changed(int row) {
        revisions[row] = claimRevision();
        setDirty();
    }

    private long claimRevision() {
        if (nextRevision == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Player settlement customization revision space exhausted");
        }
        return nextRevision++;
    }

    private void ensureCapacity(int required) {
        if (required <= ownerMost.length) return;
        int capacity = Math.min(
                MAX_SETTLEMENTS,
                Math.max(required, ownerMost.length + (ownerMost.length >>> 1)));
        ownerMost = Arrays.copyOf(ownerMost, capacity);
        ownerLeast = Arrays.copyOf(ownerLeast, capacity);
        settlementMost = Arrays.copyOf(settlementMost, capacity);
        settlementLeast = Arrays.copyOf(settlementLeast, capacity);
        names = Arrays.copyOf(names, capacity);
        villageTypes = Arrays.copyOf(villageTypes, capacity);
        profiles = Arrays.copyOf(profiles, capacity);
        automatic = Arrays.copyOf(automatic, capacity);
        queueLimits = Arrays.copyOf(queueLimits, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
    }

    private static void validateQueueLimit(int limit) {
        if (limit < MIN_QUEUE_LIMIT || limit > MAX_QUEUE_LIMIT) {
            throw new IllegalArgumentException(
                    "Queue limit outside " + MIN_QUEUE_LIMIT + ".." + MAX_QUEUE_LIMIT);
        }
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(
                long ownerMost,
                long ownerLeast,
                long settlementMost,
                long settlementLeast,
                PlayerSettlementProfile profile,
                int queueLimit);
    }

    public static final class View {
        private UUID owner;
        private UUID settlement;
        private String name;
        private ResourceLocation villageType;
        private PlayerSettlementProfile profile;
        private boolean automatic;
        private int queueLimit;
        private long revision;

        public UUID owner() { return owner; }
        public UUID settlement() { return settlement; }
        public String name() { return name; }
        public ResourceLocation villageType() { return villageType; }
        public PlayerSettlementProfile profile() { return profile; }
        public boolean automatic() { return automatic; }
        public int queueLimit() { return queueLimit; }
        public long revision() { return revision; }
    }
}
