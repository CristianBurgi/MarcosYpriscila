package com.tuapp.eventfoto.event;

import com.tuapp.eventfoto.common.exception.FileTooLargeException;
import com.tuapp.eventfoto.common.exception.InvalidEventSettingsException;
import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;
import com.tuapp.eventfoto.event.dto.EventSettingsDTO;
import com.tuapp.eventfoto.storage.ImageContent;
import com.tuapp.eventfoto.storage.StorageKeys;
import com.tuapp.eventfoto.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Fecha y personalización (color, imagen de fondo) de un evento: lo que completa el wizard y después edita la
 * tarjeta "Personalización" del panel. Mismos métodos para los dos. Cada cambio es un UPDATE puntual, así no
 * pisa otros campos con la copia del evento que trae la request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSettingsService {

    static final String DATE_LOCKED_MESSAGE = "La fecha ya no se puede cambiar: la subida de fotos ya está habilitada";
    private static final String IMAGE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final EventRepository eventRepository;
    private final UploadWindow uploadWindow;
    private final StorageService storageService;
    private final ImageContent imageContent;
    private final Clock clock;

    @Value("${app.upload.max-file-bytes:15728640}")
    private long maxFileBytes;

    /**
     * Fecha válida, no anterior a hoy y no más de 2 años adelante (hora de Argentina). Con el wizard completo,
     * además, solo hasta que abre la ventana de subida de la fecha actual. Antes de completarlo no rige ese
     * bloqueo: si no, elegir "mañana" en el paso 1 dejaría la fecha trabada sin poder corregirla.
     */
    public LocalDate updateDate(Event event, String rawDate) {
        LocalDate date;
        try {
            date = LocalDate.parse(rawDate == null ? "" : rawDate.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidEventSettingsException("La fecha no es válida.");
        }
        LocalDate today = uploadWindow.today();
        if (date.isBefore(today)) {
            throw new InvalidEventSettingsException("La fecha del evento no puede ser anterior a hoy.");
        }
        if (date.isAfter(today.plusYears(2))) {
            throw new InvalidEventSettingsException("La fecha del evento no puede ser más de 2 años adelante.");
        }
        if (!isDateEditable(event)) {
            throw new InvalidEventSettingsException(DATE_LOCKED_MESSAGE);
        }
        eventRepository.updateEventDate(event.getId(), date);
        return date;
    }

    /** {@code null} vuelve a la paleta por defecto de la app. */
    public String updateColor(Event event, String rawColor) {
        String color;
        try {
            color = rawColor == null ? null : EventPalette.normalize(rawColor.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidEventSettingsException("El color tiene que tener el formato #rrggbb.");
        }
        eventRepository.updateBackgroundColor(event.getId(), color);
        return color;
    }

    /**
     * Valida (firma, HEIC -> JPEG), le saca los metadatos (EXIF/GPS) y la guarda bajo
     * events/{eventId}/branding/. El objeto anterior se borra recién con el UPDATE ya hecho.
     */
    public String replaceImage(Event event, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileFormatException("El archivo enviado está vacío.");
        }
        if (file.getSize() > maxFileBytes) {
            throw new FileTooLargeException("El archivo supera el tope de " + maxFileBytes + " bytes");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new InvalidFileFormatException("No pudimos leer la imagen. Probá con otra.");
        }
        byte[] jpeg = ImageContent.stripJpegMetadata(
                imageContent.validateAndConvert(bytes, "branding event=" + event.getId()).bytes());

        String key = StorageKeys.newBrandingKey(event.getId());
        storageService.uploadBytes(key, jpeg, "image/jpeg", IMAGE_CACHE_CONTROL);
        swapImageKey(event, key);
        return key;
    }

    public void removeImage(Event event) {
        swapImageKey(event, null);
    }

    /** El estado actual, leído de la base (no de la copia que trajo la request). */
    public EventSettingsDTO view(UUID eventId) {
        Event e = eventRepository.findById(eventId).orElseThrow();
        String imageUrl = e.getBackgroundImageKey() == null ? null : storageService.generatePublicUrl(e.getBackgroundImageKey());
        return new EventSettingsDTO(e.getEventDate(), e.getBackgroundColor(), imageUrl, isDateEditable(e), e.getWizardCompletedAt() != null);
    }

    public boolean isDateEditable(Event e) {
        return e.getWizardCompletedAt() == null || e.getEventDate() == null || !uploadWindow.hasOpened(e.getEventDate());
    }

    /** Idempotente: repetirlo no cambia la marca del primer "Ir a mi panel". */
    public void completeWizard(Event event) {
        if (event.getEventDate() == null) {
            throw new InvalidEventSettingsException("Elegí la fecha del evento antes de terminar.");
        }
        eventRepository.markWizardCompleted(event.getId(), clock.instant());
    }

    // ponytail: leer-actualizar no es atómico; dos reemplazos simultáneos pueden dejar un objeto huérfano bajo
    // events/{id}/branding/ (lo limpia el borrado por prefijo de la 9.6). UPDATE ... RETURNING si llegara a importar.
    private void swapImageKey(Event event, String newKey) {
        String oldKey = eventRepository.findBackgroundImageKey(event.getId());
        eventRepository.updateBackgroundImageKey(event.getId(), newKey);
        if (oldKey != null && StorageKeys.isBrandingKeyOf(event.getId(), oldKey)) {
            try {
                storageService.deleteFile(oldKey);
            } catch (Exception e) {
                log.warn("No se pudo borrar la imagen de fondo anterior '{}': {}", oldKey, e.getMessage());
            }
        }
    }
}
