-- términos de uso y supresión de datos personales (ADR-0030, ADR-0037, RNF-SEG-11). una versión de los términos es
-- inmutable: publicar otra no toca las aceptaciones de la anterior. cada persona tiene a lo sumo una aceptación, con el
-- plazo que aprobó ya calculado (vence_en); retirarla o que venza encola su supresión, que al final la borra
CREATE TABLE lms.terminos_uso (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id uuid        NOT NULL,
    version         integer     NOT NULL CHECK (version > 0),
    texto           text        NOT NULL CHECK (length(btrim(texto)) > 0),
    -- el plazo de conservación que ofrece esta versión
    plazo_dias      integer     NOT NULL CHECK (plazo_dias BETWEEN 1 AND 36500),
    publicada_en    timestamptz NOT NULL,
    UNIQUE (organizacion_id, version)
);

CREATE FUNCTION lms.terminos_uso_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lms.terminos_uso solo admite INSERT';
END;
$$;

CREATE TRIGGER terminos_uso_inmutable
    BEFORE UPDATE OR DELETE ON lms.terminos_uso
    FOR EACH ROW EXECUTE FUNCTION lms.terminos_uso_inmutable();

-- sin datos que no hagan falta: quién, qué versión, cuándo, hasta cuándo y si la retiró
CREATE TABLE lms.aceptacion_terminos (
    organizacion_id uuid        NOT NULL,
    usuario_id      uuid        NOT NULL,
    terminos_id     uuid        NOT NULL REFERENCES lms.terminos_uso (id),
    aceptada_en     timestamptz NOT NULL,
    vence_en        timestamptz NOT NULL,
    retirada_en     timestamptz,
    PRIMARY KEY (organizacion_id, usuario_id)
);

-- el barrido busca las vencidas de todas las organizaciones
CREATE INDEX aceptacion_terminos_vence ON lms.aceptacion_terminos (vence_en);

-- la constancia de cada supresión: cuántos registros de cada clase se suprimieron y cuántos quedaron disociados, sin
-- datos personales (ni la persona ni su seudónimo). una por trabajo: repetirlo no escribe otra
CREATE TABLE lms.supresion (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organizacion_id uuid        NOT NULL,
    trabajo_id      uuid        NOT NULL UNIQUE,
    motivo          text        NOT NULL CHECK (motivo IN ('WITHDRAWN', 'EXPIRED')),
    suprimidos      jsonb       NOT NULL,
    disociados      jsonb       NOT NULL,
    ejecutada_en    timestamptz NOT NULL
);

-- lo que solo se añade sigue protegido contra cambios y borrados, salvo dentro de la transacción de una supresión, que
-- lo declara con set_config('lms.supresion', 'on', true). protege de errores, no de quien ya puede escribir SQL
CREATE OR REPLACE FUNCTION lms.evento_aprendizaje_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('lms.supresion', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'lms.evento_aprendizaje solo admite INSERT';
END;
$$;

CREATE OR REPLACE FUNCTION lms.intento_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF current_setting('lms.supresion', true) = 'on' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'lms.intento no se borra';
    END IF;
    IF NEW.instantanea IS DISTINCT FROM OLD.instantanea THEN
        RAISE EXCEPTION 'la instantánea de un intento no cambia';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION lms.calificacion_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('lms.supresion', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'lms.calificacion solo admite INSERT';
END;
$$;

-- un certificado no se borra nunca: la supresión solo lo disocia (titular y nombre)
CREATE OR REPLACE FUNCTION lms.certificado_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND current_setting('lms.supresion', true) = 'on'
        AND NEW.codigo = OLD.codigo AND NEW.organizacion_id = OLD.organizacion_id AND NEW.matricula_id = OLD.matricula_id
        AND NEW.emitido_en = OLD.emitido_en AND NEW.datos = jsonb_set(OLD.datos, '{holderName}', 'null') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'lms.certificado solo admite INSERT';
END;
$$;
