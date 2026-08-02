package ru.kaiserroman.millenairearmies.client;

/**
 * Read-mostly client view of the strategic simulation.
 *
 * <p>The UI never reads a level, entity, saved data, or Millenaire singleton directly. The
 * networking layer installs an implementation backed by its latest immutable/ping-pong snapshot.
 * Implementations should return stable strings and avoid allocating from getters because they are
 * called from the render loop. Indices are snapshot-local; ids remain stable across revisions.</p>
 *
 * <p>Command methods are intents only. A network adapter validates and sends them to the server;
 * returning {@code false} means that the current connection cannot accept the intent.</p>
 */
public interface ArmyClientMirror {
    ArmyClientMirror EMPTY = new ArmyClientMirror() {};

    default long revision() {
        return 0L;
    }

    /** Monotonic client presentation version; may advance for same-revision acknowledgements. */
    default long viewVersion() { return revision(); }

    default boolean isReady() {
        return false;
    }

    default String statusText() {
        return "";
    }

    default int playerFactionId() {
        return -1;
    }

    default String playerFactionName() {
        return "";
    }

    default int totalUnitCount() {
        return 0;
    }

    default int factionCount() {
        return 0;
    }

    default int factionId(int index) {
        return -1;
    }

    default String factionName(int index) {
        return "";
    }

    /** A cached, compact line such as "Norman • Allied • 3 settlements". */
    default String factionSummary(int index) {
        return "";
    }

    default String factionCultureName(int index) {
        return "";
    }

    default byte factionRelationCode(int index) {
        return 1;
    }

    default int factionReputation(int index) {
        return 0;
    }

    default int factionInfluence(int index) {
        return 0;
    }

    default int factionSettlementCount(int index) {
        return 0;
    }

    default int factionArmyCount(int index) {
        return 0;
    }

    default int factionPopulation(int index) {
        return 0;
    }

    default String factionCapitalName(int index) {
        return "";
    }

    default int armyCount() {
        return 0;
    }

    default int armyId(int index) {
        return -1;
    }

    default String armyName(int index) {
        return "";
    }

    /** A cached, compact line such as "42/55 ready • 71% supplies". */
    default String armySummary(int index) {
        return "";
    }

    default int armyFactionId(int index) {
        return -1;
    }

    default String armyFactionName(int index) {
        return "";
    }

    default int armyUnitCount(int index) {
        return 0;
    }

    default int armyReadyUnitCount(int index) {
        return 0;
    }

    default int armyMoralePercent(int index) {
        return 0;
    }

    default int armySupplyPercent(int index) {
        return 0;
    }

    default int armySpeedPercent(int index) {
        return 0;
    }

    default int armyOrderTypeCode(int index) {
        return 0;
    }

    default String armyOrderTarget(int index) {
        return "";
    }

    default String armyLocation(int index) {
        return "";
    }

    default String armyComposition(int index) {
        return "";
    }

    default int orderCount() {
        return 0;
    }

    default long orderId(int index) {
        return -1L;
    }

    default int orderArmyId(int index) {
        return -1;
    }

    default String orderArmyName(int index) {
        return "";
    }

    /** A cached, compact line containing state and target. */
    default String orderSummary(int index) {
        return "";
    }

    default int orderTypeCode(int index) {
        return 0;
    }

    default String orderTarget(int index) {
        return "";
    }

    default String orderState(int index) {
        return "";
    }

    default long orderIssuedGameTime(int index) {
        return 0L;
    }

    default int logisticsCount() {
        return 0;
    }

    default int logisticsId(int index) {
        return -1;
    }

    default String logisticsName(int index) {
        return "";
    }

    /** A cached, compact line containing route and progress. */
    default String logisticsSummary(int index) {
        return "";
    }

    default String logisticsCargo(int index) {
        return "";
    }

    default int logisticsCargoCount(int index) {
        return 0;
    }

    default String logisticsSource(int index) {
        return "";
    }

    default String logisticsDestination(int index) {
        return "";
    }

    default String logisticsAssignedArmy(int index) {
        return "";
    }

    default byte logisticsStatusCode(int index) {
        return 0;
    }

    default int logisticsProgressPercent(int index) {
        return 0;
    }

    default int logisticsRiskPercent(int index) {
        return 0;
    }

    default boolean logisticsHighPriority(int index) {
        return false;
    }

    default int settlementCount() { return 0; }
    default long settlementUuidMost(int index) { return 0L; }
    default long settlementUuidLeast(int index) { return 0L; }
    default long settlementPosition(int index) { return 0L; }
    default int settlementFactionId(int index) { return -1; }
    default int settlementPopulation(int index) { return 0; }
    default int settlementAvailableRecruitCount(int index) { return 0; }
    default boolean settlementControlled(int index) { return false; }
    default String settlementName(int index) { return ""; }
    default String settlementCulture(int index) { return ""; }

    default int recruitCount() { return 0; }
    default long recruitUuidMost(int index) { return 0L; }
    default long recruitUuidLeast(int index) { return 0L; }
    default long recruitVillageMost(int index) { return 0L; }
    default long recruitVillageLeast(int index) { return 0L; }
    default int recruitStrength(int index) { return 0; }
    default int recruitMode(int index) { return 0; }
    default int recruitCost(int index) { return 0; }
    default int recruitReputation(int index) { return 0; }
    default String recruitName(int index) { return ""; }
    default String recruitRole(int index) { return ""; }

    default int acknowledgedActionId() { return 0; }
    default byte acknowledgedAction() { return 0; }
    default int acknowledgedResult() { return 0; }
    default int acknowledgedAffected() { return 0; }

    default boolean realmFounded() { return false; }
    default long realmRevision() { return 0L; }
    default String realmName() { return ""; }
    default String realmCapitalName() { return ""; }
    default int realmTaxRate() { return 0; }
    default long realmTreasury() { return 0L; }
    default int realmSettlementCount() { return 0; }
    default int realmPopulation() { return 0; }
    default int realmCapturedSettlements() { return 0; }
    default int realmFood() { return 0; }
    default int realmIron() { return 0; }
    default int realmLeather() { return 0; }
    default int realmArrows() { return 0; }
    default int acknowledgedRealmActionId() { return 0; }
    default byte acknowledgedRealmAction() { return 0; }
    default int acknowledgedRealmResult() { return 0; }

    /**
     * Begins an authoritative order request. For target-requiring orders the adapter may enter its
     * own target-selection flow before sending a packet; the screen never invents a target.
     */
    default boolean requestIssueOrder(int armyHandleBits, int orderTypeCode) {
        return false;
    }

    default boolean requestCreateArmy(long villageUuidMost, long villageUuidLeast) {
        return false;
    }

    default boolean requestRecruitUnits(
            int armyHandleBits,
            long villageUuidMost,
            long villageUuidLeast,
            int count,
            long[] villagerUuidBits) {
        return false;
    }

    default boolean requestHireRecruit(long villagerUuidMost, long villagerUuidLeast) {
        return false;
    }

    default boolean requestFoundRealm(long capitalVillageMost, long capitalVillageLeast) {
        return false;
    }

    default boolean requestSetRealmTax(int taxRate) {
        return false;
    }

    default boolean requestCancelOrder(long orderId) {
        return false;
    }

    default boolean requestSetLogisticsPriority(int logisticsId, boolean highPriority) {
        return false;
    }

    default boolean requestCancelLogistics(int logisticsId) {
        return false;
    }

    default void requestFullSync() {
    }
}
