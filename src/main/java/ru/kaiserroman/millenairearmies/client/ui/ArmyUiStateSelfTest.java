package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.network.chat.Component;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.client.state.ClientArmyRosterState;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.network.ArmyRosterSnapshotPayload;

/** Deterministic roster/ack state checks used by the build without launching a client. */
public final class ArmyUiStateSelfTest {
    private ArmyUiStateSelfTest() {}

    public static void main(String[] args) {
        testRosterAndAcknowledgements();
        testFormationValidation();
        testScrollAndKeepVisible();
        testUtilityRendering();
        testParchmentButtonFactory();
        testLedgerLayoutGeometry();
        testRecruitmentLayoutGeometry();
        testRealmLayoutGeometry();
        testHudLayoutGeometry();
        MilitaryLedgerController.reset();
        System.out.println("Army command UI state self-test passed");
    }

    private static void testRosterAndAcknowledgements() {
        ClientArmyRosterState state = new ClientArmyRosterState();
        ArmyRosterSnapshotPayload initial = snapshot(7L, 0, ArmiesProtocol.RESULT_NONE, 2);
        check(state.apply(initial), "initial roster applied");
        check(state.settlementCount() == 1 && state.recruitCount() == 2, "bounded rows visible");
        check("Caen".equals(state.settlementString(0, ArmyRosterSnapshotPayload.SETTLEMENT_NAME)),
                "server settlement name retained");

        ArmyRosterSnapshotPayload accepted = snapshot(7L, 9, ArmiesProtocol.RESULT_ACCEPTED, 1);
        check(state.apply(accepted), "same-revision acknowledgement applied");
        check(state.acknowledgementActionId() == 9
                        && state.acknowledgementAffected() == 1,
                "server acknowledgement visible");
        check(!state.apply(snapshot(7L, 8, ArmiesProtocol.RESULT_INVALID, 2)),
                "older acknowledgement rejected");
        check(!state.apply(snapshot(6L, 10, ArmiesProtocol.RESULT_ACCEPTED, 2)),
                "older state revision rejected");
    }

    private static void testFormationValidation() {
        RecordingMirror mirror = new RecordingMirror();
        MilitaryLedgerController.reset();
        MilitaryLedgerController.validate(mirror);
        check(MilitaryLedgerController.hasSelectedArmy
                        && MilitaryLedgerController.selectedArmyId == RecordingMirror.ARMY,
                "first controllable army anchored for the command desk");
        for (int formation = 0; formation < 5; formation++) {
            check(MilitaryLedgerController.setFormation(mirror, formation),
                    "direct formation " + formation + " accepted");
            check(mirror.lastFormation == formation,
                    "direct formation " + formation + " forwarded unchanged");
        }
        int calls = mirror.formationRequests;
        check(!MilitaryLedgerController.setFormation(mirror, -1)
                        && !MilitaryLedgerController.setFormation(mirror, 5),
                "out-of-range direct formations rejected client-side");
        check(mirror.formationRequests == calls, "invalid formation never reached the network mirror");
    }

    private static void testScrollAndKeepVisible() {
        check(MilitaryUi.clampScroll(99, 7, 3) == 4, "scroll clamped to final card page");
        check(MilitaryUi.clampScroll(0, 3, 5) == 0, "clampScroll floors to zero when visible > count");
        check(MilitaryUi.clampScroll(5, 3, 3) == 0, "clampScroll caps to max scroll");
        check(MilitaryUi.keepVisible(0, 4, 3) == 2, "keyboard selection remains visible");
        check(MilitaryUi.keepVisible(2, 0, 4) == 0, "keepVisible pulls up for row before scroll");
        check(MilitaryUi.keepVisible(0, 4, 3) == 2, "keepVisible pushes down for row after visible range");
        check(MilitaryUi.keepVisible(1, 2, 3) == 1, "keepVisible no-op when row already visible");
    }

