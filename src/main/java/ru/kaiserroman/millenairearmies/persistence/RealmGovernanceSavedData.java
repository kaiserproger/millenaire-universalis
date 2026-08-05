package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Normalized realm membership and settlement authority.
 *
 * <p>Every controller and every settlement can occur in at most one row. The capital row is always
 * controlled by the realm head. A feudal region remains directly commanded by its owner; a
 * governor remains the local controller, but the capital owner may also issue strategic orders for
 * that settlement.</p>
 */
public final class RealmGovernanceSavedData extends SavedData {
    public static final String FILE_ID = "millenaire_armies_realm_governance";
    public static final int MAX_ASSIGNMENTS = 16_384;

    public static final byte ROLE_NONE = 0;
    public static final byte ROLE_HEAD = 1;
    public static final byte ROLE_FEUDAL = 2;
    public static final byte ROLE_GOVERNOR = 3;

    public static final byte GOVERNMENT_FEUDAL = 1;
    public static final byte GOVERNMENT_ADMINISTRATIVE = 2;

    private static final int SCHEMA_VERSION = 1;
    private static final SavedData.Factory<RealmGovernanceSavedData> FACTORY =
            new SavedData.Factory<>(RealmGovernanceSavedData::new, RealmGovernanceSavedData::load);

    private int size;
    private long revision;
    private long[] headMost = new long[8];
    private long[] headLeast = new long[8];
    private long[] controllerMost = new long[8];
    private long[] controllerLeast = new long[8];
    private long[] villageMost = new long[8];
    private long[] villageLeast = new long[8];
    private byte[] roles = new byte[8];
    private byte[] governments = new byte[8];

    public static RealmGovernanceSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public int size() {
        return size;
    }

    public long revision() {
        return revision;
    }

    public boolean canFoundCapital(UUID owner, UUID capital) {
        return owner != null
                && capital != null
                && size < MAX_ASSIGNMENTS
                && findController(owner.getMostSignificantBits(), owner.getLeastSignificantBits()) < 0
                && findVillage(capital.getMostSignificantBits(), capital.getLeastSignificantBits()) < 0;
    }

    public boolean foundCapital(UUID owner, UUID capital, byte government) {
        requireGovernment(government);
        if (!canFoundCapital(owner, capital)) {
            return false;
        }
        append(
                owner.getMostSignificantBits(),
                owner.getLeastSignificantBits(),
                owner.getMostSignificantBits(),
                owner.getLeastSignificantBits(),
                capital.getMostSignificantBits(),
                capital.getLeastSignificantBits(),
                ROLE_HEAD,
                government);
        changed();
        return true;
    }

    public boolean canReserveRegion(UUID controller, UUID village) {
        if (controller == null || village == null || size == MAX_ASSIGNMENTS) return false;
        return findController(
                        controller.getMostSignificantBits(),
                        controller.getLeastSignificantBits()) < 0
                && findVillage(
                        village.getMostSignificantBits(),
                        village.getLeastSignificantBits()) < 0;
    }

    public boolean canAttachRegion(UUID head, UUID controller, UUID village) {
        return head != null && findCapital(head) >= 0 && canReserveRegion(controller, village);
    }

    /** Adds a region using the realm's default political relationship. */
    public boolean attachRegion(UUID head, UUID controller, UUID village) {
        int capital = findCapital(head);
        if (capital < 0) {
            return false;
        }
        byte role = governments[capital] == GOVERNMENT_ADMINISTRATIVE
                ? ROLE_GOVERNOR
                : ROLE_FEUDAL;
        return attachRegion(head, controller, village, role);
    }

