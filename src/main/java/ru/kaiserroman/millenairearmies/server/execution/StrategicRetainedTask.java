package ru.kaiserroman.millenairearmies.server.execution;

/** Identity shared by retained move and attack tasks installed into Millenaire's scheduler. */
interface StrategicRetainedTask {
    int unitHandle();

    int armyHandle();

    long revision();

    boolean cancel();
}