    private static void testUtilityRendering() {
        check(MilitaryUi.statusColor(80) == MilitaryUi.GOOD, "statusColor >= 67 is good");
        check(MilitaryUi.statusColor(50) == MilitaryUi.WARNING, "statusColor 34-66 is warning");
        check(MilitaryUi.statusColor(20) == MilitaryUi.BAD, "statusColor < 34 is bad");
        check(MilitaryUi.percent(75).equals("75%"), "percent rendering");
        check(MilitaryUi.signed(5).equals("+5"), "signed positive");
        check(MilitaryUi.signed(0).equals("0"), "signed zero");
        check(MilitaryUi.signed(-3).equals("-3"), "signed negative");
        check(MilitaryUi.safe("Caen").equals("Caen"), "safe passthrough");
        check(!MilitaryUi.safe("").equals(""), "safe replaces blank");
        check(MilitaryUi.optional("").isEmpty(), "optional hides blank");
        check(MilitaryUi.optional("Visible").equals("Visible"), "optional passes through");
    }

    private static void testParchmentButtonFactory() {
        check(ParchmentButton.class.getSimpleName().equals("ParchmentButton"),
                "parchment button class present");
        check(MilitaryUi.parchmentButton(Component.literal("test"), 0, 0, 50, 18, btn -> {}).getClass()
                == ParchmentButton.class, "parchment button factory returns correct type");
    }

    private static void testLedgerLayoutGeometry() {
        int panelX = 0;
        int gap = 4;

        int panelW360 = 360;
        int panelH278 = 278;
        MilitaryUi.LedgerPlacement compactPlacement = MilitaryUi.computeLedgerLayout(
                0, 0, panelW360, panelH278);

        check(compactPlacement.contentTop() == 54, "compact contentTop = 54");
        check(compactPlacement.contentBottom() > compactPlacement.contentTop(),
                "compact contentBottom > contentTop");
        check(compactPlacement.contentBottom() < 278, "compact contentBottom within panel");
        check(compactPlacement.orderCols() == 4, "compact order columns = 4");
        check(compactPlacement.orderTileHeight() == 28, "compact order tile height = 28");
        check(compactPlacement.formationSectionY() > compactPlacement.contentBottom(),
                "compact formation section below content");

        int order0Top = compactPlacement.orderY0();
        int order1Top = compactPlacement.orderY1();
        int order0Bottom = order0Top + compactPlacement.orderTileHeight();
        int order1Bottom = order1Top + compactPlacement.orderTileHeight();
        int orderSectionBottom = compactPlacement.orderSectionY() - 2;
        check(order0Bottom <= panelH278 - 4, "compact orderRow0 bottom inside panel");
        check(order1Bottom <= panelH278, "compact orderRow1 bottom inside panel");
        check(compactPlacement.orderSectionY() < order0Top, "compact order label above row0");

        int formationY = compactPlacement.formationY();
        int formationBottom = formationY + 22;
        check(formationBottom < compactPlacement.orderSectionY(),
                "compact formations above order section");
        check(compactPlacement.formationSectionY() < formationY, "compact formation label above buttons");

        int tileWidth = compactPlacement.orderTileWidth();
        int tilesRowWidth = 4 * tileWidth + 3 * gap;
        check(tilesRowWidth <= panelW360 - 20, "compact 4 tiles fit in panel-20");

        int panelW640 = 640;
        int panelH384 = 384;
        MilitaryUi.LedgerPlacement normalPlacement = MilitaryUi.computeLedgerLayout(
                0, 0, panelW640, panelH384);

        check(normalPlacement.contentTop() == 54, "normal contentTop = 54");
        check(normalPlacement.contentBottom() > normalPlacement.contentTop(),
                "normal contentBottom > contentTop");
        check(normalPlacement.contentBottom() < 384, "normal contentBottom within panel");
        check(!normalPlacement.compact(), "normal mode not compact");
        check(normalPlacement.orderCols() == 4, "normal order columns = 4");
        check(normalPlacement.orderTileHeight() == 34, "normal order tile height = 34");

        int normOrder1Bottom = normalPlacement.orderY1() + normalPlacement.orderTileHeight();
        check(normOrder1Bottom <= 384, "normal orderRow1 bottom inside panel");
        int normOrder0Bottom = normalPlacement.orderY0() + normalPlacement.orderTileHeight();
        check(normOrder0Bottom <= normalPlacement.orderY1() - 4, "normal orderRow0 above row1 gap");

        int normFormBottom = normalPlacement.formationY() + 22;
        check(normFormBottom < normalPlacement.orderSectionY(),
                "normal formations above order section");

        int listMinWidth = panelW360 < 430 ? 116 : panelW360;
        check(normalPlacement.listWidth() > 0, "listWidth > 0");
        check(normalPlacement.detailX() >= 0, "detailX >= 0");
        check(normalPlacement.detailWidth() > 0, "detailWidth > 0");

        check(MilitaryUi.ledgerMinPanelHeight() <= panelH278,
                "ledgerMinPanelHeight <= 278 (valid for compact)");
    }

