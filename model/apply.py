#!/usr/bin/env python3
"""Aplica el modelo LMS (model/model.json) y los roles (model/roles.json) a un Wasichai por su API REST.

Escrito para este proyecto, solo con la biblioteca estándar. Idempotente: compara lo que el servidor ya tiene con lo que
declara el modelo y solo envía la diferencia, así que una segunda ejecución no cambia nada. Nunca borra, renombra ni
cambia el tipo de un campo, ni quita opciones de un ENUM: eso es una migración de datos y se decide a mano.

Fases (cada una necesita la anterior):
  1. objetos con sus campos propios (sin relaciones) y opciones de ENUM nuevas;
  2. acciones declaradas por objeto (ADR-042 de Wasichai);
  3. relaciones (crean los campos RELATION), su obligatoriedad y las restricciones/índices que las nombran;
  4. roles y sus permisos (el PUT de permisos reemplaza el conjunto completo del rol);
  5. con --demo, los usuarios y registros sintéticos de model/demo/data.json (ver model/demo/README.md): cada uno se
     busca por su clave natural y solo se crea lo que falta; lo que ya existe no se modifica.

Uso:
  python3 model/apply.py --validate-only [--demo]
  python3 model/apply.py --url http://localhost:8090 [--email … --password …] [--demo]
Credenciales también por WASICHAI_URL, WASICHAI_EMAIL, WASICHAI_PASSWORD; la contraseña de los usuarios de demostración,
solo por WASICHAI_DEMO_PASSWORD. Nunca se escriben en disco.
"""

from __future__ import annotations

