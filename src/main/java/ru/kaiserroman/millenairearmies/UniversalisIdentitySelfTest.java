package ru.kaiserroman.millenairearmies;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Verifies the new runtime identity without breaking existing resource-pack contracts. */
public final class UniversalisIdentitySelfTest {
    private UniversalisIdentitySelfTest() {}

    public static void main(String[] args) throws IOException {
        assert UniversalisIds.MOD_ID.equals("millenaire_universalis");
        assert UniversalisIds.LEGACY_CONTENT_NAMESPACE.equals("millenaire_armies");
        assert !UniversalisIds.MOD_ID.equals(UniversalisIds.LEGACY_CONTENT_NAMESPACE);
        assert UniversalisIds.MOD_ID.matches("[a-z][a-z0-9_]{1,63}");

        ClassLoader loader = UniversalisIdentitySelfTest.class.getClassLoader();
        assert loader.getResource("assets/millenaire_armies/lang/en_us.json") != null
                : "legacy client resource namespace disappeared";
        assert loader.getResource("data/millenaire_armies/army_unit_descriptors/roles/levy.json") != null
                : "legacy datapack namespace disappeared";

        try (InputStream stream = loader.getResourceAsStream("META-INF/neoforge.mods.toml")) {
            assert stream != null : "NeoForge descriptor is absent";
            String descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assert descriptor.contains("modId=\"" + UniversalisIds.MOD_ID + "\"")
                    : "descriptor does not declare the Universalis mod id";
            assert descriptor.contains("displayName=\"Millenaire Universalis\"")
                    : "descriptor does not declare the Universalis display name";
        }

        System.out.println("UniversalisIdentitySelfTest: OK");
    }
}
