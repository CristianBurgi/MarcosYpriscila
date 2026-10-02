package com.tuapp.eventfoto.moderation;

import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventCreationService;
import com.tuapp.eventfoto.event.EventOrigin;
import com.tuapp.eventfoto.event.ModeratorTokens;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import com.tuapp.eventfoto.testsupport.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fase 9.2 Bloque A: lo que el link de moderador puede hacer, lo que no, y que nunca sale del evento. */
class ModeratorLinkTest extends ModeratorTestBase {

    @Autowired private EventCreationService eventCreationService;
    @Autowired private ModeratorStreamRegistry streamRegistry;
    @SpyBean private SseBroadcaster sseBroadcaster;

    // ---------- el token ----------

    @Test
    @DisplayName("Todo evento nace con un token de 43 caracteres base64url, distinto en cada evento y para cualquier origen")
    void everyNewEventGetsAUniqueWellFormedToken() {
        Set<String> tokens = new HashSet<>();
        for (EventOrigin origin : EventOrigin.values()) {
            Event created = eventCreationService.createEvent(organizerA, "Evento " + origin, origin, origin == EventOrigin.COURTESY ? "motivo" : null);
            assertThat(created.getModeratorToken()).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(tokens.add(created.getModeratorToken())).isTrue();
        }
        assertThat(ModeratorTokens.generate()).matches(ModeratorTokens.FORMAT);
    }

    // ---------- lo que SÍ puede ----------

