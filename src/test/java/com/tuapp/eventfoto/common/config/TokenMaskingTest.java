package com.tuapp.eventfoto.common.config;

import com.tuapp.eventfoto.event.AccessRequest;
import com.tuapp.eventfoto.event.AccessScope;
import com.tuapp.eventfoto.event.Event;
import com.tuapp.eventfoto.event.EventRepository;
import com.tuapp.eventfoto.event.ModeratorTokenPolicy;
import com.tuapp.eventfoto.event.ModeratorTokens;
import com.tuapp.eventfoto.event.OrganizerOwnerPolicy;
import com.tuapp.eventfoto.event.UploadWindow;
import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** El token se enmascara en textos libres, en lo que se manda a Sentry, y cada policy solo concede en su ámbito. */
class TokenMaskingTest {

    private final String token = ModeratorTokens.generate();

    @Test
    @DisplayName("TokenMasker reemplaza el token por posición (en las dos rutas) y por forma (en texto libre)")
    void masksByRouteAndByShape() {
        assertThat(TokenMasker.mask("/moderar/" + token)).isEqualTo("/moderar/{token}");
        assertThat(TokenMasker.mask("/api/v1/moderate/" + token + "/photos/x?page=1")).isEqualTo("/api/v1/moderate/{token}/photos/x?page=1");
        assertThat(TokenMasker.mask("/moderar/corto")).isEqualTo("/moderar/{token}");
        assertThat(TokenMasker.mask("No static resource api/v1/moderate/" + token + "/zzz.")).doesNotContain(token);
        assertThat(TokenMasker.mask("falló con " + token + " adentro")).isEqualTo("falló con {token} adentro");
        assertThat(TokenMasker.mask("/admin/eventos/mi-evento")).isEqualTo("/admin/eventos/mi-evento");
        assertThat(TokenMasker.mask(null)).isNull();
    }

    @Test
    @DisplayName("Sentry: la URL de la request, el mensaje, las excepciones y las breadcrumbs salen sin el token")
    void sentryEventsAreScrubbed() {
        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setUrl("https://eventfoto.example/moderar/" + token);
        request.setQueryString("t=" + token);
        event.setRequest(request);
        event.setTransaction("GET /api/v1/moderate/" + token + "/photos");
        Message message = new Message();
        message.setFormatted("falló " + token);
        event.setMessage(message);
        SentryException exception = new SentryException();
        exception.setValue("No static resource api/v1/moderate/" + token + "/zzz.");
        event.setExceptions(List.of(exception));
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("GET /moderar/" + token);
        breadcrumb.setData("url", "https://eventfoto.example/api/v1/moderate/" + token + "/stream");
        event.addBreadcrumb(breadcrumb);

        SentryEvent scrubbed = SentryTokenScrubber.scrub(event);

        String everything = String.join("|", scrubbed.getRequest().getUrl(), scrubbed.getRequest().getQueryString(),
                scrubbed.getTransaction(), scrubbed.getMessage().getFormatted(), scrubbed.getExceptions().get(0).getValue(),
                scrubbed.getBreadcrumbs().get(0).getMessage(), String.valueOf(scrubbed.getBreadcrumbs().get(0).getData("url")));
        assertThat(everything).doesNotContain(token).contains("{token}");
    }

    @Test
    @DisplayName("Cada policy concede solo en su ámbito: el token no abre el panel y el organizador no abre el lado del moderador")
    void policiesGrantOnlyInTheirOwnScope() {
        UUID organizerId = UUID.randomUUID();
        Event event = Event.builder().id(UUID.randomUUID()).slug("x").moderatorToken(token).build();
        EventRepository repository = mock(EventRepository.class);
        when(repository.existsByIdAndOrganizerId(any(), any())).thenReturn(true);
        OrganizerOwnerPolicy ownerPolicy = new OrganizerOwnerPolicy(repository);
        ModeratorTokenPolicy moderatorPolicy = new ModeratorTokenPolicy(new UploadWindow(java.time.Clock.systemUTC()));
        Authentication organizer = new UsernamePasswordAuthenticationToken(organizerId, null, List.of());

        assertThat(moderatorPolicy.canAccess(new AccessRequest(AccessScope.MODERATOR, null), event)).isTrue();
        assertThat(moderatorPolicy.canAccess(new AccessRequest(AccessScope.PANEL, null), event)).isFalse();
        assertThat(moderatorPolicy.canAccess(new AccessRequest(AccessScope.PANEL, organizer), event)).isFalse();

        assertThat(ownerPolicy.canAccess(new AccessRequest(AccessScope.PANEL, organizer), event)).isTrue();
        assertThat(ownerPolicy.canAccess(new AccessRequest(AccessScope.MODERATOR, organizer), event)).isFalse();
        assertThat(ownerPolicy.canAccess(new AccessRequest(AccessScope.PANEL, null), event)).isFalse();
    }
}
