-- Migration V11: tokens de un solo uso del organizador (fase 9.1 Bloque 1).
-- Se guarda el HASH del token, nunca el token en claro -- si alguien lee la base no
-- puede activar cuentas ni resetear contraseñas ajenas. 'purpose' distingue el uso
-- (ACCOUNT_ACTIVATION en este bloque; PASSWORD_RESET se suma en la 9.2 reusando esta
-- misma tabla). Generar un token nuevo para el mismo organizer_id+purpose no borra
-- los anteriores: la validación exige used_at IS NULL AND expires_at > now(), así que
-- un token viejo sin usar queda simplemente vencido/ignorado en la práctica, pero
-- además el servicio marca explícitamente como usados los anteriores no consumidos
-- al emitir uno nuevo, para que quede registro de que se invalidaron a propósito.
CREATE TABLE IF NOT EXISTS organizer_token (
    id UUID PRIMARY KEY,
    organizer_id UUID NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_organizer_token_organizer FOREIGN KEY (organizer_id) REFERENCES organizer(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_organizer_token_hash ON organizer_token(token_hash);
CREATE INDEX idx_organizer_token_organizer_purpose ON organizer_token(organizer_id, purpose);
