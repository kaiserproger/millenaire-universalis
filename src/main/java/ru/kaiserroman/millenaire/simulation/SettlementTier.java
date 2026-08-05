package ru.kaiserroman.millenaire.simulation;

public enum SettlementTier {
    OUTPOST(1),
    HAMLET(20),
    VILLAGE(60),
    TOWN(180),
    CITY(500);

    private final int minimumPopulation;

    SettlementTier(int minimumPopulation) {
        this.minimumPopulation = minimumPopulation;
    }

    public int minimumPopulation() {
        return minimumPopulation;
    }

    public static SettlementTier forPopulation(long population) {
        SettlementTier result = OUTPOST;
        for (SettlementTier tier : values()) {
            if (population >= tier.minimumPopulation) {
                result = tier;
            }
        }
        return result;
    }
}
