# ADR-0007: Carga académica y créditos como política versionada que propone

**Estado**: aceptada · 2026-10-27

## Contexto

- La especificación pide un modelo de créditos explícito, configurable por programa, institución y jurisdicción, sin
  equivalencia universal; distinguir horas de contacto teóricas, prácticas/laboratorio, otras supervisadas, estudio
  autónomo, semanas y carga total; y distinguir créditos **calculados, propuestos y aprobados**. No se asignan créditos
  oficiales sin validar la regla ni se mezclan ECTS con SCH.
- Fuentes normativas verificadas ([`reference-repositories.md` §7](../research/reference-repositories.md#7-créditos-académicos-fuentes-normativas)):
  - **Estados Unidos**, [34 CFR 600.2](https://www.ecfr.gov/current/title-34/subtitle-B/chapter-VI/part-600) (*credit
    hour*): no menos de una hora de instrucción directa y un mínimo de dos horas de trabajo fuera de clase por semana
    durante unas quince semanas para un crédito semestral, o una cantidad equivalente de trabajo en otras actividades,
    según la institución y su acreditadora.
  - **Europa**, ECTS (ECTS Users' Guide, [EHEA](https://ehea.info/)): 60 créditos por año a tiempo completo, de 1500 a
    1800 horas; un crédito equivale a 25–30 horas de carga total del estudiante.
- Ejemplo de la especificación: 6 semanas × (12 h de teoría + 12 h de laboratorio + 12 h de trabajo autónomo) por
  semana = 216 h de carga total.

## Decisión

**La carga es un dato del curso; el crédito es el resultado de aplicar una política versionada a esa carga.**

- `workload` es dominio puro (sin Spring ni base de datos): horas por semana por tipo (teoría, práctica/laboratorio,
  otras supervisadas, autónomo) × semanas → horas de contacto, autónomas y carga total.
- Una **política de créditos** tiene sistema (`ECTS`, `US_SCH`), método, parámetros (p. ej. horas por crédito, qué tipos
  de hora cuentan como instrucción directa, redondeo), fuente normativa y versión. Una política se publica y no se edita:
  un cambio es una versión nueva.
- Aplicar una política produce una **propuesta**: `CreditProposal(sistema, método, parámetros, fuente, versión, valor)`.
  Con ECTS a 25–30 h por crédito, las 216 h del ejemplo dan una propuesta de entre 7,2 y 8,64 créditos; cuál se usa y
  cómo se redondea son parámetros de la política, no constantes del código.
- Tres estados, nunca confundidos: **calculado** (la aritmética), **propuesto** (calculado con una política concreta y
  mostrado como «créditos propuestos, no oficiales»), **aprobado** (una persona autorizada lo aprueba en un acto
  registrado y auditado). El código nunca marca un crédito como aprobado. Aprobar es F6.
- **Sin conversión** ECTS↔SCH: cada sistema se calcula desde la carga con su propia política; la interfaz nunca muestra
  uno como equivalente del otro.

## Alternativas consideradas

- **Un campo `creditos` libre en el curso.** Rechazado: no dice método, fuente ni versión, y no distingue propuesta de
  aprobación.
- **Fórmula fija en el código.** Rechazado: la especificación exige configurar por institución y jurisdicción; y la
  propia norma estadounidense deja la equivalencia a la institución.
- **Tabla de conversión ECTS↔SCH.** Rechazado: no existe una equivalencia oficial universal; mostrarla parecería un
  reconocimiento.

## Consecuencias

- Cada número de créditos mostrado se puede explicar: qué carga, qué política, qué versión, qué fuente.
- Cambiar una política no cambia en silencio lo ya propuesto o aprobado: queda ligado a su versión.
- La ficha pública muestra créditos propuestos con su etiqueta; un visitante no ve un crédito como oficial.
- Coste: más modelo (políticas versionadas, propuestas, aprobaciones) que un número en un campo.
- F1 implementa el cálculo y la propuesta con políticas de referencia en código y probadas (incluido el caso de 216 h);
  persistir políticas configurables por institución y el flujo de aprobación es F6.
- Abierto: cómo cuenta el laboratorio en SCH (instrucción directa o «trabajo equivalente») lo decide cada institución;
  la política de referencia lo deja como parámetro explícito.
