package ru.kaiserroman.millenaire.realm;

/** Executable assertions keep the pure Realm kernel independent from Minecraft and NeoForge. */
public final class RealmCoreSelfTest {
    private RealmCoreSelfTest() {}

    public static void main(String[] args) {
        registrySupportsNpcPlayerAndMixedRealms();
        lifecycleUsesHysteresis();
        governmentEvolutionIsPluralAndPathDependent();
        administrationConstrainsSnowballing();
        historicalTempoSupportsLongStabilityRapidCollapseAndRecovery();
        statePlannerChoosesMaterialProgrammesAndExpansion();
        collapsingRealmsLoseProvincesToSuccessorStates();
        dependencyHierarchyIsAcyclicAndBounded();
        institutionLedgerPersistsPathDependence();
        lifecycleLedgerPersistsHysteresis();
        diplomacyLedgerPersistsDirectedWarState();
        diplomacyProducesMandatesNotBattles();
        System.out.println("RealmCoreSelfTest: OK");
    }

    private static void registrySupportsNpcPlayerAndMixedRealms() {
        RealmRegistry registry = new RealmRegistry(16, 64);
        long npcRealm = registry.createRealm(
                101L, RealmMemberKind.NPC_SETTLEMENT, 0L,
                GovernmentForm.CLAN_CONFEDERATION, 720, 10L);
        assert npcRealm != RealmRegistry.NO_REALM;
        assert registry.addMember(npcRealm, 102L, RealmMemberKind.NPC_SETTLEMENT, 0L, 440);
        assert !registry.hasPlayerMembers(npcRealm);
        assert registry.settlementCount(npcRealm) == 2;

        long mixedRealm = registry.createRealm(
                201L, RealmMemberKind.PLAYER_SETTLEMENT, 9001L,
                GovernmentForm.FEUDAL_MONARCHY, 680, 20L);
        assert mixedRealm != RealmRegistry.NO_REALM;
        assert registry.addMember(mixedRealm, 202L, RealmMemberKind.NPC_SETTLEMENT, 0L, 350);
        assert registry.addMember(mixedRealm, 301L, RealmMemberKind.PLAYER, 9001L, 700);
        assert registry.hasPlayerMembers(mixedRealm);
        assert registry.mayControllerCommand(mixedRealm, 9001L, 202L);

        assert registry.transferMember(102L, mixedRealm);
        assert registry.realmOfMember(102L) == mixedRealm;
        assert registry.memberCount(npcRealm) == 1;
        assert registry.dissolveRealm(npcRealm);
        assert registry.realmOfMember(101L) == RealmRegistry.NO_REALM;
        assert registry.realmCount() == 1;
        assert registry.revision() > 0L;
    }

    private static void lifecycleUsesHysteresis() {
        RealmFormationPolicy policy = new RealmFormationPolicy();
        RealmFormationPolicy.FormationContext formation = new RealmFormationPolicy.FormationContext(
                4, 420L, 850, 780, 620, 760, 810, 680, 300);
        assert policy.formationPressure(formation) >= RealmFormationPolicy.FORMATION_THRESHOLD;
        assert !policy.shouldForm(formation, 7_999);
        assert policy.shouldForm(formation, 8_000);

        RealmFormationPolicy.DissolutionContext stable = new RealmFormationPolicy.DissolutionContext(
                5, true, 780, 760, 700, 180, 160, 720);
        assert !policy.shouldDissolve(stable, 30_000);

        RealmFormationPolicy.DissolutionContext collapsing = new RealmFormationPolicy.DissolutionContext(
                7, true, 120, 220, 180, 900, 880, 170);
        assert policy.dissolutionPressure(collapsing) >= RealmFormationPolicy.DISSOLUTION_THRESHOLD;
        assert !policy.shouldDissolve(collapsing, 5_999);
        assert policy.shouldDissolve(collapsing, 6_000);
        assert policy.shouldSplit(collapsing, 8_000);

        RealmFormationPolicy.DissolutionContext viableCityState =
                new RealmFormationPolicy.DissolutionContext(
                        1, true, 760, 1000, 750, 80, 100, 720);
        assert policy.dissolutionPressure(viableCityState) < RealmFormationPolicy.DISSOLUTION_THRESHOLD;
        assert !policy.shouldDissolve(viableCityState, 100_000);
    }

