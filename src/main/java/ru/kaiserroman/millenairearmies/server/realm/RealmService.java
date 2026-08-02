package ru.kaiserroman.millenairearmies.server.realm;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.PackedSettlementEconomyState;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.server.economy.SettlementEconomyEngine;
import ru.kaiserroman.millenairearmies.server.execution.RealmCapturePolicy;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/** Server-authoritative player realm, taxes, treasury and controlled-settlement resources. */
public final class RealmService implements RealmCapturePolicy, ArmyCommandService.ArmyOrderValidator {
    public static final int SUCCESS = 1;
    public static final int NOT_FOUNDED = -1;
    public static final int ALREADY_FOUNDED = -2;
    public static final int SETTLEMENT_NOT_FOUND = -3;
    public static final int SETTLEMENT_NOT_CONTROLLED = -4;
    public static final int TOO_FAR = -5;
    public static final int INVALID_TAX = -6;
    public static final int INVALID_NAME = -7;

    private static final int TAX_TICK_INTERVAL = 200;
    private static final long TAX_PERIOD_TICKS = 24_000L;
    private static final long MAX_FOUND_DISTANCE_SQ = 128L * 128L;

    private final MinecraftServer server;
    private final PlayerRealmSavedData data;
    private final MillenaireVillageIndex villages;
    private final MillenaireVillageIndex.Cursor villageCursor;
    private final SettlementEconomyEngine economy;
    private final PlayerRealmSavedData.View view = new PlayerRealmSavedData.View();
    private int ticksUntilTax = TAX_TICK_INTERVAL;

    public RealmService(
            MinecraftServer server,
            PlayerRealmSavedData data,
            MillenaireVillageIndex villages,
            SettlementEconomyEngine economy) {
        this.server = Objects.requireNonNull(server, "server");
        this.data = Objects.requireNonNull(data, "data");
        this.villages = Objects.requireNonNull(villages, "villages");
        this.villageCursor = villages.newCursor();
        this.economy = economy;
    }

    public int found(ServerPlayer player, long villageMost, long villageLeast, String requestedName) {
        requireServerThread();
        if (data.exists(player.getUUID())) return ALREADY_FOUNDED;
        Village capital = villages.find(villageMost, villageLeast);
        ServerLevel level = capital == null ? null : villages.level(capital.getId());
        if (capital == null || level == null) return SETTLEMENT_NOT_FOUND;
        if (!capital.isControlledBy(player.getUUID())) return SETTLEMENT_NOT_CONTROLLED;
        if (level != player.serverLevel()
                || distanceSquared(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ(),
                                capital.getCenter().getX(), capital.getCenter().getY(), capital.getCenter().getZ())
                        > MAX_FOUND_DISTANCE_SQ) {
            return TOO_FAR;
        }
        String capitalName = villageName(capital);
        String name = requestedName == null || requestedName.isBlank()
                ? capitalName + " Realm"
                : requestedName;
        try {
            boolean founded = data.found(
                    player.getUUID(), name, capital.getId().uuid(), level.dimension().location(), level.getGameTime());
            return founded ? SUCCESS : ALREADY_FOUNDED;
        } catch (IllegalArgumentException invalid) {
            return INVALID_NAME;
        }
    }

