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

/** Persistent player-founded realms: capital, tax policy, treasury and conquest history. */
public final class PlayerRealmSavedData extends SavedData {
    public static final String FILE_ID = "millenaire_armies_player_realms";
    public static final int MAX_REALMS = 4_096;
    public static final int MAX_NAME_LENGTH = 48;
    private static final int SCHEMA_VERSION = 1;
    private static final SavedData.Factory<PlayerRealmSavedData> FACTORY =
            new SavedData.Factory<>(PlayerRealmSavedData::new, PlayerRealmSavedData::load);

    private int size;
    private long nextRevision = 1L;
    private long[] ownerMost = new long[8];
    private long[] ownerLeast = new long[8];
    private long[] capitalMost = new long[8];
    private long[] capitalLeast = new long[8];
    private String[] names = new String[8];
    private String[] dimensions = new String[8];
    private byte[] taxRates = new byte[8];
    private long[] treasuries = new long[8];
    private long[] lastTaxTicks = new long[8];
    private int[] capturedSettlements = new int[8];
    private long[] revisions = new long[8];

    public static PlayerRealmSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public int size() { return size; }

    public boolean exists(UUID owner) {
        return owner != null && find(owner.getMostSignificantBits(), owner.getLeastSignificantBits()) >= 0;
    }

    public long revision(UUID owner) {
        int row = owner == null ? -1 : find(owner.getMostSignificantBits(), owner.getLeastSignificantBits());
        return row < 0 ? 0L : revisions[row];
    }

    public boolean found(
            UUID owner,
            String name,
            UUID capital,
            ResourceLocation dimension,
            long gameTime) {
        if (owner == null || capital == null || dimension == null) throw new NullPointerException();
        String validatedName = validateName(name);
        if (find(owner.getMostSignificantBits(), owner.getLeastSignificantBits()) >= 0) return false;
        if (size == MAX_REALMS) throw new IllegalStateException("Player realm limit reached");
        ensureCapacity(size + 1);
        int row = size++;
        ownerMost[row] = owner.getMostSignificantBits();
        ownerLeast[row] = owner.getLeastSignificantBits();
        capitalMost[row] = capital.getMostSignificantBits();
        capitalLeast[row] = capital.getLeastSignificantBits();
        names[row] = validatedName;
        dimensions[row] = dimension.toString();
        taxRates[row] = 10;
        lastTaxTicks[row] = Math.max(0L, gameTime);
        revisions[row] = claimRevision();
        setDirty();
        return true;
    }

    public boolean rename(UUID owner, String name) {
        int row = findOwner(owner);
        if (row < 0) return false;
        String validated = validateName(name);
        if (validated.equals(names[row])) return true;
        names[row] = validated;
        revisions[row] = claimRevision();
        setDirty();
        return true;
    }

    public boolean setTaxRate(UUID owner, int rate) {
        if (rate < 0 || rate > 25) throw new IllegalArgumentException("Tax rate outside 0..25");
        int row = findOwner(owner);
        if (row < 0) return false;
        if (Byte.toUnsignedInt(taxRates[row]) == rate) return true;
        taxRates[row] = (byte) rate;
        revisions[row] = claimRevision();
        setDirty();
        return true;
    }

    public void collectTaxes(long ownerMostBits, long ownerLeastBits, long amount, long gameTime) {
        if (amount < 0L) throw new IllegalArgumentException("Negative tax amount");
        int row = find(ownerMostBits, ownerLeastBits);
        if (row < 0) return;
        treasuries[row] = saturatedAdd(treasuries[row], amount);
        lastTaxTicks[row] = Math.max(lastTaxTicks[row], gameTime);
        revisions[row] = claimRevision();
        setDirty();
    }

    public void recordCapture(UUID owner) {
        int row = findOwner(owner);
        if (row < 0) return;
        if (capturedSettlements[row] != Integer.MAX_VALUE) capturedSettlements[row]++;
        revisions[row] = claimRevision();
        setDirty();
    }

    public boolean read(UUID owner, View destination) {
        if (destination == null) throw new NullPointerException("destination");
        int row = findOwner(owner);
        if (row < 0) return false;
        destination.name = names[row];
        destination.ownerMost = ownerMost[row];
        destination.ownerLeast = ownerLeast[row];
        destination.capitalMost = capitalMost[row];
        destination.capitalLeast = capitalLeast[row];
        destination.dimension = ResourceLocation.parse(dimensions[row]);
        destination.taxRate = Byte.toUnsignedInt(taxRates[row]);
        destination.treasury = treasuries[row];
        destination.lastTaxTick = lastTaxTicks[row];
        destination.capturedSettlements = capturedSettlements[row];
        destination.revision = revisions[row];
        return true;
    }

