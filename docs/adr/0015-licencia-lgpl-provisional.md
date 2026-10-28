# ADR-0015: Licencia provisional LGPL-3.0-or-later

**Estado**: aceptada · 2026-10-28 · reemplaza a [ADR-0009](0009-licencia-diferida.md)

## Contexto

ADR-0009 difería la licencia hasta que la dirección del proyecto decidiera. El 2026-10-28 decidió: **LGPL de momento,
actualizable después**. Los hechos de [`docs/legal/licensing.md`](../legal/licensing.md) siguen en pie: Wasichai y los
manuales SRTM y SGSPE no tienen licencia.

## Decisión

1. El código de `elearning-backend` y `elearning-frontend` se publica bajo la **GNU Lesser General Public License,
   versión 3 o posterior** (SPDX `LGPL-3.0-or-later`). `LICENSE` lleva el texto de la LGPL-3.0 y `COPYING` el de la
   GPL-3.0, que la LGPL incorpora por referencia; `package.json` declara la licencia.
2. «O posterior» y el carácter provisional permiten **actualizarla** (otra versión o licencia) con un ADR que reemplace
   este. Mientras el proyecto tenga un único titular, cambiarla no requiere el permiso de nadie más; cuando haya
   contribuciones externas, sí, salvo que los términos de contribución lo prevean (se fijan antes de aceptar la primera).
3. Cubre lo escrito en estos repositorios: el código, la documentación y los materiales propios de la demo (lectura,
   guía PDF y vídeo sintéticos). **No** cubre Wasichai ni los manuales: siguen sin licencia y no se copian.

## Consecuencias

- Los repositorios pasan a ser open source para su propio contenido: se pueden estudiar, modificar y redistribuir con
  las condiciones de la LGPL.
- **Sigue bloqueado** distribuir binarios o imágenes que incluyan Wasichai (el jar del backend, la build del frontend con
  `@wasichai/*`): la LGPL de este proyecto no concede derechos sobre Wasichai. Hace falta que sus titulares le den una
  licencia, compatible con la LGPL.
- La LGPL permite que se enlace como librería desde software con otras licencias; quien modifique este código y lo
  distribuya publica sus cambios bajo la misma licencia. Ofrecerlo como servicio en red no obliga a publicar cambios
  (eso lo haría la AGPL, considerada en ADR-0009).
- Pendiente: términos de contribución (DCO o CLA) y, si se quiere, una licencia de contenido aparte para los materiales
  de curso (por ejemplo CC BY 4.0).
