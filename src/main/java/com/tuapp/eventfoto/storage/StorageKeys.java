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
    /** Imagen de fondo del evento (Fase 9.5): no es una Photo, pero vive bajo el prefijo del evento. */
    private static final Pattern BRANDING_KEY = Pattern.compile("^events/" + UUID_REGEX + "/branding/" + UUID_REGEX + "\\.jpg$");

    private StorageKeys() {
    }

    /** Todo lo de la demo (Fase 9.7-B) vive bajo este prefijo; nada de eventos reales empieza así. */
    public static final String DEMO_ROOT = "demo/";
    /** sid de una demo: 32 bytes aleatorios en base64url sin relleno. Se valida antes de usarlo en una clave. */
    public static final Pattern DEMO_SID = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public static String demoPrefixOf(String sid) {
        if (sid == null || !DEMO_SID.matcher(sid).matches()) {
            throw new IllegalArgumentException("sid de demo inválido");
        }
        return DEMO_ROOT + sid + "/";
    }

    public static String newDemoKey(String sid, String extension) {
        return demoPrefixOf(sid) + UUID.randomUUID() + extension;
    }

    public static String newDemoBrandingKey(String sid) {
        return demoPrefixOf(sid) + "branding/" + UUID.randomUUID() + ".jpg";
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

    public static String newBrandingKey(UUID eventId) {
        return prefixOf(eventId) + "branding/" + UUID.randomUUID() + ".jpg";
    }

    /** Imagen de fondo de ESTE evento, con el formato exacto {@code events/{eventId}/branding/{uuid}.jpg}. */
    public static boolean isBrandingKeyOf(UUID eventId, String key) {
        return key != null && key.startsWith(prefixOf(eventId)) && BRANDING_KEY.matcher(key).matches();
    }

    /** Formato exacto de cualquier evento (foto o imagen de fondo): lo usa el modo local, que no conoce el evento. */
    public static boolean isValidFormat(String key) {
        return key != null && (ANY_EVENT_KEY.matcher(key).matches() || BRANDING_KEY.matcher(key).matches());
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
     * Excepción documentada (transcodificación): cualquier extensión permitida con "image/jpeg" es
     * coherente. El navegador recodifica a JPEG toda foto de más de 1,5 MB (PNG, WebP, HEIC...) y
     * upload.html le pone nombre .jpg, pero un cliente que conserve el nombre original no debe fallar.
     * En ese caso el contenido declarado es JPEG, así que la clave queda ".jpg". La extensión sigue
     * validada contra la lista permitida (.php/.exe dan 400) y la verdad sobre el contenido la dan
     * los bytes (FileSignatureValidator), no esta comparación.
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
            if (declaredIsUsable && !TYPES_BY_EXTENSION.get(extension).contains(declared)) {
                if (!isJpeg(declared)) {
                    throw new InvalidFileFormatException("El tipo de contenido declarado no coincide con la extensión del archivo.");
                }
                return ".jpg"; // transcodificada a JPEG: la clave refleja el contenido declarado
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

    private static boolean isJpeg(String declaredType) {
        return declaredType.equals("image/jpeg") || declaredType.equals("image/jpg");
    }
}
