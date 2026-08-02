package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.network.chat.Component;

enum StrategicTab {
    OVERVIEW("gui.millenaire_armies.tab.overview"),
    REALM("gui.millenaire_armies.tab.realm"),
    RECRUITMENT("gui.millenaire_armies.tab.recruitment"),
    FACTIONS("gui.millenaire_armies.tab.factions"),
    ARMIES("gui.millenaire_armies.tab.armies"),
    ORDERS("gui.millenaire_armies.tab.orders"),
    LOGISTICS("gui.millenaire_armies.tab.logistics");

    static final StrategicTab[] VALUES = values();

    final Component title;

    StrategicTab(String translationKey) {
        this.title = Component.translatable(translationKey);
    }
}
