package ru.kaiserroman.millenairearmies.integration.millenaire;

/**
 * Allocation-free boundary between recruitment and the faction projection.
 *
 * <p>The recruitment layer intentionally does not hash a culture id into a faction id. The
 * projection owns that mapping and installs a policy which answers from its primitive village
 * index. Until then non-operator recruitment is denied. Operator bypass is applied by the
 * server-authoritative recruitment service, never by this policy.</p>
 */
@FunctionalInterface
public interface RecruitmentFactionPolicy {
    RecruitmentFactionPolicy DENY_ALL = (armyFactionId, villageUuidMost, villageUuidLeast) -> false;

    boolean villageBelongsToFaction(int armyFactionId, long villageUuidMost, long villageUuidLeast);

    /** Resolves the stable faction for a server-validated settlement, or {@code -1} when unknown. */
    default int factionForVillage(long villageUuidMost, long villageUuidLeast) {
        return -1;
    }
}
