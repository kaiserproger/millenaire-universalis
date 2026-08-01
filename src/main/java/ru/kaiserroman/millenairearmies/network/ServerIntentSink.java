package ru.kaiserroman.millenairearmies.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * Integration boundary implemented by the lifecycle-owned command service. The authenticated
 * player always comes from NeoForge's payload context; implementations must derive authority from
 * that player and never from a client-provided controller/faction field.
 */
public interface ServerIntentSink {
    void open(ServerPlayer player, OpenCommandIntent intent);

    void requestState(ServerPlayer player, RequestStateIntent intent);

    void createArmy(ServerPlayer player, CreateArmyIntent intent);

    void issueOrder(ServerPlayer player, IssueOrderIntent intent);
}
