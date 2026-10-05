-- Fase 9.4: confirmacion del pago (creacion idempotente de cuenta y evento) e incidentes de pago.

-- Resultado de la compra, para la pagina de retorno y el mail de confirmacion.
-- event_id: ON DELETE SET NULL para no impedir borrar un evento; por eso no se exige en el CHECK.
ALTER TABLE pending_purchase ADD COLUMN event_id UUID NULL REFERENCES events(id) ON DELETE SET NULL;
-- created_organizer: true si la confirmacion creo la cuenta (compra nueva), false si fue una recompra. Despues de
-- procesar, las dos quedan con organizer_id y sin email ni hash: sin esta columna no se distinguen.
ALTER TABLE pending_purchase ADD COLUMN created_organizer BOOLEAN NULL;
-- Reserva del mail "tu evento esta listo": se marca en la misma transaccion que crea el evento (uno solo por compra).
ALTER TABLE pending_purchase ADD COLUMN confirmation_email_sent_at TIMESTAMP WITH TIME ZONE NULL;
-- Ultimo estado de un pago NO aprobado (rejected, cancelled...) para mostrar "el pago no se aprobo" sin exponer el
-- estado por payment_id (que es numerico y enumerable).
ALTER TABLE pending_purchase ADD COLUMN last_payment_status VARCHAR(20) NULL;

-- El modo XOR (cliente existente / cliente nuevo) aplica mientras la compra no esta procesada. Procesada, siempre queda
-- con organizer_id (el creado o el existente), con el pago asociado y SIN datos personales: el email y el hash ya
-- viven en organizer. Una compra con incidente de "email ya registrado" sigue sin procesar, con email + hash y con
-- mp_payment_id (lo que la protege del descarte hasta la revision manual).
ALTER TABLE pending_purchase DROP CONSTRAINT ck_pending_purchase_mode;
ALTER TABLE pending_purchase ADD CONSTRAINT ck_pending_purchase_mode CHECK (
    (processed_at IS NULL AND (
        (organizer_id IS NOT NULL AND email IS NULL AND password_hash IS NULL)
        OR (organizer_id IS NULL AND email IS NOT NULL AND password_hash IS NOT NULL)))
    OR
    (processed_at IS NOT NULL AND organizer_id IS NOT NULL AND mp_payment_id IS NOT NULL
        AND email IS NULL AND password_hash IS NULL)
);

-- Pagos aprobados que NO crearon nada y requieren revision manual (pantalla en la 9.7; nunca reembolso automatico).
-- Uno por payment_id: la misma notificacion repetida no registra (ni avisa) dos veces.
-- external_reference es texto: puede venir algo que no es un UUID de una compra nuestra.
CREATE TABLE payment_incident (
    id UUID PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    external_reference VARCHAR(100) NULL,
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT uq_payment_incident_payment_id UNIQUE (payment_id)
);
CREATE INDEX idx_payment_incident_external_reference ON payment_incident(external_reference);
