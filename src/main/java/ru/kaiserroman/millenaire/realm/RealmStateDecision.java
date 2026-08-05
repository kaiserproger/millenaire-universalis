package ru.kaiserroman.millenaire.realm;

/** One persisted strategic decision and the behavioural permissions derived from it. */
public record RealmStateDecision(
        RealmStatePriority priority,
        int investmentPermille,
        boolean constructionPermitted,
        boolean pursueExpansion,
        boolean seekPeace,
        int pressure,
        int reasonMask) {

    public RealmStateDecision {
        if (priority == null || investmentPermille < 0 || investmentPermille > 1000
                || pressure < 0 || pressure > 1000) {
            throw new IllegalArgumentException("Invalid Realm state decision");
        }
        if (constructionPermitted && !priority.permitsConstruction()) {
            throw new IllegalArgumentException("Priority does not permit construction");
        }
        if (pursueExpansion && !priority.isExpansionary()) {
            throw new IllegalArgumentException("Non-expansion priority cannot pursue expansion");
        }
    }
}