    private static void testRecruitmentLayoutGeometry() {
        int panelW360 = 360;
        int panelH278 = 278;
        MilitaryUi.RecruitmentPlacement compactRec =
                MilitaryUi.computeRecruitmentLayout(0, 0, panelW360, panelH278);

        check(compactRec.compact(), "compact recruitment for 360 width");
        check(compactRec.contentTop() == 54, "compact recruitment contentTop = 54");
        check(compactRec.contentBottom() > compactRec.contentTop(),
                "compact recruitment contentBottom > contentTop");
        check(compactRec.contentBottom() < panelH278,
                "compact recruitment contentBottom inside panel");
        check(compactRec.statusCardHeight() == 38, "compact recruitment statusH = 38");
        check(compactRec.statusCardY() > compactRec.contentBottom(),
                "compact recruitment status card after content");
        int compactCardBottom = compactRec.statusCardY() + compactRec.statusCardHeight();
        check(compactCardBottom < panelH278, "compact recruitment status card inside panel");
        check(compactRec.actionY() + 22 <= panelH278,
                "compact recruitment action buttons inside panel");
        check(compactRec.actionY() > compactCardBottom,
                "compact recruitment actions after status card");
        check(compactRec.listWidth() > 0, "compact recruitment listWidth > 0");
        check(compactRec.rightWidth() > 0, "compact recruitment rightWidth > 0");

        int listX = 10;
        int listEnd = listX + compactRec.listWidth();
        check(listEnd + 10 + compactRec.rightWidth() == panelW360 - 10,
                "compact listEnd + gap + rightWidth = panelW-10");
        int statusCardLeft = 10;
        int statusCardRight = panelW360 - 10;
        check(compactRec.statusCardY() + compactRec.statusCardHeight() < compactRec.actionY(),
                "compact status card before actions");
        int cycleX = panelW360 - 10 - 78;
        int cycleY = compactRec.statusCardY() + 4;
        int cycleR = cycleX + 78;
        int cycleB = cycleY + 18;
        check(cycleX >= 10, "compact cycle inside panel left");
        check(cycleR <= panelW360 - 10, "compact cycle inside panel right");
        check(cycleY >= compactRec.statusCardY(), "compact cycle inside card top");
        check(cycleB <= compactRec.statusCardY() + compactRec.statusCardHeight(),
                "compact cycle inside card bottom");

        int createW = Math.max(104, Math.min(140, (panelW360 - 26) / 3));
        int recruitW = panelW360 - 26 - createW;
        check(10 + createW + 6 + recruitW + 10 == panelW360,
                "compact create+gap+recruit fills panel-20");
        int createRight = 10 + createW;
        int recruitLeft = createRight + 6;
        int recruitRight = recruitLeft + recruitW;
        check(recruitRight == panelW360 - 10, "compact recruit right=panelW-10");

        int panelW640 = 640;
        int panelH384 = 384;
        MilitaryUi.RecruitmentPlacement normalRec =
                MilitaryUi.computeRecruitmentLayout(0, 0, panelW640, panelH384);

        check(!normalRec.compact(), "normal recruitment not compact for 640 width");
        check(normalRec.contentTop() == 54, "normal recruitment contentTop = 54");
        check(normalRec.contentBottom() > normalRec.contentTop(),
                "normal recruitment contentBottom > contentTop");
        check(normalRec.contentBottom() < panelH384,
                "normal recruitment contentBottom inside panel");
        check(normalRec.statusCardHeight() == 52, "normal recruitment statusH = 52");
        check(normalRec.rightWidth() > 0, "normal recruitment rightWidth > 0");
        check(normalRec.listWidth() > 0, "normal recruitment listWidth > 0");
        check(normalRec.actionY() + 22 <= panelH384,
                "normal recruitment action buttons inside panel");

        listX = 10;
        listEnd = listX + normalRec.listWidth();
        check(listEnd + 10 + normalRec.rightWidth() == panelW640 - 10,
                "normal listEnd + gap + rightWidth = panelW-10");
        check(normalRec.statusCardY() + normalRec.statusCardHeight() < normalRec.actionY(),
                "normal status card before actions");
        cycleX = panelW640 - 10 - 78;
        cycleY = normalRec.statusCardY() + 4;
        cycleR = cycleX + 78;
        cycleB = cycleY + 18;
        check(cycleX >= 10, "normal cycle inside panel left");
        check(cycleR <= panelW640 - 10, "normal cycle inside panel right");
        check(cycleY >= normalRec.statusCardY(), "normal cycle inside card top");
        check(cycleB <= normalRec.statusCardY() + normalRec.statusCardHeight(),
                "normal cycle inside card bottom");

        createW = Math.max(104, Math.min(140, (panelW640 - 26) / 3));
        recruitW = panelW640 - 26 - createW;
        check(10 + createW + 6 + recruitW + 10 == panelW640,
                "normal create+gap+recruit fills panel-20");
        createRight = 10 + createW;
        recruitLeft = createRight + 6;
        recruitRight = recruitLeft + recruitW;
        check(recruitRight == panelW640 - 10, "normal recruit right=panelW-10");
    }