    private static void governmentEvolutionIsPluralAndPathDependent() {
        GovernmentEvolution evolution = new GovernmentEvolution();
        Constitution feudal = Constitution.archetype(GovernmentForm.FEUDAL_MONARCHY, 760);
        RealmIndicators commercial = new RealmIndicators(
                6, 2_500L, 860, 920, 880, 220, 780,
                180, 120, 320, 820, 380, 760, 760, 900);
        EvolutionDecision commercialDecision = evolution.evaluate(feudal, commercial, 30_000);
        assert commercialDecision.changesGovernment();
        assert commercialDecision.proposed() != GovernmentForm.FEUDAL_MONARCHY;
        assert commercialDecision.proposed() == GovernmentForm.MERCHANT_REPUBLIC
                || commercialDecision.proposed() == GovernmentForm.CITY_LEAGUE
                || commercialDecision.proposed() == GovernmentForm.CITIZEN_POLITY
                || commercialDecision.proposed() == GovernmentForm.COMMERCIAL_MONARCHY;
        Constitution reformed = evolution.apply(feudal, commercialDecision, 120);
        assert reformed.government() == commercialDecision.proposed();
        assert reformed.legitimacy() < feudal.legitimacy();

        Constitution confederation = Constitution.archetype(GovernmentForm.CLAN_CONFEDERATION, 210);
        RealmIndicators emergency = new RealmIndicators(
                9, 4_000L, 420, 260, 180, 640, 660,
                960, 260, 920, 120, 860, 520, 210, 300);
        EvolutionDecision emergencyDecision = evolution.evaluate(confederation, emergency, 3_000);
        assert emergencyDecision.changesGovernment();
        assert emergencyDecision.proposed() == GovernmentForm.MILITARY_AUTOCRACY
                || emergencyDecision.proposed() == GovernmentForm.BUREAUCRATIC_MONARCHY;
    }

    private static void administrationConstrainsSnowballing() {
        RealmAdministrationPolicy policy = new RealmAdministrationPolicy();
        Constitution capable = Constitution.archetype(
                GovernmentForm.BUREAUCRATIC_MONARCHY, 800);
        RealmIndicators compact = new RealmIndicators(
                4, 1_000L, 600, 750, 450, 250, 850,
                200, 100, 200, 600, 800, 850, 800, 750);
        AdministrativeAssessment stable = policy.evaluate(capable, compact);
        assert stable.capacity() > stable.load();
        assert stable.coverage() >= 900;
        assert stable.corruption() < 200;
        assert stable.taxEfficiency() > 800;
        assert stable.separatismPressure() < 300;
        assert stable.legitimacyDelta() > 0;
        assert !stable.overextended();
        assert !stable.secessionRisk();
        assert stable.collectibleRevenue(10_000L) == 10L * stable.taxEfficiency();
        assert stable.applyLegitimacy(capable).legitimacy()
                == capable.legitimacy() + stable.legitimacyDelta();

        Constitution weak = Constitution.archetype(
                GovernmentForm.CLAN_CONFEDERATION, 250);
        RealmIndicators overstretched = new RealmIndicators(
                18, 12_000L, 250, 250, 200, 800, 150,
                800, 850, 800, 150, 250, 200, 250, 200);
        AdministrativeAssessment crisis = policy.evaluate(weak, overstretched);
        assert crisis.load() > crisis.capacity();
        assert crisis.coverage() < 400;
        assert crisis.corruption() > 600;
        assert crisis.taxEfficiency() < 200;
        assert crisis.separatismPressure() > 800;
        assert crisis.legitimacyDelta() < 0;
        assert crisis.overextended();
        assert crisis.secessionRisk();
        assert (crisis.reasonMask() & RealmAdministrationPolicy.REASON_SIZE) != 0;
        assert (crisis.reasonMask() & RealmAdministrationPolicy.REASON_CAPACITY_DEFICIT) != 0;
        assert (crisis.reasonMask() & RealmAdministrationPolicy.REASON_SECESSION_RISK) != 0;
    }

