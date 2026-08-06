package ru.kaiserroman.millenairearmies;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;

/** Reads the deliberately small bootstrap configuration for the addon. */
public final class ArmiesConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_PATH = Path.of("config", "millenaire-armies.properties");
    private static final String SYSTEM_PROPERTY_PREFIX = "millenairearmies.";
    private static final Properties VALUES = load();

    public static final boolean ENABLED = bool("enabled", true);
    /**
     * Bounded server-thread entity-side delegation. Operators can fail closed to state-only
     * commands without changing persisted orders.
     */
    public static final boolean ORDER_EXECUTION_ENABLED = bool("orderExecutionEnabled", true);
    /**
     * Requested only by the isolated stress harness. No worker kernel is currently production-safe,
     * so runtime active count remains zero and non-zero requests are reported NOT_APPLICABLE.
     */
    public static final int REQUESTED_STRATEGIC_WORKER_COUNT = experimentalWorkerCount();
    public static final int ACTIVE_STRATEGIC_WORKER_COUNT = 0;
    public static final int MAX_FACTIONS = integer("maxFactions", 256, 1, 65_536);
    public static final int MAX_ARMIES = integer("maxArmies", 1_024, 1, 1_000_000);
    public static final int MAX_UNITS_PER_ARMY =
            integer("maxUnitsPerArmy", 128, 1, 4_096);
    public static final int RECRUITMENT_VILLAGE_RADIUS =
            integer("recruitmentVillageRadius", 96, 16, 1_024);
    public static final int ARMY_FORMATION_EMERALD_COST =
            integer("armyFormationEmeraldCost", 16, 0, 1_000_000);
    public static final int UNIT_RECRUITMENT_EMERALD_COST =
            integer("unitRecruitmentEmeraldCost", 4, 0, 1_000_000);
    /** Millenaire-denier recurring player army upkeep, charged once per configured interval. */
    public static final int ARMY_UPKEEP_INTERVAL_TICKS =
            integer("armyUpkeepIntervalTicks", 24_000, 1_200, 2_400_000);
    public static final int ARMY_UPKEEP_ARMIES_PER_TICK =
            integer("armyUpkeepArmiesPerTick", 8, 1, 256);
    public static final int ARMY_LEVY_UPKEEP_DENIERS =
            integer("armyLevyUpkeepDeniers", 1, 0, 10_000);
    public static final int ARMY_REGULAR_UPKEEP_DENIERS = integer(
            "armyRegularUpkeepDeniers",
            5,
            ARMY_LEVY_UPKEEP_DENIERS + 1,
            100_000);
    public static final int ARMY_NOBLE_UPKEEP_DENIERS = integer(
            "armyNobleUpkeepDeniers",
            12,
            ARMY_REGULAR_UPKEEP_DENIERS + 1,
            1_000_000);
    public static final int ARMY_DEMOBILIZE_AFTER_MISSED_UPKEEP =
            integer("armyDemobilizeAfterMissedUpkeep", 2, 1, 32);
    public static final int ARMY_DESERT_AFTER_MISSED_UPKEEP = integer(
            "armyDesertAfterMissedUpkeep",
            3,
            ARMY_DEMOBILIZE_AFTER_MISSED_UPKEEP + 1,
            64);

    /** Physical feudal leader projection and deterministic levy call policy. */
    public static final boolean FEUDAL_LEADER_PROJECTION_ENABLED =
            bool("feudalLeaderProjectionEnabled", true);
    public static final int FEUDAL_LEADER_RECONCILE_TICKS =
            integer("feudalLeaderReconcileTicks", 200, 20, 24_000);
    public static final double FEUDAL_LEADER_HEALTH_BONUS =
            real("feudalLeaderHealthBonus", 20.0D, 0.0D, 1_000.0D);
    public static final double FEUDAL_LEADER_DAMAGE_BONUS =
            real("feudalLeaderDamageBonus", 4.0D, 0.0D, 100.0D);
    public static final double FEUDAL_LEADER_ATTACK_SPEED_BONUS =
            real("feudalLeaderAttackSpeedBonus", 0.35D, 0.0D, 10.0D);
    public static final double FEUDAL_LEADER_MOVEMENT_SPEED_BONUS =
            real("feudalLeaderMovementSpeedBonus", 0.04D, 0.0D, 1.0D);
    public static final boolean FEUDAL_LEADER_EQUIPMENT_ENABLED =
            bool("feudalLeaderEquipmentEnabled", true);
    public static final boolean FEUDAL_EXISTING_HORSE_ASSIGNMENT_ENABLED =
            bool("feudalExistingHorseAssignmentEnabled", true);
    public static final int FEUDAL_HORSE_SEARCH_RADIUS =
            integer("feudalHorseSearchRadius", 10, 2, 64);
    public static final int FEUDAL_LEVY_REFUSAL_THRESHOLD =
            integer("feudalLevyRefusalThreshold", 470, 1, 1_000);
    public static final int FEUDAL_LEVY_REBELLION_THRESHOLD = integer(
            "feudalLevyRebellionThreshold", 310, 0, FEUDAL_LEVY_REFUSAL_THRESHOLD - 1);
    public static final int FEUDAL_MAXIMUM_LEVY =
            integer("feudalMaximumLevy", 24, 1, 256);

    public static final int MAX_PENDING_ORDERS = integer("maxPendingOrders", 16_384, 1, 4_000_000);
    public static final int MAX_LOGISTICS_REQUESTS =
            integer("maxLogisticsRequests", 32_768, 1, 4_000_000);
    public static final int MAX_SUPPLY_KEYS = integer("maxSupplyKeys", 8_192, 16, 1_000_000);
    public static final int LOGISTICS_EVENT_CAPACITY =
            integer("logisticsEventCapacity", 2_048, 64, 1_000_000);
    public static final int BATTLE_EVENT_CAPACITY =
            integer("battleEventCapacity", 4_096, 64, 1_000_000);
    public static final int REALM_BATTLE_EVENTS_PER_TICK =
            integer("realmBattleEventsPerTick", 128, 1, 4_096);
    public static final int LOGISTICS_REQUEST_STRIPES =
            integer("logisticsRequestStripes", 16, 1, 4_096);
    public static final int LOGISTICS_EVENTS_PER_TICK =
            integer("logisticsEventsPerTick", 128, 1, 65_536);
    public static final boolean LOGISTICS_INVENTORY_PROJECTION_ENABLED =
            bool("logisticsInventoryProjectionEnabled", true);
    public static final int LOGISTICS_PUBLISHER_REQUEST_ROWS_PER_TICK =
            integer("logisticsPublisherRequestRowsPerTick", 64, 1, 65_536);
    public static final int LOGISTICS_PUBLISHER_KEYS_PER_TICK =
            integer("logisticsPublisherKeysPerTick", 4, 1, 4_096);
    public static final int LOGISTICS_PUBLISHER_SWEEP_TICKS =
            integer("logisticsPublisherSweepTicks", 200, 20, 72_000);
    public static final int MAX_SETTLEMENTS = integer("maxSettlements", 4_096, 1, 65_536);
    public static final int MAX_SETTLEMENT_SHIPMENTS =
            integer("maxSettlementShipments", 16_384, 16, 1_000_000);
    public static final int SETTLEMENT_ECONOMY_INTERVAL_TICKS =
            integer("settlementEconomyIntervalTicks", 200, 20, 72_000);
    public static final int SETTLEMENT_ECONOMY_ROWS_PER_TICK =
            integer("settlementEconomyRowsPerTick", 16, 1, 4_096);
    public static final int SETTLEMENT_SHIPMENTS_PER_TICK =
            integer("settlementShipmentsPerTick", 64, 1, 65_536);
    public static final int SETTLEMENT_ROUTES_PER_TICK =
            integer("settlementRoutesPerTick", 8, 1, 4_096);
    public static final int SETTLEMENT_SCAN_ROWS_PER_TICK =
            integer("settlementScanRowsPerTick", 2, 1, 1_024);
    public static final int SETTLEMENT_MAX_ROUTE_BLOCKS =
            integer("settlementMaxRouteBlocks", 16_384, 128, 30_000_000);
    public static final int GARRISON_MIN_RADIUS = integer("garrisonMinRadius", 12, 4, 64);
    public static final int GARRISON_MAX_RADIUS = integer("garrisonMaxRadius", 64, 12, 256);
    public static final int GARRISON_DEFAULT_RADIUS = integer(
            "garrisonDefaultRadius", 32, GARRISON_MIN_RADIUS, GARRISON_MAX_RADIUS);
    public static final int GARRISON_MAX_MUSTER_DISTANCE =
            integer("garrisonMaxMusterDistance", 96, 16, 512);
    public static final int GARRISON_SETTLEMENT_RESOLVE_RADIUS =
            integer("garrisonSettlementResolveRadius", 96, 16, 512);
    public static final int GARRISON_UPKEEP_INTERVAL_TICKS =
            integer("garrisonUpkeepIntervalTicks", 1_200, 200, 72_000);
    public static final int GARRISON_ROWS_PER_TICK =
            integer("garrisonRowsPerTick", 8, 1, 256);
    public static final int GARRISON_FOOD_PER_UNIT =
            integer("garrisonFoodPerUnit", 1, 0, 64);
    public static final int GARRISON_ARROWS_PER_RANGED_UNIT =
            integer("garrisonArrowsPerRangedUnit", 2, 0, 128);

    /** Server-authoritative foreign settlement dismantling and bounded siege-breach policy. */
    public static final boolean SETTLEMENT_BLOCK_PROTECTION_ENABLED =
            bool("settlementBlockProtectionEnabled", true);
    public static final boolean SETTLEMENT_OPERATOR_CREATIVE_BYPASS =
            bool("settlementOperatorCreativeBypass", true);
    public static final int SETTLEMENT_FOREIGN_BREAK_SPEED_PERMILLE =
            integer("settlementForeignBreakSpeedPermille", 20, 1, 1_000);
    public static final int SETTLEMENT_SIEGE_BREAK_SPEED_PERMILLE = integer(
            "settlementSiegeBreakSpeedPermille",
            125,
            SETTLEMENT_FOREIGN_BREAK_SPEED_PERMILLE,
            1_000);
    public static final int SETTLEMENT_SIEGE_BREACH_BAND_BLOCKS =
            integer("settlementSiegeBreachBandBlocks", 6, 1, 32);
    public static final int SETTLEMENT_SIEGE_OBJECTIVE_RADIUS_BLOCKS =
            integer("settlementSiegeObjectiveRadiusBlocks", 96, 16, 512);

    /** Read-only physical-world adapter plus persisted coarse demography/markets. */
    public static final boolean WORLD_SIMULATION_ENABLED = bool("worldSimulationEnabled", true);
    /**
     * Historical calendar scale. At the default 20 TPS, 1,728,000 ticks equal one real day,
     * so one historical year passes per real day. Simulation may still evaluate more frequently;
     * long-term demography, state formation, decadence and recovery are expressed against this year.
     */
    public static final int HISTORICAL_YEAR_TICKS =
            integer("historicalYearTicks", 1_728_000, 24_000, 172_800_000);
    /** Technical evaluation cadence, deliberately independent from the historical year length. */
    public static final int WORLD_SIMULATION_INTERVAL_TICKS =
            integer("worldSimulationIntervalTicks", 24_000, 200, 2_400_000);
    public static final int WORLD_SIMULATION_ROWS_PER_TICK =
            integer("worldSimulationRowsPerTick", 8, 1, 4_096);
    public static final int WORLD_SIMULATION_SCAN_ROWS_PER_TICK =
            integer("worldSimulationScanRowsPerTick", 4, 1, 4_096);
    public static final int WORLD_SIMULATION_PHYSICAL_STOCK_WEIGHT_PERMILLE =
            integer("worldSimulationPhysicalStockWeightPermille", 250, 0, 1_000);
    public static final int WORLD_SIMULATION_BATTLE_EVENTS_PER_TICK =
            integer("worldSimulationBattleEventsPerTick", 128, 1, 4_096);
    public static final int WORLD_SIMULATION_EVENT_CAPACITY =
            integer("worldSimulationEventCapacity", 2_048, 16, 1_000_000);
    public static final int WORLD_SIMULATION_MAX_CULTURES =
            integer("worldSimulationMaxCultures", 1_024, 1, 65_536);
    public static final int WORLD_SIMULATION_MAX_DIMENSIONS =
            integer("worldSimulationMaxDimensions", 64, 1, 4_096);
    public static final int WORLD_SIMULATION_REGION_SIZE_BLOCKS =
            integer("worldSimulationRegionSizeBlocks", 2_048, 128, 1_048_576);
    public static final int WORLD_SIMULATION_MAX_CATCH_UP_CYCLES =
            integer("worldSimulationMaxCatchUpCycles", 32, 1, 4_096);
    public static final int WORLD_SIMULATION_DECLINE_GRACE_CYCLES =
            integer("worldSimulationDeclineGraceCycles", 6, 1, 10_000);
    public static final int WORLD_SIMULATION_ABANDONMENT_GRACE_CYCLES =
            integer("worldSimulationAbandonmentGraceCycles", 12,
                    WORLD_SIMULATION_DECLINE_GRACE_CYCLES + 1, 20_000);
    public static final int WORLD_SIMULATION_MISSING_CYCLES_BEFORE_RUIN =
            integer("worldSimulationMissingCyclesBeforeRuin", 3, 1, 1_000);
    public static final int WORLD_SIMULATION_FOUNDING_COOLDOWN_CYCLES =
            integer("worldSimulationFoundingCooldownCycles", 20, 1, 20_000);
    /** Historical lifecycle settings. Legacy cycle settings above are read only for schema-1 migration. */
    public static final int WORLD_SIMULATION_DECLINE_GRACE_YEARS =
            integer("worldSimulationDeclineGraceYears", 8, 1, 10_000);
    public static final int WORLD_SIMULATION_ABANDONMENT_GRACE_YEARS =
            integer("worldSimulationAbandonmentGraceYears", 25,
                    WORLD_SIMULATION_DECLINE_GRACE_YEARS + 1, 20_000);
    public static final int WORLD_SIMULATION_MISSING_YEARS_BEFORE_RUIN =
            integer("worldSimulationMissingYearsBeforeRuin", 5, 1, 1_000);
    public static final int WORLD_SIMULATION_FOUNDING_COOLDOWN_YEARS =
            integer("worldSimulationFoundingCooldownYears", 30, 1, 20_000);
    public static final int WORLD_SIMULATION_FOUNDING_POPULATION =
            integer("worldSimulationFoundingPopulation", 120, 8, 1_000_000);
    public static final int WORLD_SIMULATION_MINIMUM_VIABLE_POPULATION =
            integer("worldSimulationMinimumViablePopulation", 8, 1,
                    WORLD_SIMULATION_FOUNDING_POPULATION);
    public static final boolean WORLD_SIMULATION_SHOCK_PROPAGATION_ENABLED =
            bool("worldSimulationShockPropagationEnabled", true);
    public static final boolean WORLD_SIMULATION_REFUGEE_MIGRATION_ENABLED =
            bool("worldSimulationRefugeeMigrationEnabled", true);
    public static final int WORLD_SIMULATION_PROPAGATION_TARGETS =
            integer("worldSimulationPropagationTargets", 8, 1, 256);
    public static final int WORLD_SIMULATION_REFUGEE_FLOWS =
            integer("worldSimulationRefugeeFlows", 4, 1, 256);
    public static final int WORLD_SIMULATION_INTERACTION_DISTANCE_BLOCKS =
            integer("worldSimulationInteractionDistanceBlocks", 4_096,
                    WORLD_SIMULATION_REGION_SIZE_BLOCKS, 65_536);
    public static final boolean WORLD_SIMULATION_ENDOGENOUS_SHOCKS_ENABLED =
            bool("worldSimulationEndogenousShocksEnabled", true);
    public static final int WORLD_SIMULATION_REGIONAL_EVALUATION_INTERVAL_CYCLES =
            integer("worldSimulationRegionalEvaluationIntervalCycles", 3, 1, 10_000);
    public static final int WORLD_SIMULATION_REGIONAL_EVALUATION_ROWS_PER_TICK =
            integer("worldSimulationRegionalEvaluationRowsPerTick", 4, 1, 4_096);
    public static final int WORLD_SIMULATION_ENDOGENOUS_SHOCKS_PER_SWEEP =
            integer("worldSimulationEndogenousShocksPerSweep", 2, 1, 256);

    /** Safe physical projection is enabled by default; destructive abandonment remains separate. */
    public static final boolean WORLD_MUTATION_ENABLED = bool("worldMutationEnabled", true);
    public static final boolean WORLD_MUTATION_FOUNDING_ENABLED =
            bool("worldMutationFoundingEnabled", true);
    public static final boolean WORLD_MUTATION_ABANDONMENT_ENABLED =
            bool("worldMutationAbandonmentEnabled", false);
    public static final int WORLD_MUTATION_EVENTS_PER_TICK =
            integer("worldMutationEventsPerTick", 4, 1, 64);
    public static final int WORLD_MUTATION_SITE_ATTEMPTS =
            integer("worldMutationSiteAttempts", 3, 1, 16);
    public static final int WORLD_MUTATION_MAX_ATTEMPTS =
            integer("worldMutationMaxAttempts", 64, 1, 10_000);
    public static final int WORLD_MUTATION_RETRY_TICKS =
            integer("worldMutationRetryTicks", 1_200, 20, 72_000);
    /**
     * Backoff for candidates that cannot be applied yet because the affected villages are not
     * loaded. Deferral does not consume a retry attempt, so an idle server never discards a
     * pending candidate; it is re-checked on this short fixed interval instead.
     */
    public static final int WORLD_MUTATION_DEFER_TICKS =
            integer("worldMutationDeferTicks", 600, 20, 72_000);
    /**
     * Safety bound on consecutive deferrals of the same FIFO head within one server session.
     * Exceeding it demotes the candidate to an ordinary retry so a permanently unloadable
     * village cannot stall the queue forever.
     */
    public static final int WORLD_MUTATION_MAX_DEFER_RUNS =
            integer("worldMutationMaxDeferRuns", 240, 1, 100_000);
    public static final int WORLD_MUTATION_MIN_FOUNDING_DISTANCE =
            integer("worldMutationMinFoundingDistance", 384, 128, 4_096);
    public static final int WORLD_MUTATION_MAX_FOUNDING_DISTANCE =
            integer("worldMutationMaxFoundingDistance", 768,
                    WORLD_MUTATION_MIN_FOUNDING_DISTANCE, 8_192);
    public static final int WORLD_MUTATION_MIN_VILLAGE_DISTANCE =
            integer("worldMutationMinVillageDistance", 320, 128, 4_096);
    public static final int WORLD_MUTATION_PLAYER_SAFETY_RADIUS =
            integer("worldMutationPlayerSafetyRadius", 96, 16, 1_024);
    public static final int WORLD_MUTATION_FOUNDING_COMPLETION =
            integer("worldMutationFoundingCompletion", 0, 0, 100);
    public static final int WORLD_MUTATION_REFUGEES_PER_EVENT =
            integer("worldMutationRefugeesPerEvent", 16, 1, 256);

    public static final boolean WORLD_PHYSICAL_PROJECTION_ENABLED =
            bool("worldPhysicalProjectionEnabled", true);
    public static final int WORLD_PHYSICAL_PROJECTION_INTERVAL_TICKS =
            integer("worldPhysicalProjectionIntervalTicks", 1_200, 20, 2_400_000);
    public static final int WORLD_PHYSICAL_PROJECTION_VILLAGES_PER_TICK =
            integer("worldPhysicalProjectionVillagesPerTick", 2, 1, 4_096);
    public static final int WORLD_PHYSICAL_PROJECTION_STOCK_CAP =
            integer("worldPhysicalProjectionStockCap", 4_096, 16, 1_000_000);
    public static final int WORLD_PHYSICAL_PROJECTION_ITEMS_PER_SWEEP =
            integer("worldPhysicalProjectionItemsPerSweep", 64, 1, 4_096);
    public static final int WORLD_PHYSICAL_PROJECTION_CATALOG_ITEMS =
            integer("worldPhysicalProjectionCatalogItems", 1_024, 8, 16_384);
    public static final int WORLD_PHYSICAL_PROJECTION_RELATIONS_PER_VILLAGE =
            integer("worldPhysicalProjectionRelationsPerVillage", 64, 1, 4_096);
    public static final int WORLD_PHYSICAL_PROJECTION_DECLINE_PAUSE_YEARS =
            integer("worldPhysicalProjectionDeclinePauseYears", 3, 1, 1_000);
    public static final int WORLD_PHYSICAL_PROJECTION_RUIN_PAUSE_YEARS =
            integer("worldPhysicalProjectionRuinPauseYears", 20,
                    WORLD_PHYSICAL_PROJECTION_DECLINE_PAUSE_YEARS, 10_000);
    public static final boolean WORLD_PHYSICAL_PROJECTION_PLAYER_VILLAGES =
            bool("worldPhysicalProjectionPlayerVillages", false);

    public static final boolean DYNAMIC_TRADE_PRICES_ENABLED =
            bool("dynamicTradePricesEnabled", true);
    public static final int DYNAMIC_TRADE_MIN_MULTIPLIER_PERMILLE =
            integer("dynamicTradeMinMultiplierPermille", 500, 100, 1_000);
    public static final int DYNAMIC_TRADE_MAX_MULTIPLIER_PERMILLE =
            integer("dynamicTradeMaxMultiplierPermille", 3_000,
                    DYNAMIC_TRADE_MIN_MULTIPLIER_PERMILLE, 10_000);
    public static final int DYNAMIC_TRADE_MAX_PRICE =
            integer("dynamicTradeMaxPrice", 1_000_000, 1_000, 100_000_000);

    public static final boolean REALM_EVOLUTION_ENABLED = bool("realmEvolutionEnabled", true);
    public static final int REALM_EVOLUTION_INTERVAL_CYCLES =
            integer("realmEvolutionIntervalCycles", 5, 1, 10_000);
    public static final int REALM_EVOLUTION_REALMS_PER_TICK =
            integer("realmEvolutionRealmsPerTick", 8, 1, 4_096);
    public static final int REALM_EVOLUTION_REFORM_STEP =
            integer("realmEvolutionReformStep", 80, 1, 1_000);

    /** Historical rise/decadence/recovery runs independently from constitutional evolution. */
    public static final boolean REALM_HISTORICAL_ENABLED = bool("realmHistoricalEnabled", true);
    public static final int REALM_HISTORICAL_EVALUATION_TICKS =
            integer("realmHistoricalEvaluationTicks", 24_000, 200, 2_400_000);
    public static final int REALM_HISTORICAL_REALMS_PER_TICK =
            integer("realmHistoricalRealmsPerTick", 8, 1, 4_096);
    public static final int REALM_CITY_STATE_MINIMUM_POPULATION =
            integer("realmCityStateMinimumPopulation", 16, 4, 100_000);
    public static final int REALM_CITY_STATE_FORMATION_YEARS =
            integer("realmCityStateFormationYears", 8, 1, 1_000);
    public static final int REALM_REGIONAL_FORMATION_YEARS =
            integer("realmRegionalFormationYears", 5, 1, 1_000);
    public static final int REALM_COLLAPSE_DISSOLUTION_YEARS =
            integer("realmCollapseDissolutionYears", 10, 1, 10_000);
    public static final int REALM_CAPITAL_LOSS_DISSOLUTION_YEARS =
            integer("realmCapitalLossDissolutionYears", 2, 1, 1_000);
    public static final boolean REALM_SECESSION_ENABLED = bool("realmSecessionEnabled", true);
    public static final int REALM_SECESSION_MINIMUM_PHASE_YEARS =
            integer("realmSecessionMinimumPhaseYears", 12, 1, 10_000);
    public static final int REALM_SECESSION_COOLDOWN_YEARS =
            integer("realmSecessionCooldownYears", 20, 1, 10_000);

    public static final boolean REALM_STATE_DECISIONS_ENABLED =
            bool("realmStateDecisionsEnabled", true);
    public static final int REALM_STATE_DECISION_EVALUATION_TICKS =
            integer("realmStateDecisionEvaluationTicks", 24_000, 200, 2_400_000);
    public static final int REALM_STATE_DECISION_REALMS_PER_TICK =
            integer("realmStateDecisionRealmsPerTick", 8, 1, 4_096);
    public static final int REALM_STATE_DECISION_INTERVAL_YEARS =
            integer("realmStateDecisionIntervalYears", 1, 1, 100);
    public static final int REALM_STATE_DECISION_BASE_INVESTMENT =
            integer("realmStateDecisionBaseInvestment", 40, 1, 1_000_000);
    public static final int REALM_STATE_DECISION_PROJECT_CANDIDATES =
            integer("realmStateDecisionProjectCandidates", 128, 1, 4_096);

    public static final boolean AUTONOMOUS_REALMS_ENABLED = bool("autonomousRealmsEnabled", true);
    public static final int AUTONOMOUS_REALM_INTERVAL_CYCLES =
            integer("autonomousRealmIntervalCycles", 1, 1, 10_000);
    public static final int AUTONOMOUS_REALM_TRANSITIONS_PER_TICK =
            integer("autonomousRealmTransitionsPerTick", 2, 1, 1_024);

    public static final boolean CANONICAL_REALM_DIPLOMACY_ENABLED =
            bool("canonicalRealmDiplomacyEnabled", true);
    public static final int CANONICAL_REALM_DIPLOMACY_INTERVAL_CYCLES =
            integer("canonicalRealmDiplomacyIntervalCycles", 3, 1, 10_000);
    public static final int CANONICAL_REALM_DIPLOMACY_RELATIONS_PER_TICK =
            integer("canonicalRealmDiplomacyRelationsPerTick", 8, 1, 4_096);
    public static final int CANONICAL_REALM_TRUCE_CYCLES =
            integer("canonicalRealmTruceCycles", 5, 1, 10_000);
    public static final int CANONICAL_REALM_OBJECTIVE_RADIUS =
            integer("canonicalRealmObjectiveRadius", 160, 16, 4_096);

    private ArmiesConfig() {}

    public static Path path() {
        return CONFIG_PATH;
    }

    private static boolean bool(String key, boolean fallback) {
        String value = value(key);
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        LOGGER.warn("Invalid boolean {}={} in {}; using {}", key, value, CONFIG_PATH, fallback);
        return fallback;
    }

    private static int integer(String key, int fallback, int minimum, int maximum) {
        String raw = value(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < minimum || parsed > maximum) {
                LOGGER.warn(
                        "Out-of-range integer {}={} in {}; expected {}..{}, using {}",
                        key,
                        raw,
                        CONFIG_PATH,
                        minimum,
                        maximum,
                        fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            LOGGER.warn("Invalid integer {}={} in {}; using {}", key, raw, CONFIG_PATH, fallback);
            return fallback;
        }
    }

    private static double real(String key, double fallback, double minimum, double maximum) {
        String raw = value(key);
        if (raw == null) return fallback;
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum) {
                LOGGER.warn(
                        "Out-of-range decimal {}={} in {}; expected {}..{}, using {}",
                        key,
                        raw,
                        CONFIG_PATH,
                        minimum,
                        maximum,
                        fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            LOGGER.warn("Invalid decimal {}={} in {}; using {}", key, raw, CONFIG_PATH, fallback);
            return fallback;
        }
    }

    private static String value(String key) {
        String override = System.getProperty(SYSTEM_PROPERTY_PREFIX + key);
        String value = override != null ? override : VALUES.getProperty(key);
        return value == null ? null : value.trim();
    }

    private static int experimentalWorkerCount() {
        String raw = System.getProperty("bannerok.experimental.workerCount");
        if (raw == null) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed >= 0 && parsed <= 2) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Fail closed below.
        }
        LOGGER.warn("Invalid bannerok.experimental.workerCount={}; using 0", raw);
        return 0;
    }

    private static Properties load() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
        } catch (IOException exception) {
            LOGGER.warn("Could not read {}: {}", CONFIG_PATH, exception.toString());
        }
        return properties;
    }
}
