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

    /**
     * Begins an authoritative order request. For target-requiring orders the adapter may enter its
     * own target-selection flow before sending a packet; the screen never invents a target.
     */
    default boolean requestIssueOrder(int armyHandleBits, int orderTypeCode) {
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