    public boolean attachRegion(UUID head, UUID controller, UUID village, byte role) {
        requireRegionalRole(role);
        if (!canAttachRegion(head, controller, village)) {
            return false;
        }
        long controllerMostBits = controller.getMostSignificantBits();
        long controllerLeastBits = controller.getLeastSignificantBits();
        long villageMostBits = village.getMostSignificantBits();
        long villageLeastBits = village.getLeastSignificantBits();
        append(
                head.getMostSignificantBits(),
                head.getLeastSignificantBits(),
                controllerMostBits,
                controllerLeastBits,
                villageMostBits,
                villageLeastBits,
                role,
                (byte) 0);
        changed();
        return true;
    }

    public boolean setRegionalRole(UUID head, UUID village, byte role) {
        requireRegionalRole(role);
        if (head == null || village == null) {
            return false;
        }
        int row = findVillage(village.getMostSignificantBits(), village.getLeastSignificantBits());
        if (row < 0 || roles[row] == ROLE_HEAD || !sameHead(row, head)) {
            return false;
        }
        if (roles[row] != role) {
            roles[row] = role;
            changed();
        }
        return true;
    }

    public boolean removeRegion(UUID head, UUID village) {
        if (head == null || village == null) {
            return false;
        }
        int row = findVillage(village.getMostSignificantBits(), village.getLeastSignificantBits());
        if (row < 0 || roles[row] == ROLE_HEAD || !sameHead(row, head)) {
            return false;
        }
        removeAt(row);
        changed();
        return true;
    }

    public boolean readPlayer(UUID player, AssignmentView destination) {
        if (player == null || destination == null) {
            return false;
        }
        int row = findController(player.getMostSignificantBits(), player.getLeastSignificantBits());
        return readRow(row, destination);
    }

    public boolean readVillage(UUID village, AssignmentView destination) {
        if (village == null || destination == null) {
            return false;
        }
        int row = findVillage(village.getMostSignificantBits(), village.getLeastSignificantBits());
        return readRow(row, destination);
    }

    public boolean readCapital(UUID head, AssignmentView destination) {
        return readRow(findCapital(head), destination);
    }

    public int settlementCount(UUID head) {
        if (head == null) {
            return 0;
        }
        long most = head.getMostSignificantBits();
        long least = head.getLeastSignificantBits();
        int count = 0;
        for (int row = 0; row < size; row++) {
            if (headMost[row] == most && headLeast[row] == least) {
                count++;
            }
        }
        return count;
    }

    public int regionCount(UUID head) {
        return Math.max(0, settlementCount(head) - (findCapital(head) < 0 ? 0 : 1));
    }

    public byte government(UUID head) {
        int row = findCapital(head);
        return row < 0 ? 0 : governments[row];
    }

    /** Local controller always commands; the head additionally commands governor-led regions. */
    public boolean canCommandSettlement(UUID actor, UUID village) {
        if (actor == null || village == null) {
            return false;
        }
        int row = findVillage(village.getMostSignificantBits(), village.getLeastSignificantBits());
        if (row < 0) {
            return false;
        }
        long actorMost = actor.getMostSignificantBits();
        long actorLeast = actor.getLeastSignificantBits();
        if (controllerMost[row] == actorMost && controllerLeast[row] == actorLeast) {
            return true;
        }
        return roles[row] == ROLE_GOVERNOR
                && headMost[row] == actorMost
                && headLeast[row] == actorLeast;
    }

    /** Head-of-state delegation used by army authorization without allocating UUID objects. */
    public boolean canDirectController(
            long actorMost,
            long actorLeast,
            long regionalControllerMost,
            long regionalControllerLeast) {
        int actorRow = findController(actorMost, actorLeast);
        int regionalRow = findController(regionalControllerMost, regionalControllerLeast);
        return actorRow >= 0
                && regionalRow >= 0
                && roles[actorRow] == ROLE_HEAD
                && roles[regionalRow] == ROLE_GOVERNOR
                && headMost[actorRow] == headMost[regionalRow]
                && headLeast[actorRow] == headLeast[regionalRow];
    }