    private static void testRealmLayoutGeometry() {
        int panelW360 = 360;
        int panelH278 = 278;
        MilitaryUi.RealmPlacement compactR =
                MilitaryUi.computeRealmLayout(0, 0, panelW360, panelH278);

        check(compactR.compact(), "compact realm for 360 width (detailW < 250)");
        check(compactR.contentTop() == 54, "compact realm contentTop = 54");
        check(compactR.contentBottom() > compactR.contentTop(),
                "compact realm contentBottom > contentTop");
        check(compactR.contentBottom() < panelH278,
                "compact realm contentBottom inside panel");
        check(compactR.actionY() + 22 <= panelH278,
                "compact realm action buttons inside panel");
        check(compactR.listWidth() == 116, "compact realm listWidth = 116 for <430");
        check(compactR.detailWidth() > 0, "compact realm detailWidth > 0");
        int detailRight = 10 + compactR.listWidth() + 10 + compactR.detailWidth();
        check(detailRight == panelW360 - 10, "compact realm detail right = panelW-10");

        int foundLeft = 10;
        int foundRight = foundLeft + panelW360 - 20;
        check(foundRight == panelW360 - 10, "compact foundRealm right = panelW-10");

        int taxLeftW = Math.max(120, (panelW360 - 26) / 2);
        int taxRightW = panelW360 - 26 - taxLeftW;
        int taxLeftR = 10 + taxLeftW;
        int taxRightL = taxLeftR + 6;
        int taxRightR = taxRightL + taxRightW;
        check(taxRightR == panelW360 - 10, "compact tax buttons right = panelW-10");
        check(taxLeftW + taxRightW == panelW360 - 26,
                "compact tax button widths sum = panelW-26");

        int panelW640 = 640;
        int panelH384 = 384;
        MilitaryUi.RealmPlacement normalR =
                MilitaryUi.computeRealmLayout(0, 0, panelW640, panelH384);

        check(!normalR.compact(), "normal realm not compact for 640 width");
        check(normalR.contentTop() == 54, "normal realm contentTop = 54");
        check(normalR.contentBottom() > normalR.contentTop(),
                "normal realm contentBottom > contentTop");
        check(normalR.contentBottom() < panelH384,
                "normal realm contentBottom inside panel");
        check(normalR.actionY() + 22 <= panelH384,
                "normal realm action buttons inside panel");
        check(normalR.listWidth() > 0, "normal realm listWidth > 0");
        check(normalR.detailWidth() > 0, "normal realm detailWidth > 0");
        detailRight = 10 + normalR.listWidth() + 10 + normalR.detailWidth();
        check(detailRight == panelW640 - 10, "normal realm detail right = panelW-10");

        foundLeft = 10;
        foundRight = foundLeft + panelW640 - 20;
        check(foundRight == panelW640 - 10, "normal foundRealm right = panelW-10");

        taxLeftW = Math.max(120, (panelW640 - 26) / 2);
        taxRightW = panelW640 - 26 - taxLeftW;
        taxLeftR = 10 + taxLeftW;
        taxRightL = taxLeftR + 6;
        taxRightR = taxRightL + taxRightW;
        check(taxRightR == panelW640 - 10, "normal tax buttons right = panelW-10");
        check(taxLeftW + taxRightW == panelW640 - 26,
                "normal tax button widths sum = panelW-26");
    }

