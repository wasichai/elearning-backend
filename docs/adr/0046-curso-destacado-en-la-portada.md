# ADR-0046: Los cursos del banner de la portada pública los marca la institución con `curso_destacado`

**Estado**: aceptada · 2026-11-14 · [#405](https://github.com/wasichai/elearning-backend/issues/405), épica
[#406](https://github.com/wasichai/elearning-backend/issues/406)

## Contexto

- La interfaz tendrá una portada pública en `/`, sin login. Su banner presenta un programa destacado, y debajo van
  cifras y filas del catálogo. `/` se resuelve en Wasichai con `publicHome` de `@wasichai/core`
  ([wasichai-ui#184](https://github.com/wasichai/wasichai-ui/issues/184)).
- El modelo no tiene ninguna noción de «destacado». Elegir «el próximo en abrir» no deja a la institución decidir qué
  se promociona.
- `CREADOR` tiene `UPDATE` sobre `curso` (`model/roles.json`). Un campo `destacado` en `curso` dejaría a cualquier autor
  destacarse a sí mismo.
- La ruta anónima solo lee la proyección `lms.catalogo_publicado` ([ADR-0011](0011-catalogo-publico-leido-de-una-proyeccion.md)),
  y nada que no esté publicado entra en ella ([ADR-0006](0006-catalogo-publico-con-cadena-de-seguridad-acotada.md)).
- Las cifras de la portada se limitan a lo que ya es público: el número de cursos (`totalElements`) y de áreas
  (`facets.categories`). Ni estudiantes, ni docentes, ni valoraciones
  ([ADR-0023](0023-cupo-agotado-en-la-proyeccion-del-catalogo.md)).

## Decisión

1. **Un Custom Object propio, `curso_destacado`**, con el curso (`MANY_TO_ONE`, obligatorio) y un `orden` entero. Quitar
   la marca es borrar el registro. Si un curso tiene varias marcas, cuenta la de menor orden.
2. **Solo lo escribe ADMIN.** `model/roles.json` no da a ningún rol del LMS ningún permiso sobre él, ni siquiera `READ`.
   Wasichai lo hace cumplir por objeto, sin comparar nombres de rol, y `test_model.py` lo vigila.
3. **La proyección lo copia** a la columna `lms.catalogo_publicado.destacado_orden` (`V32`). Como el resto de la fila,
   solo existe para un curso con versión publicada: un borrador destacado no sale. El orden entra en el hash de la fila
   para que un cambio de orden también se proyecte.
4. **El contrato público gana `featured`**, de forma aditiva:
   - `featured` (booleano) en el resumen y en la ficha;
   - el filtro `featured=true|false` en `GET /api/lms/public/catalog/courses`. Con `true`, los resultados van por
     `destacado_orden` y luego por `sort`.

   El banner pide `?featured=true&size=1`.

5. **La cadena de seguridad no cambia.** La ruta ya está bajo `/api/lms/public/**`, y el filtro solo deja ver si un
   curso publicado está destacado, algo que la portada muestra de todos modos.

## Alternativas descartadas

- **Un campo `destacado` en `curso`:** lo editaría cualquier `CREADOR`. Protegerlo con un `RecordWriteGuard` por campo
  sería una regla escondida, cuando un objeto aparte expresa lo mismo con permisos.
- **Destacar «el próximo en abrir»:** no hay que decidir nada, pero la institución no elige qué se promociona.
- **Una fecha de vigencia en la marca:** lo que depende del día se calcula al leer (`OfferingCalendar`), lo que suma una
  regla más. Hoy basta con quitar la marca. Si aparece un requisito real, se agrega.
- **Darle la marca a `AUTORIDAD_ACADEMICA`:** ese rol actúa por el órgano que reconoce créditos, no por la comunicación
  de la institución. Cuando haga falta delegarlo, será otro rol con su propio ADR.

## Consecuencias

- La portada tiene un banner que la institución controla, sin nuevas rutas públicas ni datos personales.
- Destacar un curso aún no tiene pantalla en el LMS: se hace en la administración de Wasichai (registros de
  `curso_destacado`), como ADMIN.
- Una marca tarda hasta un intervalo de refresco de la proyección (`lms.catalog.refresh`) en llegar a la portada.
