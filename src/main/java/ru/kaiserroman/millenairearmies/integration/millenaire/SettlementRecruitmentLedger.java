package ru.kaiserroman.millenairearmies.integration.millenaire;

import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;

/**
 * Transaction boundary for charging a settlement when troops are raised.
 *
 * <p>The recruitment service validates every candidate and all packed-store capacity before
 * calling this boundary. Implementations must fail closed when the settlement inventory is not
 * fully observable. A successful debit is followed by packed mutations on the same server thread;
 * {@link #refund} is only the compensating path for an unexpected failed mutation.</p>
 */
public interface SettlementRecruitmentLedger {
    int UNAVAILABLE = -1;
    int INSUFFICIENT_RESOURCES = -2;

    /** Returns the charged amount, or one of the negative failure constants. */
    int debit(ServerLevel level, Village village, int armyCount, int unitCount);

    /** Best-effort compensation for a previously returned positive debit. */
    boolean refund(ServerLevel level, Village village, int amount);
}
