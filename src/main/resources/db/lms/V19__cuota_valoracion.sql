-- los límites diarios de las valoraciones (ADR-0038, RNF-SEG-10): cuántos cambios (alta, edición o retirada) y cuántas
-- denuncias lleva una persona en un día UTC. se suma en la misma transacción que el cambio, así que lo rechazado no
-- cuenta. es un dato personal: la supresión de la persona la borra, y el trabajo de agregados borra los días pasados
CREATE TABLE lms.cuota_valoracion (
    organizacion_id uuid    NOT NULL,
    usuario_id      uuid    NOT NULL,
    dia             date    NOT NULL,
    accion          text    NOT NULL CHECK (accion IN ('CAMBIO', 'DENUNCIA')),
    usos            integer NOT NULL CHECK (usos > 0),
    PRIMARY KEY (organizacion_id, usuario_id, dia, accion)
);

CREATE INDEX cuota_valoracion_dia ON lms.cuota_valoracion (dia);