import argparse
from decimal import Decimal, InvalidOperation
import datetime
import json
import mimetypes
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
NAME = re.compile(r"^[a-z][a-z0-9_]{0,62}$")
ACTION = re.compile(r"^[A-Z][A-Z0-9_]{1,48}$")
ROLE = re.compile(r"^[A-Z][A-Z0-9_]{1,62}$")
FIELD_TYPES = {"TEXT", "LONG_TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME", "ENUM", "EMAIL", "URL", "UUID", "FILE", "IMAGE"}
RELATIONSHIP_TYPES = {"MANY_TO_ONE", "ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_MANY"}
RECORD_ACTIONS = {"READ", "CREATE", "UPDATE", "DELETE"}
BUILT_IN_ACTIONS = RECORD_ACTIONS | {"MANAGE_METADATA", "MANAGE_ORGANIZATION", "MANAGE_TENANTS"}
# los que la plataforma se reserva (GET /api/metadata/system-fields)
RESERVED_FIELDS = {"id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "workflow_state", "version", "count", "after"}
FIELD_KEYS = ("name", "label", "type", "required", "unique", "enumOptions", "defaultValue", "indexed", "timeZone", "maxBytes", "contentTypes")
DEMO_DOMAIN = "demo.wasichai-learning.test"
DEMO_EMAIL = re.compile(r"^[a-z0-9][a-z0-9._-]*@" + re.escape(DEMO_DOMAIN) + "$")
ALIAS = re.compile(r"^[a-z0-9][a-z0-9-]{0,79}$")
# una fecha relativa al día en que se aplica la demo ("hoy", "hoy+12", "hoy-2"): las ediciones no envejecen (#96)
RELATIVE_DATE = re.compile(r"^hoy(?:([+-]\d{1,4}))?$")
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class ModelError(Exception):
    """El modelo no es válido. Lleva todos los problemas encontrados, no solo el primero."""

    def __init__(self, problems: list[str]):
        super().__init__("\n".join(problems))
        self.problems = problems


class DemoError(Exception):
    """Los datos de demostración chocan con lo que ya hay en el servidor (por ejemplo, una clave natural repetida)."""


class ApiError(Exception):
    def __init__(self, status: int, method: str, path: str, body: str):
        detail = body
        try:
            problem = json.loads(body)
        except ValueError:
            problem = None
        if isinstance(problem, dict):
            detail = str(problem.get("detail") or body)
            errors = problem.get("errors") if isinstance(problem.get("errors"), list) else []
            if errors:
                detail += " — " + "; ".join(f"{e.get('field')}: {e.get('message')}" if isinstance(e, dict) else str(e) for e in errors)
        super().__init__(f"{method} {path} → {status}: {detail}")
        self.status = status


# ---------------------------------------------------------------------------------------------------------------------
# validación, sin red
# ---------------------------------------------------------------------------------------------------------------------


def validate(model: dict, roles: dict | None = None) -> None:
    problems: list[str] = []
    enums = model.get("enums", {})
    for enum, options in enums.items():
        if not NAME.match(enum):
            problems.append(f"enum '{enum}': nombre inválido")
        if not options or any(not isinstance(o, str) or not o.strip() for o in options) or len(set(options)) != len(options):
            problems.append(f"enum '{enum}': necesita opciones no vacías y sin repetir")

    objects: dict[str, dict] = {}
    for obj in model.get("objects", []):
        name = obj.get("name", "")
        if not NAME.match(name):
            problems.append(f"objeto '{name}': nombre inválido")
        if name in objects:
            problems.append(f"objeto '{name}': repetido")
        if not obj.get("label"):
            problems.append(f"objeto '{name}': falta label")
        objects[name] = obj
        fields: set[str] = set()
        for f in obj.get("fields", []):
            fname = f.get("name", "")
            where = f"{name}.{fname}"
            if not NAME.match(fname):
                problems.append(f"campo '{where}': nombre inválido")
            if fname in RESERVED_FIELDS:
                problems.append(f"campo '{where}': nombre reservado por la plataforma")
            if fname in fields:
                problems.append(f"campo '{where}': repetido")
            fields.add(fname)
            ftype = f.get("type")
            if ftype == "RELATION":
                problems.append(f"campo '{where}': un RELATION se declara en relationships, no como campo")
            elif ftype not in FIELD_TYPES:
                problems.append(f"campo '{where}': tipo desconocido {ftype!r}")
            if ftype == "ENUM":
                ref = f.get("enum")
                if ref not in enums:
                    problems.append(f"campo '{where}': enum desconocido {ref!r}")
                elif f.get("defaultValue") is not None and f["defaultValue"] not in enums[ref]:
                    problems.append(f"campo '{where}': el valor por defecto no es una opción de '{ref}'")
            elif "enum" in f:
                problems.append(f"campo '{where}': solo un ENUM lleva 'enum'")
            if f.get("timeZone") and ftype != "DATETIME":
                problems.append(f"campo '{where}': solo un DATETIME lleva timeZone")
            if ("maxBytes" in f or "contentTypes" in f) and ftype not in ("FILE", "IMAGE"):
                problems.append(f"campo '{where}': maxBytes y contentTypes son de FILE o IMAGE")
            if "maxBytes" in f and (not isinstance(f["maxBytes"], int) or f["maxBytes"] <= 0):
                problems.append(f"campo '{where}': maxBytes debe ser un entero positivo")
            if f.get("indexed") and ftype == "LONG_TEXT":
                problems.append(f"campo '{where}': un LONG_TEXT no se indexa")
        actions: set[str] = set()
        for a in obj.get("actions", []):
            aname = a.get("name", "")
            if not ACTION.match(aname) or aname in BUILT_IN_ACTIONS:
                problems.append(f"acción '{name}.{aname}': nombre inválido o reservado")
            if aname in actions:
                problems.append(f"acción '{name}.{aname}': repetida")
            actions.add(aname)

    relationship_fields: dict[str, set[str]] = {n: set() for n in objects}
    for rel in model.get("relationships", []):
        rname = rel.get("name", "")
        if not NAME.match(rname):
            problems.append(f"relación '{rname}': nombre inválido")
        if rel.get("type") not in RELATIONSHIP_TYPES:
            problems.append(f"relación '{rname}': tipo desconocido {rel.get('type')!r}")
        for end in ("source", "target"):
            if rel.get(end) not in objects:
                problems.append(f"relación '{rname}': {end} desconocido {rel.get(end)!r}")
        if rel.get("type") != "MANY_TO_MANY":
            fname = rel.get("fieldName", "")
            owner = rel.get("target") if rel.get("type") == "ONE_TO_MANY" else rel.get("source")
            if not NAME.match(fname) or fname in RESERVED_FIELDS:
                problems.append(f"relación '{rname}': fieldName inválido")
            elif owner in objects:
                if any(f.get("name") == fname for f in objects[owner].get("fields", [])) or fname in relationship_fields[owner]:
                    problems.append(f"relación '{rname}': el campo '{owner}.{fname}' ya existe")
                relationship_fields[owner].add(fname)
        elif rel.get("required"):
            problems.append(f"relación '{rname}': un MANY_TO_MANY no puede ser obligatorio")

    for obj in model.get("objects", []):
        known = {f.get("name", "") for f in obj.get("fields", [])} | relationship_fields.get(obj.get("name", ""), set())
        long_text = {f.get("name", "") for f in obj.get("fields", []) if f.get("type") == "LONG_TEXT"}
        for key, minimum in (("indexes", 1), ("uniqueConstraints", 2)):
            for entry in obj.get(key, []):
                if len(entry) < minimum or len(set(entry)) != len(entry):
                    problems.append(f"{key} de '{obj.get('name')}': entrada {entry} inválida")
                for fname in entry:
                    if fname not in known:
                        problems.append(f"{key} de '{obj.get('name')}': campo desconocido '{fname}'")
                    elif fname in long_text:
                        problems.append(f"{key} de '{obj.get('name')}': '{fname}' es LONG_TEXT")

    if roles is not None:
        seen: set[str] = set()
        for role in roles.get("roles", []):
            rname = role.get("name", "")
            if not ROLE.match(rname) or rname == "ADMIN":
                problems.append(f"rol '{rname}': nombre inválido (ADMIN es de la plataforma)")
            if rname in seen:
                problems.append(f"rol '{rname}': repetido")
            seen.add(rname)
            for grant in role.get("permissions", []):
                obj = grant.get("object")
                if obj not in objects:
                    problems.append(f"rol '{rname}': objeto desconocido {obj!r}")
                    continue
                declared = {a.get("name") for a in objects[obj].get("actions", [])}
                for action in grant.get("actions", []):
                    if action not in RECORD_ACTIONS | declared:
                        problems.append(f"rol '{rname}': acción '{action}' no existe en '{obj}'")
            for fp in role.get("fieldPermissions", []):
                obj, fname = fp.get("object"), fp.get("field")
                if obj not in objects:
                    problems.append(f"rol '{rname}': permiso de campo sobre objeto desconocido {obj!r}")
                    continue
                known = {f.get("name") for f in objects[obj].get("fields", [])} | relationship_fields.get(obj, set())
                if fname not in known:
                    problems.append(f"rol '{rname}': permiso de campo sobre campo desconocido '{obj}.{fname}'")
                if not isinstance(fp.get("read"), bool) or not isinstance(fp.get("write"), bool):
                    problems.append(f"rol '{rname}': '{obj}.{fname}' necesita read y write booleanos")
    if problems:
        raise ModelError(problems)


def _relation_fields(model: dict) -> dict[str, dict[str, dict]]:
    """Por objeto, sus campos RELATION (fieldName → relación)."""
    found: dict[str, dict[str, dict]] = {}
    for rel in model.get("relationships", []):
        if rel.get("type") != "MANY_TO_MANY":
            found.setdefault(_relationship_owner(rel), {})[rel["fieldName"]] = rel
    return found


def _natural_keys(obj: dict) -> list[set[str]]:
    """Las combinaciones de campos que el modelo declara únicas: un campo unique o una uniqueConstraint."""
    return [{f["name"]} for f in obj.get("fields", []) if f.get("unique")] + [set(c) for c in obj.get("uniqueConstraints", [])]


def _value_problem(f: dict, value: object, enums: dict) -> str | None:
    ftype = f.get("type")
    if ftype in ("TEXT", "LONG_TEXT", "EMAIL", "URL"):
        if isinstance(value, str) and RELATIVE_DATE.match(value):
            return "una fecha relativa (hoy±N) solo va en un campo DATE"
        return None if isinstance(value, str) and value.strip() else "se esperaba un texto no vacío"
    if ftype == "INTEGER":
        return None if isinstance(value, int) and not isinstance(value, bool) else "se esperaba un entero"
    if ftype == "DECIMAL":
        return None if isinstance(value, (int, float)) and not isinstance(value, bool) else "se esperaba un número"
    if ftype == "BOOLEAN":
        return None if isinstance(value, bool) else "se esperaba true o false"
    if ftype == "ENUM":
        return None if value in enums.get(f.get("enum"), []) else f"{value!r} no es una opción de '{f.get('enum')}'"
    if ftype == "UUID":
        return None if isinstance(value, str) and UUID.match(value) else "se esperaba un UUID"
    if ftype == "DATE" and isinstance(value, str) and RELATIVE_DATE.match(value):
        return None
    if isinstance(value, str) and RELATIVE_DATE.match(value):
        return "una fecha relativa (hoy±N) solo va en un campo DATE"
    if ftype in ("DATE", "DATETIME"):
        try:
            if ftype == "DATE":
                datetime.date.fromisoformat(value)
            elif datetime.datetime.fromisoformat(value).tzinfo is None:
                return "un DATETIME lleva zona horaria (por ejemplo, Z)"
        except (TypeError, ValueError):
            return f"{value!r} no es un {ftype} ISO 8601"
        return None
    return f"el tipo {ftype} no se admite en los datos de demostración"


def validate_demo(data: dict, model: dict, roles: dict | None = None, base: Path = HERE / "demo") -> None:
    """Valida model/demo/data.json contra el modelo y los roles, sin red. Reúne todos los problemas, como validate()."""
    problems: list[str] = []
    enums = model.get("enums", {})
    objects = {o["name"]: o for o in model.get("objects", [])}
    relations = _relation_fields(model)
    known_roles = {"ADMIN"} | {r["name"] for r in (roles or {}).get("roles", [])}

    for key in data:
        if key not in ("users", "records", "workflows", "enrollments", "sessions") and not key.startswith("_"):
            problems.append(f"clave desconocida '{key}' en los datos de demostración")

    # workflows: el camino de estados que un registro recorre hasta su estado final, como lo haría una persona
    for obj_name, flow in (data.get("workflows") or {}).items():
        obj = objects.get(obj_name)
        fields = {f.get("name"): f for f in (obj or {}).get("fields", [])}
        state = fields.get(flow.get("field"))
        if obj is None or state is None or state.get("type") != "ENUM":
            problems.append(f"workflow de '{obj_name}': objeto o campo ENUM desconocido")
            continue
        path = flow.get("path") or []
        if not path or any(s not in enums.get(state["enum"], []) for s in path):
            problems.append(f"workflow de '{obj_name}': el camino {path} no son estados de '{state['enum']}'")
        if not isinstance(flow.get("inmediato", False), bool):
            problems.append(f"workflow de '{obj_name}': 'inmediato' es true o false")
        for deferred in flow.get("deferred", []):
            if deferred not in fields:
                problems.append(f"workflow de '{obj_name}': campo diferido desconocido '{deferred}'")
        # un paso que se da por un comando del LMS y no por la API de registros (la decisión de la bandeja, ADR-0021)
        for step, command in (flow.get("commands") or {}).items():
            if step not in path or not isinstance(command, dict) or not isinstance(command.get("body", {}), dict):
                problems.append(f"workflow de '{obj_name}': el comando de {step!r} no es un paso del camino con su cuerpo")
            elif not str(command.get("path", "")).startswith("/api/lms/") or "{id}" not in command["path"]:
                problems.append(f"workflow de '{obj_name}': el comando de {step!r} es una ruta /api/lms/ con {{id}}")
        for record in data.get("records", []):
            target = record.get("attributes", {}).get(flow["field"])
            if record.get("object") == obj_name and target is not None and target not in path:
                problems.append(f"registro '{record.get('ref')}': el estado {target!r} no está en el camino de '{obj_name}'")

    emails: set[str] = set()
    for user in data.get("users", []):
        email = user.get("email", "")
        if not DEMO_EMAIL.match(email):
            problems.append(f"usuario '{email}': el correo debe ser del dominio {DEMO_DOMAIN}, en minúsculas")
        if email in emails:
            problems.append(f"usuario '{email}': repetido")
        emails.add(email)
        if not str(user.get("displayName", "")).strip():
            problems.append(f"usuario '{email}': falta displayName")
        if "password" in user:
            problems.append(f"usuario '{email}': la contraseña no va en el archivo, sino en WASICHAI_DEMO_PASSWORD")
        if not user.get("roles"):
            problems.append(f"usuario '{email}': necesita al menos un rol")
        for role in user.get("roles", []):
            if role not in known_roles:
                problems.append(f"usuario '{email}': rol desconocido {role!r}")

    aliases: dict[str, str] = {}
    keys_seen: set[tuple] = set()
    for i, record in enumerate(data.get("records", [])):
        alias, oname = record.get("ref", ""), record.get("object", "")
        where = f"registro {alias or '#' + str(i)} ({oname})"
        if not ALIAS.match(alias):
            problems.append(f"{where}: 'ref' inválido (minúsculas, dígitos y guiones)")
        elif alias in aliases:
            problems.append(f"{where}: 'ref' repetido")
        for key in record:
            if key not in ("ref", "object", "key", "attributes", "refs", "users", "files"):
                problems.append(f"{where}: clave desconocida '{key}'")
        if oname not in objects:
            problems.append(f"{where}: objeto desconocido")
            continue
        fields = {f["name"]: f for f in objects[oname].get("fields", [])}
        rels = relations.get(oname, {})
        attributes, refs, users = record.get("attributes", {}), record.get("refs", {}), record.get("users", {})
        for fname, value in attributes.items():
            if fname in rels:
                problems.append(f"{where}: '{fname}' es una relación: va en 'refs'")
            elif fname not in fields:
                problems.append(f"{where}: campo desconocido '{fname}'")
            elif problem := _value_problem(fields[fname], value, enums):
                problems.append(f"{where}: campo '{fname}': {problem}")
        for fname, target in refs.items():
            if fname not in rels:
                problems.append(f"{where}: '{fname}' no es una relación de '{oname}'")
            elif target not in aliases:
                problems.append(f"{where}: '{fname}' apunta a '{target}', que no está antes en el archivo")
            elif aliases[target] != rels[fname]["target"]:
                problems.append(f"{where}: '{fname}' apunta a un {aliases[target]}, no a un {rels[fname]['target']}")
        for fname, email in users.items():
            if fields.get(fname, {}).get("type") != "UUID":
                problems.append(f"{where}: '{fname}' no es un campo UUID donde poner un usuario")
            elif email not in emails:
                problems.append(f"{where}: '{fname}' apunta al usuario '{email}', que no está en 'users'")
        # archivos: rutas dentro de la carpeta de la demo, a un campo FILE o IMAGE y dentro de su límite
        for fname, rel_path in record.get("files", {}).items():
            spec = fields.get(fname, {})
            if spec.get("type") not in ("FILE", "IMAGE"):
                problems.append(f"{where}: '{fname}' no es un campo FILE o IMAGE")
                continue
            target = (base / str(rel_path)).resolve()
            if Path(str(rel_path)).is_absolute() or not target.is_relative_to(base.resolve()):
                problems.append(f"{where}: el archivo {rel_path!r} debe estar dentro de la carpeta de la demo")
            elif not target.is_file():
                problems.append(f"{where}: no existe el archivo {rel_path!r}")
            elif "maxBytes" in spec and target.stat().st_size > spec["maxBytes"]:
                problems.append(f"{where}: {rel_path!r} supera los {spec['maxBytes']} bytes de '{fname}'")
        given = set(attributes) | set(refs) | set(users)
        for f in fields.values():
            if f.get("required") and f.get("defaultValue") is None and f["name"] not in given:
                problems.append(f"{where}: falta el campo obligatorio '{f['name']}'")
        for fname, rel in rels.items():
            if rel.get("required") and fname not in refs:
                problems.append(f"{where}: falta la relación obligatoria '{fname}'")
        key = record.get("key") or []
        if set(key) not in _natural_keys(objects[oname]):
            problems.append(f"{where}: la clave {key} no es un campo único ni una uniqueConstraint del modelo")
        elif not set(key) <= given:
            problems.append(f"{where}: la clave {key} nombra campos sin valor")
        else:
            values = tuple(sorted((k, json.dumps(attributes.get(k, refs.get(k, users.get(k))))) for k in key))
            if (oname, values) in keys_seen:
                problems.append(f"{where}: clave natural repetida {dict(values)}")
            keys_seen.add((oname, values))
        if ALIAS.match(alias) and alias not in aliases:
            aliases[alias] = oname

    # matrículas: por el comando, como el estudiante; solo en ediciones abiertas hoy
    records_by_ref = {r.get("ref"): r for r in data.get("records", [])}
    for i, enrollment in enumerate(data.get("enrollments", [])):
        where = f"matrícula #{i + 1}"
        if enrollment.get("user") not in emails:
            problems.append(f"{where}: el usuario {enrollment.get('user')!r} no está en 'users'")
        offering = records_by_ref.get(enrollment.get("offering"))
        if offering is None or offering.get("object") != "edicion_curso":
            problems.append(f"{where}: {enrollment.get('offering')!r} no es una edicion_curso del archivo")
            continue
        a = offering.get("attributes", {})
        window = [a.get("inscripcion_desde"), a.get("inscripcion_hasta")]
        open_today = all(isinstance(d, str) and RELATIVE_DATE.match(d) for d in window) and _days(window[0]) <= 0 <= _days(window[1])
        if a.get("estado") != "INSCRIPCION_ABIERTA" or not open_today:
            problems.append(f"{where}: la edición {enrollment['offering']!r} debe tener la inscripción abierta hoy (fechas relativas)")
    # sesiones: por el comando, como el docente asignado a esa edición
    assigned = {(r.get("refs", {}).get("edicion_curso"), r.get("users", {}).get("usuario_id")) for r in data.get("records", []) if r.get("object") == "docente_edicion"}
    for i, session in enumerate(data.get("sessions", [])):
        where = f"sesión #{i + 1}"
        if (session.get("offering"), session.get("teacher")) not in assigned:
            problems.append(f"{where}: {session.get('teacher')!r} no está asignado a {session.get('offering')!r}")
        lesson = records_by_ref.get(session.get("lesson"))
        if session.get("lesson") is not None and (lesson is None or lesson.get("object") != "leccion"):
            problems.append(f"{where}: {session.get('lesson')!r} no es una leccion del archivo")
        if not (isinstance(session.get("date"), str) and RELATIVE_DATE.match(session["date"])):
            problems.append(f"{where}: la fecha debe ser relativa (hoy±N)")
    if problems:
        raise ModelError(problems)


def _days(relative: str) -> int:
    return int(RELATIVE_DATE.match(relative)[1] or 0)


def resolve_dates(attributes: dict, today: datetime.date) -> dict:
    """Las fechas relativas ("hoy+12") pasan a fechas ISO del día en que se aplica la demo."""
    return {k: (today + datetime.timedelta(days=_days(v))).isoformat() if isinstance(v, str) and RELATIVE_DATE.match(v) else v
            for k, v in attributes.items()}


# ---------------------------------------------------------------------------------------------------------------------
# cliente HTTP mínimo
# ---------------------------------------------------------------------------------------------------------------------


# http en claro solo hacia esta máquina: la contraseña del administrador y su token viajan en cada llamada
LOOPBACK = {"localhost", "127.0.0.1", "::1"}


def check_url(url: str) -> None:
    parts = urllib.parse.urlsplit(url)
    if parts.scheme == "https" or (parts.scheme == "http" and parts.hostname in LOOPBACK):
        return
    raise ValueError(f"{url}: usa https (http solo para localhost)")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    # urllib reenvía Authorization al seguir una redirección, aunque cambie de servidor: no se sigue ninguna
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base_url: str, token: str | None = None):
        self.base = base_url.rstrip("/")
        self.token = token
        self.opener = urllib.request.build_opener(_NoRedirect)

    def call(self, method: str, path: str, body: object | None = None, ok_statuses: tuple[int, ...] = ()) -> object | None:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with self.opener.open(request, timeout=60) as response:
                raw = response.read().decode()
        except urllib.error.HTTPError as error:
            raw = error.read().decode()
            if error.code in ok_statuses:
                return None
            raise ApiError(error.code, method, path, raw) from None
        if not raw.strip():
            return None
        try:
            return json.loads(raw)
        except ValueError:
            raise ApiError(200, method, path, f"la respuesta no es JSON (¿--url apunta a otro servicio?): {raw[:120]}") from None

    def upload(self, path: str, file: Path) -> object | None:
        """multipart/form-data con una parte "file", como la ruta de archivos de wasichai-files."""
        boundary = f"----wasichai-learning-{os.urandom(12).hex()}"
        content_type = mimetypes.guess_type(file.name)[0] or "application/octet-stream"
        # comillas y saltos de línea cerrarían filename o añadirían cabeceras: se escapan como los navegadores (RFC 7578)
        name = file.name.replace('"', "%22").replace("\r", "%0D").replace("\n", "%0A")
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                f"Content-Type: {content_type}\r\n\r\n").encode() + file.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        request = urllib.request.Request(self.base + path, data=body, method="POST")
        request.add_header("Accept", "application/json")
        request.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with self.opener.open(request, timeout=120) as response:
                raw = response.read().decode()
        except urllib.error.HTTPError as error:
            raise ApiError(error.code, "POST", path, error.read().decode()) from None
        return json.loads(raw) if raw.strip() else None

    def login(self, email: str, password: str) -> None:
        answer = self.call("POST", "/api/auth/login", {"email": email, "password": password})
        self.token = answer["token"]


