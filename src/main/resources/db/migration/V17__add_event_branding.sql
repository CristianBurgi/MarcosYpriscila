-- Fase 9.5: personalizacion del evento (wizard de onboarding).
-- event_date y wizard_completed_at ya existen desde V12.
-- background_color: NULL = paleta por defecto de la app. Siempre en minusculas (lo normaliza el codigo).
-- background_image_key: clave en storage (events/{eventId}/branding/{uuid}.jpg) o NULL.
-- Sin relleno a proposito: los eventos que ya existen pasan por el wizard.
ALTER TABLE events ADD COLUMN background_color VARCHAR(7) NULL;
ALTER TABLE events ADD COLUMN background_image_key VARCHAR(255) NULL;

ALTER TABLE events ADD CONSTRAINT ck_events_background_color
    CHECK (background_color IS NULL OR background_color ~ '^#[0-9a-f]{6}$');
