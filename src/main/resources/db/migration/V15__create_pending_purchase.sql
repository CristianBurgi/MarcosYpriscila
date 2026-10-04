-- Fase 9.3: compra pendiente (checkout con Mercado Pago). Esta tabla solo registra la intencion de compra y el
-- monto con el que se creo la preferencia; ni la cuenta ni el evento se crean aca (eso es la confirmacion del pago, 9.4).
--
-- id: UUID aleatorio, no secuencial. Es el external_reference de la preferencia y la back_url de la 9.4 no exige
-- sesion, asi que de hecho funciona como un secreto: no tiene que ser adivinable ni enumerable.
-- amount: monto en ARS con el que se creo la preferencia, para que la 9.4 verifique contra lo que esperabamos
-- y no contra la configuracion vigente en ese momento.
-- password_hash: BCrypt (mismo encoder que el alta de cuentas); jamas la contrasena en claro.
CREATE TABLE pending_purchase (
    id UUID PRIMARY KEY,
    organizer_id UUID NULL REFERENCES organizer(id),
    email VARCHAR(255) NULL,
    password_hash VARCHAR(255) NULL,
    event_name VARCHAR(255) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    mp_preference_id VARCHAR(100) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE NULL,
    mp_payment_id VARCHAR(64) NULL,
    -- Cliente existente (organizer_id) XOR cliente nuevo (email + password_hash): nunca ambos ni ninguno.
    CONSTRAINT ck_pending_purchase_mode CHECK (
        (organizer_id IS NOT NULL AND email IS NULL AND password_hash IS NULL)
        OR (organizer_id IS NULL AND email IS NOT NULL AND password_hash IS NOT NULL)
    ),
    CONSTRAINT ck_pending_purchase_amount CHECK (amount > 0)
);

-- Un mismo pago de MP no puede quedar asociado a dos compras (idempotencia de la 9.4 garantizada por la base).
CREATE UNIQUE INDEX uq_pending_purchase_mp_payment_id ON pending_purchase(mp_payment_id) WHERE mp_payment_id IS NOT NULL;
CREATE INDEX idx_pending_purchase_created_at ON pending_purchase(created_at);