    private static void historicalTempoSupportsLongStabilityRapidCollapseAndRecovery() {
        RealmHistoricalPolicy policy = new RealmHistoricalPolicy();
        RealmHistoricalInputs prosperousEmpire = new RealmHistoricalInputs(
                12, 10_000L, 900, 900, 850, 750, 800, 800,
                750, 800, 800, 850, 100, 100, 700, true);
        RealmHistoricalAssessment state = policy.initial(prosperousEmpire, 0L);
        assert state.phase() == RealmHistoricalPhase.ASCENDANT;
        assert state.scale() == RealmScale.EMPIRE;
        assert state.viability() > 850;
        assert state.mayExpand();

        for (int year = 1; year <= 250; year++) {
            state = policy.evaluate(
                    state.phase(),
                    state.scale(),
                    state.crisisMomentum(),
                    state.recoveryMomentum(),
                    state.phaseSinceMilliYear(),
                    prosperousEmpire,
                    1_000,
                    year * 1_000L);
        }
        assert state.phase() == RealmHistoricalPhase.ASCENDANT;
        assert state.crisisMomentum() == 0;

        RealmHistoricalInputs imperialCrisis = new RealmHistoricalInputs(
                12, 7_500L, 600, 100, 200, 350, 200, 500,
                300, 300, 100, 500, 1000, 1000, 200, true);
        boolean leftAscendancyWithinTwentyYears = false;
        for (int year = 251; year <= 270; year++) {
            RealmHistoricalPhase previous = state.phase();
            state = policy.evaluate(
                    state.phase(),
                    state.scale(),
                    state.crisisMomentum(),
                    state.recoveryMomentum(),
                    state.phaseSinceMilliYear(),
                    imperialCrisis,
                    1_000,
                    year * 1_000L);
            if (previous == RealmHistoricalPhase.ASCENDANT
                    && state.phase() != RealmHistoricalPhase.ASCENDANT) {
                leftAscendancyWithinTwentyYears = true;
            }
        }
        assert leftAscendancyWithinTwentyYears;
        assert state.phase() == RealmHistoricalPhase.STRAINED
                || state.phase() == RealmHistoricalPhase.DECADENT;
        for (int year = 271; year <= 300; year++) {
            state = policy.evaluate(
                    state.phase(), state.scale(), state.crisisMomentum(), state.recoveryMomentum(),
                    state.phaseSinceMilliYear(), imperialCrisis, 1_000, year * 1_000L);
        }
        assert state.phase() == RealmHistoricalPhase.COLLAPSING;
        assert state.scale().ordinal() < RealmScale.EMPIRE.ordinal();
        assert !state.mayExpand();

        RealmHistoricalInputs restoration = new RealmHistoricalInputs(
                5, 6_000L, 900, 950, 900, 700, 850, 850,
                850, 720, 800, 900, 80, 50, 450, true);
        for (int year = 301; year <= 380; year++) {
            state = policy.evaluate(
                    state.phase(), state.scale(), state.crisisMomentum(), state.recoveryMomentum(),
                    state.phaseSinceMilliYear(), restoration, 1_000, year * 1_000L);
        }
        assert state.phase() == RealmHistoricalPhase.RESTORING
                || state.phase() == RealmHistoricalPhase.STABLE
                || state.phase() == RealmHistoricalPhase.ASCENDANT;
        assert state.viability() > 750;

        RealmHistoricalInputs strongCity = new RealmHistoricalInputs(
                1, 80L, 900, 850, 780, 720, 800, 760,
                780, 760, 760, 1000, 0, 0, 650, true);
        assert !policy.mayFormCityState(strongCity, 7_999);
        assert policy.mayFormCityState(strongCity, 8_000);
        RealmHistoricalAssessment cityInitial = policy.initial(strongCity, 400_000L);
        assert cityInitial.scale() == RealmScale.CITY_STATE;

        RealmHistoryLedger ledger = new RealmHistoryLedger(4);
        assert ledger.ensureRealm(11L, cityInitial, 400_000L) == 0;
        RealmHistoricalAssessment cityUpdate = policy.evaluate(
                cityInitial.phase(), cityInitial.scale(), 0, 0,
                cityInitial.phaseSinceMilliYear(), strongCity, 10_000, 410_000L);
        assert ledger.update(11L, cityUpdate, 410_000L);
        assert ledger.scale(11L) == RealmScale.CITY_STATE;
        assert ledger.viability(11L) == cityUpdate.viability();
        final Object[] restoredRow = new Object[15];
        ledger.visit((realmId, phase, scale, capacity, burden, viability, expansion,
                crisisMomentum, recoveryMomentum, crisisRate, recoveryRate, reasonMask,
                foundedMilliYear, phaseSinceMilliYear, lastEvaluationMilliYear) -> {
            restoredRow[0] = realmId;
            restoredRow[1] = phase;
            restoredRow[2] = scale;
            restoredRow[3] = capacity;
            restoredRow[4] = burden;
            restoredRow[5] = viability;
            restoredRow[6] = expansion;
            restoredRow[7] = crisisMomentum;
            restoredRow[8] = recoveryMomentum;
            restoredRow[9] = crisisRate;
            restoredRow[10] = recoveryRate;
            restoredRow[11] = reasonMask;
            restoredRow[12] = foundedMilliYear;
            restoredRow[13] = phaseSinceMilliYear;
            restoredRow[14] = lastEvaluationMilliYear;
        });
        RealmHistoryLedger restored = new RealmHistoryLedger(4);
        restored.restore(
                (long) restoredRow[0],
                (RealmHistoricalPhase) restoredRow[1],
                (RealmScale) restoredRow[2],
                (int) restoredRow[3], (int) restoredRow[4], (int) restoredRow[5],
                (int) restoredRow[6], (int) restoredRow[7], (int) restoredRow[8],
                (int) restoredRow[9], (int) restoredRow[10], (int) restoredRow[11],
                (long) restoredRow[12], (long) restoredRow[13], (long) restoredRow[14]);
        restored.restoreRevision(ledger.revision());
        assert restored.phase(11L) == ledger.phase(11L);
        assert restored.expansionReadiness(11L) == ledger.expansionReadiness(11L);
        assert restored.removeRealm(11L);
        assert restored.size() == 0;
    }

