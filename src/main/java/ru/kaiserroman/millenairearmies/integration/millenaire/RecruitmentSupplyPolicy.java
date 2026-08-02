package ru.kaiserroman.millenairearmies.integration.millenaire;

/** Atomic settlement-stock gate for turning existing residents into army units. */
public interface RecruitmentSupplyPolicy {
    RecruitmentSupplyPolicy ALLOW_ALL = new RecruitmentSupplyPolicy() {
        @Override
        public boolean tryConsumeRecruitmentKits(long villageMost, long villageLeast, int count) {
            return count > 0;
        }

        @Override
        public void refundRecruitmentKits(long villageMost, long villageLeast, int count) {}
    };

    boolean tryConsumeRecruitmentKits(long villageMost, long villageLeast, int count);

    default boolean tryConsumeRecruitmentKit(long villageMost, long villageLeast) {
        return tryConsumeRecruitmentKits(villageMost, villageLeast, 1);
    }

    void refundRecruitmentKits(long villageMost, long villageLeast, int count);
}
