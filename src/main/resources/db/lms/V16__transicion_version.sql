-- el historial de estados de una versión del curso (#259, ADR-0032): una fila por cada cambio de estado, también la
-- creación (desde nulo), escrita por VersionTransitionListener en la transacción de quien escribe. autor_id nulo es la
-- plataforma. solo se añaden: un disparador impide cambiarlas o borrarlas, como en lms.decision_revision. el id da el
-- orden en que se escribieron
CREATE TABLE lms.transicion_version (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organizacion_id  uuid        NOT NULL,
    version_curso_id uuid        NOT NULL,
    desde            text,
    hacia            text        NOT NULL,
    autor_id         uuid,
    ocurrida_en      timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (desde IS DISTINCT FROM hacia)
);

CREATE INDEX transicion_version_version ON lms.transicion_version (organizacion_id, version_curso_id, id);

CREATE FUNCTION lms.transicion_version_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lms.transicion_version solo admite INSERT';
END;
$$;

CREATE TRIGGER transicion_version_inmutable
    BEFORE UPDATE OR DELETE ON lms.transicion_version
    FOR EACH ROW EXECUTE FUNCTION lms.transicion_version_inmutable();
