package ru.kaiserroman.millenairearmies.server.realm;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.BattleOutcome;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.DiplomaticDecision;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmDependencyLedger;
import ru.kaiserroman.millenaire.realm.RealmDiplomacyEngine;
import ru.kaiserroman.millenaire.realm.RealmDiplomacyLedger;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmMilitaryPolicy;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;
import ru.kaiserroman.millenaire.realm.WarGoal;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SimulationShockLedger;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Canonical high-level Realm diplomacy. It evaluates only neighbouring or already-known pairs and
 * exposes a narrow military policy to Armies; it never moves units or resolves physical combat.
 */
public final class CanonicalRealmDiplomacyService implements RealmMilitaryPolicy {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final RealmSavedData realms;
    private final SimulationSavedData simulation;
    private final RealmRegistry registry;
    private final RealmDiplomacyLedger ledger;
    private final RealmDependencyLedger dependencies;
    private final RealmDiplomacyEngine engine = new RealmDiplomacyEngine();
    private final int intervalCycles;
    private final int relationsPerTick;
    private final int truceCycles;
    private final long simulationCycleTicks;

    private final long[] realmIds;
    private final int[] settlementCounts;
    private final long[] populations;
    private final long[] powers;
    private final long[] marketSums;
    private final long[] prosperitySums;
    private final long[] threatSums;
    private final int[] cultureCandidates;
    private final long[] cultureBalances;
    private final long[] dominantCulturePopulations;

    private final long[] realmMapKeys;
    private final int[] realmMapRows;
    private final int[] realmMapEpochs;
    private final int realmMapMask;
    private int realmMapEpoch;
    private int snapshotRealmCount;

    private final long[] regionHashKeys;
    private final int[] regionHashHeads;
    private final int[] regionHashEpochs;
    private final int regionHashMask;
    private int regionHashEpoch;

    private final long[] membershipRegions;
    private final long[] membershipRealms;
    private final int[] membershipNext;
    private int membershipCount;
    private final long[] membershipHashRegions;
    private final long[] membershipHashRealms;
    private final int[] membershipHashEpochs;
    private final int membershipHashMask;
    private int membershipHashEpoch;

    private final long[] candidateFirstRealms;
    private final long[] candidateSecondRealms;
    private final byte[] candidateAdjacent;
    private int candidateCount;
    private int candidateCursor;
    private final long[] candidateHashFirst;
    private final long[] candidateHashSecond;
    private final int[] candidateHashRows;
    private final int[] candidateHashEpochs;
    private final int candidateHashMask;
    private int candidateHashEpoch;

    private long snapshotCycle = -1L;
    private long nextSnapshotCycle;
    private long snapshotCount;
    private long evaluatedRelationCount;
    private long statusChangeCount;
    private long declaredWarCount;
    private long blockedExpansionWarCount;
    private long truceCount;
    private long battleOutcomeCount;
    private long capturedSettlementCount;
    private long annexedRealmCount;
    private long subjugatedRealmCount;
    private long liberatedRealmCount;
    private long skippedLegacyCaptureCount;
    private long rejectedCaptureCount;
    private long droppedCandidateCount;
    private int lastTickWorkUnits;

    public CanonicalRealmDiplomacyService(
            RealmSavedData realms,
            SimulationSavedData simulation,
            int maximumRealms,
            int maximumSettlements,
            int maximumRelations,
            int intervalCycles,
            int relationsPerTick,
            int truceCycles,
            long simulationCycleTicks) {
        if (realms == null || simulation == null) {
            throw new NullPointerException("canonical diplomacy stores");
        }
        if (maximumRealms <= 0 || maximumSettlements <= 0 || maximumRelations <= 0
                || intervalCycles <= 0 || relationsPerTick <= 0 || truceCycles <= 0
                || simulationCycleTicks <= 0L) {
            throw new IllegalArgumentException("Invalid canonical diplomacy bounds");
        }
        this.realms = realms;
        this.simulation = simulation;
        registry = realms.registry();
        ledger = realms.diplomacy();
        dependencies = realms.dependencies();
        this.intervalCycles = intervalCycles;
        this.relationsPerTick = relationsPerTick;
        this.truceCycles = truceCycles;
        this.simulationCycleTicks = simulationCycleTicks;

        realmIds = new long[maximumRealms];
        settlementCounts = new int[maximumRealms];
        populations = new long[maximumRealms];
        powers = new long[maximumRealms];
        marketSums = new long[maximumRealms];
        prosperitySums = new long[maximumRealms];
        threatSums = new long[maximumRealms];
        cultureCandidates = new int[maximumRealms];
        cultureBalances = new long[maximumRealms];
        dominantCulturePopulations = new long[maximumRealms];

        int realmMapCapacity = powerOfTwoAtLeast(Math.max(16L, maximumRealms * 2L));
        realmMapKeys = new long[realmMapCapacity];
        realmMapRows = new int[realmMapCapacity];
        realmMapEpochs = new int[realmMapCapacity];
        realmMapMask = realmMapCapacity - 1;

        int regionCapacity = powerOfTwoAtLeast(Math.max(16L, maximumSettlements * 2L));
        regionHashKeys = new long[regionCapacity];
        regionHashHeads = new int[regionCapacity];
        regionHashEpochs = new int[regionCapacity];
        regionHashMask = regionCapacity - 1;
        membershipRegions = new long[maximumSettlements];
        membershipRealms = new long[maximumSettlements];
        membershipNext = new int[maximumSettlements];
        membershipHashRegions = new long[regionCapacity];
        membershipHashRealms = new long[regionCapacity];
        membershipHashEpochs = new int[regionCapacity];
        membershipHashMask = regionCapacity - 1;

        candidateFirstRealms = new long[maximumRelations];
        candidateSecondRealms = new long[maximumRelations];
        candidateAdjacent = new byte[maximumRelations];
        int candidateHashCapacity = powerOfTwoAtLeast(Math.max(16L, maximumRelations * 2L));
        candidateHashFirst = new long[candidateHashCapacity];
        candidateHashSecond = new long[candidateHashCapacity];
        candidateHashRows = new int[candidateHashCapacity];
        candidateHashEpochs = new int[candidateHashCapacity];
        candidateHashMask = candidateHashCapacity - 1;
    }

