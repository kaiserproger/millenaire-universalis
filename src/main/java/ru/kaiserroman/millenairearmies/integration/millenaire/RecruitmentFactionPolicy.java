package ru.kaiserroman.millenairearmies.integration.millenaire;

/**
 * Allocation-free boundary between recruitment and the faction projection.
 *
 * <p>The recruitment layer intentionally does not hash a culture id into a faction id. The
 * projection owns that mapping and installs a policy which answers from its primitive village
 * index. Until then recruitment is denied. Settlement ownership is checked independently and
 * never inferred from culture, faction, or operator status.</p>
 */
@FunctionalInterface
public interface RecruitmentFactionPolicy {
    RecruitmentFactionPolicy DENY_ALL = (armyFactionId, villageUuidMost, villageUuidLeast) -> false;

    boolean villageBelongsToFaction(int armyFactionId, long villageUuidMost, long villageUuidLeast);

    /** Stable projected faction id for a village, or a negative value when unavailable. */
    default int factionForVillage(long villageUuidMost, long villageUuidLeast) {
        return -1;
    }
}
