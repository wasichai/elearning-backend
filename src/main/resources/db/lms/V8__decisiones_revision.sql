-- las decisiones de la revisión de una versión del curso (ADR-0018 §2, RF-CON-06): solo se añaden y siguen ligadas a su
-- versión aunque esta vuelva a borrador o se cree otra. un disparador impide cambiarlas o borrarlas
CREATE TABLE lms.decision_revision (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id  uuid        NOT NULL,
    version_curso_id uuid        NOT NULL,
    decision         text        NOT NULL CHECK (decision IN ('APROBAR', 'PEDIR_CAMBIOS', 'RECHAZAR')),
    motivo           text,
    autor_id         uuid        NOT NULL,
    decidida_en      timestamptz NOT NULL,
    -- siempre con motivo salvo aprobar
    CHECK (decision = 'APROBAR' OR length(btrim(motivo)) > 0)
);

CREATE INDEX decision_revision_version ON lms.decision_revision (organizacion_id, version_curso_id, decidida_en);

CREATE FUNCTION lms.decision_revision_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lms.decision_revision solo admite INSERT';
END;
$$;

CREATE TRIGGER decision_revision_inmutable
    BEFORE UPDATE OR DELETE ON lms.decision_revision
    FOR EACH ROW EXECUTE FUNCTION lms.decision_revision_inmutable();
