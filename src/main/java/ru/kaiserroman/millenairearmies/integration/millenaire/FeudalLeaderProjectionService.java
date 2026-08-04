package ru.kaiserroman.millenairearmies.integration.millenaire;

import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.server.unit.PackedUnitRoleState;

/**
 * Projects canonical/legacy feudal office onto the real loaded Millenaire village chief.
 *
 * <p>No duplicate leader entity is created. Stable permanent attribute modifiers survive chunk
 * unloads and restarts, and an already-tamed, saddled, unused horse may be adopted; the service does
 * not spawn or steal mounts. Equipment is only filled into empty slots. A chief that is currently in
 * an addon army is persisted as the expensive NOBLE troop class.</p>
 */
public final class FeudalLeaderProjectionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation HEALTH = id("feudal_leader_health");
    private static final ResourceLocation DAMAGE = id("feudal_leader_damage");
    private static final ResourceLocation ATTACK_SPEED = id("feudal_leader_attack_speed");
    private static final ResourceLocation MOVE_SPEED = id("feudal_leader_move_speed");

    private final RealmGovernanceSavedData.AssignmentView governanceView =
            new RealmGovernanceSavedData.AssignmentView();
    private MinecraftServer server;
    private MillenaireEntityBridge entities;
    private RealmSavedData realms;
    private RealmGovernanceSavedData governance;
    private ArmySavedData armies;
    private long nextReconcileTick;
    private long projectedLeaders;
    private long removedProjections;
    private long equippedLeaders;
    private long mountedLeaders;
    private long safeMountMisses;
    private long nobleClassifications;

    public void start(
            MinecraftServer startingServer,
            MillenaireEntityBridge entityBridge,
            RealmSavedData realmData,
            RealmGovernanceSavedData governanceData,
            ArmySavedData armyData) {
        if (server != null) throw new IllegalStateException("Feudal leader projection already started");
        server = startingServer;
        entities = entityBridge;
        realms = realmData;
        governance = governanceData;
        armies = armyData;
        nextReconcileTick = 0L;
    }

    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) return;
        LOGGER.info(
                "[BANNEROK_FEUDAL_LEADER_METRICS] projected={} removed={} equipped={} mounted={} safe_mount_misses={} noble_classifications={}",
                projectedLeaders,
                removedProjections,
                equippedLeaders,
                mountedLeaders,
                safeMountMisses,
                nobleClassifications);
        server = null;
        entities = null;
        realms = null;
        governance = null;
        armies = null;
        nextReconcileTick = 0L;
    }

    public void tick(long gameTime) {
        if (server == null || !ArmiesConfig.FEUDAL_LEADER_PROJECTION_ENABLED
                || gameTime < nextReconcileTick) {
            return;
        }
        requireServerThread();
        nextReconcileTick = saturatedAdd(gameTime, ArmiesConfig.FEUDAL_LEADER_RECONCILE_TICKS);
        entities.visitLoaded(this::reconcileLoaded);
    }

    private void reconcileLoaded(MillVillager villager, Village village) {
        if (!villager.isAlive() || villager.isBaby() || !villager.isChief()) return;
        boolean noble = eligible(village);
        if (!noble) {
            if (removeModifiers(villager)) removedProjections++;
            return;
        }
        boolean firstProjection = applyModifiers(villager);
        if (firstProjection) projectedLeaders++;
        if (ArmiesConfig.FEUDAL_LEADER_EQUIPMENT_ENABLED && equipMissing(villager)) {
            equippedLeaders++;
        }
        classifyNoble(villager);
        if (ArmiesConfig.FEUDAL_EXISTING_HORSE_ASSIGNMENT_ENABLED
                && !villager.isPassenger()) {
            if (mountExistingHorse(villager)) mountedLeaders++;
            else safeMountMisses++;
        }
    }

    private boolean eligible(Village village) {
        if (village == null || village.getId() == null || village.getId().uuid() == null) return false;
        long realmId = realms.realmForSettlement(village.getId().uuid());
        if (realmId == RealmRegistry.NO_REALM || !realms.registry().exists(realmId)) return false;
        long member = realms.keys().findSettlement(village.getId().uuid());
        if (member != 0L && realms.registry().capitalMemberId(realmId) == member) return true;
        if (governance != null && governance.readVillage(village.getId().uuid(), governanceView)
                && (governanceView.role() == RealmGovernanceSavedData.ROLE_FEUDAL
                        || governanceView.role() == RealmGovernanceSavedData.ROLE_HEAD)) {
            return true;
        }
        Constitution constitution = realms.institutions().constitution(realmId);
        return constitution != null && constitution.noblePower() >= 350;
    }

    private static boolean applyModifiers(MillVillager villager) {
        boolean first = !has(villager, Attributes.MAX_HEALTH, HEALTH);
        put(villager, Attributes.MAX_HEALTH, new AttributeModifier(
                HEALTH,
                ArmiesConfig.FEUDAL_LEADER_HEALTH_BONUS,
                AttributeModifier.Operation.ADD_VALUE));
        put(villager, Attributes.ATTACK_DAMAGE, new AttributeModifier(
                DAMAGE,
                ArmiesConfig.FEUDAL_LEADER_DAMAGE_BONUS,
                AttributeModifier.Operation.ADD_VALUE));
        put(villager, Attributes.ATTACK_SPEED, new AttributeModifier(
                ATTACK_SPEED,
                ArmiesConfig.FEUDAL_LEADER_ATTACK_SPEED_BONUS,
                AttributeModifier.Operation.ADD_VALUE));
        put(villager, Attributes.MOVEMENT_SPEED, new AttributeModifier(
                MOVE_SPEED,
                ArmiesConfig.FEUDAL_LEADER_MOVEMENT_SPEED_BONUS,
                AttributeModifier.Operation.ADD_VALUE));
        if (first) villager.setHealth(villager.getMaxHealth());
        return first;
    }

    private static boolean removeModifiers(MillVillager villager) {
        boolean changed = remove(villager, Attributes.MAX_HEALTH, HEALTH);
        changed |= remove(villager, Attributes.ATTACK_DAMAGE, DAMAGE);
        changed |= remove(villager, Attributes.ATTACK_SPEED, ATTACK_SPEED);
        changed |= remove(villager, Attributes.MOVEMENT_SPEED, MOVE_SPEED);
        if (villager.getHealth() > villager.getMaxHealth()) villager.setHealth(villager.getMaxHealth());
        return changed;
    }

    private static boolean equipMissing(MillVillager villager) {
        boolean changed = false;
        changed |= equip(villager, EquipmentSlot.MAINHAND, Items.IRON_SWORD);
        changed |= equip(villager, EquipmentSlot.HEAD, Items.IRON_HELMET);
        changed |= equip(villager, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
        changed |= equip(villager, EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
        changed |= equip(villager, EquipmentSlot.FEET, Items.IRON_BOOTS);
        if (changed) villager.ensureCombatWeaponEquipped();
        return changed;
    }

    private static boolean equip(MillVillager villager, EquipmentSlot slot, Item item) {
        ItemStack existing = villager.getItemBySlot(slot);
        if (existing != null && !existing.isEmpty()) return false;
        villager.setItemSlot(slot, new ItemStack(item));
        villager.setDropChance(slot, 0.0F);
        return true;
    }

    private boolean mountExistingHorse(MillVillager villager) {
        if (!(villager.level() instanceof ServerLevel level)) return false;
        int radius = ArmiesConfig.FEUDAL_HORSE_SEARCH_RADIUS;
        List<Horse> horses = level.getEntitiesOfClass(
                Horse.class,
                villager.getBoundingBox().inflate(radius),
                horse -> horse.isAlive()
                        && horse.isTamed()
                        && horse.isSaddled()
                        && !horse.isVehicle()
                        && horse.getPassengers().isEmpty());
        Horse best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < horses.size(); index++) {
            Horse horse = horses.get(index);
            double distance = horse.distanceToSqr(villager);
            if (distance < bestDistance) {
                best = horse;
                bestDistance = distance;
            }
        }
        return best != null && villager.startRiding(best, true);
    }

    private void classifyNoble(MillVillager villager) {
        PackedUnitMembership memberships = armies.memberships();
        long most = villager.getUUID().getMostSignificantBits();
        long least = villager.getUUID().getLeastSignificantBits();
        for (int row = 0; row < memberships.size(); row++) {
            if (memberships.uuidMostAt(row) != most || memberships.uuidLeastAt(row) != least) continue;
            int unit = memberships.unitHandleAt(row);
            if (armies.unitRoles().assignTroopClass(unit, PackedUnitRoleState.TROOP_CLASS_NOBLE)) {
                armies.markArmyChanged();
                nobleClassifications++;
            }
            return;
        }
    }

    private static boolean has(
            MillVillager villager,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            ResourceLocation id) {
        AttributeInstance instance = villager.getAttribute(attribute);
        return instance != null && instance.hasModifier(id);
    }

    private static void put(
            MillVillager villager,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            AttributeModifier modifier) {
        AttributeInstance instance = villager.getAttribute(attribute);
        if (instance != null) instance.addOrReplacePermanentModifier(modifier);
    }

    private static boolean remove(
            MillVillager villager,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            ResourceLocation id) {
        AttributeInstance instance = villager.getAttribute(attribute);
        return instance != null && instance.removeModifier(id);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Feudal leader projection must run on the server thread");
        }
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("millenaire_armies", path);
    }
}
