package com.tuapp.eventfoto.demo;

import com.tuapp.eventfoto.common.config.AppUrls;
import com.tuapp.eventfoto.common.config.ClientIpResolver;
import com.tuapp.eventfoto.common.config.RateLimiterService;
import com.tuapp.eventfoto.event.UploadWindow;
import com.tuapp.eventfoto.message.dto.CreateMessageRequestDTO;
import com.tuapp.eventfoto.message.dto.MessageResponseDTO;
import com.tuapp.eventfoto.photo.dto.PhotoResponseDTO;
import com.tuapp.eventfoto.qr.QrCodeService;
import com.tuapp.eventfoto.realtime.SseBroadcaster;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API de la demo (Fase 9.7-B). Las páginas de invitados la usan con EVENT_API = /api/v1/demo/{sid}, así que
 * replica la forma de /api/v1/events/{slug} en lo que esas páginas leen. Un sid sin el formato, inexistente o con
 * más de 30 minutos responde 404 "Tu demo terminó." (DemoService.requireActive), sin distinguir los casos.
 */
@RestController
@RequestMapping("/api/v1/demo")
@RequiredArgsConstructor
public class DemoApiController {

    private final DemoService demoService;
    private final RateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;
    private final SseBroadcaster sseBroadcaster;
    private final QrCodeService qrCodeService;
    private final UploadWindow uploadWindow;
    private final AppUrls appUrls;

    /** Lo que leen menu, album, messages y screen de GET /api/v1/events/{slug}, más expiresAt y los topes. */
    public record DemoEventDTO(String name, LocalDate eventDate, boolean isActive, UploadWindow.Status uploadStatus,
                               String uploadMessage, String guestbookMessage, Instant expiresAt,
                               int maxPhotos, long photos, int maxMessages, long messages) {
    }

    /** Forma de un Page de Spring: las páginas leen content y last. Todo entra en una página (5 + 10 como mucho). */
    public record DemoPage<T>(List<T> content, boolean last, int totalElements) {
        static <T> DemoPage<T> of(List<T> all, int page) {
            return new DemoPage<>(page == 0 ? all : List.of(), true, all.size());
        }
    }

    // --- Wizard ---

    /** Fin del wizard: fecha, color e imagen opcional en un solo multipart. Crea la demo y devuelve su URL. */
    @PostMapping(value = "/sessions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> create(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("color") String color,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        rateLimiterService.checkDemoSessionRateLimit(clientIpResolver.resolve(request));
        DemoSession session = demoService.create(date, color, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("url", "/demo/" + session.getSid()));
    }

    /** Las reglas de un evento real con esa fecha, calculadas por UploadWindow (en la demo la subida está siempre abierta). */
    @GetMapping("/rules")
    public Map<String, String> rules(@RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate opens = uploadWindow.opensAt(date).atZone(UploadWindow.ZONE).toLocalDate();
        LocalDate lastDay = uploadWindow.closesAt(date).minus(Duration.ofSeconds(1)).atZone(UploadWindow.ZONE).toLocalDate();
        return Map.of("text", "En tu evento, tus invitados podrían subir fotos desde el " + UploadWindow.day(opens)
                + " a las 00:00 hasta el " + UploadWindow.day(lastDay) + " a las 23:59, y el álbum queda disponible "
                + UploadWindow.RETENTION_DAYS + " días, hasta el " + UploadWindow.day(date.plusDays(UploadWindow.RETENTION_DAYS)) + ".");
    }

    // --- Lo que leen las páginas de invitados ---

    @GetMapping("/{sid}")
    public ResponseEntity<DemoEventDTO> event(@PathVariable String sid) {
        DemoSession s = demoService.requireActive(sid);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DemoEventDTO("Demo en vivo", s.getEventDate(), true,
                UploadWindow.Status.OPEN, null, null, DemoService.expiresAt(s),
                DemoService.MAX_PHOTOS, demoService.photoCount(s), DemoService.MAX_MESSAGES, demoService.messageCount(s)));
    }

    @GetMapping("/{sid}/photos")
    public DemoPage<PhotoResponseDTO> photos(@PathVariable String sid, @RequestParam(defaultValue = "0") int page) {
        return DemoPage.of(demoService.photos(demoService.requireActive(sid)), page);
    }

    @PostMapping(value = "/{sid}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoResponseDTO> upload(@PathVariable String sid, @RequestParam("file") MultipartFile file,
                                                   HttpServletRequest request) {
        DemoSession session = demoService.requireActive(sid);
        rateLimiterService.checkDemoUploadRateLimit(clientIpResolver.resolve(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(demoService.uploadPhoto(session, file));
    }

    /** El álbum pide los comentarios al abrir una foto. En la demo no hay comentarios: siempre vacío. */
    @GetMapping("/{sid}/photos/{photoId}/comments")
    public List<Object> comments(@PathVariable String sid, @PathVariable UUID photoId) {
        demoService.requireActive(sid);
        return List.of();
    }

    @GetMapping("/{sid}/messages")
    public DemoPage<MessageResponseDTO> messages(@PathVariable String sid, @RequestParam(defaultValue = "0") int page) {
        return DemoPage.of(demoService.messages(demoService.requireActive(sid)), page);
    }

    /** Mismo DTO con @Valid y mismo rate limit que POST /api/v1/events/{slug}/messages. */
    @PostMapping("/{sid}/messages")
    public ResponseEntity<MessageResponseDTO> addMessage(@PathVariable String sid,
                                                         @Valid @RequestBody CreateMessageRequestDTO body,
                                                         HttpServletRequest request) {
        DemoSession session = demoService.requireActive(sid);
        rateLimiterService.checkCommentMessageRateLimit(clientIpResolver.resolve(request), body.guestToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(demoService.addMessage(session, body));
    }

    /** SSE de UNA demo: canal DemoChannel(sid), nunca el de un evento ni el de otra demo. */
    @GetMapping(value = "/{sid}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sid) {
        return sseBroadcaster.subscribeDemo(demoService.requireActive(sid).getSid());
    }

    /** QR de la pantalla: lleva al menú de invitados de esta demo, armado con APP_BASE_URL. */
    @GetMapping("/{sid}/qr")
    public ResponseEntity<byte[]> qr(@PathVariable String sid, @RequestParam(defaultValue = "400") int size) {
        DemoSession session = demoService.requireActive(sid);
        int dimension = Math.min(Math.max(size, 100), 1000);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .body(qrCodeService.generateQrCodePng(appUrls.demoMenuUrl(session.getSid()), dimension, dimension));
    }
}