# ---------------------------------------------------------------------------------------------------------------------
# aplicación
# ---------------------------------------------------------------------------------------------------------------------


@dataclass
class Report:
    changes: list[str] = field(default_factory=list)

    warnings: list[str] = field(default_factory=list)

    # algo existente difiere del modelo y el script no lo cambia a propósito: lo decide una persona
    def warn(self, what: str) -> None:
        self.warnings.append(what)
        print(f"  ! {what}", flush=True)

    def add(self, what: str) -> None:
        self.changes.append(what)
        print(f"  + {what}", flush=True)


def _field_payload(f: dict, enums: dict) -> dict:
    payload = {k: f[k] for k in FIELD_KEYS if k in f}
    if f.get("type") == "ENUM":
        payload["enumOptions"] = list(enums[f["enum"]])
    payload.setdefault("label", f["name"].replace("_", " ").capitalize())
    return payload


def _relationship_owner(rel: dict) -> str:
    return rel["target"] if rel["type"] == "ONE_TO_MANY" else rel["source"]


def _deferred(obj: dict, relationship_fields: set[str], key: str) -> list[list[str]]:
    return [entry for entry in obj.get(key, []) if any(f in relationship_fields for f in entry)]


def apply_model(client: Client, model: dict, report: Report) -> None:
    enums = model.get("enums", {})
    rel_fields: dict[str, set[str]] = {}
    for rel in model.get("relationships", []):
        if rel["type"] != "MANY_TO_MANY":
            rel_fields.setdefault(_relationship_owner(rel), set()).add(rel["fieldName"])

    existing = {o["name"] for o in client.call("GET", "/api/objects") or []}

    # 1. objetos y campos propios
    for obj in model.get("objects", []):
        name = obj["name"]
        mine = rel_fields.get(name, set())
        if name not in existing:
            body = {k: obj[k] for k in ("name", "label", "pluralLabel", "description", "appendOnly", "apiOnly", "requiresReason") if k in obj}
            body["fields"] = [_field_payload(f, enums) for f in obj.get("fields", [])]
            for key in ("indexes", "uniqueConstraints"):
                now = [e for e in obj.get(key, []) if e not in _deferred(obj, mine, key)]
                if now:
                    body[key] = now
            client.call("POST", "/api/objects", body)
            report.add(f"objeto {name}")
            continue
        definition = client.call("GET", f"/api/metadata/objects/{name}")
        current = {f["name"]: f for f in definition.get("fields", [])}
        for f in obj.get("fields", []):
            if f["name"] not in current:
                client.call("POST", f"/api/metadata/objects/{name}/fields", _field_payload(f, enums))
                report.add(f"campo {name}.{f['name']}")
            else:
                for prop in ("required", "unique"):
                    if bool(f.get(prop)) != bool(current[f["name"]].get(prop)):
                        report.warn(f"{name}.{f['name']}: {prop} es {bool(current[f['name']].get(prop))} en el servidor y {bool(f.get(prop))} en el modelo (no se cambia)")
            if f["name"] in current and f.get("type") == "ENUM":
                have = list(current[f["name"]].get("enumOptions") or [])
                missing = [o for o in enums[f["enum"]] if o not in have]
                if missing:
                    client.call("PUT", f"/api/metadata/objects/{name}/fields/{f['name']}", {"enumOptions": have + missing})
                    report.add(f"opciones {name}.{f['name']}: {', '.join(missing)}")

    # 2. acciones declaradas
    for obj in model.get("objects", []):
        if not obj.get("actions"):
            continue
        have = {a["name"] for a in client.call("GET", f"/api/metadata/objects/{obj['name']}/actions") or []}
        for action in obj["actions"]:
            if action["name"] not in have:
                client.call("POST", f"/api/metadata/objects/{obj['name']}/actions", action, ok_statuses=(409,))
                report.add(f"acción {obj['name']}.{action['name']}")

    # 3. relaciones, obligatoriedad y lo que las nombra
    have_rels = {r["name"] for r in client.call("GET", "/api/relationships") or []}
    for rel in model.get("relationships", []):
        if rel["name"] not in have_rels:
            body = {k: rel[k] for k in ("name", "label", "inverseLabel", "type", "source", "target", "fieldName") if k in rel}
            client.call("POST", "/api/relationships", body)
            report.add(f"relación {rel['name']}")
    for rel in model.get("relationships", []):
        if rel.get("required"):
            owner, fname = _relationship_owner(rel), rel["fieldName"]
            current = {f["name"]: f for f in client.call("GET", f"/api/metadata/objects/{owner}").get("fields", [])}
            if not current.get(fname, {}).get("required"):
                client.call("PUT", f"/api/metadata/objects/{owner}/fields/{fname}", {"required": True})
                report.add(f"obligatorio {owner}.{fname}")
    for obj in model.get("objects", []):
        # los que nombran un campo de relación ya existen; los demás pueden faltar en un objeto creado antes
        wanted = {key: obj[key] for key in ("indexes", "uniqueConstraints") if obj.get(key)}
        if not wanted:
            continue
        definition = client.call("GET", f"/api/metadata/objects/{obj['name']}")
        changed = {key: value for key, value in wanted.items() if not _same(definition.get(key) or [], value)}
        if changed:
            # UpdateObjectRequest exige label y su enabled vale true por omisión: label del modelo y enabled del servidor,
            # para que actualizar una restricción no vuelva a habilitar un objeto que alguien deshabilitó
            body = {k: obj[k] for k in ("label", "pluralLabel", "description") if k in obj}
            body |= {"enabled": definition.get("enabled", True)} | changed
            client.call("PUT", f"/api/objects/{obj['name']}", body)
            report.add(f"{', '.join(changed)} de {obj['name']}")


