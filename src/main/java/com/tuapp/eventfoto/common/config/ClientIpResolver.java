package com.tuapp.eventfoto.common.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Única fuente de la IP del cliente para rate limiting (login de organizador/superadmin,
 * comentarios, mensajes, subida de fotos) -- antes había 5 copias de esta lógica, cada
 * una tomando el PRIMER valor de X-Forwarded-For sin más chequeo. Ese header lo arma el
 * cliente: cualquiera puede mandar un valor distinto en cada request y esquivar el límite
 * por IP por completo.
 *
 * La única parte del request que el cliente no puede falsificar es la conexión TCP en sí
 * ({@code request.getRemoteAddr()}). En Railway, esa dirección es la del último hop de su
 * red interna (rango 100.64.0.0/10, RFC 6598 -- documentado por Railway como el rango de
 * sus proxies internos entre el edge y el contenedor de la app), nunca la del cliente
 * externo. Por eso el algoritmo es: SOLO se confía en X-Forwarded-For cuando quien nos
 * conectó directamente es un hop interno conocido de Railway -- y ahí se toma, de derecha
 * a izquierda, la primera dirección de la cadena que NO sea también un hop interno (puede
 * haber más de uno). Si quien nos conectó no es un proxy interno reconocido (desarrollo
 * local, por ejemplo), se ignora el header por completo y se usa remoteAddr tal cual: ahí
 * no hay ningún proxy de por medio cuya palabra valga más que la conexión real.
 *
 * Pendiente de confirmar contra logs reales de producción: esta implementación asume
 * el rango de proxies internos que documenta Railway. Para verificarlo, activar el log
 * DEBUG de este paquete en Railway con la variable de entorno
 * {@code LOGGING_LEVEL_COM_TUAPP_EVENTFOTO_COMMON_CONFIG=DEBUG} (a nivel de PAQUETE, no
 * de clase: Spring hace binding de variables de entorno en minúsculas --
 * "LOGGING_LEVEL_..._CLIENTIPRESOLVER" resolvería a la propiedad
 * "logging.level....clientipresolver", que nunca matchea el logger real
 * "com.tuapp.eventfoto.common.config.ClientIpResolver" porque los nombres de clase Java
 * llevan mayúsculas. El paquete no tiene ese problema porque ya es todo minúsculas).
 * Si el patrón real difiere de lo asumido, ajustar TRUSTED_PROXY_RANGE_* acá, en un solo
 * lugar.
 */
@Slf4j
@Component
public class ClientIpResolver {

    private static final long TRUSTED_PROXY_RANGE_START = ipv4ToLong(100, 64, 0, 0);
    private static final long TRUSTED_PROXY_RANGE_END = ipv4ToLong(100, 127, 255, 255);

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String resolved = doResolve(remoteAddr, forwardedFor);

        log.debug("ClientIpResolver: remoteAddr='{}' X-Forwarded-For='{}' -> resuelto='{}'", remoteAddr, forwardedFor, resolved);
        return resolved;
    }

    private String doResolve(String remoteAddr, String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank() || !isTrustedInternalProxy(remoteAddr)) {
            return remoteAddr;
        }

        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (!candidate.isEmpty() && !isTrustedInternalProxy(candidate)) {
                return candidate;
            }
        }

        // Toda la cadena son proxies internos conocidos (sin IP de cliente real presente):
        // no hay nada mejor para devolver que la conexión directa.
        return remoteAddr;
    }

    private static boolean isTrustedInternalProxy(String ip) {
        Long value = ipv4ToLongOrNull(ip);
        return value != null && value >= TRUSTED_PROXY_RANGE_START && value <= TRUSTED_PROXY_RANGE_END;
    }

    private static Long ipv4ToLongOrNull(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            return null; // IPv6 u otro formato: no es parte del rango interno conocido, se trata como no confiable
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
}