    @Test
    @DisplayName("El moderador lista las fotos y los mensajes de su evento, los más recientes primero, sin datos internos")
    void moderatorListsOwnPhotosAndMessages() throws Exception {
        var newer = photo(eventA, "Carla");
        String token = tokenOf(eventA);

        mockMvc.perform(get(api(token, "/photos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$.items[1].id").value(photoA.getId().toString()))
                .andExpect(jsonPath("$.items[0].url").value("/uploads/" + newer.getStorageKey()))
                .andExpect(jsonPath("$.items[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].eventId").doesNotExist())
                .andExpect(jsonPath("$.items[0].comments").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get(api(token, "/messages")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(messageA.getId().toString()))
                .andExpect(jsonPath("$.items[0].text").value("Mensaje de A"));
    }

    @Test
    @DisplayName("Borrar una foto quita el objeto del storage, la fila de la base y emite PHOTO_DELETED")
    void moderatorDeletesPhotoFromStorageDatabaseAndSse() throws Exception {
        String key = photoA.getStorageKey();

        mockMvc.perform(delete(api(tokenOf(eventA), "/photos/" + photoA.getId())))
                .andExpect(status().isNoContent());

        verify(storageService).deleteFile(key);
        assertThat(photoRepository.existsById(photoA.getId())).isFalse();
        verify(sseBroadcaster).broadcastPhotoDeleted(eventA.getId(), photoA.getId());
        assertThat(photoRepository.existsById(photoB.getId())).isTrue();
    }

    @Test
    @DisplayName("Borrar un mensaje quita la fila y emite MESSAGE_DELETED")
    void moderatorDeletesMessageFromDatabaseAndSse() throws Exception {
        mockMvc.perform(delete(api(tokenOf(eventA), "/messages/" + messageA.getId())))
                .andExpect(status().isNoContent());

        assertThat(messageRepository.existsById(messageA.getId())).isFalse();
        verify(sseBroadcaster).broadcastMessageDeleted(eventA.getId(), messageA.getId());
        assertThat(messageRepository.existsById(messageB.getId())).isTrue();
    }

    @Test
    @DisplayName("Cerrar la recepción de fotos (isActive=false) NO cierra la moderación")
    void closedEventStillModeratable() throws Exception {
        Event closed = eventRepository.findById(eventA.getId()).orElseThrow();
        closed.setActive(false);
        eventRepository.save(closed);

        mockMvc.perform(get(api(closed.getModeratorToken(), "/photos"))).andExpect(status().isOk());
        mockMvc.perform(delete(api(closed.getModeratorToken(), "/photos/" + photoA.getId()))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("El log de cada borrado dice quién lo hizo: 'moderador' desde el link, 'organizador' desde el panel, y nunca el token")
    void deletionLogNamesTheActorAndNeverTheToken() throws Exception {
        String token = tokenOf(eventA);
        var secondPhoto = photo(eventA, "Dani");
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(delete(api(token, "/photos/" + photoA.getId()))).andExpect(status().isNoContent());
            mockMvc.perform(delete("/api/v1/admin/events/" + eventA.getSlug() + "/photos/" + secondPhoto.getId()).cookie(cookieOf(organizerA)))
                    .andExpect(status().isNoContent());

            assertThat(logs.events()).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains(photoA.getId().toString()).contains("por moderador"));
            assertThat(logs.events()).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains(secondPhoto.getId().toString()).contains("por organizador"));
            assertThat(logs.text()).doesNotContain(token);
        }
    }

    // ---------- aislamiento ----------

    @Test
    @DisplayName("Con el token de A no se lista, ni se borra, nada de B: un id de B responde 404 igual que un id inexistente")
    void tokenOfAReachesNothingOfB() throws Exception {
        String tokenA = tokenOf(eventA);

        mockMvc.perform(get(api(tokenA, "/photos")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(photoA.getId().toString()));
        mockMvc.perform(get(api(tokenA, "/messages")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(messageA.getId().toString()));

        String foreignPhoto = mockMvc.perform(delete(api(tokenA, "/photos/" + photoB.getId())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingPhoto = mockMvc.perform(delete(api(tokenA, "/photos/" + UUID.randomUUID())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String foreignMessage = mockMvc.perform(delete(api(tokenA, "/messages/" + messageB.getId())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingMessage = mockMvc.perform(delete(api(tokenA, "/messages/" + UUID.randomUUID())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(strip(foreignPhoto)).isEqualTo(strip(missingPhoto));
        assertThat(strip(foreignMessage)).isEqualTo(strip(missingMessage));
        assertThat(photoRepository.existsById(photoB.getId())).isTrue();
        assertThat(messageRepository.existsById(messageB.getId())).isTrue();
        verify(storageService, never()).deleteFile(photoB.getStorageKey());
        verify(sseBroadcaster, never()).broadcastPhotoDeleted(eq(eventB.getId()), any());
    }

    // ---------- tokens inválidos y regeneración ----------

    @Test
    @DisplayName("Token inexistente, mal formado o ajeno: 404 con cuerpo idéntico (API y página)")
    void invalidTokensAllRespondTheSame404() throws Exception {
        String wellFormedButUnknown = ModeratorTokens.generate();
        String[] invalid = {wellFormedButUnknown, "corto", "x".repeat(44), "!".repeat(43), "a".repeat(42) + "."};

        String reference = strip(mockMvc.perform(get(api(wellFormedButUnknown, "/photos"))).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());
        String referencePage = mockMvc.perform(get("/moderar/" + wellFormedButUnknown)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        for (String token : invalid) {
            rateLimiter.reset();
            var apiResponse = mockMvc.perform(get(api(token, "/photos"))).andReturn().getResponse();
            assertThat(apiResponse.getStatus()).as("API con token '%s'", token).isEqualTo(404);
            assertThat(strip(apiResponse.getContentAsString())).as("API con token '%s'", token).isEqualTo(reference);
            assertThat(mockMvc.perform(delete(api(token, "/photos/" + photoA.getId()))).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString()).contains("Evento no encontrado");
            assertThat(mockMvc.perform(get("/moderar/" + token)).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString()).as("página con token '%s'", token.length()).isEqualTo(referencePage);
        }
        assertThat(referencePage).contains("Este link no es válido");
        assertThat(photoRepository.existsById(photoA.getId())).isTrue();
    }

    @Test
    @DisplayName("El token del evento A abre la página del moderador de A (con el nombre del evento) y nada de B")
    void moderatorPageShowsOwnEventOnly() throws Exception {
        mockMvc.perform(get("/moderar/" + tokenOf(eventA)))
                .andExpect(status().isOk())
                .andExpect(view().name("moderator/moderate"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("Evento A")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Evento B"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(eventA.getSlug()))));
    }

    @Test
    @DisplayName("Tras 'generar un link nuevo' el viejo da 404 y el nuevo funciona; el stream abierto con el viejo se cierra")
    void regenerationInvalidatesOldLinkAndClosesOldStream() throws Exception {
        String oldToken = tokenOf(eventA);

        MvcResult oldStream = mockMvc.perform(get(api(oldToken, "/stream"))).andExpect(request().asyncStarted()).andReturn();
        assertThat(streamRegistry.openStreams(eventA.getId())).isEqualTo(1);
        assertThatThrownBy(() -> oldStream.getAsyncResult(300)).as("el stream sigue abierto antes de regenerar")
                .isInstanceOf(IllegalStateException.class);

        String responseBody = mockMvc.perform(post("/api/v1/admin/events/" + eventA.getSlug() + "/moderator-link/regenerate").cookie(cookieOf(organizerA)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse().getContentAsString();
        String newToken = tokenOf(eventA);
        assertThat(newToken).isNotEqualTo(oldToken).matches(ModeratorTokens.FORMAT);
        assertThat(responseBody).contains("/moderar/" + newToken);

        // El stream abierto con el link viejo se cerró.
        assertThat(streamRegistry.openStreams(eventA.getId())).isZero();
        oldStream.getAsyncResult(2000); // si el stream siguiera abierto, esto lanza IllegalStateException

        // El viejo ya no sirve en ninguna ruta; el nuevo sí.
        mockMvc.perform(get(api(oldToken, "/photos"))).andExpect(status().isNotFound());
        mockMvc.perform(delete(api(oldToken, "/photos/" + photoA.getId()))).andExpect(status().isNotFound());
        mockMvc.perform(get(api(oldToken, "/stream"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/moderar/" + oldToken)).andExpect(status().isNotFound());
        mockMvc.perform(get(api(newToken, "/photos"))).andExpect(status().isOk());
        mockMvc.perform(get("/moderar/" + newToken)).andExpect(status().isOk());
        MvcResult newStream = mockMvc.perform(get(api(newToken, "/stream"))).andExpect(request().asyncStarted()).andReturn();
        assertThat(streamRegistry.openStreams(eventA.getId())).isEqualTo(1);
        streamRegistry.closeAll(eventA.getId());
        newStream.getAsyncResult(2000);
    }

    // ---------- lo que NO puede ----------

    @Test
    @DisplayName("El moderador no puede descargar ZIP/PDF, cambiar el estado del evento, regenerar el token ni entrar a /admin/**")
    void moderatorCannotDoAnythingBeyondModeration() throws Exception {
        String token = tokenOf(eventA);
        String slug = eventA.getSlug();

        // Sin sesión de organizador, el panel no responde (401 la API, redirect al login las páginas), lleve lo que lleve en la URL.
        mockMvc.perform(get("/api/v1/admin/events/" + slug + "/photos/download-zip")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/events/" + slug + "/libro-de-visitas.pdf")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/admin/events/" + slug + "/toggle-status")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/events/" + slug + "/moderator-link/regenerate")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/events/" + token + "/photos")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/eventos/" + slug)).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/eventos")).andExpect(status().is3xxRedirection());
        // El token en un header o cookie tampoco es una sesión.
        mockMvc.perform(get("/api/v1/admin/events/" + slug + "/photos").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());

        // Y el estado del evento no cambió.
        assertThat(eventRepository.findById(eventA.getId()).orElseThrow().isActive()).isTrue();
        assertThat(tokenOf(eventA)).isEqualTo(token);

        // Dentro del lado del moderador no existen descargas, configuración ni comentarios.
        for (String path : new String[]{"/photos/download-zip", "/download-zip", "/libro-de-visitas.pdf", "/toggle-status", "/comments",
                "/photos/" + photoA.getId() + "/download", "/moderator-link/regenerate", "/event", "/qr"}) {
            int status = mockMvc.perform(get(api(token, path))).andReturn().getResponse().getStatus();
            assertThat(status).as("GET %s", path).isIn(404, 405);
        }
        mockMvc.perform(post(api(token, "/moderator-link/regenerate"))).andExpect(status().isNotFound());
        mockMvc.perform(patch(api(token, "/toggle-status"))).andExpect(status().isNotFound());
        mockMvc.perform(delete(api(token, "/comments/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        assertThat(photoRepository.existsById(photoA.getId())).isTrue();
    }

    @Test
    @DisplayName("Un organizador ajeno no puede ver ni regenerar el token de un evento que no es suyo (404) y su panel no lo muestra")
    void foreignOrganizerCanNeitherSeeNorRegenerateTheToken() throws Exception {
        String tokenA = tokenOf(eventA);

        mockMvc.perform(post("/api/v1/admin/events/" + eventA.getSlug() + "/moderator-link/regenerate").cookie(cookieOf(organizerB)))
                .andExpect(status().isNotFound());
        assertThat(tokenOf(eventA)).isEqualTo(tokenA);

        String foreignDashboard = mockMvc.perform(get("/admin/eventos/" + eventA.getSlug()).cookie(cookieOf(organizerB)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreignDashboard).doesNotContain(tokenA);

        // El dueño sí lo ve en su panel, armado desde APP_BASE_URL, y esa página no se cachea.
        MvcResult own = mockMvc.perform(get("/admin/eventos/" + eventA.getSlug()).cookie(cookieOf(organizerA)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        assertThat(own.getResponse().getContentAsString()).contains("/moderar/" + tokenA)
                .contains("Borrar es definitivo: la foto o el mensaje no se pueden recuperar.")
                .contains("Generar un link nuevo");
        assertThat((String) own.getModelAndView().getModel().get("moderatorLink")).endsWith("/moderar/" + tokenA).startsWith("http");
    }

    private static String strip(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\",?", "")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "ID");
    }
}
