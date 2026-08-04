package ru.kaiserroman.millenairearmies.network;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** Hot-swappable bridge so networking does not own or duplicate the server ECS/service. */
public final class ServerIntentRouter {
    private static final ServerIntentSink NOOP = new ServerIntentSink() {
        @Override
        public void open(ServerPlayer player, OpenCommandIntent intent) {}

        @Override
        public void requestState(ServerPlayer player, RequestStateIntent intent) {}

        @Override
        public void createArmy(ServerPlayer player, CreateArmyIntent intent) {}

        @Override
        public void recruitUnits(ServerPlayer player, RecruitUnitsIntent intent) {}

        @Override
        public void hireRecruit(ServerPlayer player, HireRecruitIntent intent) {}

        @Override
        public void issueOrder(ServerPlayer player, IssueOrderIntent intent) {}

        @Override
        public void setFormation(ServerPlayer player, SetFormationIntent intent) {}

        @Override
        public void setTactical(ServerPlayer player, SetTacticalIntent intent) {}

        @Override
        public void setSupplyChest(ServerPlayer player, SetSupplyChestIntent intent) {}

        @Override
        public void setUnitLoadout(ServerPlayer player, SetUnitLoadoutIntent intent) {}

        @Override
        public void setGarrison(ServerPlayer player, SetGarrisonIntent intent) {}

        @Override
        public void realmAction(ServerPlayer player, RealmActionIntent intent) {}
    };

    private static volatile ServerIntentSink sink = NOOP;

    private ServerIntentRouter() {}

    /** Install once from the lifecycle-owned command service after server startup. */
    public static void install(ServerIntentSink replacement) {
        sink = Objects.requireNonNull(replacement, "replacement");
    }

    /** Remove only the expected service, avoiding a late stop clearing a replacement server. */
    public static void uninstall(ServerIntentSink expected) {
        if (sink == expected) {
            sink = NOOP;
        }
    }

    static void dispatch(ServerPlayer player, OpenCommandIntent intent) {
        sink.open(player, intent);
    }

    static void dispatch(ServerPlayer player, RequestStateIntent intent) {
        sink.requestState(player, intent);
    }

    static void dispatch(ServerPlayer player, CreateArmyIntent intent) {
        sink.createArmy(player, intent);
    }

    static void dispatch(ServerPlayer player, RecruitUnitsIntent intent) {
        sink.recruitUnits(player, intent);
    }

    static void dispatch(ServerPlayer player, HireRecruitIntent intent) {
        sink.hireRecruit(player, intent);
    }

    static void dispatch(ServerPlayer player, IssueOrderIntent intent) {
        sink.issueOrder(player, intent);
    }

    static void dispatch(ServerPlayer player, SetFormationIntent intent) {
        sink.setFormation(player, intent);
    }

    static void dispatch(ServerPlayer player, SetTacticalIntent intent) {
        sink.setTactical(player, intent);
    }

    static void dispatch(ServerPlayer player, SetSupplyChestIntent intent) {
        sink.setSupplyChest(player, intent);
    }

    static void dispatch(ServerPlayer player, SetUnitLoadoutIntent intent) {
        sink.setUnitLoadout(player, intent);
    }

    static void dispatch(ServerPlayer player, SetGarrisonIntent intent) {
        sink.setGarrison(player, intent);
    }

    static void dispatch(ServerPlayer player, RealmActionIntent intent) {
        sink.realmAction(player, intent);
    }
}
