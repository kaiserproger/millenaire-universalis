package ru.kaiserroman.millenaire.realm;

import java.util.Arrays;

/**
 * Bounded packed registry for player, mixed and NPC-only realms. External integrations own names,
 * culture distributions and persistence codecs; this core owns identity and membership invariants.
 */
public final class RealmRegistry {
    public static final long NO_REALM = 0L;

    private final int maximumRealms;
    private final int maximumMembers;

    private int realmCount;
    private int memberCount;
    private long nextRealmId = 1L;
    private long revision;

    private long[] realmIds;
    private long[] capitalMemberIds;
    private long[] foundedCycles;
    private byte[] governments;
    private int[] legitimacies;

    private long[] memberIds;
    private long[] memberRealmIds;
    private long[] controllerIds;
    private byte[] memberKinds;
    private int[] memberInfluences;

    public RealmRegistry(int maximumRealms, int maximumMembers) {
        if (maximumRealms <= 0 || maximumMembers <= 0 || maximumMembers < maximumRealms) {
            throw new IllegalArgumentException("Invalid realm registry bounds");
        }
        this.maximumRealms = maximumRealms;
        this.maximumMembers = maximumMembers;
        int realmCapacity = Math.min(8, maximumRealms);
        int memberCapacity = Math.min(16, maximumMembers);
        realmIds = new long[realmCapacity];
        capitalMemberIds = new long[realmCapacity];
        foundedCycles = new long[realmCapacity];
        governments = new byte[realmCapacity];
        legitimacies = new int[realmCapacity];
        memberIds = new long[memberCapacity];
        memberRealmIds = new long[memberCapacity];
        controllerIds = new long[memberCapacity];
        memberKinds = new byte[memberCapacity];
        memberInfluences = new int[memberCapacity];
    }

    public long createRealm(
            long capitalMemberId,
            RealmMemberKind capitalKind,
            long controllerId,
            GovernmentForm government,
            int legitimacy,
            long foundedCycle) {
        requireMember(capitalMemberId, capitalKind, controllerId, 0);
        if (government == null) throw new NullPointerException("government");
        requireIndex(legitimacy, "legitimacy");
        if (foundedCycle < 0L) throw new IllegalArgumentException("Negative foundedCycle");
        if (findMember(capitalMemberId) >= 0 || realmCount == maximumRealms
                || memberCount == maximumMembers) {
            return NO_REALM;
        }
        ensureRealmCapacity(realmCount + 1);
        ensureMemberCapacity(memberCount + 1);
        long realmId = claimRealmId();
        int realmRow = realmCount++;
        realmIds[realmRow] = realmId;
        capitalMemberIds[realmRow] = capitalMemberId;
        foundedCycles[realmRow] = foundedCycle;
        governments[realmRow] = (byte) government.ordinal();
        legitimacies[realmRow] = legitimacy;
        appendMember(realmId, capitalMemberId, capitalKind, controllerId, 1000);
        changed();
        return realmId;
    }

    public boolean addMember(
            long realmId,
            long memberId,
            RealmMemberKind kind,
            long controllerId,
            int influence) {
        requireMember(memberId, kind, controllerId, influence);
        if (findRealm(realmId) < 0 || findMember(memberId) >= 0 || memberCount == maximumMembers) {
            return false;
        }
        ensureMemberCapacity(memberCount + 1);
        appendMember(realmId, memberId, kind, controllerId, influence);
        changed();
        return true;
    }

    public boolean transferMember(long memberId, long targetRealmId) {
        int memberRow = findMember(memberId);
        int targetRealmRow = findRealm(targetRealmId);
        if (memberRow < 0 || targetRealmRow < 0 || memberRealmIds[memberRow] == targetRealmId) {
            return memberRow >= 0 && targetRealmRow >= 0;
        }
        int sourceRealmRow = findRealm(memberRealmIds[memberRow]);
        if (sourceRealmRow < 0 || capitalMemberIds[sourceRealmRow] == memberId) {
            return false;
        }
        memberRealmIds[memberRow] = targetRealmId;
        changed();
        return true;
    }

    public boolean removeMember(long memberId) {
        int memberRow = findMember(memberId);
        if (memberRow < 0) return false;
        int realmRow = findRealm(memberRealmIds[memberRow]);
        if (realmRow < 0 || capitalMemberIds[realmRow] == memberId) return false;
        removeMemberAt(memberRow);
        changed();
        return true;
    }

