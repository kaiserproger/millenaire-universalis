package ru.kaiserroman.millenairearmies.presentation;

/** The four independently data-driven parts of an army unit's presentation. */
public enum PresentationKind {
    ROLE("roles"),
    RANK("ranks"),
    BANNER("banners"),
    ORDER_STATUS("order_statuses");

    private final String directory;

    PresentationKind(String directory) {
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }

    public static PresentationKind fromDirectory(String directory) {
        return switch (directory) {
            case "roles" -> ROLE;
            case "ranks" -> RANK;
            case "banners" -> BANNER;
            case "order_statuses" -> ORDER_STATUS;
            default -> null;
        };
    }
}
