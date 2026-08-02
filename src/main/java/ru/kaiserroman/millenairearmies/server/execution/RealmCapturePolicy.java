package ru.kaiserroman.millenairearmies.server.execution;

import java.util.UUID;
import org.millenaire.village.Village;

/** Authority boundary between physical battlefield victory and player-founded realm ownership. */
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
