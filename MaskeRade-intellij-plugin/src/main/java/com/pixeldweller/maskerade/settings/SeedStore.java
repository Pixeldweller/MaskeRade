package com.pixeldweller.maskerade.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The per-project seed for placeholder suggestions lives in the IDE's PasswordSafe: whoever knows it could
 * re-derive which placeholder belongs to which term. Call off the EDT (the credential store may block).
 */
public final class SeedStore {

    private SeedStore() {}

    public static String seed(Project project) {
        var attributes = new CredentialAttributes(
                CredentialAttributesKt.generateServiceName("MaskeRade", "seed-" + TermStore.projectKey(project)));
        var safe = PasswordSafe.getInstance();
        var seed = safe.getPassword(attributes);
        if (seed == null || seed.isBlank()) {
            var bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            seed = HexFormat.of().formatHex(bytes);
            safe.setPassword(attributes, seed);
        }
        return seed;
    }
}