def _same(have: list[list[str]], want: list[list[str]]) -> bool:
    return sorted(map(tuple, have)) == sorted(map(tuple, want))


def apply_roles(client: Client, roles: dict, report: Report) -> None:
    have = {r["name"]: r for r in client.call("GET", "/api/roles") or []}
    for role in roles.get("roles", []):
        name = role["name"]
        if name not in have:
            client.call("POST", "/api/roles", {"name": name, "label": role["label"], "ownRecordsOnly": role.get("ownRecordsOnly", False)})
            report.add(f"rol {name}")
            current: set[tuple[str | None, str]] = set()
        else:
            current = {(p["objectName"], p["action"]) for p in have[name].get("permissions", []) if p.get("allowed", True)}
            if bool(have[name].get("ownRecordsOnly")) != bool(role.get("ownRecordsOnly", False)):
                report.warn(f"rol {name}: ownRecordsOnly es {bool(have[name].get('ownRecordsOnly'))} en el servidor (no se cambia)")
        wanted = {(g["object"], a) for g in role.get("permissions", []) for a in g["actions"]}
        if wanted != current:
            entries = [{"objectName": o, "action": a} for o, a in sorted(wanted, key=lambda p: (p[0] or "", p[1]))]
            client.call("PUT", f"/api/roles/{name}/permissions", {"permissions": entries})
            report.add(f"permisos de {name} ({len(entries)})")
        # permisos de campo (#65): solo si el rol los declara; el PUT reemplaza el conjunto, así que va entero o no va
        if "fieldPermissions" in role:
            want_fields = sorted((f["object"], f["field"], f["read"], f["write"]) for f in role["fieldPermissions"])
            have_fields = sorted(
                (f.get("objectName"), f.get("fieldName"), bool(f.get("read")), bool(f.get("write")))
                for f in (have.get(name) or {}).get("fieldPermissions", [])
            )
            if want_fields != have_fields:
                fields = [{"objectName": o, "fieldName": f, "read": r, "write": w} for o, f, r, w in want_fields]
                client.call("PUT", f"/api/roles/{name}/field-permissions", {"fields": fields})
                report.add(f"permisos de campo de {name} ({len(fields)})")