    public int foundNearest(ServerPlayer player, String requestedName) {
        requireServerThread();
        Village nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            if (village == null
                    || villageCursor.level() != player.serverLevel()
                    || !village.isControlledBy(player.getUUID())) {
                continue;
            }
            long distance = distanceSquared(
                    player.blockPosition().getX(),
                    player.blockPosition().getY(),
                    player.blockPosition().getZ(),
                    village.getCenter().getX(),
                    village.getCenter().getY(),
                    village.getCenter().getZ());
            if (distance <= MAX_FOUND_DISTANCE_SQ && distance < nearestDistance) {
                nearest = village;
                nearestDistance = distance;
            }
        }
        if (nearest == null) return SETTLEMENT_NOT_FOUND;
        UUID id = nearest.getId().uuid();
        return found(player, id.getMostSignificantBits(), id.getLeastSignificantBits(), requestedName);
    }

    public int rename(ServerPlayer player, String name) {
        requireServerThread();
        if (!data.exists(player.getUUID())) return NOT_FOUNDED;
        try {
            return data.rename(player.getUUID(), name) ? SUCCESS : NOT_FOUNDED;
        } catch (IllegalArgumentException invalid) {
            return INVALID_NAME;
        }
    }

    public int setTaxRate(ServerPlayer player, int taxRate) {
        requireServerThread();
        if (taxRate < 0 || taxRate > 25) return INVALID_TAX;
        return data.setTaxRate(player.getUUID(), taxRate) ? SUCCESS : NOT_FOUNDED;
    }

    /** Collects realm tax once per Minecraft day from actual currently controlled villages. */
    public void tick(long gameTime) {
        requireServerThread();
        if (--ticksUntilTax > 0) return;
        ticksUntilTax = TAX_TICK_INTERVAL;
        data.visit((ownerMost, ownerLeast, taxRate, lastTaxTick) -> {
            if (gameTime - lastTaxTick < TAX_PERIOD_TICKS) return;
            UUID owner = new UUID(ownerMost, ownerLeast);
            long income = 0L;
            for (villageCursor.reset(); villageCursor.advance(); ) {
                Village village = villageCursor.village();
                if (village != null && village.isControlledBy(owner)) {
                    // Tax is an explicit governance ledger, not battle simulation. Five points of
                    // tax rate produce one denier per living resident per day.
                    income = saturatedAdd(income, (long) livingPopulation(village) * taxRate / 5L);
                }
            }
            data.collectTaxes(ownerMost, ownerLeast, income, gameTime);
        });
    }

    public Snapshot snapshot(UUID owner) {
        requireServerThread();
        if (!data.read(owner, view)) return Snapshot.EMPTY;
        int settlementCount = 0;
        int population = 0;
        int food = 0;
        int iron = 0;
        int leather = 0;
        int arrows = 0;
        String capitalName = "";
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            if (village == null) continue;
            if (village.getId().uuid().getMostSignificantBits() == view.capitalMost()
                    && village.getId().uuid().getLeastSignificantBits() == view.capitalLeast()) {
                capitalName = villageName(village);
            }
            if (!village.isControlledBy(owner)) continue;
            settlementCount++;
            population = saturatedIntAdd(population, livingPopulation(village));
            if (economy != null) {
                PackedSettlementEconomyState state = economy.state();
                int row = state.findSettlement(
                        village.getId().uuid().getMostSignificantBits(),
                        village.getId().uuid().getLeastSignificantBits());
                if (row >= 0) {
                    food = saturatedIntAdd(food, state.stockAt(row, PackedSettlementEconomyState.FOOD));
                    iron = saturatedIntAdd(iron, state.stockAt(row, PackedSettlementEconomyState.IRON));
                    leather = saturatedIntAdd(leather, state.stockAt(row, PackedSettlementEconomyState.LEATHER));
                    arrows = saturatedIntAdd(arrows, state.stockAt(row, PackedSettlementEconomyState.ARROWS));
                }
            }
        }
        return new Snapshot(
                true,
                view.revision(),
                view.name(),
                capitalName,
                view.taxRate(),
                view.treasury(),
                settlementCount,
                population,
                view.capturedSettlements(),
                food,
                iron,
                leather,
                arrows);
    }

    @Override
    public boolean canCapture(UUID owner) {
        requireServerThread();
        return data.exists(owner);
    }

    @Override
    public boolean isValid(
            ArmyCommandAuthority authority,
            int armyHandle,
            StrategicArmyOrder order,
            ResourceLocation targetDimension,
            long packedTargetPosition) {
        requireServerThread();
        if (order != StrategicArmyOrder.ATTACK || authority.operator()) return true;
        if (!authority.hasIdentity()) return false;
        UUID owner = new UUID(authority.uuidMost(), authority.uuidLeast());
        if (!data.exists(owner)) return false;
        int x = net.minecraft.core.BlockPos.getX(packedTargetPosition);
        int y = net.minecraft.core.BlockPos.getY(packedTargetPosition);
        int z = net.minecraft.core.BlockPos.getZ(packedTargetPosition);
        Village nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            if (village == null
                    || !villageCursor.level().dimension().location().equals(targetDimension)) {
                continue;
            }
            long distance = distanceSquared(
                    x, y, z,
                    village.getCenter().getX(),
                    village.getCenter().getY(),
                    village.getCenter().getZ());
            if (distance <= 96L * 96L && distance < nearestDistance) {
                nearest = village;
                nearestDistance = distance;
            }
        }
        return nearest != null && !nearest.isControlledBy(owner);
    }

    @Override
    public void captured(UUID owner, Village village) {
        requireServerThread();
        data.recordCapture(owner);
    }

    public PlayerRealmSavedData data() { return data; }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Realm service must run on server thread");
    }

    private static int livingPopulation(Village village) {
        int count = 0;
        for (VillagerRecord record : village.getVillagerRecords().values()) {
            if (record != null && !record.isKilled()) count++;
        }
        return count;
    }

    private static String villageName(Village village) {
        String name = village.getVillageName();
        return name == null || name.isBlank() ? village.getVillageTypeId().getPath() : name;
    }

    private static long distanceSquared(int x1, int y1, int z1, int x2, int y2, int z2) {
        long dx = (long) x1 - x2;
        long dy = (long) y1 - y2;
        long dz = (long) z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static int saturatedIntAdd(int left, int right) {
        long value = (long) left + right;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public record Snapshot(
            boolean founded,
            long revision,
            String name,
            String capitalName,
            int taxRate,
            long treasury,
            int settlementCount,
            int population,
            int capturedSettlements,
            int food,
            int iron,
            int leather,
            int arrows) {
        public static final Snapshot EMPTY = new Snapshot(
                false, 0L, "", "", 0, 0L, 0, 0, 0, 0, 0, 0, 0);
    }
}
