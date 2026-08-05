package ru.kaiserroman.millenaire.simulation;

/** Bounded population movement causes used by the pure Simulation kernel. */
public enum MigrationReason {
    WAR(350, 250, 1 << 9),
    FAMINE(300, 0, 1 << 10),
    EPIDEMIC(220, 250, 1 << 11),
    ECONOMIC(120, 1000, 1 << 12),
    RESETTLEMENT(500, 0, 1 << 13);

    private final int maximumSharePermille;
    private final int minimumViableRetentionPermille;
    private final int reasonMask;

    MigrationReason(
            int maximumSharePermille,
            int minimumViableRetentionPermille,
            int reasonMask) {
        this.maximumSharePermille = maximumSharePermille;
        this.minimumViableRetentionPermille = minimumViableRetentionPermille;
        this.reasonMask = reasonMask;
    }

    public int maximumSharePermille() { return maximumSharePermille; }
    public int minimumViableRetentionPermille() { return minimumViableRetentionPermille; }
    public int reasonMask() { return reasonMask; }
}