def _content(answer: object) -> list[dict]:
    if isinstance(answer, dict):
        return list(answer.get("content") or [])
    return list(answer or [])


def _role_names(roles: object) -> set[str]:
    return {r["name"] if isinstance(r, dict) else r for r in roles or []}


def _matches(have: object, want: object) -> bool:
    if isinstance(have, dict):  # una relación puede volver expandida
        have = have.get("id")
    if have is None:
        return False
    if isinstance(have, bool) or isinstance(want, bool) or str(have).lower() in ("true", "false"):
        return str(have).lower() == str(want).lower()
    try:
        return Decimal(str(have)) == Decimal(str(want))
    except InvalidOperation:
        return str(have) == str(want)


def apply_users(client: Client, users: list[dict], password: str, report: Report) -> dict[str, str]:
    """Crea los usuarios que faltan y deja sus roles en los declarados. Devuelve correo → id. No cambia contraseñas."""
    existing = {u["email"].lower(): u for u in _content(client.call("GET", "/api/users"))}
    ids: dict[str, str] = {}
    for user in users:
        email, wanted = user["email"], sorted(user["roles"])
        have = existing.get(email.lower())
        if have is None:
            created = client.call("POST", "/api/users", {"email": email, "displayName": user["displayName"], "password": password, "roles": wanted})
            ids[email] = created["id"]
            report.add(f"usuario {email}")
        else:
            ids[email] = have["id"]
            if _role_names(have.get("roles")) != set(wanted):
                client.call("PUT", f"/api/users/{have['id']}/roles", {"roles": wanted})
                report.add(f"roles de {email}: {', '.join(wanted)}")
    return ids


