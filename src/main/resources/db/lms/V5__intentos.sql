-- copia del contenido de las evaluaciones (ADR-0017): el estudiante no puede leer sus registros, porque llevan las
-- soluciones, y la plataforma no lee dentro de una petición. AssessmentContentListener la mantiene al escribir el temario
CREATE TABLE lms.contenido_evaluacion (
    organizacion_id uuid  NOT NULL,
    objeto          text  NOT NULL,
    registro_id     uuid  NOT NULL,
    padre_id        uuid  NOT NULL,
    datos           jsonb NOT NULL,
    PRIMARY KEY (organizacion_id, objeto, registro_id)
);

CREATE INDEX contenido_evaluacion_padre ON lms.contenido_evaluacion (organizacion_id, objeto, padre_id);

-- un intento opera sobre su instantánea (ADR-0016 §2): se copia al empezar, con soluciones, y la API del estudiante no las
-- expone. un disparador impide cambiarla o borrar el intento, también con SQL directo
CREATE TABLE lms.intento (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id uuid        NOT NULL,
    edicion_id      uuid        NOT NULL,
    matricula_id    uuid        NOT NULL,
    leccion_id      uuid        NOT NULL,
    estudiante_id   uuid        NOT NULL,
    numero          integer     NOT NULL CHECK (numero > 0),
    estado          text        NOT NULL DEFAULT 'OPEN' CHECK (estado IN ('OPEN', 'SUBMITTED', 'GRADED')),
    instantanea     jsonb       NOT NULL,
    respuestas      jsonb       NOT NULL DEFAULT '[]',
    iniciado_en     timestamptz NOT NULL,
    vence_en        timestamptz,
    enviado_en      timestamptz,
    resultado       jsonb,
    porcentaje      integer,
    aprobado        boolean,
    UNIQUE (matricula_id, leccion_id, numero)
);

CREATE UNIQUE INDEX intento_uno_abierto ON lms.intento (matricula_id, leccion_id) WHERE estado = 'OPEN';
CREATE INDEX intento_edicion ON lms.intento (organizacion_id, edicion_id, estado);

CREATE FUNCTION lms.intento_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'lms.intento no se borra';
    END IF;
    IF NEW.instantanea IS DISTINCT FROM OLD.instantanea THEN
        RAISE EXCEPTION 'la instantánea de un intento no cambia';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER intento_inmutable
    BEFORE UPDATE OR DELETE ON lms.intento
    FOR EACH ROW EXECUTE FUNCTION lms.intento_inmutable();
