-- Migration V13: fase 9.2 Bloque A - link de moderador. Cada evento tiene un token aleatorio que habilita
-- /moderar/{token}: listar y borrar fotos y mensajes de ESE evento, sin cuenta.
--
-- El token se genera en SQL para los eventos que ya existen: gen_random_uuid() es del núcleo de Postgres
-- (no depende de pgcrypto, que puede no estar habilitada en Railway). Dos UUID v4 (32 bytes, 244 bits
-- aleatorios: v4 fija 6 bits por UUID) se pasan a hex -> bytes -> base64 -> base64url sin relleno = 43
-- caracteres, el mismo formato (^[A-Za-z0-9_-]{43}$) que genera la aplicación con SecureRandom (256 bits).
-- La función volátil se evalúa por fila: cada evento recibe un token distinto.
ALTER TABLE events ADD COLUMN moderator_token VARCHAR(43);

UPDATE events
SET moderator_token = rtrim(
        translate(
                encode(decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex'), 'base64'),
                '+/', '-_'),
        '=');

ALTER TABLE events ALTER COLUMN moderator_token SET NOT NULL;
CREATE UNIQUE INDEX idx_events_moderator_token ON events(moderator_token);