    public void visitRealm(UUID head, Visitor visitor) {
        if (head == null || visitor == null) {
            return;
        }
        long most = head.getMostSignificantBits();
        long least = head.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (headMost[row] == most && headLeast[row] == least) {
                visitor.accept(
                        controllerMost[row],
                        controllerLeast[row],
                        villageMost[row],
                        villageLeast[row],
                        roles[row]);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putLong("Revision", revision);
        ListTag rows = new ListTag();
        for (int row = 0; row < size; row++) {
            CompoundTag assignment = new CompoundTag();
            assignment.putLong("HeadMost", headMost[row]);
            assignment.putLong("HeadLeast", headLeast[row]);
            assignment.putLong("ControllerMost", controllerMost[row]);
            assignment.putLong("ControllerLeast", controllerLeast[row]);
            assignment.putLong("VillageMost", villageMost[row]);
            assignment.putLong("VillageLeast", villageLeast[row]);
            assignment.putByte("Role", roles[row]);
            assignment.putByte("Government", governments[row]);
            rows.add(assignment);
        }
        tag.put("Assignments", rows);
        return tag;
    }

    static RealmGovernanceSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("SchemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported realm governance schema " + tag.getInt("SchemaVersion"));
        }
        ListTag rows = tag.getList("Assignments", Tag.TAG_COMPOUND);
        if (rows.size() > MAX_ASSIGNMENTS) {
            throw new IllegalArgumentException("Too many realm assignments");
        }
        RealmGovernanceSavedData data = new RealmGovernanceSavedData();
        data.ensureCapacity(rows.size());
        for (int row = 0; row < rows.size(); row++) {
            CompoundTag assignment = rows.getCompound(row);
            long headMostBits = assignment.getLong("HeadMost");
            long headLeastBits = assignment.getLong("HeadLeast");
            long controllerMostBits = assignment.getLong("ControllerMost");
            long controllerLeastBits = assignment.getLong("ControllerLeast");
            long villageMostBits = assignment.getLong("VillageMost");
            long villageLeastBits = assignment.getLong("VillageLeast");
            byte role = assignment.getByte("Role");
            byte government = assignment.getByte("Government");
            if (!validRole(role)
                    || role == ROLE_HEAD && (government != GOVERNMENT_FEUDAL
                            && government != GOVERNMENT_ADMINISTRATIVE)
                    || role != ROLE_HEAD && government != 0
                    || role == ROLE_HEAD && (headMostBits != controllerMostBits
                            || headLeastBits != controllerLeastBits)
                    || data.findController(controllerMostBits, controllerLeastBits) >= 0
                    || data.findVillage(villageMostBits, villageLeastBits) >= 0) {
                throw new IllegalArgumentException("Invalid governance row " + row);
            }
            data.append(
                    headMostBits,
                    headLeastBits,
                    controllerMostBits,
                    controllerLeastBits,
                    villageMostBits,
                    villageLeastBits,
                    role,
                    government);
        }
        for (int row = 0; row < data.size; row++) {
            if (data.findCapital(data.headMost[row], data.headLeast[row]) < 0) {
                throw new IllegalArgumentException("Region without realm capital at row " + row);
            }
        }
        data.revision = Math.max(0L, tag.getLong("Revision"));
        return data;
    }

    private boolean readRow(int row, AssignmentView destination) {
        if (row < 0) {
            return false;
        }
        destination.headMost = headMost[row];
        destination.headLeast = headLeast[row];
        destination.controllerMost = controllerMost[row];
        destination.controllerLeast = controllerLeast[row];
        destination.villageMost = villageMost[row];
        destination.villageLeast = villageLeast[row];
        destination.role = roles[row];
        destination.government = government(new UUID(headMost[row], headLeast[row]));
        return true;
    }

    private void append(
            long headMostBits,
            long headLeastBits,
            long controllerMostBits,
            long controllerLeastBits,
            long villageMostBits,
            long villageLeastBits,
            byte role,
            byte government) {
        if (size == MAX_ASSIGNMENTS) {
            throw new IllegalStateException("Realm assignment limit reached");
        }
        ensureCapacity(size + 1);
        headMost[size] = headMostBits;
        headLeast[size] = headLeastBits;
        controllerMost[size] = controllerMostBits;
        controllerLeast[size] = controllerLeastBits;
        villageMost[size] = villageMostBits;
        villageLeast[size] = villageLeastBits;
        roles[size] = role;
        governments[size] = government;
        size++;
    }

    private void removeAt(int row) {
        int last = --size;
        if (row != last) {
            headMost[row] = headMost[last];
            headLeast[row] = headLeast[last];
            controllerMost[row] = controllerMost[last];
            controllerLeast[row] = controllerLeast[last];
            villageMost[row] = villageMost[last];
            villageLeast[row] = villageLeast[last];
            roles[row] = roles[last];
            governments[row] = governments[last];
        }
    }

    private int findCapital(UUID head) {
        return head == null ? -1 : findCapital(head.getMostSignificantBits(), head.getLeastSignificantBits());
    }

    private int findCapital(long most, long least) {
        for (int row = 0; row < size; row++) {
            if (roles[row] == ROLE_HEAD && headMost[row] == most && headLeast[row] == least) {
                return row;
            }
        }
        return -1;
    }

    private int findController(long most, long least) {
        for (int row = 0; row < size; row++) {
            if (controllerMost[row] == most && controllerLeast[row] == least) {
                return row;
            }
        }
        return -1;
    }

    private int findVillage(long most, long least) {
        for (int row = 0; row < size; row++) {
            if (villageMost[row] == most && villageLeast[row] == least) {
                return row;
            }
        }
        return -1;
    }

    private boolean sameHead(int row, UUID head) {
        return headMost[row] == head.getMostSignificantBits()
                && headLeast[row] == head.getLeastSignificantBits();
    }

    private void changed() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Realm governance revision exhausted");
        }
        revision++;
        setDirty();
    }

    private void ensureCapacity(int required) {
        if (required <= headMost.length) {
            return;
        }
        int capacity = Math.max(required, headMost.length + (headMost.length >>> 1));
        headMost = Arrays.copyOf(headMost, capacity);
        headLeast = Arrays.copyOf(headLeast, capacity);
        controllerMost = Arrays.copyOf(controllerMost, capacity);
        controllerLeast = Arrays.copyOf(controllerLeast, capacity);
        villageMost = Arrays.copyOf(villageMost, capacity);
        villageLeast = Arrays.copyOf(villageLeast, capacity);
        roles = Arrays.copyOf(roles, capacity);
        governments = Arrays.copyOf(governments, capacity);
    }

    private static void requireGovernment(byte government) {
        if (government != GOVERNMENT_FEUDAL && government != GOVERNMENT_ADMINISTRATIVE) {
            throw new IllegalArgumentException("Unknown realm government " + government);
        }
    }

    private static void requireRegionalRole(byte role) {
        if (role != ROLE_FEUDAL && role != ROLE_GOVERNOR) {
            throw new IllegalArgumentException("Unknown regional role " + role);
        }
    }

    private static boolean validRole(byte role) {
        return role == ROLE_HEAD || role == ROLE_FEUDAL || role == ROLE_GOVERNOR;
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(long controllerMost, long controllerLeast, long villageMost, long villageLeast, byte role);
    }

    public static final class AssignmentView {
        private long headMost;
        private long headLeast;
        private long controllerMost;
        private long controllerLeast;
        private long villageMost;
        private long villageLeast;
        private byte role;
        private byte government;

        public UUID head() { return new UUID(headMost, headLeast); }
        public UUID controller() { return new UUID(controllerMost, controllerLeast); }
        public UUID village() { return new UUID(villageMost, villageLeast); }
        public long villageMost() { return villageMost; }
        public long villageLeast() { return villageLeast; }
        public byte role() { return role; }
        public byte government() { return government; }
        public boolean isHead() { return role == ROLE_HEAD; }
    }
}
