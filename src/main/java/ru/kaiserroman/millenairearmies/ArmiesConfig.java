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
    public static final int MAX_PENDING_ORDERS = integer("maxPendingOrders", 16_384, 1, 4_000_000);
    public static final int MAX_LOGISTICS_REQUESTS =
            integer("maxLogisticsRequests", 32_768, 1, 4_000_000);
    public static final int MAX_SUPPLY_KEYS = integer("maxSupplyKeys", 8_192, 16, 1_000_000);
    public static final int LOGISTICS_EVENT_CAPACITY =
            integer("logisticsEventCapacity", 2_048, 64, 1_000_000);
    public static final int LOGISTICS_REQUEST_STRIPES =
            integer("logisticsRequestStripes", 16, 1, 4_096);
    public static final int LOGISTICS_EVENTS_PER_TICK =
            integer("logisticsEventsPerTick", 128, 1, 65_536);
    public static final boolean LOGISTICS_INVENTORY_PROJECTION_ENABLED =
            bool("logisticsInventoryProjectionEnabled", false);
    public static final int LOGISTICS_PUBLISHER_REQUEST_ROWS_PER_TICK =
            integer("logisticsPublisherRequestRowsPerTick", 64, 1, 65_536);
    public static final int LOGISTICS_PUBLISHER_KEYS_PER_TICK =
            integer("logisticsPublisherKeysPerTick", 4, 1, 4_096);
    public static final int LOGISTICS_PUBLISHER_SWEEP_TICKS =
            integer("logisticsPublisherSweepTicks", 200, 20, 72_000);

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
