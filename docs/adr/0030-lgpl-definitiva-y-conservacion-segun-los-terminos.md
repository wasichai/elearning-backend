# ADR-0030: LGPL definitiva y conservación de datos según los términos de uso

**Estado**: aceptada · 2026-11-03 · reemplaza a [ADR-0015](0015-licencia-lgpl-provisional.md)

## Contexto

ADR-0015 adoptó la LGPL-3.0-or-later como licencia **provisional**, «actualizable después», y dejó RNF-OSS-07 abierto
hasta una decisión registrada del titular. La privacidad (`docs/security/README.md` §7) dejaba para F6 los plazos de
conservación por tipo de dato; ADR-0020 ya fijó que los eventos de aprendizaje se guardan mientras el estudiante acepte
los términos. El 2026-11-03 la dirección decidió las dos cosas (#303).

## Decisión

1. **La licencia es definitiva: LGPL-3.0-or-later** (SPDX `LGPL-3.0-or-later`) para el código, la documentación y los
   materiales propios de `elearning-backend` y `elearning-frontend`. `LICENSE` (LGPL-3.0) y `COPYING` (GPL-3.0) no
   cambian. Cambiarla en el futuro exige un ADR que reemplace este y, cuando haya contribuciones externas, el acuerdo de sus
   autores o unos términos de contribución que lo prevean. Wasichai y los manuales siguen fuera: no tienen licencia y no
   se copian.
2. **Los datos personales se conservan el tiempo que el usuario aprueba en los términos de uso.** La aplicación no fija
   plazos propios por tipo de dato: el plazo es el que ofrece la versión de los términos que la persona aceptó. Al
   retirar la aceptación, o al vencer el plazo aprobado, se suprimen sus datos personales; lo que deba conservarse
   (calificaciones emitidas, certificados, la auditoría de Wasichai) se disocia o se documenta por qué queda. Generaliza
   la regla de ADR-0020 para los eventos de aprendizaje.
3. Los límites técnicos que ya existen siguen: la cola borra sus trabajos terminados pasado `lms.jobs.retention` (ADR-0022),
   que es **más corto** que cualquier plazo aceptado; la purga de `audit_log` sigue siendo del rol de purga de Wasichai.

## Alternativas

- **Mantener la LGPL como provisional**: deja RNF-OSS-07 abierto sin un motivo concreto. Descartada.
- **AGPL o Apache-2.0**: evaluadas en ADR-0009; la dirección se queda con la LGPL.
- **Plazos fijos por tipo de dato** (cuentas inactivas, intentos, registros de IA): obligan a la aplicación a sostener
  plazos legales que dependen de cada organización y jurisdicción. Atarlos a los términos deja la decisión a quien
  despliega, explícita y aceptada por la persona.

## Consecuencias

- RNF-OSS-07 queda implementado: `LICENSE` fusionado y decisión registrada aquí.
- RNF-SEG-11 necesita la aceptación de términos con versión y plazo, y la supresión en la cola: #304. Hasta entonces,
  ningún dato personal se borra por plazo salvo los trabajos de la cola.
- Las nuevas capacidades que traten datos personales (valoraciones #268, IA de F5) heredan esta regla y la prueban con la
  supresión de un usuario de demostración.
- Sigue pendiente lo que ADR-0015 dejaba: la licencia de Wasichai (bloquea distribuir binarios que la incluyan), términos de
  contribución antes de aceptar la primera externa y, si se quiere, una licencia de contenido para los materiales de curso.
