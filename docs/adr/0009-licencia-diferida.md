# ADR-0009: La licencia del proyecto se difiere

**Estado**: reemplazada por [ADR-0015](0015-licencia-lgpl-provisional.md) · 2026-10-27

## Contexto

- El proyecto pretende ser open source, y la especificación pide investigar licencias y **no atribuir una licencia
  sin autorización**.
- **Ningún repositorio de Wasichai tiene licencia** (verificado: `wasichai` y `wasichai-ui` sin archivo `LICENSE`,
  `GET /license` 404, POM y `package.json` sin licencia; `sgspe`/`sgspe-ui` sin licencia, `slides/package.json`
  `"UNLICENSED"`; `srtm-backend` sin licencia del proyecto). Embabel Agent es Apache-2.0
  ([`reference-repositories.md` §6](../research/reference-repositories.md#6-licencias)).
- Sin licencia rige el _copyright_ por defecto: nadie más puede copiar, modificar ni redistribuir. Eso afecta a
  depender de las librerías de Wasichai y a reutilizar el texto de los manuales SRTM y SGSPE.
- Las licencias de Wasichai y de los manuales las gobierna la organización `wasichai` de GitHub, de la que el usuario
  es administrador.

## Decisión

**No se añade archivo `LICENSE` ni campo de licencia hasta que el usuario lo decida.** Mientras tanto:

- `README.md` dice que la licencia está pendiente.
- La semilla de demostración no copia texto de los manuales: usa títulos, resúmenes propios y referencias
  `repo@sha:ruta#ancla` a los originales.
- No se vendoriza código de Wasichai ([ADR-0002](0002-wasichai-desde-github-packages.md)).

Opciones para este proyecto, a decidir:

| Licencia   | A favor                                                                                 | En contra                                             |
| ---------- | --------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| Apache-2.0 | permisiva, con concesión explícita de patentes; la de Embabel                           | permite derivados cerrados                            |
| AGPL-3.0   | obliga a publicar cambios también si se ofrece como servicio en red (el caso de un LMS) | ahuyenta a integradores; compatibilidad con Wasichai  |
| MIT        | la más simple y permisiva                                                               | sin concesión de patentes; permite derivados cerrados |

El análisis completo está en [`docs/legal/licensing.md`](../legal/licensing.md).

## Alternativas consideradas

- **Elegir ahora una licencia para este proyecto.** Rechazado: la especificación lo prohíbe sin autorización, y el
  proyecto no puede ofrecer más derechos de los que Wasichai concede sobre lo que distribuye.
- **Publicar sin licencia y decidir después.** Es lo que pasa de hecho mientras dure este ADR; se acepta solo como
  estado transitorio.

## Consecuencias

- El repositorio puede ser público y legible, pero **no es open source** mientras no tenga licencia: un tercero no
  puede legalmente reutilizarlo ni contribuir con garantías claras.
- `CONTRIBUTING.md` no puede fijar todavía los términos de las contribuciones (DCO o CLA).
- Lo que necesita decidirse, en este orden, para una publicación open source real:
  1. la licencia de `wasichai` y `wasichai-ui` (librerías de las que depende el proyecto);
  2. la licencia de los manuales SRTM y SGSPE (texto y capturas), si se van a reutilizar en cursos;
  3. la licencia de este proyecto, compatible con las dos anteriores;
  4. los términos de contribución.
- Las tres primeras decisiones dependen de la organización `wasichai`; el usuario puede tomarlas como administrador.
- Al decidirse, un ADR nuevo reemplaza este.
