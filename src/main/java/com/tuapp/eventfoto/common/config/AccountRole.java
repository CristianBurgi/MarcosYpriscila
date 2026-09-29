package com.tuapp.eventfoto.common.config;

/**
 * Los dos tipos de cuenta del JWT (fase 9.1 Bloque 1). Un token con cualquier otro
 * valor de rol -- incluido el "ROLE_ADMIN" del formato anterior a este bloque -- no
 * matchea ninguna constante acá y se trata como inválido (ver JwtTokenProvider).
 */
public enum AccountRole {
    ORGANIZER,
    SUPERADMIN
}
