-- Migration V12: fase 9.1 Bloque 1 - cada evento pertenece a un organizador, y queda
-- registrado si nació pago o de cortesía.
--
-- Guard explícito: 'events' tiene que estar vacía (se vació en el paso cero, fase 9.1
-- Bloque 0). Si alguna vez hay filas acá, la migración TIENE que fallar de forma
-- visible en vez de agregar organizer_id NOT NULL sin poder rellenarlo -- eso dejaría
-- filas inconsistentes o rompería el CREATE de la columna. Mejor un deploy roto y
-- ruidoso que datos corruptos en silencio.
DO $$
DECLARE
    filas INT;
BEGIN
    SELECT count(*) INTO filas FROM events;
    IF filas > 0 THEN
        RAISE EXCEPTION 'V12 no puede correr: events tiene % fila(s). Esta migración asume events vacía (organizer_id NOT NULL sin relleno posible). Resolver manualmente antes de reintentar.', filas;
    END IF;
END $$;

ALTER TABLE events ADD COLUMN organizer_id UUID NOT NULL REFERENCES organizer(id);
CREATE INDEX idx_events_organizer_id ON events(organizer_id);

-- Origen del evento: pago o cortesía. origin_reason es obligatorio para COURTESY,
-- validado a nivel aplicación (no CHECK constraint) para poder dar un mensaje de
-- error claro desde el servicio de creación en vez de una excepción de BD genérica.
ALTER TABLE events ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'PAID';
ALTER TABLE events ALTER COLUMN origin DROP DEFAULT;
ALTER TABLE events ADD COLUMN origin_reason VARCHAR(500);

-- event_date pasa a ser la fecha del evento (sin hora) y nullable: un evento sin
-- costo se crea solo con nombre + email, la fecha se completa después con el wizard
-- (wizard_completed_at marca cuándo se terminó ese paso). upload_deadline sigue
-- siendo NOT NULL -- EventCreationService le pone un valor por defecto al crear,
-- independiente de si ya se sabe la fecha del evento.
ALTER TABLE events ALTER COLUMN event_date DROP NOT NULL;
ALTER TABLE events ALTER COLUMN event_date TYPE DATE USING event_date::date;

ALTER TABLE events ADD COLUMN wizard_completed_at TIMESTAMP WITH TIME ZONE;
