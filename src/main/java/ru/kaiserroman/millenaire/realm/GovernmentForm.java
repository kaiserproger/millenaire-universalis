package ru.kaiserroman.millenaire.realm;

/**
 * High-level constitutional archetypes. They are attractors, not a hard historical tech tree:
 * a realm may modernise, regress, or stabilise in an antique-style polity when its institutions
 * support it.
 */
public enum GovernmentForm {
    CLAN_CONFEDERATION(220, 120, 450, 180, 580, 220, 350, 450),
    FEUDAL_MONARCHY(620, 250, 820, 180, 120, 280, 820, 600),
    ESTATE_MONARCHY(650, 420, 650, 430, 300, 480, 650, 540),
    BUREAUCRATIC_MONARCHY(820, 820, 380, 420, 180, 620, 470, 650),
    COMMERCIAL_MONARCHY(760, 680, 350, 720, 240, 820, 380, 540),
    MERCHANT_REPUBLIC(520, 650, 180, 900, 620, 920, 250, 380),
    CITY_LEAGUE(300, 420, 160, 820, 720, 880, 220, 320),
    CITIZEN_POLITY(360, 500, 100, 520, 920, 700, 160, 550),
    OLIGARCHIC_POLITY(480, 520, 280, 760, 320, 780, 520, 480),
    MILITARY_AUTOCRACY(900, 560, 420, 260, 80, 350, 600, 940);

    private final int centralization;
    private final int bureaucracy;
    private final int noblePower;
    private final int merchantPower;
    private final int citizenPower;
    private final int marketFreedom;
    private final int landConcentration;
    private final int militarization;

    GovernmentForm(
            int centralization,
            int bureaucracy,
            int noblePower,
            int merchantPower,
            int citizenPower,
            int marketFreedom,
            int landConcentration,
            int militarization) {
        this.centralization = centralization;
        this.bureaucracy = bureaucracy;
        this.noblePower = noblePower;
        this.merchantPower = merchantPower;
        this.citizenPower = citizenPower;
        this.marketFreedom = marketFreedom;
        this.landConcentration = landConcentration;
        this.militarization = militarization;
    }

    public int centralization() { return centralization; }
    public int bureaucracy() { return bureaucracy; }
    public int noblePower() { return noblePower; }
    public int merchantPower() { return merchantPower; }
    public int citizenPower() { return citizenPower; }
    public int marketFreedom() { return marketFreedom; }
    public int landConcentration() { return landConcentration; }
    public int militarization() { return militarization; }
}
