-- el historial de calificaciones manuales (ADR-0016 §2, RF-EVA-06): solo se añade. un disparador impide cambiarlo o borrarlo
CREATE TABLE lms.calificacion (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id uuid          NOT NULL,
    intento_id      uuid          NOT NULL REFERENCES lms.intento (id),
    pregunta_id     uuid          NOT NULL,
    puntos          numeric(10,2) NOT NULL CHECK (puntos >= 0),
    comentario      text,
    motivo          text,
    autor_id        uuid          NOT NULL,
    creado_en       timestamptz   NOT NULL
);

CREATE INDEX calificacion_intento ON lms.calificacion (intento_id, creado_en);

CREATE FUNCTION lms.calificacion_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lms.calificacion solo admite INSERT';
END;
$$;

CREATE TRIGGER calificacion_inmutable
    BEFORE UPDATE OR DELETE ON lms.calificacion
    FOR EACH ROW EXECUTE FUNCTION lms.calificacion_inmutable();
