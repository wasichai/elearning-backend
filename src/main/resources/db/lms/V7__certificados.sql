-- el certificado congelado al emitirse (ADR-0016 §7, RF-EVA-08): datos es la instantánea de lo certificado y no se recalcula.
-- un disparador impide cambiarlo o borrarlo. el código es aleatorio y se puede publicar: verifica sin sesión
CREATE TABLE lms.certificado (
    codigo          text        PRIMARY KEY,
    organizacion_id uuid        NOT NULL,
    matricula_id    uuid        NOT NULL UNIQUE,
    estudiante_id   uuid        NOT NULL,
    emitido_en      timestamptz NOT NULL,
    datos           jsonb       NOT NULL
);

CREATE FUNCTION lms.certificado_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lms.certificado solo admite INSERT';
END;
$$;

CREATE TRIGGER certificado_inmutable
    BEFORE UPDATE OR DELETE ON lms.certificado
    FOR EACH ROW EXECUTE FUNCTION lms.certificado_inmutable();
