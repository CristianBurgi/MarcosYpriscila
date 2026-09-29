-- Migration V10: fase 9.1 Bloque 1 - cuentas de organizador.
-- password_hash es nullable: una cuenta creada por el superadmin (evento sin costo)
-- no tiene contraseña hasta que la persona activa su cuenta con el link de un solo uso.
-- token_version se incrementa cada vez que hay que invalidar los JWT ya emitidos de
-- este organizador (cambio de contraseña, etc.) -- el JWT lleva su propio tokenVersion
-- y cada request lo compara contra esta columna.
CREATE TABLE IF NOT EXISTS organizer (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    token_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_organizer_email ON organizer(LOWER(email));
