package ru.kaiserroman.millenairearmies.server.execution;

import java.util.UUID;
import org.millenaire.village.Village;

/**
 * Optional integration boundary reserved for the external Realm + Simulation modules.
 * Millenaire Armies does not install or invoke it while executing recruitment, commands or combat.
 */
public interface RealmCapturePolicy {
    RealmCapturePolicy ALLOW_ALL = new RealmCapturePolicy() {
        @Override
        public boolean canCapture(UUID owner) { return true; }

        @Override
        public void captured(UUID owner, Village village) {}
    };

    boolean canCapture(UUID owner);

    void captured(UUID owner, Village village);
}
