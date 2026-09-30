package com.tuapp.eventfoto.common.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Enumeration;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Única fuente de la IP del cliente para rate limiting (login de organizador/superadmin,
 * comentarios, mensajes, subida de fotos) -- antes había 5 copias de esta lógica, cada
 * una tomando el PRIMER valor de X-Forwarded-For sin más chequeo. Ese header lo arma el
 * cliente: cualquiera puede mandar un valor distinto en cada request y esquivar el límite
 * por IP por completo.
 *
 * <p><b>ESTADO ACTUAL (30/09/2026): la lógica de abajo está CONFIRMADA ROTA en
 * producción, a propósito, mientras se termina de diagnosticar.</b> Verificado con dos
 * redes reales distintas (WiFi y datos móviles): {@code remoteAddr} sí cae en
 * 100.64.0.0/10 como se esperaba, pero el ÚLTIMO valor de X-Forwarded-For no es el
 * cliente -- es un hop de borde de Railway con IP PÚBLICA, igual para las dos redes
 * (152.233.23.194 en ambos casos). Como ese hop no está en el rango interno conocido,
 * {@link #doResolve} lo toma como si fuera el cliente real: hoy, en la práctica, TODOS
 * los clientes resuelven a esa misma IP y el rate limiting por IP es global (cualquiera
 * puede agotarle el cupo de login a todos los demás). No cambiar la regla todavía --
 * falta confirmar con curl contra producción qué headers pisa Railway (X-Real-IP,
 * Forwarded, X-Envoy-External-Address) antes de elegir cuál usar.
 *
 * <p>La única parte del request que el cliente no puede falsificar es la conexión TCP en
 * sí ({@code request.getRemoteAddr()}). Por eso {@link #resolve} vuelca en el log DEBUG
 * remoteAddr, todos los headers candidatos y la lista completa de nombres de header
 * recibidos -- para diagnosticar con curl (mandando X-Forwarded-For/X-Real-IP falsos)
 * cuál de ellos pisa Railway con un valor propio (ese es confiable) y cuál deja pasar
 * el valor del cliente tal cual (ese no sirve).
 *
 * <p>Activar en Railway con {@code LOGGING_LEVEL_COM_TUAPP_EVENTFOTO_COMMON_CONFIG=DEBUG}
 * (a nivel de PAQUETE, no de clase: Spring hace binding de variables de entorno en
 * minúsculas -- apuntar a la clase resolvería a un nombre de logger que nunca matchea
 * el real, que lleva mayúsculas).
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

        if (log.isDebugEnabled()) {
            log.debug("ClientIpResolver: remoteAddr='{}' X-Forwarded-For='{}' X-Real-IP='{}' Forwarded='{}' "
                            + "X-Envoy-External-Address='{}' headers-recibidos={} -> resuelto='{}'",
                    remoteAddr, forwardedFor,
                    request.getHeader("X-Real-IP"),
                    request.getHeader("Forwarded"),
                    request.getHeader("X-Envoy-External-Address"),
                    headerNames(request),
                    resolved);
        }
        return resolved;
    }

    private static String headerNames(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return "[]";
        }
        return StreamSupport.stream(Collections.list(names).spliterator(), false)
                .collect(Collectors.joining(", ", "[", "]"));
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