    private static void statePlannerChoosesMaterialProgrammesAndExpansion() {
        RealmStateDecisionPolicy policy = new RealmStateDecisionPolicy();
        RealmStateDecision collapse = policy.evaluate(new RealmStateDecisionInputs(
                RealmHistoricalPhase.COLLAPSING,
                RealmScale.KINGDOM,
                190,
                300,
                600,
                300,
                500,
                450,
                800,
                180,
                100,
                true,
                5,
                1_000L));
        assert collapse.priority() == RealmStatePriority.AUSTERITY;
        assert collapse.seekPeace();
        assert !collapse.constructionPermitted();

        RealmStateDecision famine = policy.evaluate(new RealmStateDecisionInputs(
                RealmHistoricalPhase.STABLE,
                RealmScale.CITY_STATE,
                700,
                400,
                250,
                700,
                700,
                700,
                100,
                700,
                700,
                false,
                1,
                120L));
        assert famine.priority() == RealmStatePriority.FOOD_SECURITY;
        assert famine.constructionPermitted();

        RealmStateDecision defence = policy.evaluate(new RealmStateDecisionInputs(
                RealmHistoricalPhase.STABLE,
                RealmScale.REGIONAL_STATE,
                680,
                620,
                800,
                300,
                650,
                650,
                220,
                650,
                650,
                true,
                3,
                900L));
        assert defence.priority() == RealmStatePriority.FORTIFICATION;
        assert !defence.pursueExpansion();

        RealmStateDecision expansion = policy.evaluate(new RealmStateDecisionInputs(
                RealmHistoricalPhase.ASCENDANT,
                RealmScale.KINGDOM,
                850,
                840,
                900,
                800,
                800,
                800,
                100,
                750,
                750,
                false,
                5,
                3_000L));
        assert expansion.priority() == RealmStatePriority.EXPANSION;
        assert expansion.pursueExpansion();
        assert expansion.constructionPermitted();
    }

