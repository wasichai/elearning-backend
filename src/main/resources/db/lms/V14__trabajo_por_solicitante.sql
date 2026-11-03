-- las importaciones de una persona, la más reciente primero (E30.2): también las terminadas, que la retención borra
CREATE INDEX trabajo_por_solicitante ON lms.trabajo (organizacion_id, solicitante, tipo, creado_en DESC);