def _find_record(client: Client, obj: str, key: dict) -> dict | None:
    # se vuelve a filtrar aquí: un filtro que el servidor no aplicara no debe hacer pasar otro registro por este. y se
    # siguen las páginas por cursor (keyset): si el servidor ignorara el filtro, el registro podría estar más allá de
    # la primera (#60). con el filtro aplicado, como hace Wasichai, basta una página
    found: list[dict] = []
    cursor = None
    while True:
        params = {**key, "size": 200, "count": "false"} | ({"after": cursor} if cursor else {})
        answer = client.call("GET", f"/api/objects/{obj}/records?{urllib.parse.urlencode(params)}")
        found += [r for r in _content(answer) if all(_matches((r.get("attributes") or {}).get(k), v) for k, v in key.items())]
        cursor = answer.get("nextCursor") if isinstance(answer, dict) else None
        if not cursor:
            break
    if len(found) > 1:
        raise DemoError(f"{obj} {key}: {len(found)} registros con la misma clave natural")
    return found[0] if found else None


def apply_demo(client: Client, data: dict, password: str, report: Report, base: Path = HERE / "demo",
               today: datetime.date | None = None) -> None:
    """Usuarios y registros de demostración: cada registro se busca por su clave natural y solo se crea si falta."""
    today = today or datetime.date.today()
    user_ids = apply_users(client, data.get("users", []), password, report)
    workflows = data.get("workflows") or {}
    ids: dict[str, str] = {}
    pending: list[tuple[dict, str, dict]] = []
    for record in data.get("records", []):
        obj = record["object"]
        attributes = resolve_dates(dict(record.get("attributes", {})), today)
        attributes.update({f: ids[alias] for f, alias in record.get("refs", {}).items()})
        attributes.update({f: user_ids[email] for f, email in record.get("users", {}).items()})
        flow = workflows.get(obj)
        final: dict = {}
        if flow:
            # nace en el primer estado del camino y sin lo que se pone al final (fecha de publicación, créditos):
            # su contenido (temario y archivos incluidos) se escribe mientras aún es borrador
            final = {flow["field"]: attributes.get(flow["field"], flow["path"][0])}
            final |= {f: attributes.pop(f) for f in flow.get("deferred", []) if f in attributes}
            attributes[flow["field"]] = flow["path"][0]
        found = _find_record(client, obj, {k: attributes[k] for k in record["key"]})
        if found is None:
            found = client.call("POST", f"/api/objects/{obj}/records", {"attributes": attributes})
            report.add(f"{obj} {record['ref']}")
        ids[record["ref"]] = found["id"]
        for fname, rel_path in record.get("files", {}).items():
            if not (found.get("attributes") or {}).get(fname):
                client.upload(f"/api/objects/{obj}/records/{found['id']}/files/{fname}", base / rel_path)
                report.add(f"{obj} {record['ref']}: archivo {Path(rel_path).name}")
        if flow and flow.get("inmediato"):
            # lo que otro registro del archivo necesita ya publicado (un recurso que usa una lección en borrador)
            _walk(client, record, found["id"], flow, final, report)
        elif flow:
            pending.append((record, found["id"], final))
    # después de todo lo demás: cada registro recorre su camino hasta el estado final, una transición por vez
    for record, record_id, final in pending:
        _walk(client, record, record_id, workflows[record["object"]], final, report)
    _enroll(client, data, ids, user_ids, password, report)
    _sessions(client, data, ids, password, today, report)