    public void visit(Visitor visitor) {
        for (int row = 0; row < size; row++) {
            visitor.accept(
                    ownerMost[row],
                    ownerLeast[row],
                    Byte.toUnsignedInt(taxRates[row]),
                    lastTaxTicks[row]);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putLong("NextRevision", nextRevision);
        ListTag realms = new ListTag();
        for (int row = 0; row < size; row++) {
            CompoundTag realm = new CompoundTag();
            realm.putLong("OwnerMost", ownerMost[row]);
            realm.putLong("OwnerLeast", ownerLeast[row]);
            realm.putLong("CapitalMost", capitalMost[row]);
            realm.putLong("CapitalLeast", capitalLeast[row]);
            realm.putString("Name", names[row]);
            realm.putString("Dimension", dimensions[row]);
            realm.putByte("TaxRate", taxRates[row]);
            realm.putLong("Treasury", treasuries[row]);
            realm.putLong("LastTaxTick", lastTaxTicks[row]);
            realm.putInt("CapturedSettlements", capturedSettlements[row]);
            realm.putLong("Revision", revisions[row]);
            realms.add(realm);
        }
        tag.put("Realms", realms);
        return tag;
    }

    static PlayerRealmSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("SchemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported player realm schema " + tag.getInt("SchemaVersion"));
        }
        ListTag realms = tag.getList("Realms", Tag.TAG_COMPOUND);
        if (realms.size() > MAX_REALMS) throw new IllegalArgumentException("Too many player realms");
        PlayerRealmSavedData data = new PlayerRealmSavedData();
        data.ensureCapacity(realms.size());
        long maximumRevision = 0L;
        for (int row = 0; row < realms.size(); row++) {
            CompoundTag realm = realms.getCompound(row);
            long most = realm.getLong("OwnerMost");
            long least = realm.getLong("OwnerLeast");
            if (data.find(most, least) >= 0) throw new IllegalArgumentException("Duplicate realm owner");
            ResourceLocation dimension = ResourceLocation.tryParse(realm.getString("Dimension"));
            int taxRate = Byte.toUnsignedInt(realm.getByte("TaxRate"));
            long treasury = realm.getLong("Treasury");
            long lastTaxTick = realm.getLong("LastTaxTick");
            int captured = realm.getInt("CapturedSettlements");
            long revision = realm.getLong("Revision");
            if (dimension == null || taxRate > 25 || treasury < 0L || lastTaxTick < 0L
                    || captured < 0 || revision <= 0L) {
                throw new IllegalArgumentException("Invalid realm row " + row);
            }
            data.ownerMost[row] = most;
            data.ownerLeast[row] = least;
            data.capitalMost[row] = realm.getLong("CapitalMost");
            data.capitalLeast[row] = realm.getLong("CapitalLeast");
            data.names[row] = validateName(realm.getString("Name"));
            data.dimensions[row] = dimension.toString();
            data.taxRates[row] = (byte) taxRate;
            data.treasuries[row] = treasury;
            data.lastTaxTicks[row] = lastTaxTick;
            data.capturedSettlements[row] = captured;
            data.revisions[row] = revision;
            data.size++;
            maximumRevision = Math.max(maximumRevision, revision);
        }
        long next = tag.getLong("NextRevision");
        data.nextRevision = Math.max(maximumRevision + 1L, next <= 0L ? 1L : next);
        return data;
    }

    private int findOwner(UUID owner) {
        return owner == null ? -1 : find(owner.getMostSignificantBits(), owner.getLeastSignificantBits());
    }

    private int find(long most, long least) {
        for (int row = 0; row < size; row++) {
            if (ownerMost[row] == most && ownerLeast[row] == least) return row;
        }
        return -1;
    }

    private long claimRevision() {
        if (nextRevision == Long.MAX_VALUE) throw new IllegalStateException("Realm revision space exhausted");
        return nextRevision++;
    }

    private void ensureCapacity(int required) {
        if (required <= ownerMost.length) return;
        int capacity = Math.max(required, ownerMost.length + (ownerMost.length >>> 1));
        ownerMost = Arrays.copyOf(ownerMost, capacity);
        ownerLeast = Arrays.copyOf(ownerLeast, capacity);
        capitalMost = Arrays.copyOf(capitalMost, capacity);
        capitalLeast = Arrays.copyOf(capitalLeast, capacity);
        names = Arrays.copyOf(names, capacity);
        dimensions = Arrays.copyOf(dimensions, capacity);
        taxRates = Arrays.copyOf(taxRates, capacity);
        treasuries = Arrays.copyOf(treasuries, capacity);
        lastTaxTicks = Arrays.copyOf(lastTaxTicks, capacity);
        capturedSettlements = Arrays.copyOf(capturedSettlements, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
    }

    private static String validateName(String value) {
        if (value == null) throw new NullPointerException("name");
        String name = value.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Realm name length outside 1.." + MAX_NAME_LENGTH);
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new IllegalArgumentException("Realm name contains control characters");
            }
        }
        return name;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(long ownerMost, long ownerLeast, int taxRate, long lastTaxTick);
    }

    public static final class View {
        private String name;
        private long ownerMost;
        private long ownerLeast;
        private long capitalMost;
        private long capitalLeast;
        private ResourceLocation dimension;
        private int taxRate;
        private long treasury;
        private long lastTaxTick;
        private int capturedSettlements;
        private long revision;

        public String name() { return name; }
        public long ownerMost() { return ownerMost; }
        public long ownerLeast() { return ownerLeast; }
        public long capitalMost() { return capitalMost; }
        public long capitalLeast() { return capitalLeast; }
        public ResourceLocation dimension() { return dimension; }
        public int taxRate() { return taxRate; }
        public long treasury() { return treasury; }
        public long lastTaxTick() { return lastTaxTick; }
        public int capturedSettlements() { return capturedSettlements; }
        public long revision() { return revision; }
    }
}
