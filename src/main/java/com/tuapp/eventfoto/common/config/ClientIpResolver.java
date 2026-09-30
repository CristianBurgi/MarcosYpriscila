package com.tuapp.eventfoto.common.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Única fuente de la IP del cliente para rate limiting (login de organizador/superadmin,
 * comentarios, mensajes, subida de fotos) -- antes había 5 copias de esta lógica, cada
 * una tomando el PRIMER valor de X-Forwarded-For sin más chequeo, un header que arma el
 * cliente y que cualquiera puede cambiar en cada request para esquivar el límite por IP.
 *
 * <p><b>Topología de Railway confirmada en producción (30/09/2026)</b>, con curl mandando
 * X-Forwarded-For/X-Real-IP/Forwarded/X-Envoy-External-Address inventados desde una red
 * real: la conexión directa ({@code remoteAddr}) cae en el rango 100.64.0.0/10 (RFC 6598,
 * hop interno de Railway entre su edge y el contenedor de la app). De los headers:
 * <ul>
 *   <li>{@code X-Forwarded-For} y {@code X-Real-IP}: Railway los REEMPLAZA -- descarta
 *       cualquier valor que mande el cliente y pone la IP real de la conexión. Confiables.
 *   <li>{@code Forwarded} y {@code X-Envoy-External-Address}: pasan el valor del cliente
 *       TAL CUAL, sin tocar. NO confiables -- nunca usarlos para esto.
 * </ul>
 * (X-Forwarded-For además viene con el hop de borde público de Railway agregado al final
 * de la cadena, no solo el cliente, así que term-a-term es más frágil que X-Real-IP; se
 * usa X-Real-IP como fuente porque ya viene resuelto a un solo valor.)
 *
 * <p>Regla: si {@code remoteAddr} es un hop interno conocido de Railway, se confía en
 * {@code X-Real-IP} (validado como IP v4 o v6 literal -- nunca un hostname, nunca vacío).
 * Si {@code remoteAddr} NO es un hop interno (desarrollo local, por ejemplo), se ignoran
 * todos los headers y se usa remoteAddr tal cual: ahí no hay ningún proxy de por medio
 * cuya palabra valga más que la conexión real. Si remoteAddr SÍ es interno pero
 * X-Real-IP falta o no es una IP válida (Railway cambió de proxy, un hop nuevo, etc.), se
 * loguea un WARN y se cae a remoteAddr -- ese es el lado seguro del fallo: en el peor caso
 * un límite compartido entre varios clientes reales, nunca uno falsificable por el cliente.
 *
 * <p>Si en el futuro se activa Cloudflare delante de la app (dominio propio), esta
 * topología cambia por completo: Cloudflare es el proxy de borde y el header confiable
 * pasa a ser {@code CF-Connecting-IP} (con Railway como hop intermedio entre Cloudflare y
 * la app) -- hay que revisar esta clase de nuevo en ese momento, no asumir que sigue igual.
 *
 * <p>Activar el diagnóstico en Railway con
 * {@code LOGGING_LEVEL_COM_TUAPP_EVENTFOTO_COMMON_CONFIG=DEBUG} (a nivel de PAQUETE, no de
 * clase: Spring hace binding de variables de entorno en minúsculas -- apuntar a la clase
 * resolvería a un nombre de logger que nunca matchea el real, que lleva mayúsculas).
 */
@Slf4j
@Component
public class ClientIpResolver {

    private static final long TRUSTED_PROXY_RANGE_START = ipv4ToLong(100, 64, 0, 0);
    private static final long TRUSTED_PROXY_RANGE_END = ipv4ToLong(100, 127, 255, 255);

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String xRealIp = request.getHeader("X-Real-IP");
        String resolved = doResolve(remoteAddr, xRealIp);

        log.debug("ClientIpResolver: remoteAddr='{}' X-Real-IP='{}' -> resuelto='{}'", remoteAddr, xRealIp, resolved);
        return resolved;
    }

    private String doResolve(String remoteAddr, String xRealIp) {
        if (!isTrustedInternalProxy(remoteAddr)) {
            return remoteAddr;
        }
        if (isValidIpLiteral(xRealIp)) {
            return xRealIp;
        }
        log.warn("remoteAddr ('{}') es un hop interno de Railway pero X-Real-IP ('{}') no es una IP válida; usando remoteAddr", remoteAddr, xRealIp);
        return remoteAddr;
    }

    private static boolean isTrustedInternalProxy(String ip) {
        Long value = ipv4ToLongOrNull(ip);
        return value != null && value >= TRUSTED_PROXY_RANGE_START && value <= TRUSTED_PROXY_RANGE_END;
    }

    static boolean isValidIpLiteral(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String trimmed = ip.trim();
        return ipv4ToLongOrNull(trimmed) != null || isValidIpv6(trimmed);
    }

    private static Long ipv4ToLongOrNull(String ip) {
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        try {
            int a = Integer.parseInt(octets[0]);
            int b = Integer.parseInt(octets[1]);
            int c = Integer.parseInt(octets[2]);
            int d = Integer.parseInt(octets[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) {
                return null;
            }
            return ipv4ToLong(a, b, c, d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long ipv4ToLong(int a, int b, int c, int d) {
        return ((long) a << 24) | (b << 16) | (c << 8) | d;
    }

    /**
     * Validación estructural de IPv6 (formas estándar, con o sin compresión "::"; no
     * intenta cubrir cada caso borde del RFC como zone IDs "%eth0" o direcciones
     * IPv4-mapeadas) -- alcanza para distinguir una IP real de basura en un header.
     */
    private static boolean isValidIpv6(String ip) {
        if (!ip.contains(":")) {
            return false;
        }
        int doubleColonCount = ip.split("::", -1).length - 1;
        if (doubleColonCount > 1) {
            return false;
        }

        String[] groups;
        if (doubleColonCount == 1) {
            String[] sides = ip.split("::", -1);
            String[] left = sides[0].isEmpty() ? new String[0] : sides[0].split(":", -1);
            String[] right = sides[1].isEmpty() ? new String[0] : sides[1].split(":", -1);
            if (left.length + right.length > 7) {
                return false;
            }
            groups = concat(left, right);
        } else {
            groups = ip.split(":", -1);
            if (groups.length != 8) {
                return false;
            }
        }

        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4 || !group.chars().allMatch(ClientIpResolver::isHexDigit)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String[] concat(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