def _walk(client: Client, record: dict, record_id: str, flow: dict, final: dict, report: Report) -> None:
    field, path = flow["field"], flow["path"]
    current = (client.call("GET", f"/api/objects/{record['object']}/records/{record_id}") or {}).get("attributes", {}).get(field)
    target = final[field]
    if current not in path or path.index(current) >= path.index(target):
        return
    steps = path[path.index(current) + 1 : path.index(target) + 1]
    commands = flow.get("commands") or {}
    for step in steps:
        later = {k: v for k, v in final.items() if k != field} if step == target else {}
        if step in commands:
            # como lo haría la persona: decidir la revisión de un curso es un comando (ADR-0021)
            client.call("POST", commands[step]["path"].replace("{id}", record_id), commands[step].get("body") or {})
            if later:
                client.call("PATCH", f"/api/objects/{record['object']}/records/{record_id}", {"attributes": later})
        else:
            client.call("PATCH", f"/api/objects/{record['object']}/records/{record_id}", {"attributes": {field: step} | later})
    report.add(f"{record['object']} {record['ref']}: {' → '.join([current, *steps])}")


def _enroll(client: Client, data: dict, ids: dict, user_ids: dict, password: str, report: Report) -> None:
    """Matrícula de los usuarios demo por el comando del aula, iniciando sesión como cada uno: ventana, cupo y versión
    se comprueban como para cualquier estudiante. Si ya existe, no se toca."""
    codes = {r["ref"]: r["attributes"]["codigo"] for r in data.get("records", []) if r["object"] == "edicion_curso"}
    for enrollment in data.get("enrollments", []):
        email, offering = enrollment["user"], enrollment["offering"]
        if _find_record(client, "matricula", {"estudiante_id": user_ids[email], "edicion": ids[offering]}):
            continue
        student = Client(client.base)
        student.login(email, password)
        student.call("POST", "/api/lms/enrollments", {"offering": codes[offering]})
        report.add(f"matrícula de {email} en {codes[offering]}")


