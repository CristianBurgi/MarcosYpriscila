-- Fase 9.5: limite de fotos por invitado configurable por evento.
-- NULL = sin limite. Los eventos que ya existen conservan el comportamiento de siempre (24).
-- Sin DEFAULT a proposito: el valor de los eventos nuevos lo pone el codigo
-- (app.guest-quota.default-max-photos-per-guest), asi hay un unico lugar donde vive ese numero.
ALTER TABLE events ADD COLUMN max_photos_per_guest INTEGER NULL;

UPDATE events SET max_photos_per_guest = 24;

ALTER TABLE events ADD CONSTRAINT ck_events_max_photos_per_guest
    CHECK (max_photos_per_guest IS NULL OR max_photos_per_guest > 0);