    public boolean setCapital(long realmId, long memberId) {
        int realmRow = findRealm(realmId);
        int memberRow = findMember(memberId);
        if (realmRow < 0 || memberRow < 0 || memberRealmIds[memberRow] != realmId) return false;
        if (capitalMemberIds[realmRow] != memberId) {
            capitalMemberIds[realmRow] = memberId;
            changed();
        }
        return true;
    }

    /** Updates a non-capital member in place, including capture-driven realm transfer. */
    public boolean updateMember(
            long memberId,
            long realmId,
            RealmMemberKind kind,
            long controllerId,
            int influence) {
        requireMember(memberId, kind, controllerId, influence);
        int memberRow = findMember(memberId);
        int targetRealmRow = findRealm(realmId);
        if (memberRow < 0 || targetRealmRow < 0) return false;
        int sourceRealmRow = findRealm(memberRealmIds[memberRow]);
        if (sourceRealmRow < 0
                || capitalMemberIds[sourceRealmRow] == memberId && memberRealmIds[memberRow] != realmId) {
            return false;
        }
        byte kindCode = (byte) kind.ordinal();
        if (memberRealmIds[memberRow] != realmId
                || memberKinds[memberRow] != kindCode
                || controllerIds[memberRow] != controllerId
                || memberInfluences[memberRow] != influence) {
            memberRealmIds[memberRow] = realmId;
            memberKinds[memberRow] = kindCode;
            controllerIds[memberRow] = controllerId;
            memberInfluences[memberRow] = influence;
            changed();
        }
        return true;
    }

    public boolean dissolveRealm(long realmId) {
        int realmRow = findRealm(realmId);
        if (realmRow < 0) return false;
        for (int row = memberCount - 1; row >= 0; row--) {
            if (memberRealmIds[row] == realmId) removeMemberAt(row);
        }
        removeRealmAt(realmRow);
        changed();
        return true;
    }

    public boolean setGovernment(long realmId, GovernmentForm government) {
        if (government == null) throw new NullPointerException("government");
        int row = findRealm(realmId);
        if (row < 0) return false;
        byte value = (byte) government.ordinal();
        if (governments[row] != value) {
            governments[row] = value;
            changed();
        }
        return true;
    }

    public boolean setLegitimacy(long realmId, int legitimacy) {
        requireIndex(legitimacy, "legitimacy");
        int row = findRealm(realmId);
        if (row < 0) return false;
        if (legitimacies[row] != legitimacy) {
            legitimacies[row] = legitimacy;
            changed();
        }
        return true;
    }

    public long realmOfMember(long memberId) {
        int row = findMember(memberId);
        return row < 0 ? NO_REALM : memberRealmIds[row];
    }

    public RealmMemberKind memberKind(long memberId) {
        int row = findMember(memberId);
        return row < 0 ? null : RealmMemberKind.values()[Byte.toUnsignedInt(memberKinds[row])];
    }

    public long memberControllerId(long memberId) {
        int row = findMember(memberId);
        return row < 0 ? 0L : controllerIds[row];
    }

    public int memberInfluence(long memberId) {
        int row = findMember(memberId);
        return row < 0 ? 0 : memberInfluences[row];
    }

    public boolean exists(long realmId) { return findRealm(realmId) >= 0; }
    public int realmCount() { return realmCount; }
    public int memberCount() { return memberCount; }
    public long revision() { return revision; }

    public long capitalMemberId(long realmId) {
        int row = findRealm(realmId);
        return row < 0 ? 0L : capitalMemberIds[row];
    }

    public long foundedCycle(long realmId) {
        int row = findRealm(realmId);
        return row < 0 ? -1L : foundedCycles[row];
    }

    public GovernmentForm government(long realmId) {
        int row = findRealm(realmId);
        return row < 0 ? null : GovernmentForm.values()[Byte.toUnsignedInt(governments[row])];
    }

    public int legitimacy(long realmId) {
        int row = findRealm(realmId);
        return row < 0 ? 0 : legitimacies[row];
    }

    public int memberCount(long realmId) {
        int count = 0;
        for (int row = 0; row < memberCount; row++) {
            if (memberRealmIds[row] == realmId) count++;
        }
        return count;
    }

    public int settlementCount(long realmId) {
        int count = 0;
        for (int row = 0; row < memberCount; row++) {
            if (memberRealmIds[row] == realmId
                    && memberKinds[row] != (byte) RealmMemberKind.PLAYER.ordinal()) {
                count++;
            }
        }
        return count;
    }

