package com.tuapp.eventfoto.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class R2ConfigurationValidatorTest {

    private R2ConfigurationValidator validator(String mode, String ak, String sk, String ep, String bucket) {
        R2ConfigurationValidator v = new R2ConfigurationValidator();
        ReflectionTestUtils.setField(v, "storageMode", mode);
        ReflectionTestUtils.setField(v, "accessKey", ak);
        ReflectionTestUtils.setField(v, "secretKey", sk);
        ReflectionTestUtils.setField(v, "endpoint", ep);
        ReflectionTestUtils.setField(v, "bucketName", bucket);
        return v;
    }

    @Test
    void modoLocalNoValidaNada() {
        assertDoesNotThrow(() -> validator("local", "", "", "", "").validateR2ConfigurationOnStartup());
    }

    @Test
    void modoR2ConCredencialesRealesArranca() {
        assertDoesNotThrow(() -> validator("r2",
                "AKIAREAL123", "secretoReal456",
                "https://abc123.r2.cloudflarestorage.com", "eventfoto-bucket")
                .validateR2ConfigurationOnStartup());
    }

    @Test
    void modoR2ConPlaceholderFallaElArranque() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator("r2",
                "r2_placeholder_access_key", "r2_placeholder_secret_key",
                "https://<account-id>.r2.cloudflarestorage.com", "eventfoto-bucket")
                .validateR2ConfigurationOnStartup());
        assertTrue(ex.getMessage().contains("R2_ACCESS_KEY"));
        assertTrue(ex.getMessage().contains("R2_ENDPOINT"));
    }

    @Test
    void modoR2ConVariableFaltanteFallaElArranque() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator("R2",
                "AKIAREAL123", "  ",
                "https://abc123.r2.cloudflarestorage.com", "eventfoto-bucket")
                .validateR2ConfigurationOnStartup());
        assertTrue(ex.getMessage().contains("R2_SECRET_KEY"));
        assertTrue(ex.getMessage().contains("no está seteada"));
    }
}