    private static void collapsingRealmsLoseProvincesToSuccessorStates() {
        RealmSecessionPolicy policy = new RealmSecessionPolicy();
        RealmSecessionInputs loyalCore = new RealmSecessionInputs(
                RealmHistoricalPhase.STABLE,
                8,
                780,
                700,
                850,
                700,
                760,
                720,
                100,
                650,
                620,
                true,
                false);
        RealmSecessionDecision loyalDecision = policy.evaluate(loyalCore);
        assert !loyalDecision.secedes();
        assert loyalDecision.pressure() == 0;

        RealmSecessionInputs damagedFrontier = new RealmSecessionInputs(
                RealmHistoricalPhase.COLLAPSING,
                8,
                240,
                180,
                350,
                800,
                400,
                300,
                780,
                720,
                700,
                false,
                true);
        RealmSecessionDecision successor = policy.evaluate(damagedFrontier);
        assert successor.secedes();
        assert successor.formsBreakawayState();
        assert successor.pressure() >= RealmSecessionPolicy.SECESSION_THRESHOLD;
        assert successor.breakawayCapacity() >= RealmSecessionPolicy.BREAKAWAY_STATE_THRESHOLD;
        assert (successor.reasonMask() & RealmSecessionPolicy.REASON_CULTURE) != 0;
        assert (successor.reasonMask() & RealmSecessionPolicy.REASON_DISTANCE) != 0;

        RealmSecessionInputs shatteredProvince = new RealmSecessionInputs(
                RealmHistoricalPhase.COLLAPSING,
                4,
                100,
                100,
                300,
                250,
                100,
                100,
                950,
                100,
                100,
                false,
                true);
        RealmSecessionDecision stateless = policy.evaluate(shatteredProvince);
        assert stateless.secedes();
        assert !stateless.formsBreakawayState();

        RealmHistoryLedger history = new RealmHistoryLedger(2);
        RealmHistoricalInputs strongCity = new RealmHistoricalInputs(
                1, 80L, 900, 850, 780, 720, 800, 760,
                780, 760, 760, 1000, 0, 0, 650, true);
        RealmHistoricalAssessment initial = new RealmHistoricalPolicy().initial(strongCity, 0L);
        assert history.ensureRealm(21L, initial, 0L) == 0;
        assert history.lastSecessionMilliYear(21L) == -1L;
        assert history.markSecession(21L, 45_000L);
        assert history.lastSecessionMilliYear(21L) == 45_000L;
    }

    private static void dependencyHierarchyIsAcyclicAndBounded() {
        RealmDependencyLedger dependencies = new RealmDependencyLedger(4);
        assert dependencies.establish(2L, 1L, 400, 180, 250, 10L);
        assert dependencies.establish(3L, 2L, 700, 90, 100, 12L);
        assert dependencies.overlordOf(2L) == 1L;
        assert dependencies.overlordOf(3L) == 2L;
        assert dependencies.directSubjectCount(1L) == 1;
        assert dependencies.directSubjectCount(2L) == 1;
        assert !dependencies.mayConductIndependentDiplomacy(2L);
        assert dependencies.mayConductIndependentDiplomacy(3L);
        assert dependencies.tributeDue(2L, 10_000L) == 1_800L;
        assert dependencies.militaryContribution(2L, 120) == 30;
        assert !dependencies.establish(1L, 3L, 500, 100, 100, 14L);
        assert dependencies.size() == 2;

        long previousRevision = dependencies.revision();
        assert dependencies.establish(2L, 1L, 750, 80, 100, 16L);
        assert dependencies.revision() > previousRevision;
        assert dependencies.mayConductIndependentDiplomacy(2L);
        assert dependencies.tributeDue(2L, 10_000L) == 800L;

        final long[] restoredRow = new long[6];
        dependencies.visit((subject, overlord, autonomy, tribute, levy, since) -> {
            if (subject != 2L) return;
            restoredRow[0] = subject;
            restoredRow[1] = overlord;
            restoredRow[2] = autonomy;
            restoredRow[3] = tribute;
            restoredRow[4] = levy;
            restoredRow[5] = since;
        });
        RealmDependencyLedger restored = new RealmDependencyLedger(4);
        restored.restore(
                restoredRow[0], restoredRow[1],
                (int) restoredRow[2], (int) restoredRow[3], (int) restoredRow[4], restoredRow[5]);
        restored.restore(3L, 2L, 700, 90, 100, 12L);
        restored.restoreRevision(dependencies.revision());
        assert restored.overlordOf(2L) == 1L;
        assert restored.overlordOf(3L) == 2L;
        assert restored.removeRealm(2L) == 2;
        assert restored.size() == 0;
        assert restored.estimatedPrimitiveBytes() < 1_000;
    }

