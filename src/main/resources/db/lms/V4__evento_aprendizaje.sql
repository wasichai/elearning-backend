-- eventos de aprendizaje (ADR-0012): la evidencia cruda del progreso y la materia de los informes. solo se añaden: una
-- vez por (organización, estudiante, identificador que pone el cliente), y un disparador impide cambiarlos o borrarlos,
-- también con SQL directo. el tiempo con la página abierta no se registra: no es evidencia
CREATE TABLE lms.evento_aprendizaje (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id   uuid        NOT NULL,
    matricula_id      uuid        NOT NULL,
    leccion_id        uuid        NOT NULL,
    estudiante_id     uuid        NOT NULL,
    tipo              text        NOT NULL,
    cliente_evento_id uuid        NOT NULL,
    ocurrido_en       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organizacion_id, estudiante_id, cliente_evento_id)
);

CREATE INDEX evento_aprendizaje_matricula ON lms.evento_aprendizaje (organizacion_id, matricula_id, leccion_id);

CREATE FUNCTION lms.evento_aprendizaje_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lms.evento_aprendizaje solo admite INSERT';
END;
$$;

CREATE TRIGGER evento_aprendizaje_inmutable
    BEFORE UPDATE OR DELETE ON lms.evento_aprendizaje
    FOR EACH ROW EXECUTE FUNCTION lms.evento_aprendizaje_inmutable();

CREATE TRIGGER evento_aprendizaje_sin_truncate
    BEFORE TRUNCATE ON lms.evento_aprendizaje
    FOR EACH STATEMENT EXECUTE FUNCTION lms.evento_aprendizaje_inmutable();
