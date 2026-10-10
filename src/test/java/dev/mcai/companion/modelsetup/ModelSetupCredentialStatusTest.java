package dev.mcai.companion.modelsetup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.runtime.ModelRuntime;
import org.junit.jupiter.api.Test;

final class ModelSetupCredentialStatusTest {
    @Test
    void onlyProcessSessionStorageRequiresRestartWarning() {
        assertTrue(ModelSetupModule.requiresCredentialRestart("process_only"));
        assertTrue(ModelSetupModule.requiresCredentialRestart(
            "process_only_secure_store_unavailable"
        ));
        assertFalse(ModelSetupModule.requiresCredentialRestart("unchanged"));
        assertFalse(ModelSetupModule.requiresCredentialRestart("macos_keychain"));
        assertFalse(ModelSetupModule.requiresCredentialRestart("windows_dpapi"));
        assertFalse(ModelSetupModule.requiresCredentialRestart("linux_secret_service"));
    }

    @Test
    void reportsTheActualPersistentPlatformStoreInsteadOfProcessOnly() {
        assertEquals(
            "saved_verified_windows_dpapi",
            ModelSetupModule.verifiedCredentialStatus(
                accepted("windows_dpapi_current_user", true)
            )
        );
        assertEquals(
            "saved_verified_linux_secret_service",
            ModelSetupModule.verifiedCredentialStatus(
                accepted("linux_secret_service", true)
            )
        );
        assertEquals(
            "saved_verified_keychain",
            ModelSetupModule.verifiedCredentialStatus(
                accepted("macos_keychain", true)
            )
        );
        assertEquals(
            "saved_verified_secure_store",
            ModelSetupModule.verifiedCredentialStatus(
                accepted("future_platform_store", true)
            )
        );
        assertEquals(
            "saved_verified_process_restart_required",
            ModelSetupModule.verifiedCredentialStatus(
                accepted("process_only_secure_store_unavailable", false)
            )
        );
    }

    private static ModelRuntime.ProfileUpdateOutcome accepted(
            final String storage,
            final boolean persistent
    ) {
        return new ModelRuntime.ProfileUpdateOutcome(
            true,
            "profile_updated",
            "https://example.test/v1",
            "example-model",
            persistent,
            storage
        );
    }
}
