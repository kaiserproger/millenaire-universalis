package ru.kaiserroman.millenairearmies.persistence;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import ru.kaiserroman.millenaire.realm.BattleOutcome;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.DiplomaticDecision;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmDiplomacyEngine;
import ru.kaiserroman.millenaire.realm.RealmHistoricalAssessment;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;
import ru.kaiserroman.millenaire.realm.WarGoal;

/** Stable subject identity, mixed membership and Realm NBT round-trip. */
public final class RealmSavedDataSelfTest {
    private RealmSavedDataSelfTest() {}

    public static void main(String[] args) {
        RealmSavedData data = new RealmSavedData();
        UUID ruler = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID capital = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID npcVillage = UUID.fromString("30000000-0000-0000-0000-000000000002");
        long rulerId = data.keys().internPlayer(ruler);
        long capitalId = data.keys().internSettlement(capital);
        long npcVillageId = data.keys().internSettlement(npcVillage);
        check(rulerId != capitalId, "typed subjects receive distinct ids");

        long realmId = data.registry().createRealm(
                capitalId,
                RealmMemberKind.PLAYER_SETTLEMENT,
                rulerId,
                GovernmentForm.FEUDAL_MONARCHY,
                720,
                10L);
        check(realmId != RealmRegistry.NO_REALM, "realm created");
        check(data.registry().addMember(
                realmId, rulerId, RealmMemberKind.PLAYER, rulerId, 900), "ruler member added");
        check(data.registry().addMember(
                realmId, npcVillageId, RealmMemberKind.NPC_SETTLEMENT, 0L, 350),
                "npc village added");
        Constitution institutions = Constitution.archetype(
                        GovernmentForm.FEUDAL_MONARCHY, 720)
                .towards(GovernmentForm.ESTATE_MONARCHY, 90)
                .withLegitimacy(680);
        check(data.institutions().ensureRealm(realmId, institutions, 5L) == 0,
                "institutions inserted");
        check(data.institutions().update(realmId, institutions, 7, 15L),
                "institution path-dependence updated");
        check(data.lifecycle().recordFormation(1001L, 3, 720, 650, 4, 20L) == 4,
                "formation hysteresis inserted");
        check(data.lifecycle().recordCrisis(realmId, 780, 700, 3, 20L) == 3,
                "crisis hysteresis inserted");
        check(data.upsertMetadata(realmId, "March of Alder", 12, 4_500L, true),
                "metadata inserted");
        check(data.recordStateDecision(
                        realmId,
                        RealmStatePriority.FORTIFICATION,
                        780,
                        650,
                        42_000L),
                "state programme inserted");
        check(data.recordCapture(realmId), "capture metadata updated");
        long metadataRevision = data.metadataRevision();

        UUID rivalCapital = UUID.fromString("30000000-0000-0000-0000-000000000003");
        long rivalCapitalId = data.keys().internSettlement(rivalCapital);
        long rivalRealm = data.registry().createRealm(
                rivalCapitalId,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CLAN_CONFEDERATION,
                610,
                12L);
        check(rivalRealm != RealmRegistry.NO_REALM, "rival Realm created");
        check(data.upsertMetadata(rivalRealm, "River Council", 6, 800L, false),
                "rival metadata inserted");
        check(data.diplomacy().updateDrivers(
                        realmId, rivalRealm,
                        180, 840, 300, 790,
                        260, 760, 620, 140,
                        20L),
                "diplomatic drivers inserted");
        check(data.diplomacy().applyDecision(
                        realmId,
                        rivalRealm,
                        new DiplomaticDecision(
                                DiplomaticStatus.WAR,
                                WarGoal.BORDER_CLAIM,
                                800,
                                100,
                                RealmDiplomacyEngine.REASON_CLAIM),
                        20L,
                        8),
                "war state inserted");
        check(data.diplomacy().recordBattleOutcome(
                        new BattleOutcome(realmId, rivalRealm, true, 2, 15, false, true),
                        new RealmDiplomacyEngine()),
                "battle result inserted");
        check(data.dependencies().establish(rivalRealm, realmId, 420, 180, 250, 24L),
                "Realm dependency inserted");
        RealmHistoricalAssessment historical = new RealmHistoricalAssessment(
                RealmHistoricalPhase.DECADENT,
                RealmScale.KINGDOM,
                620,
                510,
                504,
                330,
                450_000,
                120_000,
                20_000,
                3_000,
                77,
                100_000L,
                false,
                false,
                false,
                false);
        check(data.history().ensureRealm(realmId, historical, 10_000L) == 0,
                "historical state inserted");
        check(data.history().markSecession(realmId, 88_000L),
                "historical secession date inserted");
        RealmHistoricalAssessment rivalHistory = new RealmHistoricalAssessment(
                RealmHistoricalPhase.STABLE,
                RealmScale.CITY_STATE,
                740,
                210,
                814,
                710,
                0,
                80_000,
                0,
                18_000,
                0,
                12_000L,
                false,
                false,
                true,
                false);
        check(data.history().ensureRealm(rivalRealm, rivalHistory, 12_000L) == 1,
                "rival historical state inserted");

        CompoundTag saved = data.save(new CompoundTag(), null);
        RealmSavedData restored = RealmSavedData.load(saved, null);
        check(restored.registry().realmCount() == 2, "realm count parity");
        check(restored.registry().memberCount() == 4, "member count parity");
        check(restored.realmForPlayer(ruler) == realmId, "player membership parity");
        check(restored.realmForSettlement(capital) == realmId, "capital membership parity");
        check(restored.realmForSettlement(npcVillage) == realmId, "npc membership parity");
        check(restored.registry().government(realmId) == GovernmentForm.FEUDAL_MONARCHY,
                "government parity");
        check(restored.registry().legitimacy(realmId) == 720, "legitimacy parity");
        check(institutions.equals(restored.institutions().constitution(realmId)),
                "institution axes parity");
        check(restored.institutions().stableCycles(realmId) == 7
                        && restored.institutions().lastEvaluationCycle(realmId) == 15L,
                "institution path-dependence parity");
        check(restored.lifecycle().formationQualifyingCycles(1001L, 3) == 4,
                "formation hysteresis parity");
        check(restored.lifecycle().crisisQualifyingCycles(realmId) == 3,
                "crisis hysteresis parity");
        check("March of Alder".equals(restored.name(realmId)), "name parity");
        check(restored.taxRate(realmId) == 12 && restored.treasury(realmId) == 4_500L,
                "fiscal metadata parity");
        check(restored.capturedSettlementCount(realmId) == 1,
                "capture metadata parity");
        check(restored.metadataRevision() >= metadataRevision,
                "metadata revision parity");
        check(restored.isLegacy(realmId), "legacy marker parity");
        check(restored.statePriority(realmId) == RealmStatePriority.FORTIFICATION
                        && restored.stateDecisionPressure(realmId) == 780
                        && restored.stateInvestmentPermille(realmId) == 650
                        && restored.lastStateDecisionMilliYear(realmId) == 42_000L,
                "persisted state programme parity");
        check(restored.realmForSettlement(rivalCapital) == rivalRealm,
                "rival membership parity");
        check(restored.diplomacy().isAtWar(realmId, rivalRealm),
                "war status parity");
        check(restored.diplomacy().warGoal(realmId, rivalRealm) == WarGoal.BORDER_CLAIM
                        && restored.diplomacy().warGoal(rivalRealm, realmId) == WarGoal.DEFEND,
                "directed war goals parity");
        check(restored.diplomacy().warScore(realmId, rivalRealm) > 0
                        && restored.diplomacy().warScore(rivalRealm, realmId) < 0,
                "war score parity");
        check(restored.diplomacy().exhaustion(rivalRealm, realmId)
                        > restored.diplomacy().exhaustion(realmId, rivalRealm),
                "war exhaustion parity");
        check(restored.dependencies().overlordOf(rivalRealm) == realmId,
                "dependency overlord parity");
        check(restored.dependencies().autonomy(rivalRealm) == 420
                        && restored.dependencies().tributeRate(rivalRealm) == 180
                        && restored.dependencies().militaryLevy(rivalRealm) == 250,
                "dependency terms parity");
        check(restored.history().size() == 2,
                "historical state count parity");
        check(restored.history().phase(realmId) == RealmHistoricalPhase.DECADENT
                        && restored.history().scale(realmId) == RealmScale.KINGDOM,
                "historical phase and scale parity");
        check(restored.history().viability(realmId) == 504
                        && restored.history().crisisMomentum(realmId) == 450_000
                        && restored.history().recoveryMomentum(realmId) == 120_000,
                "historical momentum parity");
        check(restored.history().foundedMilliYear(realmId) == 10_000L
                        && restored.history().phaseSinceMilliYear(realmId) == 100_000L
                        && restored.history().lastSecessionMilliYear(realmId) == 88_000L,
                "historical dating parity");
        check(restored.history().scale(rivalRealm) == RealmScale.CITY_STATE
                        && restored.history().expansionReadiness(rivalRealm) == 710,
                "city-state history parity");

        CompoundTag legacySchema = saved.copy();
        legacySchema.putInt("SchemaVersion", 1);
        legacySchema.remove("Dependencies");
        legacySchema.remove("DependencyRevision");
        legacySchema.remove("History");
        legacySchema.remove("HistoryRevision");
        RealmSavedData migrated = RealmSavedData.load(legacySchema, null);
        check(migrated.dependencies().size() == 0, "schema 1 migrates with independent Realms");
        check(migrated.history().size() == 0,
                "schema 1 migrates without fabricated historical state");

        CompoundTag corrupt = saved.copy();
        corrupt.putLong("NextRealmId", 1L);
        expectIllegal(() -> RealmSavedData.load(corrupt, null), "next realm id validation");
        System.out.println("Realm SavedData self-test passed");
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
