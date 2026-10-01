package com.tuapp.eventfoto.storage;

import com.tuapp.eventfoto.common.exception.InvalidFileFormatException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Única fuente del formato de las claves de storage (R2 y modo local):
 * {@code events/{eventId}/{uuid}.{ext}}.
 *
 * La clave pertenece a UN evento y eso se verifica: /confirm rechaza cualquier clave que no
 * tenga el prefijo del evento de la request, y el borrado de una foto no toca objetos de otro
 * evento. Sin soporte del formato anterior ({@code photos/{slug}/...}): el bucket arrancó vacío.
 */
public final class StorageKeys {

    /** Extensiones permitidas para una clave y los content-types declarables para cada una. */
    private static final Map<String, Set<String>> TYPES_BY_EXTENSION = Map.of(
            ".jpg", Set.of("image/jpeg", "image/jpg"),
            ".jpeg", Set.of("image/jpeg", "image/jpg"),
            ".png", Set.of("image/png"),
            ".webp", Set.of("image/webp"),
            ".heic", Set.of("image/heic", "image/heif"),
            ".heif", Set.of("image/heic", "image/heif"));

    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/jpeg", ".jpg",
            "image/jpg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/heic", ".heic",
            "image/heif", ".heif");

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String EXTENSIONS_REGEX = "(?:jpg|jpeg|png|webp|heic|heif)";
    private static final Pattern ANY_EVENT_KEY = Pattern.compile("^events/" + UUID_REGEX + "/" + UUID_REGEX + "\\." + EXTENSIONS_REGEX + "$");

    private StorageKeys() {
    }

    public static String prefixOf(UUID eventId) {
        return "events/" + eventId + "/";
    }

    public static String newKey(UUID eventId, String extension) {
        return prefixOf(eventId) + UUID.randomUUID() + extension;
    }

    /** Clave con el formato exacto y el prefijo de ESTE evento (nada de ".." ni mayúsculas). */
    public static boolean belongsToEvent(UUID eventId, String key) {
        return key != null && key.startsWith(prefixOf(eventId)) && ANY_EVENT_KEY.matcher(key).matches();
    }

    /** Formato exacto de cualquier evento: lo usa el modo local, que no conoce el evento. */
    public static boolean isValidFormat(String key) {
        return key != null && ANY_EVENT_KEY.matcher(key).matches();
    }

    /** ¿La clave pertenece al evento por prefijo? (para decidir si se puede borrar su objeto). */
    public static boolean hasEventPrefix(UUID eventId, String key) {
        return key != null && key.startsWith(prefixOf(eventId));
    }

    /**
     * Extensión validada para la clave. Sale del nombre del archivo si lo trae (debe ser una
     * extensión permitida) y, si no, del content-type. Si el content-type declarado es de una
     * imagen y contradice la extensión, se rechaza (400).
     *
     * Excepción documentada: ".heic"/".heif" con "image/jpeg" es coherente. El navegador de un
     * iPhone decodifica el HEIC en un canvas y lo vuelve a codificar a JPEG conservando el nombre
     * (ver upload.html, compressImageIfNeeded). La verdad sobre el contenido la dan los bytes
     * (FileSignatureValidator), no esta comparación.
     */
    public static String validatedExtension(String filename, String contentType) {
        String declared = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        boolean declaredIsUsable = !declared.isEmpty() && !declared.equals("application/octet-stream");

        String extension;
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot >= 0) {
            extension = filename.substring(dot).toLowerCase(Locale.ROOT).trim();
            if (!TYPES_BY_EXTENSION.containsKey(extension)) {
                throw new InvalidFileFormatException("El formato del archivo no está permitido. Usá JPEG, PNG, WEBP o HEIC.");
            }
            if (declaredIsUsable && !TYPES_BY_EXTENSION.get(extension).contains(declared) && !isTranscodedHeic(extension, declared)) {
                throw new InvalidFileFormatException("El tipo de contenido declarado no coincide con la extensión del archivo.");
            }
            return extension;
        }

        extension = EXTENSION_BY_TYPE.get(declared);
        if (extension == null) {
            if (!declaredIsUsable) {
                return ".jpg"; // sin nombre ni tipo declarado: el contenido real lo valida la firma binaria
            }
            throw new InvalidFileFormatException("El tipo de contenido no está permitido. Usá JPEG, PNG, WEBP o HEIC.");
        }
        return extension;
    }

    private static boolean isTranscodedHeic(String extension, String declaredType) {
        return (extension.equals(".heic") || extension.equals(".heif")) && (declaredType.equals("image/jpeg") || declaredType.equals("image/jpg"));
    }
}