def _sessions(client: Client, data: dict, ids: dict, password: str, today: datetime.date, report: Report) -> None:
    """Sesiones de la demo por el comando del docente asignado, iniciando sesión como él. Si ya existe, no se toca."""
    codes = {r["ref"]: r["attributes"]["codigo"] for r in data.get("records", []) if r["object"] == "edicion_curso"}
    for session in data.get("sessions", []):
        offering = session["offering"]
        if _find_record(client, "sesion", {"edicion": ids[offering], "titulo": session["title"]}):
            continue
        date = resolve_dates({"date": session["date"]}, today)["date"]
        teacher = Client(client.base)
        teacher.login(session["teacher"], password)
        body = {k: session[k] for k in ("title", "type", "modality", "durationMinutes", "link") if k in session}
        body["lessonId"] = ids[session["lesson"]] if session.get("lesson") else None
        body["recurrence"] = {"pattern": "ONCE", "days": [], "startDate": date, "endDate": date, "localTime": session["localTime"]}
        teacher.call("POST", f"/api/lms/teaching/offerings/{codes[offering]}/sessions", body)
        report.add(f"sesión {session['title']} en {codes[offering]}")


def load(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--model", type=Path, default=HERE / "model.json")
    parser.add_argument("--roles", type=Path, default=HERE / "roles.json")
    parser.add_argument("--validate-only", action="store_true", help="valida y termina, sin llamar a la API")
    parser.add_argument("--url", default=os.environ.get("WASICHAI_URL", "http://localhost:8090"))
    parser.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL"))
    parser.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD"))
    parser.add_argument("--demo", action="store_true", help="valida y aplica también los datos de demostración")
    parser.add_argument("--demo-data", type=Path, default=HERE / "demo" / "data.json")
    args = parser.parse_args(argv)
    if args.password is not None and args.password != os.environ.get("WASICHAI_PASSWORD"):
        print("Aviso: --password queda en el historial del shell y en la lista de procesos; usa WASICHAI_PASSWORD.", file=sys.stderr)

    model = load(args.model)
    roles = load(args.roles) if args.roles.exists() else None
    try:
        validate(model, roles)
    except ModelError as error:
        print("Modelo inválido:", *(f"  - {p}" for p in error.problems), sep="\n", file=sys.stderr)
        return 2
    print(f"Modelo válido: {len(model.get('objects', []))} objetos, {len(model.get('relationships', []))} relaciones, "
          f"{len((roles or {}).get('roles', []))} roles.")
    demo = None
    if args.demo:
        demo = load(args.demo_data)
        try:
            validate_demo(demo, model, roles, args.demo_data.parent)
        except ModelError as error:
            print("Datos de demostración inválidos:", *(f"  - {p}" for p in error.problems), sep="\n", file=sys.stderr)
            return 2
        print(f"Datos de demostración válidos: {len(demo.get('users', []))} usuarios, {len(demo.get('records', []))} registros.")
    if args.validate_only:
        return 0
    try:
        check_url(args.url)
    except ValueError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 2
    if not args.email or not args.password:
        print("Faltan credenciales: --email/--password o WASICHAI_EMAIL/WASICHAI_PASSWORD.", file=sys.stderr)
        return 2
    demo_password = os.environ.get("WASICHAI_DEMO_PASSWORD", "")
    if demo is not None and not demo_password.strip():
        print("Falta WASICHAI_DEMO_PASSWORD: con --demo, la contraseña de los usuarios de demostración se pasa solo por el entorno.", file=sys.stderr)
        return 2
    client = Client(args.url)
    report = Report()
    try:
        client.login(args.email, args.password)
        apply_model(client, model, report)
        if roles is not None:
            apply_roles(client, roles, report)
        if demo is not None:
            apply_demo(client, demo, demo_password, report, base=args.demo_data.parent)
    except (ApiError, DemoError, urllib.error.URLError, TimeoutError, ConnectionError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    done = "el modelo y los datos de demostración" if demo is not None else "el modelo"
    print(f"{len(report.changes)} cambios." if report.changes else f"Sin cambios: el servidor ya tiene {done}.")
    if report.warnings:
        print(f"{len(report.warnings)} diferencias con el modelo sin aplicar: revísalas a mano (el script no cambia lo existente).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