    private static void institutionLedgerPersistsPathDependence() {
        RealmInstitutionLedger ledger = new RealmInstitutionLedger(8);
        Constitution initial = Constitution.archetype(GovernmentForm.FEUDAL_MONARCHY, 700);
        assert ledger.ensureRealm(11L, initial, 4L) == 0;
        assert ledger.ensureRealm(11L, initial, 8L) == 0;
        Constitution commercial = initial.towards(GovernmentForm.COMMERCIAL_MONARCHY, 140)
                .withLegitimacy(640);
        assert ledger.update(11L, commercial, 9, 12L);
        assert ledger.constitution(11L).equals(commercial);
        assert ledger.stableCycles(11L) == 9;
        assert ledger.lastEvaluationCycle(11L) == 12L;
        assert ledger.revision() >= 2L;

        RealmInstitutionLedger restored = new RealmInstitutionLedger(8);
        restored.restore(11L, commercial, 9, 12L);
        restored.restoreRevision(ledger.revision());
        assert restored.constitution(11L).equals(commercial);
        assert restored.stableCycles(11L) == 9;
        assert restored.removeRealm(11L);
        assert restored.size() == 0;
    }

    private static void lifecycleLedgerPersistsHysteresis() {
        RealmLifecycleLedger ledger = new RealmLifecycleLedger(8, 8);
        assert ledger.recordFormation(101L, 2, 700, 650, 3, 10L) == 3;
        assert ledger.recordFormation(101L, 2, 720, 650, 3, 13L) == 6;
        assert ledger.recordCrisis(11L, 760, 700, 2, 10L) == 2;
        assert ledger.recordCrisis(11L, 800, 700, 2, 12L) == 4;
        assert ledger.formationQualifyingCycles(101L, 2) == 6;
        assert ledger.crisisQualifyingCycles(11L) == 4;

        RealmLifecycleLedger restored = new RealmLifecycleLedger(8, 8);
        restored.restoreFormation(101L, 2, 6, 720, 13L);
        restored.restoreCrisis(11L, 4, 800, 12L);
        restored.restoreRevision(ledger.revision());
        assert restored.formationQualifyingCycles(101L, 2) == 6;
        assert restored.crisisQualifyingCycles(11L) == 4;
        assert restored.finishFormationSweep(14L) == 1;
        assert restored.finishCrisisSweep(14L) == 1;
        assert restored.formationSize() == 0 && restored.crisisSize() == 0;
    }