    public boolean hasPlayerMembers(long realmId) {
        for (int row = 0; row < memberCount; row++) {
            if (memberRealmIds[row] == realmId
                    && (memberKinds[row] == (byte) RealmMemberKind.PLAYER.ordinal()
                            || memberKinds[row] == (byte) RealmMemberKind.PLAYER_SETTLEMENT.ordinal())) {
                return true;
            }
        }
        return false;
    }

    public boolean mayControllerCommand(long realmId, long controllerId, long settlementMemberId) {
        int memberRow = findMember(settlementMemberId);
        if (memberRow < 0 || memberRealmIds[memberRow] != realmId) return false;
        if (controllerIds[memberRow] == controllerId && controllerId != 0L) return true;
        int capitalRow = findMember(capitalMemberId(realmId));
        return capitalRow >= 0 && controllerIds[capitalRow] == controllerId && controllerId != 0L;
    }

    public void visitMembers(long realmId, MemberVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < memberCount; row++) {
            if (memberRealmIds[row] == realmId) {
                visitor.accept(
                        memberIds[row],
                        RealmMemberKind.values()[Byte.toUnsignedInt(memberKinds[row])],
                        controllerIds[row],
                        memberInfluences[row]);
            }
        }
    }

    public void visitRealms(RealmVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < realmCount; row++) {
            visitor.accept(
                    realmIds[row],
                    capitalMemberIds[row],
                    foundedCycles[row],
                    GovernmentForm.values()[Byte.toUnsignedInt(governments[row])],
                    legitimacies[row]);
        }
    }

    public void visitAllMembers(AllMemberVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < memberCount; row++) {
            visitor.accept(
                    memberRealmIds[row],
                    memberIds[row],
                    RealmMemberKind.values()[Byte.toUnsignedInt(memberKinds[row])],
                    controllerIds[row],
                    memberInfluences[row]);
        }
    }

    /** Cold persistence hook: realm rows must be restored before member rows. */
    public void restoreRealm(
            long realmId,
            long capitalMemberId,
            long foundedCycle,
            GovernmentForm government,
            int legitimacy) {
        if (realmId <= 0L || capitalMemberId <= 0L || foundedCycle < 0L || government == null) {
            throw new IllegalArgumentException("Invalid restored realm row");
        }
        requireIndex(legitimacy, "legitimacy");
        if (findRealm(realmId) >= 0 || realmCount == maximumRealms) {
            throw new IllegalArgumentException("Duplicate or excessive restored realm " + realmId);
        }
        ensureRealmCapacity(realmCount + 1);
        int row = realmCount++;
        realmIds[row] = realmId;
        capitalMemberIds[row] = capitalMemberId;
        foundedCycles[row] = foundedCycle;
        governments[row] = (byte) government.ordinal();
        legitimacies[row] = legitimacy;
    }

    public void restoreMember(
            long realmId,
            long memberId,
            RealmMemberKind kind,
            long controllerId,
            int influence) {
        requireMember(memberId, kind, controllerId, influence);
        if (findRealm(realmId) < 0 || findMember(memberId) >= 0 || memberCount == maximumMembers) {
            throw new IllegalArgumentException("Invalid restored realm member " + memberId);
        }
        ensureMemberCapacity(memberCount + 1);
        appendMember(realmId, memberId, kind, controllerId, influence);
    }

    public void finishRestore(long restoredNextRealmId, long restoredRevision) {
        long maximumRealmId = 0L;
        for (int row = 0; row < realmCount; row++) {
            maximumRealmId = Math.max(maximumRealmId, realmIds[row]);
            int capitalRow = findMember(capitalMemberIds[row]);
            if (capitalRow < 0 || memberRealmIds[capitalRow] != realmIds[row]) {
                throw new IllegalArgumentException("Restored realm has no valid capital member");
            }
        }
        if (restoredNextRealmId <= maximumRealmId || restoredRevision < 0L) {
            throw new IllegalArgumentException("Invalid restored realm registry metadata");
        }
        nextRealmId = restoredNextRealmId;
        revision = restoredRevision;
    }

    public long nextRealmId() { return nextRealmId; }

    public int estimatedPrimitiveBytes() {
        return realmIds.length * Long.BYTES
                + capitalMemberIds.length * Long.BYTES
                + foundedCycles.length * Long.BYTES
                + governments.length
                + legitimacies.length * Integer.BYTES
                + memberIds.length * Long.BYTES
                + memberRealmIds.length * Long.BYTES
                + controllerIds.length * Long.BYTES
                + memberKinds.length
                + memberInfluences.length * Integer.BYTES;
    }

    private void appendMember(
            long realmId,
            long memberId,
            RealmMemberKind kind,
            long controllerId,
            int influence) {
        int row = memberCount++;
        memberRealmIds[row] = realmId;
        memberIds[row] = memberId;
        memberKinds[row] = (byte) kind.ordinal();
        controllerIds[row] = controllerId;
        memberInfluences[row] = influence;
    }

    private void removeRealmAt(int row) {
        int last = --realmCount;
        if (row != last) {
            realmIds[row] = realmIds[last];
            capitalMemberIds[row] = capitalMemberIds[last];
            foundedCycles[row] = foundedCycles[last];
            governments[row] = governments[last];
            legitimacies[row] = legitimacies[last];
        }
        realmIds[last] = 0L;
        capitalMemberIds[last] = 0L;
        foundedCycles[last] = 0L;
        governments[last] = 0;
        legitimacies[last] = 0;
    }

    private void removeMemberAt(int row) {
        int last = --memberCount;
        if (row != last) {
            memberIds[row] = memberIds[last];
            memberRealmIds[row] = memberRealmIds[last];
            controllerIds[row] = controllerIds[last];
            memberKinds[row] = memberKinds[last];
            memberInfluences[row] = memberInfluences[last];
        }
        memberIds[last] = 0L;
        memberRealmIds[last] = 0L;
        controllerIds[last] = 0L;
        memberKinds[last] = 0;
        memberInfluences[last] = 0;
    }

    private int findRealm(long realmId) {
        if (realmId <= 0L) return -1;
        for (int row = 0; row < realmCount; row++) {
            if (realmIds[row] == realmId) return row;
        }
        return -1;
    }

    private int findMember(long memberId) {
        if (memberId <= 0L) return -1;
        for (int row = 0; row < memberCount; row++) {
            if (memberIds[row] == memberId) return row;
        }
        return -1;
    }

    private long claimRealmId() {
        if (nextRealmId == Long.MAX_VALUE) {
            throw new IllegalStateException("Realm id space exhausted");
        }
        return nextRealmId++;
    }

    private void changed() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Realm registry revision exhausted");
        }
        revision++;
    }

    private void ensureRealmCapacity(int required) {
        if (required <= realmIds.length) return;
        int capacity = Math.min(maximumRealms, Math.max(required, realmIds.length + Math.max(1, realmIds.length >>> 1)));
        realmIds = Arrays.copyOf(realmIds, capacity);
        capitalMemberIds = Arrays.copyOf(capitalMemberIds, capacity);
        foundedCycles = Arrays.copyOf(foundedCycles, capacity);
        governments = Arrays.copyOf(governments, capacity);
        legitimacies = Arrays.copyOf(legitimacies, capacity);
    }

    private void ensureMemberCapacity(int required) {
        if (required <= memberIds.length) return;
        int capacity = Math.min(maximumMembers, Math.max(required, memberIds.length + Math.max(1, memberIds.length >>> 1)));
        memberIds = Arrays.copyOf(memberIds, capacity);
        memberRealmIds = Arrays.copyOf(memberRealmIds, capacity);
        controllerIds = Arrays.copyOf(controllerIds, capacity);
        memberKinds = Arrays.copyOf(memberKinds, capacity);
        memberInfluences = Arrays.copyOf(memberInfluences, capacity);
    }

    private static void requireMember(
            long memberId,
            RealmMemberKind kind,
            long controllerId,
            int influence) {
        if (memberId <= 0L || kind == null || controllerId < 0L) {
            throw new IllegalArgumentException("Invalid realm member");
        }
        requireIndex(influence, "influence");
        if (kind == RealmMemberKind.PLAYER && controllerId == 0L) {
            throw new IllegalArgumentException("Player member requires a controller");
        }
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }

    @FunctionalInterface
    public interface MemberVisitor {
        void accept(long memberId, RealmMemberKind kind, long controllerId, int influence);
    }

    @FunctionalInterface
    public interface RealmVisitor {
        void accept(
                long realmId,
                long capitalMemberId,
                long foundedCycle,
                GovernmentForm government,
                int legitimacy);
    }

    @FunctionalInterface
    public interface AllMemberVisitor {
        void accept(
                long realmId,
                long memberId,
                RealmMemberKind kind,
                long controllerId,
                int influence);
    }
}