    private static void testHudLayoutGeometry() {
        ArmyCommandHud.HudLayout h = ArmyCommandHud.computeHudLayout(320, 240, 5, 0);
        check(h.width() == 304, "320→304 hud width");
        check(h.height() == 50, "hud height 50");
        check(h.x() == 8, "x=8");
        check(h.y() == 240 - 50 - 6, "y=screenH-h-6");
        check(h.x() + h.width() <= 320, "hud inside horizontal");
        check(h.y() + h.height() <= 240, "hud inside vertical");
        check(h.chipCount() <= 3, "320→capacity 3");
        check(h.infoWidth() > 0, "infoWidth>0");
        check(h.feedbackY() + 16 <= h.y(), "feedback above hud");
        check(h.chipX() >= h.x() + 8, "chips inside hud left");
        check(h.chipX() + h.chipCount() * (h.chipWidth() + h.chipGap()) - h.chipGap()
                <= h.x() + h.width() - 8, "chips inside hud right");
        check(h.infoX() + h.infoWidth() <= h.x() + h.width() - 8, "info inside hud right");
        check(h.hintsX() >= h.x() && h.hintsX() + h.hintsWidth() <= h.x() + h.width(),
                "hints inside hud");
        check(h.hintsY() + 8 <= h.y() + h.height(), "hints inside hud vertically");

        h = ArmyCommandHud.computeHudLayout(640, 360, 9, 7);
        check(h.width() == 480, "640→480 hud width");
        check(h.chipCount() == 9, "640→capacity 9 for armyCount 9");
        check(h.chipStart() + h.chipCount() > 7,
                "selectedIndex 7 in window");
        check(h.chipStart() >= 0, "chipStart >=0");
        check(h.x() + h.width() <= 640, "hud inside 640 horizontal");
        check(h.y() + h.height() <= 360, "hud inside 360 vertical");
        check(h.infoWidth() > 0, "640 infoWidth>0");

        h = ArmyCommandHud.computeHudLayout(1280, 720, 9, 0);
        check(h.width() == 480, "1280→capped 480");
        check(h.chipCount() <= 9, "1280→capacity 9");
        check(h.x() + h.width() <= 1280, "1280 hud inside");

        h = ArmyCommandHud.computeHudLayout(320, 240, 9, 8);
        check(h.chipCount() == 3, "army9 cap3→chipCount 3");
        check(h.chipStart() + h.chipCount() >= 9,
                "window includes index 8");
        int topChip = h.chipStart() + 1;
        int bottomChip = h.chipStart() + h.chipCount();
        check(topChip >= 1 && bottomChip <= 9,
                "chip numbers 1..9");

        h = ArmyCommandHud.computeHudLayout(320, 240, 9, 7);
        check(h.chipStart() <= 7 && h.chipStart() + h.chipCount() > 7,
                "army9 sel7 visible in window");

        h = ArmyCommandHud.computeHudLayout(320, 240, 1, 0);
        check(h.chipCount() == 1, "army1→1 chip");
        check(h.chipStart() == 0, "chipStart 0");
        check(h.infoWidth() > 0, "single army infoWidth>0");

        h = ArmyCommandHud.computeHudLayout(640, 360, 0, -1);
        check(h.chipCount() == 0, "army0→0 chips");
    }

