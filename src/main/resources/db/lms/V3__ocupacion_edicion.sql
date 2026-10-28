-- cupo ocupado por edición (ADR-0013). el comando de matrícula bloquea la fila con SELECT … FOR UPDATE en la misma
-- transacción que crea la matrícula con RecordService y suma uno: dos altas simultáneas no superan el cupo, y si la
-- matrícula falla el contador vuelve atrás con ella. es dato propio: no cuenta lo que se cree como la plataforma
CREATE TABLE lms.ocupacion_edicion (
    organizacion_id  uuid    NOT NULL,
    edicion_id       uuid    NOT NULL,
    matriculados     integer NOT NULL DEFAULT 0 CHECK (matriculados >= 0),
    PRIMARY KEY (organizacion_id, edicion_id)
);