    public void tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        long cycle = gameTime / simulationCycleTicks;
        lastTickWorkUnits = 0;
        if (candidateCursor >= candidateCount && cycle >= nextSnapshotCycle) {
            buildSnapshot(cycle);
            nextSnapshotCycle = saturatedAdd(cycle, intervalCycles);
        }
        if (candidateCursor >= candidateCount) return;
        int budget = Math.min(relationsPerTick, candidateCount - candidateCursor);
        for (int processed = 0; processed < budget; processed++) {
            evaluateCandidate(candidateCursor++, snapshotCycle);
            lastTickWorkUnits++;
        }
    }

    @Override
    public boolean isAtWar(long sourceRealmId, long targetRealmId) {
        return ledger.isAtWar(sourceRealmId, targetRealmId);
    }

    @Override
    public boolean mayCommandSettlement(long actorId, long settlementId) {
        long realmId = registry.realmOfMember(settlementId);
        return realmId != RealmRegistry.NO_REALM
                && registry.mayControllerCommand(realmId, actorId, settlementId);
    }

    @Override
    public WarGoal warGoal(long sourceRealmId, long targetRealmId) {
        return ledger.warGoal(sourceRealmId, targetRealmId);
    }

    @Override
    public void recordBattleOutcome(BattleOutcome outcome) {
        long oldRevision = ledger.revision();
        WarGoal attackerGoal = ledger.warGoal(
                outcome.attackerRealmId(), outcome.defenderRealmId());
        if (ledger.recordBattleOutcome(outcome, engine)) {
            battleOutcomeCount++;
            LOGGER.info(
                    "[BANNEROK_REALM_BATTLE_OUTCOME] attacker={} defender={} victory={} attacker_losses={} defender_losses={} settlement={} capital={} occupied={} attacker_score={} defender_score={}",
                    outcome.attackerRealmId(),
                    outcome.defenderRealmId(),
                    outcome.attackerVictory(),
                    outcome.attackerLosses(),
                    outcome.defenderLosses(),
                    outcome.settlementId(),
                    outcome.capitalCaptured(),
                    outcome.settlementOccupied(),
                    ledger.warScore(outcome.attackerRealmId(), outcome.defenderRealmId()),
                    ledger.warScore(outcome.defenderRealmId(), outcome.attackerRealmId()));
            if (!resolveDependencyOutcome(outcome, attackerGoal)) {
                captureSettlement(outcome);
            }
        }
        markIfChanged(oldRevision);
    }

    private boolean resolveDependencyOutcome(BattleOutcome outcome, WarGoal attackerGoal) {
        if (!outcome.attackerVictory()
                || !outcome.capitalCaptured()
                || !outcome.settlementOccupied()
                || attackerGoal == null) {
            return false;
        }
        long attackerRealm = outcome.attackerRealmId();
        long defenderRealm = outcome.defenderRealmId();
        if (!registry.exists(attackerRealm)
                || !registry.exists(defenderRealm)
                || realms.isLegacy(attackerRealm)
                || realms.isLegacy(defenderRealm)) {
            return false;
        }
        long cycle = Math.max(0L, snapshotCycle);
        if (attackerGoal == WarGoal.LIBERATE && dependencies.isSubject(defenderRealm)) {
            long oldOverlord = dependencies.overlordOf(defenderRealm);
            if (!dependencies.release(defenderRealm)) return false;
            ledger.applyDecision(
                    attackerRealm,
                    defenderRealm,
                    new DiplomaticDecision(DiplomaticStatus.TRUCE, WarGoal.NONE, 0, 650, 0),
                    cycle,
                    truceCycles);
            realms.markChanged();
            liberatedRealmCount++;
            LOGGER.info(
                    "[BANNEROK_REALM_LIBERATED] liberator={} realm={} former_overlord={} cycle={}",
                    attackerRealm,
                    defenderRealm,
                    oldOverlord,
                    cycle);
            return true;
        }
        if (attackerGoal != WarGoal.SUBJUGATE || registry.hasPlayerMembers(defenderRealm)) {
            return false;
        }
        GovernmentForm government = registry.government(defenderRealm);
        int institutionalResistance = government == null
                ? 500
                : (government.centralization() + government.bureaucracy()) / 2;
        int autonomy = clamp(300 + institutionalResistance / 3);
        int tribute = clamp(280 - autonomy / 5);
        int levy = clamp(320 - autonomy / 4);
        if (!dependencies.establish(
                defenderRealm,
                attackerRealm,
                autonomy,
                tribute,
                levy,
                cycle)) {
            return false;
        }
        ledger.applyDecision(
                attackerRealm,
                defenderRealm,
                new DiplomaticDecision(DiplomaticStatus.TRUCE, WarGoal.NONE, 0, 350, 0),
                cycle,
                truceCycles);
        realms.markChanged();
        subjugatedRealmCount++;
        LOGGER.info(
                "[BANNEROK_REALM_SUBJUGATED] overlord={} subject={} autonomy={} tribute={} levy={} cycle={}",
                attackerRealm,
                defenderRealm,
                autonomy,
                tribute,
                levy,
                cycle);
        return true;
    }

    private void captureSettlement(BattleOutcome outcome) {
        if (!outcome.attackerVictory()
                || !outcome.settlementOccupied()
                || outcome.settlementId() <= 0L) {
            return;
        }
        long attackerRealm = outcome.attackerRealmId();
        long defenderRealm = outcome.defenderRealmId();
        long settlementId = outcome.settlementId();
        if (realms.isLegacy(attackerRealm) || realms.isLegacy(defenderRealm)) {
            skippedLegacyCaptureCount++;
            return;
        }
        RealmMemberKind oldKind = registry.memberKind(settlementId);
        if (!registry.exists(attackerRealm)
                || !registry.exists(defenderRealm)
                || registry.realmOfMember(settlementId) != defenderRealm
                || oldKind == null
                || oldKind == RealmMemberKind.PLAYER) {
            rejectedCaptureCount++;
            return;
        }
        int oldInfluence = registry.memberInfluence(settlementId);
        int occupationInfluence = Math.max(250, oldInfluence * 2 / 3);
        boolean wasCapital = registry.capitalMemberId(defenderRealm) == settlementId;
        boolean annexed = false;
        if (wasCapital) {
            long[] replacement = {0L};
            int[] bestInfluence = {-1};
            registry.visitMembers(defenderRealm, (memberId, kind, controllerId, influence) -> {
                if (memberId != settlementId
                        && kind != RealmMemberKind.PLAYER
                        && influence > bestInfluence[0]) {
                    replacement[0] = memberId;
                    bestInfluence[0] = influence;
                }
            });
            if (replacement[0] != 0L) {
                if (!registry.setCapital(defenderRealm, replacement[0])) {
                    rejectedCaptureCount++;
                    return;
                }
            } else {
                realms.institutions().removeRealm(defenderRealm);
                realms.lifecycle().removeCrisis(defenderRealm);
                ledger.removeRealm(defenderRealm);
                dependencies.removeRealm(defenderRealm);
                realms.history().removeRealm(defenderRealm);
                registry.dissolveRealm(defenderRealm);
                realms.removeMetadata(defenderRealm);
                if (!registry.addMember(
                        attackerRealm,
                        settlementId,
                        RealmMemberKind.NPC_SETTLEMENT,
                        0L,
                        occupationInfluence)) {
                    throw new IllegalStateException("Could not attach annexed settlement to victor Realm");
                }
                annexed = true;
            }
        }
        if (!annexed && !registry.updateMember(
                settlementId,
                attackerRealm,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                occupationInfluence)) {
            rejectedCaptureCount++;
            return;
        }

        UUID settlementUuid = realms.keys().uuid(settlementId);
        long simulationSettlement = simulation.keys().findSettlement(settlementUuid);
        if (simulationSettlement != 0L) {
            simulation.state().assignRealm(simulationSettlement, attackerRealm);
            simulation.markChanged();
        }
        realms.recordCapture(attackerRealm);
        realms.markChanged();
        capturedSettlementCount++;
        if (annexed) annexedRealmCount++;
        LOGGER.info(
                "[BANNEROK_REALM_CAPTURE] attacker={} defender={} settlement={} former_capital={} annexed={} old_kind={} influence={}",
                attackerRealm,
                defenderRealm,
                settlementId,
                wasCapital,
                annexed,
                oldKind,
                occupationInfluence);
    }

    /** Trusted administrative/script boundary. */
    public boolean declareWar(long sourceRealm, long targetRealm, WarGoal goal, long cycle) {
        if (!registry.exists(sourceRealm) || !registry.exists(targetRealm)
                || sourceRealm == targetRealm || goal == null
                || goal == WarGoal.NONE || goal == WarGoal.DEFEND || cycle < 0L
                || !dependencies.mayConductIndependentDiplomacy(sourceRealm)
                || dependencies.overlordOf(targetRealm) == sourceRealm) {
            return false;
        }
        if (isExpansionGoal(goal) && !mayInitiateExpansion(sourceRealm)) {
            blockedExpansionWarCount++;
            return false;
        }
        long oldRevision = ledger.revision();
        boolean applied = ledger.applyDecision(
                sourceRealm,
                targetRealm,
                new DiplomaticDecision(DiplomaticStatus.WAR, goal, 1000, 0, 0),
                cycle,
                truceCycles);
        markIfChanged(oldRevision);
        if (applied) declaredWarCount++;
        return applied;
    }

    /** Trusted administrative/script boundary. */
    public boolean makeTruce(long sourceRealm, long targetRealm, long cycle) {
        if (!registry.exists(sourceRealm) || !registry.exists(targetRealm)
                || sourceRealm == targetRealm || cycle < 0L) {
            return false;
        }
        long oldRevision = ledger.revision();
        boolean applied = ledger.applyDecision(
                sourceRealm,
                targetRealm,
                new DiplomaticDecision(DiplomaticStatus.TRUCE, WarGoal.NONE, 0, 500, 0),
                cycle,
                truceCycles);
        markIfChanged(oldRevision);
        if (applied) truceCount++;
        return applied;
    }

    public DiplomaticStatus status(long firstRealm, long secondRealm, long cycle) {
        return ledger.status(firstRealm, secondRealm, cycle);
    }

    public boolean hasPendingEvaluation() { return candidateCursor < candidateCount; }
    public long snapshotCount() { return snapshotCount; }
    public long evaluatedRelationCount() { return evaluatedRelationCount; }
    public long statusChangeCount() { return statusChangeCount; }
    public long declaredWarCount() { return declaredWarCount; }
    public long blockedExpansionWarCount() { return blockedExpansionWarCount; }
    public long truceCount() { return truceCount; }
    public long battleOutcomeCount() { return battleOutcomeCount; }
    public long capturedSettlementCount() { return capturedSettlementCount; }
    public long annexedRealmCount() { return annexedRealmCount; }
    public long subjugatedRealmCount() { return subjugatedRealmCount; }
    public long liberatedRealmCount() { return liberatedRealmCount; }
    public long skippedLegacyCaptureCount() { return skippedLegacyCaptureCount; }
    public long rejectedCaptureCount() { return rejectedCaptureCount; }
    public long droppedCandidateCount() { return droppedCandidateCount; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_CANONICAL_DIPLOMACY_METRICS] relations={} dependencies={} snapshots={} evaluated={} status_changes={} wars={} blocked_expansion_wars={} truces={} battle_outcomes={} captured_settlements={} annexed_realms={} subjugated_realms={} liberated_realms={} skipped_legacy_captures={} rejected_captures={} dropped_candidates={} pending={} last_work={}",
                ledger.size(),
                dependencies.size(),
                snapshotCount,
                evaluatedRelationCount,
                statusChangeCount,
                declaredWarCount,
                blockedExpansionWarCount,
                truceCount,
                battleOutcomeCount,
                capturedSettlementCount,
                annexedRealmCount,
                subjugatedRealmCount,
                liberatedRealmCount,
                skippedLegacyCaptureCount,
                rejectedCaptureCount,
                droppedCandidateCount,
                candidateCount - candidateCursor,
                lastTickWorkUnits);
    }

    private void buildSnapshot(long cycle) {
        beginRealmMapEpoch();
        beginRegionEpoch();
        beginMembershipEpoch();
        beginCandidateEpoch();
        snapshotRealmCount = 0;
        membershipCount = 0;
        candidateCount = 0;
        candidateCursor = 0;

        registry.visitRealms((realmId, capitalMemberId, foundedCycle, government, legitimacy) -> {
            if (snapshotRealmCount == realmIds.length) {
                throw new IllegalStateException("Realm count exceeds diplomacy capacity");
            }
            int row = snapshotRealmCount++;
            realmIds[row] = realmId;
            putRealmRow(realmId, row);
        });
        clearAggregates(snapshotRealmCount);

        PackedSettlementSimulationState state = simulation.state();
        SimulationShockLedger shocks = simulation.shocks();
        for (int settlement = 0; settlement < state.size(); settlement++) {
            if (state.statusAt(settlement) == SettlementStatus.RUINED) continue;
            int realmRow = findRealmRow(state.realmIdAt(settlement));
            if (realmRow < 0) continue;
            long population = Math.max(0L, state.populationAt(settlement));
            settlementCounts[realmRow]++;
            populations[realmRow] = saturatedAdd(populations[realmRow], population);
            long militaryPower = population
                    + state.securityAt(settlement) * 2L
                    + state.productiveCapitalAt(settlement) * 2L
                    + state.buildingCountAt(settlement) * 20L;
            powers[realmRow] = saturatedAdd(powers[realmRow], militaryPower);
            marketSums[realmRow] = saturatedAdd(
                    marketSums[realmRow], state.marketAccessAt(settlement));
            prosperitySums[realmRow] = saturatedAdd(
                    prosperitySums[realmRow],
                    (state.productivityAt(settlement)
                                    + state.stabilityAt(settlement)
                                    + state.attractivenessAt(settlement)
                                    + state.productiveCapitalAt(settlement))
                            / 4L);
            threatSums[realmRow] = saturatedAdd(
                    threatSums[realmRow], state.damageAt(settlement));
            updateCultureCandidate(realmRow, state.cultureKeyAt(settlement), population);
            addRegionMembership(state.regionKeyAt(settlement), state.realmIdAt(settlement));
            for (int shock = 0; shock < shocks.size(); shock++) {
                if (shocks.matchesAt(
                        shock,
                        state.settlementIdAt(settlement),
                        state.regionKeyAt(settlement),
                        state.cultureKeyAt(settlement),
                        cycle)) {
                    threatSums[realmRow] = saturatedAdd(
                            threatSums[realmRow], shocks.magnitudeAt(shock));
                }
            }
        }
        for (int settlement = 0; settlement < state.size(); settlement++) {
            int realmRow = findRealmRow(state.realmIdAt(settlement));
            if (realmRow >= 0
                    && state.statusAt(settlement) != SettlementStatus.RUINED
                    && state.cultureKeyAt(settlement) == cultureCandidates[realmRow]) {
                dominantCulturePopulations[realmRow] = saturatedAdd(
                        dominantCulturePopulations[realmRow], state.populationAt(settlement));
            }
        }

        for (int slot = 0; slot < regionHashEpochs.length; slot++) {
            if (regionHashEpochs[slot] != regionHashEpoch) continue;
            for (int firstEntry = regionHashHeads[slot] - 1;
                    firstEntry >= 0;
                    firstEntry = membershipNext[firstEntry]) {
                for (int secondEntry = membershipNext[firstEntry];
                        secondEntry >= 0;
                        secondEntry = membershipNext[secondEntry]) {
                    addCandidate(
                            membershipRealms[firstEntry],
                            membershipRealms[secondEntry],
                            true);
                }
            }
        }
        ledger.visit((firstRealm, secondRealm, status, firstGoal, secondGoal,
                firstTrust, secondTrust, firstGrievances, secondGrievances,
                firstFear, secondFear, firstClaims, secondClaims,
                firstExhaustion, secondExhaustion, firstWarScore, secondWarScore,
                tradeInterdependence, borderFriction, ideologicalDistance,
                commonThreat, truceUntilCycle, lastEvaluationCycle) ->
                addCandidate(firstRealm, secondRealm, false));
        snapshotCycle = cycle;
        snapshotCount++;
    }

    private void evaluateCandidate(int candidate, long cycle) {
        long firstRealm = candidateFirstRealms[candidate];
        long secondRealm = candidateSecondRealms[candidate];
        int firstRow = findRealmRow(firstRealm);
        int secondRow = findRealmRow(secondRealm);
        if (firstRow < 0 || secondRow < 0
                || !registry.exists(firstRealm)
                || !registry.exists(secondRealm)) {
            return;
        }

        long oldRevision = ledger.revision();
        int relation = ledger.ensureRelation(firstRealm, secondRealm, cycle);
        if (relation < 0) {
            droppedCandidateCount++;
            return;
        }
        long previousEvaluation = ledger.lastEvaluationCycle(firstRealm, secondRealm);
        int elapsedCycles = previousEvaluation < 0L
                ? intervalCycles
                : Math.max(1, saturatedInt(cycle - previousEvaluation));
        ledger.recover(firstRealm, secondRealm, elapsedCycles);

        Constitution firstConstitution = constitution(firstRealm);
        Constitution secondConstitution = constitution(secondRealm);
        boolean adjacent = candidateAdjacent[candidate] != 0;
        int ideology = ideologyDistance(firstConstitution, secondConstitution);
        int trade = Math.min(
                average(marketSums[firstRow], settlementCounts[firstRow]),
                average(marketSums[secondRow], settlementCounts[secondRow]));
        if (!adjacent) trade /= 2;
        int firstThreat = average(threatSums[firstRow], settlementCounts[firstRow]);
        int secondThreat = average(threatSums[secondRow], settlementCounts[secondRow]);
        int border = adjacent ? clamp(540 + (firstThreat + secondThreat) / 6) : 80;
        int sharedThreat = Math.min(firstThreat, secondThreat);
        int firstPower = powerAdvantage(powers[firstRow], powers[secondRow]);
        int secondPower = 1000 - firstPower;
        int firstGrievances = ledger.grievances(firstRealm, secondRealm);
        int secondGrievances = ledger.grievances(secondRealm, firstRealm);
        boolean sameCulture = cultureCandidates[firstRow] != 0
                && cultureCandidates[firstRow] == cultureCandidates[secondRow];
        int culturalTrust = sameCulture ? 650 : 360;
        int firstTrust = clamp(culturalTrust + trade / 4 + (1000 - ideology) / 5
                - firstGrievances / 2 - border / 6);
        int secondTrust = clamp(culturalTrust + trade / 4 + (1000 - ideology) / 5
                - secondGrievances / 2 - border / 6);
        int firstFear = clamp(500 - firstPower + secondThreat / 3);
        int secondFear = clamp(500 - secondPower + firstThreat / 3);
        int firstClaims = clamp(border * 2 / 3
                + firstConstitution.militarization() / 4 + firstGrievances / 3);
        int secondClaims = clamp(border * 2 / 3
                + secondConstitution.militarization() / 4 + secondGrievances / 3);

        ledger.updateDrivers(
                firstRealm, secondRealm,
                firstTrust, firstGrievances, firstFear, firstClaims,
                trade, border, ideology, sharedThreat, cycle);
        ledger.updateDrivers(
                secondRealm, firstRealm,
                secondTrust, secondGrievances, secondFear, secondClaims,
                trade, border, ideology, sharedThreat, cycle);

        DiplomaticStatus oldStatus = ledger.status(firstRealm, secondRealm, cycle);
        DiplomaticDecision firstDecision = engine.evaluate(
                oldStatus,
                ledger.inputs(firstRealm, secondRealm, firstPower, cycle));
        DiplomaticDecision secondDecision = engine.evaluate(
                oldStatus,
                ledger.inputs(secondRealm, firstRealm, secondPower, cycle));
        Commit commit = resolveDecision(
                firstRealm, secondRealm, oldStatus, firstDecision, secondDecision);
        if (commit == null) {
            ledger.markEvaluated(firstRealm, secondRealm, cycle);
        } else if (commit.decision().status() == DiplomaticStatus.WAR
                && isExpansionGoal(commit.decision().warGoal())
                && !mayInitiateExpansion(commit.sourceRealm())) {
            blockedExpansionWarCount++;
            ledger.markEvaluated(firstRealm, secondRealm, cycle);
            commit = null;
        } else {
            ledger.applyDecision(
                    commit.sourceRealm(),
                    commit.targetRealm(),
                    commit.decision(),
                    cycle,
                    truceCycles);
        }
        DiplomaticStatus newStatus = ledger.status(firstRealm, secondRealm, cycle);
        if (newStatus != oldStatus) {
            statusChangeCount++;
            if (newStatus == DiplomaticStatus.WAR) declaredWarCount++;
            if (newStatus == DiplomaticStatus.TRUCE) truceCount++;
            LOGGER.info(
                    "[BANNEROK_REALM_DIPLOMACY] first={} second={} old={} new={} initiator={} goal={} first_power={} trade={} border={} ideology={} first_grievances={} second_grievances={} cycle={}",
                    firstRealm,
                    secondRealm,
                    oldStatus,
                    newStatus,
                    commit == null ? 0L : commit.sourceRealm(),
                    commit == null ? WarGoal.NONE : commit.decision().warGoal(),
                    firstPower,
                    trade,
                    border,
                    ideology,
                    firstGrievances,
                    secondGrievances,
                    cycle);
        }
        evaluatedRelationCount++;
        markIfChanged(oldRevision);
    }

    private Commit resolveDecision(
            long firstRealm,
            long secondRealm,
            DiplomaticStatus current,
            DiplomaticDecision first,
            DiplomaticDecision second) {
        if (current == DiplomaticStatus.WAR) {
            if (first.status() == DiplomaticStatus.TRUCE || second.status() == DiplomaticStatus.TRUCE) {
                boolean firstMoreExhausted = ledger.exhaustion(firstRealm, secondRealm)
                        >= ledger.exhaustion(secondRealm, firstRealm);
                long source = firstMoreExhausted ? firstRealm : secondRealm;
                long target = firstMoreExhausted ? secondRealm : firstRealm;
                DiplomaticDecision decision = new DiplomaticDecision(
                        DiplomaticStatus.TRUCE,
                        WarGoal.NONE,
                        Math.max(first.aggressionPressure(), second.aggressionPressure()),
                        Math.max(first.cooperationPressure(), second.cooperationPressure()),
                        first.reasonMask() | second.reasonMask());
                return new Commit(source, target, decision);
            }
            boolean firstAttacker = ledger.warGoal(firstRealm, secondRealm) != WarGoal.DEFEND
                    && ledger.warGoal(firstRealm, secondRealm) != WarGoal.NONE;
            return firstAttacker
                    ? new Commit(firstRealm, secondRealm, first)
                    : new Commit(secondRealm, firstRealm, second);
        }
        if (current == DiplomaticStatus.TRUCE) return null;
        if (first.status() == DiplomaticStatus.WAR || second.status() == DiplomaticStatus.WAR) {
            boolean useFirst = first.status() == DiplomaticStatus.WAR
                    && (second.status() != DiplomaticStatus.WAR
                            || first.aggressionPressure() >= second.aggressionPressure());
            return useFirst
                    ? new Commit(firstRealm, secondRealm, first)
                    : new Commit(secondRealm, firstRealm, second);
        }
        if (first.status() == DiplomaticStatus.ALLIANCE
                && second.status() == DiplomaticStatus.ALLIANCE) {
            return new Commit(firstRealm, secondRealm, new DiplomaticDecision(
                    DiplomaticStatus.ALLIANCE,
                    WarGoal.NONE,
                    Math.max(first.aggressionPressure(), second.aggressionPressure()),
                    Math.min(first.cooperationPressure(), second.cooperationPressure()),
                    first.reasonMask() | second.reasonMask()));
        }
        if (first.status() == DiplomaticStatus.TENSION
                || second.status() == DiplomaticStatus.TENSION) {
            return new Commit(firstRealm, secondRealm, new DiplomaticDecision(
                    DiplomaticStatus.TENSION,
                    WarGoal.NONE,
                    Math.max(first.aggressionPressure(), second.aggressionPressure()),
                    Math.min(first.cooperationPressure(), second.cooperationPressure()),
                    first.reasonMask() | second.reasonMask()));
        }
        return new Commit(firstRealm, secondRealm, new DiplomaticDecision(
                DiplomaticStatus.PEACE,
                WarGoal.NONE,
                Math.max(first.aggressionPressure(), second.aggressionPressure()),
                Math.min(first.cooperationPressure(), second.cooperationPressure()),
                first.reasonMask() | second.reasonMask()));
    }

    private Constitution constitution(long realmId) {
        Constitution value = realms.institutions().constitution(realmId);
        if (value != null) return value;
        GovernmentForm government = registry.government(realmId);
        return Constitution.archetype(
                government == null ? GovernmentForm.CLAN_CONFEDERATION : government,
                registry.legitimacy(realmId));
    }

    private void addRegionMembership(long regionKey, long realmId) {
        if (realmId <= 0L) return;
        int memberSlot = hashPair(regionKey, realmId) & membershipHashMask;
        while (membershipHashEpochs[memberSlot] == membershipHashEpoch) {
            if (membershipHashRegions[memberSlot] == regionKey
                    && membershipHashRealms[memberSlot] == realmId) {
                return;
            }
            memberSlot = (memberSlot + 1) & membershipHashMask;
        }
        if (membershipCount == membershipRegions.length) {
            throw new IllegalStateException("Realm-region membership capacity exhausted");
        }
        int regionSlot = hashLong(regionKey) & regionHashMask;
        while (regionHashEpochs[regionSlot] == regionHashEpoch
                && regionHashKeys[regionSlot] != regionKey) {
            regionSlot = (regionSlot + 1) & regionHashMask;
        }
        if (regionHashEpochs[regionSlot] != regionHashEpoch) {
            regionHashEpochs[regionSlot] = regionHashEpoch;
            regionHashKeys[regionSlot] = regionKey;
            regionHashHeads[regionSlot] = 0;
        }
        int entry = membershipCount++;
        membershipRegions[entry] = regionKey;
        membershipRealms[entry] = realmId;
        membershipNext[entry] = regionHashHeads[regionSlot] - 1;
        regionHashHeads[regionSlot] = entry + 1;
        membershipHashEpochs[memberSlot] = membershipHashEpoch;
        membershipHashRegions[memberSlot] = regionKey;
        membershipHashRealms[memberSlot] = realmId;
    }

    private void addCandidate(long sourceRealm, long targetRealm, boolean adjacent) {
        if (sourceRealm <= 0L || targetRealm <= 0L || sourceRealm == targetRealm) return;
        long first = Math.min(sourceRealm, targetRealm);
        long second = Math.max(sourceRealm, targetRealm);
        int slot = hashPair(first, second) & candidateHashMask;
        while (candidateHashEpochs[slot] == candidateHashEpoch) {
            if (candidateHashFirst[slot] == first && candidateHashSecond[slot] == second) {
                if (adjacent) candidateAdjacent[candidateHashRows[slot]] = 1;
                return;
            }
            slot = (slot + 1) & candidateHashMask;
        }
        if (candidateCount == candidateFirstRealms.length) {
            droppedCandidateCount++;
            return;
        }
        int row = candidateCount++;
        candidateFirstRealms[row] = first;
        candidateSecondRealms[row] = second;
        candidateAdjacent[row] = adjacent ? (byte) 1 : (byte) 0;
        candidateHashEpochs[slot] = candidateHashEpoch;
        candidateHashFirst[slot] = first;
        candidateHashSecond[slot] = second;
        candidateHashRows[slot] = row;
    }

    private void putRealmRow(long realmId, int row) {
        int slot = hashLong(realmId) & realmMapMask;
        while (realmMapEpochs[slot] == realmMapEpoch) {
            if (realmMapKeys[slot] == realmId) {
                throw new IllegalStateException("Duplicate Realm id in diplomacy snapshot");
            }
            slot = (slot + 1) & realmMapMask;
        }
        realmMapEpochs[slot] = realmMapEpoch;
        realmMapKeys[slot] = realmId;
        realmMapRows[slot] = row;
    }

    private int findRealmRow(long realmId) {
        if (realmId <= 0L) return -1;
        int slot = hashLong(realmId) & realmMapMask;
        while (realmMapEpochs[slot] == realmMapEpoch) {
            if (realmMapKeys[slot] == realmId) return realmMapRows[slot];
            slot = (slot + 1) & realmMapMask;
        }
        return -1;
    }

    private void updateCultureCandidate(int realmRow, int cultureKey, long population) {
        if (cultureKey <= 0 || population <= 0L) return;
        if (cultureBalances[realmRow] == 0L) {
            cultureCandidates[realmRow] = cultureKey;
            cultureBalances[realmRow] = population;
        } else if (cultureCandidates[realmRow] == cultureKey) {
            cultureBalances[realmRow] = saturatedAdd(cultureBalances[realmRow], population);
        } else if (cultureBalances[realmRow] > population) {
            cultureBalances[realmRow] -= population;
        } else {
            cultureCandidates[realmRow] = cultureKey;
            cultureBalances[realmRow] = population - cultureBalances[realmRow];
        }
    }

    private void clearAggregates(int count) {
        Arrays.fill(settlementCounts, 0, count, 0);
        Arrays.fill(populations, 0, count, 0L);
        Arrays.fill(powers, 0, count, 0L);
        Arrays.fill(marketSums, 0, count, 0L);
        Arrays.fill(prosperitySums, 0, count, 0L);
        Arrays.fill(threatSums, 0, count, 0L);
        Arrays.fill(cultureCandidates, 0, count, 0);
        Arrays.fill(cultureBalances, 0, count, 0L);
        Arrays.fill(dominantCulturePopulations, 0, count, 0L);
    }

    private void beginRealmMapEpoch() {
        realmMapEpoch++;
        if (realmMapEpoch == 0) {
            Arrays.fill(realmMapEpochs, 0);
            realmMapEpoch = 1;
        }
    }

    private void beginRegionEpoch() {
        regionHashEpoch++;
        if (regionHashEpoch == 0) {
            Arrays.fill(regionHashEpochs, 0);
            regionHashEpoch = 1;
        }
    }

    private void beginMembershipEpoch() {
        membershipHashEpoch++;
        if (membershipHashEpoch == 0) {
            Arrays.fill(membershipHashEpochs, 0);
            membershipHashEpoch = 1;
        }
    }

    private void beginCandidateEpoch() {
        candidateHashEpoch++;
        if (candidateHashEpoch == 0) {
            Arrays.fill(candidateHashEpochs, 0);
            candidateHashEpoch = 1;
        }
    }

    private boolean mayInitiateExpansion(long realmId) {
        RealmHistoricalPhase phase = realms.history().phase(realmId);
        RealmStatePriority priority = realms.statePriority(realmId);
        if (phase == null) return priority == RealmStatePriority.NONE
                || priority == RealmStatePriority.EXPANSION;
        return phase.permitsExpansion()
                && (priority == RealmStatePriority.NONE
                        || priority == RealmStatePriority.EXPANSION)
                && realms.history().viability(realmId) >= 600
                && realms.history().expansionReadiness(realmId) >= 700;
    }

    private static boolean isExpansionGoal(WarGoal goal) {
        return goal == WarGoal.BORDER_CLAIM
                || goal == WarGoal.SUBJUGATE
                || goal == WarGoal.TRADE_ACCESS
                || goal == WarGoal.SUCCESSION;
    }

    private void markIfChanged(long oldRevision) {
        if (ledger.revision() != oldRevision) realms.markChanged();
    }

    private static int ideologyDistance(Constitution first, Constitution second) {
        long total = Math.abs(first.centralization() - second.centralization())
                + Math.abs(first.bureaucracy() - second.bureaucracy())
                + Math.abs(first.noblePower() - second.noblePower())
                + Math.abs(first.merchantPower() - second.merchantPower())
                + Math.abs(first.citizenPower() - second.citizenPower())
                + Math.abs(first.marketFreedom() - second.marketFreedom())
                + Math.abs(first.militarization() - second.militarization());
        return clamp((int) (total / 7L));
    }

    private static int powerAdvantage(long sourcePower, long targetPower) {
        long total = sourcePower + targetPower;
        if (total <= 0L) return 500;
        long delta = sourcePower - targetPower;
        return clamp((int) (500L + delta * 500L / total));
    }

    private static int average(long sum, int count) {
        return count <= 0 ? 0 : clamp((int) Math.min(Integer.MAX_VALUE, sum / count));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int saturatedInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedAdd(long left, int right) {
        return saturatedAdd(left, (long) right);
    }

    private static int powerOfTwoAtLeast(long requested) {
        int capacity = 1;
        while (capacity < requested) {
            if (capacity >= 1 << 29) {
                throw new IllegalArgumentException("Canonical diplomacy table is too large");
            }
            capacity <<= 1;
        }
        return capacity;
    }

    private static int hashPair(long first, long second) {
        return hashLong(first ^ Long.rotateLeft(second, 29));
    }

    private static int hashLong(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }

    private record Commit(long sourceRealm, long targetRealm, DiplomaticDecision decision) {}
}