    private static ArmyRosterSnapshotPayload snapshot(long revision, int actionId, int result, int recruits) {
        int[] recruitInts = new int[recruits * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS];
        long[] recruitLongs = new long[recruits * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS];
        String[] recruitStrings = new String[recruits * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS];
        for (int row = 0; row < recruits; row++) {
            int ints = row * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS;
            int longs = row * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS;
            int strings = row * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS;
            recruitInts[ints + ArmyRosterSnapshotPayload.RECRUIT_STRENGTH] = 10 + row;
            recruitInts[ints + ArmyRosterSnapshotPayload.RECRUIT_OPTION] =
                    ArmiesProtocol.RECRUIT_OPTION_ENLIST;
            recruitInts[ints + ArmyRosterSnapshotPayload.RECRUIT_REQUIRED_REPUTATION] = 4096;
            recruitLongs[longs + ArmyRosterSnapshotPayload.RECRUIT_UUID_MOST] = 100 + row;
            recruitLongs[longs + ArmyRosterSnapshotPayload.RECRUIT_UUID_LEAST] = 200 + row;
            recruitLongs[longs + ArmyRosterSnapshotPayload.RECRUIT_VILLAGE_MOST] = 1L;
            recruitLongs[longs + ArmyRosterSnapshotPayload.RECRUIT_VILLAGE_LEAST] = 2L;
            recruitStrings[strings + ArmyRosterSnapshotPayload.RECRUIT_NAME] = "Resident " + row;
            recruitStrings[strings + ArmyRosterSnapshotPayload.RECRUIT_ROLE] = "Guard";
        }
        return new ArmyRosterSnapshotPayload(
                revision,
                actionId,
                actionId == 0 ? ArmiesProtocol.ACTION_NONE : ArmiesProtocol.ACTION_RECRUIT,
                result,
                actionId == 0 ? 0 : 1,
                1,
                recruits,
                new int[] {
                    3, 20, recruits, ArmiesProtocol.SETTLEMENT_ACCESS_CONTROLLED
                },
                new long[] {1L, 2L, 123L},
                new String[] {"Caen", "millenaire:norman"},
                recruitInts,
                recruitLongs,
                recruitStrings);
    }

    private static final class RecordingMirror implements ArmyClientMirror {
        private static final int ARMY = 0x0010_0001;
        private int lastFormation = -1;
        private int formationRequests;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public int armyCount() {
            return 1;
        }

        @Override
        public int armyId(int index) {
            if (index != 0) throw new IndexOutOfBoundsException(index);
            return ARMY;
        }

        @Override
        public boolean requestSetFormation(int armyHandleBits, int formationCode) {
            if (armyHandleBits != ARMY) return false;
            lastFormation = formationCode;
            formationRequests++;
            return true;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
