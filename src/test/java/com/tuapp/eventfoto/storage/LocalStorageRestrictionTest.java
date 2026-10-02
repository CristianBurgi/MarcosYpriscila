package com.tuapp.eventfoto.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 9.1 Bloque 4: prueban las RAZONES por las que las rutas de storage local están en la lista de
 * excepciones del test de rutas públicas: solo existen en modo local, y ahí exigen el formato de
 * clave events/{uuid}/{uuid}.ext.
 */
class LocalStorageRestrictionTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LocalStorageController.class);
    private Path createdFile;

    @AfterEach
    void cleanUp() throws Exception {
        if (createdFile != null) {
            Files.deleteIfExists(createdFile);
        }
    }

    @Test
    @DisplayName("En producción (app.storage.mode=r2) el LocalStorageController no existe: las rutas no se registran")
    void controllerDoesNotExistOutsideLocalMode() {
        runner.withPropertyValues("app.storage.mode=r2")
                .run(context -> assertThat(context).doesNotHaveBean(LocalStorageController.class));
        runner.withPropertyValues("app.storage.mode=local")
                .run(context -> assertThat(context).hasSingleBean(LocalStorageController.class));
        runner.run(context -> assertThat(context).as("sin la propiedad rige el default 'local'").hasSingleBean(LocalStorageController.class));
    }

    @Test
    @DisplayName("Aun si el controller estuviera presente fuera del modo local: PUT local-upload da 403 y GET files no sirve nada")
    void beltAndBracesOutsideLocalMode() throws Exception {
        LocalStorageController controller = new LocalStorageController();
        ReflectionTestUtils.setField(controller, "storageMode", "r2");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String key = "events/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".jpg";

        mvc.perform(put("/api/v1/storage/local-upload").param("key", key).content(JPEG)).andExpect(status().isForbidden());

        // Hasta con un archivo realmente presente en el disco, GET files no lo sirve.
        Path file = Paths.get("uploads", key).toAbsolutePath();
        Files.createDirectories(file.getParent());
        Files.write(file, JPEG);
        createdFile = file;
        mvc.perform(get("/api/v1/storage/files").param("key", key)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("En modo local exige el formato events/{uuid}/{uuid}.ext (rechaza photos/..., '..', mayúsculas y extensiones raras)")
    void localModeRequiresStrictKeyFormat() throws Exception {
        LocalStorageController controller = new LocalStorageController();
        ReflectionTestUtils.setField(controller, "storageMode", "local");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String uuid = UUID.randomUUID().toString();

        for (String bad : new String[]{
                "photos/test-guest-upload.jpg",
                "events/" + uuid + "/../" + uuid + ".jpg",
                "events/" + uuid + "/" + uuid.toUpperCase() + ".jpg",
                "events/" + uuid + "/" + uuid + ".php"}) {
            mvc.perform(put("/api/v1/storage/local-upload").param("key", bad).content(JPEG)).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/storage/files").param("key", bad)).andExpect(status().isNotFound());
        }
    }
}