    private static void diplomacyLedgerPersistsDirectedWarState() {
        RealmDiplomacyLedger ledger = new RealmDiplomacyLedger(8);
        RealmDiplomacyEngine engine = new RealmDiplomacyEngine();
        assert ledger.updateDrivers(
                11L, 22L,
                180, 850, 420, 800,
                240, 780, 650, 120,
                3L);
        DiplomaticDecision war = engine.evaluate(
                ledger.status(11L, 22L, 3L),
                ledger.inputs(11L, 22L, 760, 3L));
        assert war.status() == DiplomaticStatus.WAR;
        assert ledger.applyDecision(11L, 22L, war, 3L, 6);
        assert ledger.isAtWar(11L, 22L);
        assert ledger.isAtWar(22L, 11L);
        assert ledger.warGoal(11L, 22L) == war.warGoal();
        assert ledger.warGoal(22L, 11L) == WarGoal.DEFEND;

        BattleOutcome outcome = new BattleOutcome(11L, 22L, true, 3, 18, true, true);
        assert ledger.recordBattleOutcome(outcome, engine);
        assert ledger.warScore(11L, 22L) > 0;
        assert ledger.warScore(22L, 11L) < 0;
        assert ledger.exhaustion(22L, 11L) > ledger.exhaustion(11L, 22L);
        assert ledger.grievances(22L, 11L) > 0;

        DiplomaticDecision truce = new DiplomaticDecision(
                DiplomaticStatus.TRUCE,
                WarGoal.NONE,
                100,
                300,
                RealmDiplomacyEngine.REASON_EXHAUSTION);
        assert ledger.applyDecision(11L, 22L, truce, 10L, 5);
        assert ledger.status(11L, 22L, 12L) == DiplomaticStatus.TRUCE;
        assert ledger.status(11L, 22L, 15L) == DiplomaticStatus.PEACE;
        assert !ledger.isAtWar(11L, 22L);
        int defenderExhaustion = ledger.exhaustion(22L, 11L);
        assert ledger.recover(11L, 22L, 4);
        assert ledger.exhaustion(22L, 11L) < defenderExhaustion;

        final Object[] restoredRow = new Object[23];
        ledger.visit((firstRealm, secondRealm, status, firstGoal, secondGoal,
                firstTrust, secondTrust, firstGrievances, secondGrievances,
                firstFear, secondFear, firstClaims, secondClaims,
                firstExhaustion, secondExhaustion, firstWarScore, secondWarScore,
                tradeInterdependence, borderFriction, ideologicalDistance,
                commonThreat, truceUntilCycle, lastEvaluationCycle) -> {
            restoredRow[0] = firstRealm;
            restoredRow[1] = secondRealm;
            restoredRow[2] = status;
            restoredRow[3] = firstGoal;
            restoredRow[4] = secondGoal;
            restoredRow[5] = firstTrust;
            restoredRow[6] = secondTrust;
            restoredRow[7] = firstGrievances;
            restoredRow[8] = secondGrievances;
            restoredRow[9] = firstFear;
            restoredRow[10] = secondFear;
            restoredRow[11] = firstClaims;
            restoredRow[12] = secondClaims;
            restoredRow[13] = firstExhaustion;
            restoredRow[14] = secondExhaustion;
            restoredRow[15] = firstWarScore;
            restoredRow[16] = secondWarScore;
            restoredRow[17] = tradeInterdependence;
            restoredRow[18] = borderFriction;
            restoredRow[19] = ideologicalDistance;
            restoredRow[20] = commonThreat;
            restoredRow[21] = truceUntilCycle;
            restoredRow[22] = lastEvaluationCycle;
        });
        RealmDiplomacyLedger restored = new RealmDiplomacyLedger(8);
        restored.restore(
                (long) restoredRow[0], (long) restoredRow[1],
                (DiplomaticStatus) restoredRow[2],
                (WarGoal) restoredRow[3], (WarGoal) restoredRow[4],
                (int) restoredRow[5], (int) restoredRow[6],
                (int) restoredRow[7], (int) restoredRow[8],
                (int) restoredRow[9], (int) restoredRow[10],
                (int) restoredRow[11], (int) restoredRow[12],
                (int) restoredRow[13], (int) restoredRow[14],
                (int) restoredRow[15], (int) restoredRow[16],
                (int) restoredRow[17], (int) restoredRow[18],
                (int) restoredRow[19], (int) restoredRow[20],
                (long) restoredRow[21], (long) restoredRow[22]);
        restored.restoreRevision(ledger.revision());
        assert restored.status(11L, 22L, 12L) == DiplomaticStatus.TRUCE;
        assert restored.status(11L, 22L, 15L) == DiplomaticStatus.PEACE;
        assert restored.exhaustion(22L, 11L) == ledger.exhaustion(22L, 11L);
    }

    private static void diplomacyProducesMandatesNotBattles() {
        RealmDiplomacyEngine diplomacy = new RealmDiplomacyEngine();
        DiplomacyInputs hostile = new DiplomacyInputs(
                100, 900, 100, 100, 850, 900, 650, 700, 100, 0, 0);
        DiplomaticDecision war = diplomacy.evaluate(DiplomaticStatus.PEACE, hostile);
        assert war.status() == DiplomaticStatus.WAR;
        assert war.warGoal() == WarGoal.BORDER_CLAIM;

        DiplomacyInputs exhausted = new DiplomacyInputs(
                200, 800, 200, 100, 800, 700, 600, 600, 900, 0, 0);
        assert diplomacy.evaluate(DiplomaticStatus.WAR, exhausted).status() == DiplomaticStatus.TRUCE;

        DiplomacyInputs friendly = new DiplomacyInputs(
                900, 80, 100, 850, 80, 40, 500, 120, 50, 760, 0);
        assert diplomacy.evaluate(DiplomaticStatus.PEACE, friendly).status() == DiplomaticStatus.ALLIANCE;

        WarImpact impact = diplomacy.battleImpact(new BattleOutcome(
                1L, 2L, true, 12, 30, true, true));
        assert impact.attackerWarScoreDelta() > 300;
        assert impact.defenderWarScoreDelta() < 0;
        assert impact.defenderExhaustionDelta() > impact.attackerExhaustionDelta();
    }
}
