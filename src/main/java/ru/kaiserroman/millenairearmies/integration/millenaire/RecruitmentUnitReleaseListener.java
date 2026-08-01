package ru.kaiserroman.millenairearmies.integration.millenaire;

/** Runtime-only hook used to relinquish an addon-owned Millenaire task before membership removal. */
@FunctionalInterface
public interface RecruitmentUnitReleaseListener {
    RecruitmentUnitReleaseListener NOOP = (unitHandle, uuidMost, uuidLeast) -> {};

    void releasing(int unitHandle, long uuidMost, long uuidLeast);
}
