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
        public void issueOrder(ServerPlayer player, IssueOrderIntent intent) {}
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

    static void dispatch(ServerPlayer player, IssueOrderIntent intent) {
        sink.issueOrder(player, intent);
    }
}
