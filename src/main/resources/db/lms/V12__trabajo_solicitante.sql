-- límites de la cola (ADR-0022, hallazgo M2 de #216). solicitante es quien pidió el trabajo: con él se cuenta el tope de
-- trabajos activos por persona. la clave deja de valer para siempre y vale mientras el trabajo está pendiente o en curso:
-- pedir lo mismo mientras tanto devuelve el mismo trabajo, y pedirlo después de terminado hace otro
ALTER TABLE lms.trabajo ADD COLUMN solicitante uuid;

ALTER TABLE lms.trabajo DROP CONSTRAINT trabajo_organizacion_id_tipo_clave_key;

CREATE UNIQUE INDEX trabajo_clave_activa ON lms.trabajo (organizacion_id, tipo, clave) WHERE estado IN ('QUEUED', 'RUNNING');

CREATE INDEX trabajo_solicitante_activo ON lms.trabajo (organizacion_id, solicitante) WHERE estado IN ('QUEUED', 'RUNNING');
