"""Datos de demostración (model/demo/data.json) y la fase --demo de model/apply.py.

Sin red: el contenido se valida contra el modelo real, y la aplicación se prueba contra un Wasichai simulado en memoria
que imita solo el contrato que usa el script (el mismo patrón que test_apply.py, copiado para no acoplar los archivos).
"""

import contextlib
import copy
import datetime
import io
import json
import os
import re
import sys
import threading
import unittest
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import apply  # noqa: E402

MODEL = apply.load(HERE / "model.json")
ROLES = apply.load(HERE / "roles.json")
DATA = apply.load(HERE / "demo" / "data.json")

# Anclas comprobadas a mano en los archivos de cada commit (gh api …/contents/<ruta>?ref=<sha>). Las de
# 05-compromisos.adoc que no son usr-compromisos no están escritas en el archivo: son los id que genera Asciidoctor con
# los atributos del manual (idprefix vacío, idseparator «-»), y ningún otro título del manual de usuario las repite.
VERIFIED_ANCHORS = {
    "wasichai/srtm-backend@6daacd3:docs/manuales/comun/acceso.adoc": {"acceso", "requisitos-acceso", "ingresar", "proc-ingresar", "sesion", "cerrar-sesion"},
    "wasichai/srtm-backend@6daacd3:docs/manuales/usuario/anuncios.adoc": {
        "presentacion-leer", "anuncio-conceptos", "estados", "proc-padron", "padron-a-fecha", "proc-nuevo", "reintento", "proc-renovar",
        "renovacion-reglas", "proc-cesar", "proc-retirar", "tasa-como-aplica", "devengado", "fichas", "errores", "faq-anuncios"},
    "wasichai/sgspe@0ebf69f:docs/manuals/user/02-primeros-pasos.adoc": {
        "usr-primeros-pasos", "usr-login", "usr-proyecto-360", "usr-listas", "usr-fichas", "usr-transiciones", "usr-mensajes"},
    "wasichai/sgspe@0ebf69f:docs/manuals/user/05-compromisos.adoc": {
        "usr-compromisos", "consultar-compromisos", "registrar-un-compromiso", "la-ficha-del-compromiso", "aprobar",
        "registrar-una-entrega-y-el-cumplimiento", "reprogramar-bloquear-e-informar-retrasos", "candidatos-a-compromiso", "acuerdos-y-convenios"},
}
EXPECTED_COURSES = {"SRTM-ANU-101": "srtm", "SGSPE-101": "sgspe", "IA-AGT-201": None, "SRTM-ATC-001": None, "CORTO-101": None, "TRAMITE-101": None}
DRAFT_ONLY = "SRTM-ATC-001"
# el del recorrido editorial (e2e real del frontend): cada ejecución lo copia, lo revisa y publica otra versión
EDITORIAL = "TRAMITE-101"


def records(obj):
    return [r for r in DATA["records"] if r["object"] == obj]


def by_ref():
    return {r["ref"]: r for r in DATA["records"]}


def children(obj, field, parent_ref):
    return [r for r in records(obj) if r.get("refs", {}).get(field) == parent_ref]


def versions_of(course_code):
    course = next(r for r in records("curso") if r["attributes"]["codigo"] == course_code)
    return children("version_curso", "curso", course["ref"])


def data_writes(writes):
    # iniciar sesión no escribe datos: comprobar el intento demo exige leer como el estudiante y como el docente
    return [w for w in writes if w != "POST /api/auth/login"]


def problems(data):
    try:
        apply.validate_demo(data, MODEL, ROLES)
    except apply.ModelError as error:
        return error.problems
    return []


class DemoDataTest(unittest.TestCase):
    def test_data_is_valid_against_the_real_model(self):
        self.assertEqual([], problems(DATA))

    def test_every_ref_resolves_to_an_earlier_record_of_the_right_object(self):
        seen = {}
        relations = {(r["source"], r["fieldName"]): r["target"] for r in MODEL["relationships"]}
        for record in DATA["records"]:
            for field, target in record.get("refs", {}).items():
                self.assertIn(target, seen, f"{record['ref']}.{field}")
                self.assertEqual(relations[(record["object"], field)], seen[target], f"{record['ref']}.{field}")
            seen[record["ref"]] = record["object"]
        emails = {u["email"] for u in DATA["users"]}
        for record in DATA["records"]:
            for email in record.get("users", {}).values():
                self.assertIn(email, emails)

    def test_exactly_the_expected_courses_and_one_draft_only(self):
        self.assertEqual(set(EXPECTED_COURSES), {r["attributes"]["codigo"] for r in records("curso")})
        for code in EXPECTED_COURSES:
            published = [v for v in versions_of(code) if v["attributes"]["estado"] == "PUBLICADA"]
            self.assertEqual(0 if code == DRAFT_ONLY else 1, len(published), code)
        self.assertEqual({"BORRADOR"}, {v["attributes"]["estado"] for v in versions_of(DRAFT_ONLY)})

    def test_course_a_has_a_draft_v2_besides_the_published_v1(self):
        states = {v["attributes"]["numero"]: v["attributes"]["estado"] for v in versions_of("SRTM-ANU-101")}
        self.assertEqual({1: "PUBLICADA", 2: "BORRADOR"}, states)

    def test_published_versions_are_complete_and_marked_illustrative(self):
        for version in records("version_curso"):
            self.assertIs(True, version["attributes"]["contenido_ilustrativo"], version["ref"])
            if version["attributes"]["estado"] != "PUBLICADA":
                continue
            for field in ("objetivos", "resultados_aprendizaje", "requisitos", "sistema_evaluacion", "certificacion", "fuentes", "publicada_en"):
                self.assertTrue(version["attributes"].get(field), f"{version['ref']}.{field}")
            units = children("unidad", "version_curso", version["ref"])
            # el curso corto recorre el aula entera con una sola unidad
            self.assertTrue((1 if version["ref"] == "version-corto-1" else 2) <= len(units) <= 4, version["ref"])
            for unit in units:
                self.assertTrue(2 <= len(children("leccion", "unidad", unit["ref"])) <= 4, unit["ref"])

    def test_lessons_of_courses_a_and_b_cite_verified_anchors(self):
        refs = by_ref()
        cited = 0
        for lesson in records("leccion"):
            source = lesson["attributes"].get("referencia_fuente")
            unit = refs[lesson["refs"]["unidad"]]
            course = refs[refs[unit["refs"]["version_curso"]]["refs"]["curso"]]
            if EXPECTED_COURSES[course["attributes"]["codigo"]] is not None:
                self.assertTrue(source, f"{lesson['ref']}: los cursos A y B citan su fuente")
            if source:
                path, _, anchor = source.partition("#")
                self.assertIn(path, VERIFIED_ANCHORS, lesson["ref"])
                self.assertIn(anchor, VERIFIED_ANCHORS[path], lesson["ref"])
                cited += 1
        self.assertGreaterEqual(cited, 20)

    def test_course_c_carries_the_specification_workload_without_approved_credits(self):
        version = next(v for v in versions_of("IA-AGT-201") if v["attributes"]["estado"] == "PUBLICADA")["attributes"]
        self.assertEqual((6, 12, 12, 12, "ECTS"), (version["semanas"], version["horas_teoria_semana"], version["horas_practica_semana"],
                                                  version["horas_autonomas_semana"], version["sistema_credito"]))
        for v in records("version_curso"):
            self.assertNotIn("creditos_aprobados", v["attributes"], "los créditos aprobados los pone una persona")

    def test_editions_are_future_relative_to_today_and_only_of_published_versions(self):
        # fechas relativas al día en que se aplica la demo (#96): nunca envejecen; una sola con la inscripción abierta hoy
        refs = by_ref()
        per_course, open_today = {}, []
        for edition in records("edicion_curso"):
            a = edition["attributes"]
            version = refs[edition["refs"]["version_curso"]]
            self.assertEqual("PUBLICADA", version["attributes"]["estado"], edition["ref"])
            self.assertEqual("America/Lima", a["zona_horaria"])
            days = {k: apply._days(a[k]) for k in ("inscripcion_desde", "inscripcion_hasta", "fecha_inicio", "fecha_fin")}
            self.assertTrue(0 < days["fecha_inicio"] < days["fecha_fin"] <= 240, edition["ref"])
            self.assertTrue(days["inscripcion_desde"] < days["inscripcion_hasta"] <= days["fecha_inicio"], edition["ref"])
            if a["estado"] == "INSCRIPCION_ABIERTA":
                self.assertTrue(days["inscripcion_desde"] <= 0 <= days["inscripcion_hasta"], edition["ref"])
                open_today.append(edition["ref"])
            self.assertGreater(a["capacidad"], 0)
            self.assertTrue(children("docente_edicion", "edicion_curso", edition["ref"]), f"{edition['ref']} sin docente")
            course = version["refs"]["curso"]
            per_course[course] = per_course.get(course, 0) + 1
        self.assertEqual(5, len(per_course))
        self.assertTrue(all(1 <= n <= 2 for n in per_course.values()))
        self.assertEqual(["edicion-srtm-a", "edicion-corto-a", "edicion-tramite-a"], open_today)

    def test_the_editorial_course_has_a_published_v1_and_an_open_offering_that_only_its_journey_uses(self):
        # #224: el recorrido editorial publica una versión nueva en cada ejecución, y publicar saca del catálogo las
        # ediciones de la anterior. con un curso propio no rompe los de otros recorridos ni crea su edición por la API
        refs = by_ref()
        (version,) = versions_of(EDITORIAL)
        self.assertEqual((1, "PUBLICADA"), (version["attributes"]["numero"], version["attributes"]["estado"]))
        self.assertEqual({"autor_id": "creador@demo.wasichai-learning.test"}, version["users"])
        (edition,) = children("edicion_curso", "version_curso", version["ref"])
        a = edition["attributes"]
        self.assertEqual("INSCRIPCION_ABIERTA", a["estado"])
        self.assertTrue(apply._days(a["inscripcion_desde"]) <= 0 <= apply._days(a["inscripcion_hasta"]), "se matricula hoy")
        # cada ejecución matricula a un estudiante nuevo; y su edición empieza después de la de SRTM, la más próxima
        self.assertGreaterEqual(a["capacidad"], 500)
        self.assertGreater(apply._days(a["fecha_inicio"]), apply._days(refs["edicion-srtm-a"]["attributes"]["fecha_inicio"]))
        # la primera lección de la primera unidad, la que se edita, es una lectura que se completa sin recursos
        unit = next(u for u in children("unidad", "version_curso", version["ref"]) if u["attributes"]["orden"] == 1)
        lesson = next(l for l in children("leccion", "unidad", unit["ref"]) if l["attributes"]["orden"] == 1)
        self.assertEqual("LECTURA", lesson["attributes"]["tipo"])
        lessons = {l["ref"] for u in children("unidad", "version_curso", version["ref"]) for l in children("leccion", "unidad", u["ref"])}
        self.assertFalse([r["ref"] for r in records("recurso_leccion") + records("evaluacion") if r["refs"]["leccion"] in lessons])
        # nadie más lo usa: ni la matrícula ni las sesiones de la demo
        self.assertNotIn(edition["ref"], [e["offering"] for e in DATA["enrollments"]] + [s["offering"] for s in DATA["sessions"]])

    def test_the_classroom_has_a_reading_a_pdf_a_video_and_a_presentation_with_their_own_synthetic_files(self):
        refs = by_ref()
        kinds = {}
        for link in records("recurso_leccion"):
            version = refs[link["refs"]["version_recurso"]]
            resource = refs[version["refs"]["recurso"]]
            lesson = refs[link["refs"]["leccion"]]
            course_version = refs[refs[lesson["refs"]["unidad"]]["refs"]["version_curso"]]
            self.assertEqual("PUBLICADA", version["attributes"]["estado"])
            self.assertEqual("PUBLICADA", course_version["attributes"]["estado"], "solo en lo que un estudiante puede cursar")
            file = HERE / "demo" / version["files"]["archivo"]
            self.assertTrue(file.is_file() and 0 < file.stat().st_size < 200_000, file)
            kinds[resource["attributes"]["tipo"]] = (lesson["attributes"]["tipo"], file.suffix)
        self.assertEqual({"HTML": ("LECTURA", ".html"), "PDF": ("LECTURA", ".pdf"), "VIDEO": ("VIDEO", ".webm"), "PRESENTACION": ("PRESENTACION", ".pdf")}, kinds)
        html = (HERE / "demo" / "archivos" / "lectura-entrar-y-salir.html").read_text()
        self.assertNotIn("<script", html.lower(), "la lectura no necesita scripts: el iframe los bloquea igual")
        self.assertTrue((HERE / "demo" / "archivos" / "guia-estados-del-anuncio.pdf").read_bytes().startswith(b"%PDF-"))
        self.assertTrue((HERE / "demo" / "archivos" / "presentacion-padron-con-filtros.pdf").read_bytes().startswith(b"%PDF-"))

    def test_the_demo_student_is_enrolled_in_the_open_offerings(self):
        student = "estudiante@demo.wasichai-learning.test"
        self.assertEqual([{"user": student, "offering": "edicion-srtm-a"}, {"user": student, "offering": "edicion-corto-a"}], DATA["enrollments"])

    def test_the_demo_student_took_the_short_course_assessment_and_its_teacher_graded_it(self):
        (attempt,) = DATA["attempts"]
        refs = by_ref()
        self.assertEqual(("estudiante@demo.wasichai-learning.test", "edicion-corto-a", "docente@demo.wasichai-learning.test"),
                         (attempt["student"], attempt["offering"], attempt["teacher"]))
        evaluation = next(r for r in records("evaluacion") if r["refs"]["leccion"] == attempt["lesson"])
        questions = {r["ref"] for r in children("pregunta", "evaluacion", evaluation["ref"])}
        # responde todas, y el docente califica todas las que se corrigen a mano
        self.assertEqual(questions, {a["question"] for a in attempt["answers"]})
        manual = {q for q in questions if refs[q]["attributes"]["tipo"] in ("ABIERTA", "ARCHIVO")}
        self.assertEqual(manual, {g["question"] for g in attempt["grades"]})
        self.assertTrue(all(g["comment"].strip() for g in attempt["grades"]))

    def test_one_user_per_role_and_one_multi_role(self):
        role_sets = [tuple(sorted(u["roles"])) for u in DATA["users"]]
        for role in ("ESTUDIANTE", "DOCENTE", "CREADOR", "REVISOR"):
            self.assertIn((role,), role_sets)
        self.assertIn(("DOCENTE", "REVISOR"), role_sets)
        self.assertTrue(all(u["email"].endswith("@demo.wasichai-learning.test") and "password" not in u for u in DATA["users"]))

    def test_no_real_data_from_the_manuals(self):
        text = json.dumps(DATA, ensure_ascii=False)
        for forbidden in ("Perené", "Perene", "Municipalidad Distrital", "20999000020", "95000077", "Wayra", "wasichai.local", "RUC", "DNI"):
            self.assertNotIn(forbidden, text)


class DemoValidationTest(unittest.TestCase):
    def broken(self, change):
        data = copy.deepcopy(DATA)
        change(data)
        return problems(data)

    def record(self, data, ref):
        return next(r for r in data["records"] if r["ref"] == ref)

    def test_reports_every_problem_at_once(self):
        def change(data):
            self.record(data, "version-srtm-1")["attributes"]["estado"] = "VIGENTE"
            self.record(data, "curso-srtm")["attributes"]["inventado"] = "x"
            data["users"][0]["roles"].append("SUPERUSUARIO")
            data["users"][1]["password"] = "secreto"
        found = self.broken(change)
        self.assertTrue(any("'VIGENTE' no es una opción" in p for p in found))
        self.assertTrue(any("campo desconocido 'inventado'" in p for p in found))
        self.assertTrue(any("rol desconocido 'SUPERUSUARIO'" in p for p in found))
        self.assertTrue(any("contraseña no va en el archivo" in p for p in found))

    def test_refs_must_exist_earlier_and_point_to_the_right_object(self):
        def change(data):
            self.record(data, "version-srtm-1")["refs"]["curso"] = "cat-ia-aplicada"
            self.record(data, "srtm-v1-u1")["refs"]["version_curso"] = "no-existe"
            self.record(data, "perfil-diego")["users"]["usuario_id"] = "nadie@demo.wasichai-learning.test"
        found = self.broken(change)
        self.assertTrue(any("apunta a un categoria, no a un curso" in p for p in found))
        self.assertTrue(any("'no-existe', que no está antes" in p for p in found))
        self.assertTrue(any("'nadie@demo.wasichai-learning.test', que no está en 'users'" in p for p in found))

    def test_keys_must_be_unique_in_the_model_and_in_the_file(self):
        def change(data):
            self.record(data, "curso-ia")["attributes"]["codigo"] = "SRTM-ANU-101"
            self.record(data, "cat-ia-aplicada")["key"] = ["nombre"]
        found = self.broken(change)
        self.assertTrue(any("clave natural repetida" in p for p in found))
        self.assertTrue(any("no es un campo único ni una uniqueConstraint" in p for p in found))

    def test_required_fields_types_and_domain(self):
        def change(data):
            del self.record(data, "curso-srtm")["attributes"]["titulo"]
            del self.record(data, "srtm-v1-u1-l1")["refs"]
            self.record(data, "edicion-ia-a")["attributes"]["fecha_inicio"] = "05/04/2027"
            self.record(data, "version-ia-1")["attributes"]["publicada_en"] = "2026-10-22T16:00:00"
            data["users"][0]["email"] = "alguien@ejemplo.com"
        found = self.broken(change)
        self.assertTrue(any("falta el campo obligatorio 'titulo'" in p for p in found))
        self.assertTrue(any("falta la relación obligatoria 'unidad'" in p for p in found))
        self.assertTrue(any("'05/04/2027' no es un DATE" in p for p in found))
        self.assertTrue(any("lleva zona horaria" in p for p in found))
        self.assertTrue(any("dominio demo.wasichai-learning.test" in p for p in found))

    def test_files_dates_and_enrollments_are_checked(self):
        def change(data):
            self.record(data, "version-recurso-guia-estados")["files"]["archivo"] = "../../model.json"
            self.record(data, "version-recurso-lectura-acceso")["files"]["archivo"] = "archivos/no-existe.html"
            self.record(data, "version-recurso-recorrido-portal")["files"] = {"numero": "archivos/recorrido-del-portal.webm"}
            self.record(data, "curso-ia")["attributes"]["titulo"] = "hoy+3"
            self.record(data, "version-ia-1")["attributes"]["publicada_en"] = "hoy"
            data["enrollments"].append({"user": "nadie@demo.wasichai-learning.test", "offering": "edicion-srtm-a"})
            data["enrollments"].append({"user": "estudiante@demo.wasichai-learning.test", "offering": "edicion-ia-a"})
            data["workflows"]["version_recurso"]["inmediato"] = "sí"
        found = self.broken(change)
        self.assertTrue(any("debe estar dentro de la carpeta de la demo" in p for p in found), found)
        self.assertTrue(any("no existe el archivo 'archivos/no-existe.html'" in p for p in found))
        self.assertTrue(any("'numero' no es un campo FILE o IMAGE" in p for p in found))
        self.assertEqual(2, sum("solo va en un campo DATE" in p for p in found), found)
        self.assertTrue(any("'nadie@demo.wasichai-learning.test' no está en 'users'" in p for p in found))
        self.assertTrue(any("'edicion-ia-a' debe tener la inscripción abierta hoy" in p for p in found))
        self.assertTrue(any("'inmediato' es true o false" in p for p in found))

    def test_attempts_are_checked(self):
        def change(data):
            data["attempts"] = [
                {
                    "student": "creador@demo.wasichai-learning.test",
                    "offering": "edicion-corto-a",
                    "lesson": "corto-v1-u1-l2",
                    "teacher": "docente.revisor@demo.wasichai-learning.test",
                    "answers": [{"question": "corto-q1", "options": ["corto-q2-a"]}, {"question": "no-existe", "text": "x"}],
                    "grades": [{"question": "corto-q1", "points": 1, "comment": "x"}, {"question": "corto-q3", "points": 7, "comment": "x"}],
                },
                {"student": "estudiante@demo.wasichai-learning.test", "offering": "edicion-ia-a", "lesson": "corto-v1-u1-l2"},
                {"student": "estudiante@demo.wasichai-learning.test", "offering": "edicion-corto-a", "lesson": "corto-v1-u1-l1"},
            ]
        found = self.broken(change)
        self.assertTrue(any("intento #1: 'creador@demo.wasichai-learning.test' no está matriculado en 'edicion-corto-a'" in p for p in found), found)
        self.assertTrue(any("intento #3: 'corto-v1-u1-l1' no es una lección con evaluación" in p for p in found), found)
        self.assertTrue(any("intento #1: 'docente.revisor@demo.wasichai-learning.test' no está asignado a 'edicion-corto-a'" in p for p in found), found)
        self.assertTrue(any("intento #1: 'corto-q2-a' no es una opción de 'corto-q1'" in p for p in found), found)
        self.assertTrue(any("intento #1: 'no-existe' no es una pregunta" in p for p in found), found)
        self.assertTrue(any("intento #1: 'corto-q1' se corrige sola" in p for p in found), found)
        self.assertTrue(any("intento #1: 'corto-q3' vale entre 0 y 6 puntos" in p for p in found), found)
        self.assertTrue(any("intento #2: 'estudiante@demo.wasichai-learning.test' no está matriculado en 'edicion-ia-a'" in p for p in found), found)

    def test_relative_dates_resolve_against_the_day_of_application(self):
        today = datetime.date(2027, 2, 27)
        resolved = apply.resolve_dates({"a": "hoy", "b": "hoy+3", "c": "hoy-30", "d": "hoyo", "e": 7}, today)
        self.assertEqual({"a": "2027-02-27", "b": "2027-03-02", "c": "2027-01-28", "d": "hoyo", "e": 7}, resolved)

    def test_validate_only_with_demo_rejects_bad_data(self):
        tmp = Path(self.id().replace(".", "_") + ".json")
        try:
            data = copy.deepcopy(DATA)
            data["records"][0]["object"] = "inexistente"
            tmp.write_text(json.dumps(data))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(2, apply.main(["--validate-only", "--demo", "--demo-data", str(tmp)]))
            self.assertIn("objeto desconocido", err.getvalue())
        finally:
            tmp.unlink(missing_ok=True)

    def test_a_workflow_command_is_a_step_of_the_path_and_an_lms_route(self):
        def change(data):
            data["workflows"]["version_curso"]["commands"] = {
                "PUBLICADISIMA": {"path": "/api/lms/x/{id}"},
                "APROBADA": {"path": "/api/objects/version_curso/records/{id}"},
            }
        found = self.broken(change)
        self.assertTrue(any("el comando de 'PUBLICADISIMA' no es un paso del camino" in p for p in found), found)
        self.assertTrue(any("el comando de 'APROBADA' es una ruta /api/lms/" in p for p in found), found)


class FakeWasichai:
    """Estado en memoria y un registro de las escrituras recibidas. Rechaza lo que el servidor real rechazaría."""

    def __init__(self):
        self.objects, self.actions, self.relationships, self.roles, self.writes = {}, {}, {}, {}, []
        self.users, self.records, self.transitions, self.decisions = {}, {}, [], []
        self.uploads, self.tokens = [], {}
        self.attempts = {}
        # un servidor que no aplicara el filtro por clave natural (#60)
        self.ignore_filters = False

    def handle(self, method, raw_path, body, auth=None):
        url = urllib.parse.urlsplit(raw_path)
        path, query = url.path, dict(urllib.parse.parse_qsl(url.query))
        if method != "GET":
            self.writes.append(f"{method} {path}")
        if (method, path) == ("POST", "/api/auth/login"):
            if body == {"email": "a@b.c", "password": "p"}:
                return 200, {"token": "t"}
            user = next((u for u in self.users.values() if u["email"] == body["email"] and u["password"] == body["password"]), None)
            if user is None:
                return 401, {"detail": "bad credentials"}
            self.tokens[f"u-{user['id']}"] = user
            return 200, {"token": f"u-{user['id']}"}
        if method == "POST" and (m := re.fullmatch(r"/api/lms/teaching/offerings/([\w-]+)/sessions", path)):
            return self.create_session((auth or "").removeprefix("Bearer "), m[1], body)
        if path.startswith(("/api/lms/classroom/", "/api/lms/teaching/")):
            return self.assessment(method, path, query, body, self.tokens.get((auth or "").removeprefix("Bearer ")))
        if (method, path) == ("POST", "/api/lms/enrollments"):
            return self.enroll((auth or "").removeprefix("Bearer "), body)
        if method == "POST" and (m := re.fullmatch(r"/api/lms/review/versions/([\w-]+)/decision", path)):
            # la bandeja: decide una versión de curso en revisión (ADR-0021)
            version = next((r for r in self.records.get("version_curso", []) if r["id"] == m[1]), None)
            if version is None or version["attributes"]["estado"] != "EN_REVISION" or body != {"decision": "APROBAR"}:
                return 409, {"detail": "only a version in review is decided"}
            version["attributes"]["estado"] = "APROBADA"
            self.transitions.append((m[1], "APROBADA", {}))
            self.decisions.append(m[1])
            return 200, {"id": m[1], "status": "APROBADA"}
        if m := re.fullmatch(r"/api/objects/(\w+)/records/([\w-]+)/files/(\w+)", path):
            # como wasichai-files: un PATCH del campo, que pasa por la guarda (solo un borrador cambia)
            record = next((r for r in self.records.get(m[1], []) if r["id"] == m[2]), None)
            if record is None:
                return 404, {"detail": "no record"}
            if record["attributes"].get("estado", "BORRADOR") != "BORRADOR":
                return 409, {"detail": "only a draft changes"}
            name = re.search(rb'filename="([^"]+)"', body)[1].decode()
            record["attributes"][m[3]] = {"id": str(uuid.uuid4()), "name": name, "size": len(body)}
            self.uploads.append((m[1], m[2], name))
            return 200, record
        if path == "/api/users":
            if method == "GET":
                return 200, [{k: v for k, v in u.items() if k != "password"} for u in self.users.values()]
            if any(u["email"] == body["email"] for u in self.users.values()):
                return 409, {"detail": "email already exists"}
            if not set(body["roles"]) <= set(self.roles) | {"ADMIN"}:
                return 400, {"detail": "unknown role"}
            user = {"id": str(uuid.uuid4()), **body}
            self.users[user["id"]] = user
            return 201, {k: v for k, v in user.items() if k != "password"}
        if m := re.fullmatch(r"/api/users/([\w-]+)/roles", path):
            self.users[m[1]]["roles"] = body["roles"]
            return 200, {}
        if m := re.fullmatch(r"/api/objects/(\w+)/records", path):
            return self.handle_records(method, m[1], query, body)
        if m := re.fullmatch(r"/api/objects/(\w+)/records/([\w-]+)", path):
            record = next((r for r in self.records.get(m[1], []) if r["id"] == m[2]), None)
            if record is None:
                return 404, {"detail": "no record"}
            if method == "PATCH":
                # como EditorialWorkflowGuard: un estado nuevo debe ser el siguiente del camino
                state = body["attributes"].get("estado")
                if state is not None and m[1] in ("version_curso", "version_recurso"):
                    if (m[1], state) == ("version_curso", "APROBADA"):
                        return 403, {"detail": "a course version review is decided in the review inbox"}
                    path_ = ["BORRADOR", "EN_REVISION", "APROBADA", "PUBLICADA"]
                    if path_.index(state) != path_.index(record["attributes"]["estado"]) + 1:
                        return 409, {"detail": f"transition {record['attributes']['estado']} -> {state} not allowed"}
                    self.transitions.append((m[2], state, dict(body["attributes"])))
                record["attributes"].update(body["attributes"])
            return 200, record
        if (method, path) == ("GET", "/api/objects"):
            return 200, [{"name": n} for n in self.objects]
        if (method, path) == ("POST", "/api/objects"):
            self.objects[body["name"]] = copy.deepcopy(body)
            return 201, body
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)", path):
            return 200, self.objects[m[1]]
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)/fields", path):
            self.objects[m[1]]["fields"].append(body)
            return 201, body
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)/fields/(\w+)", path):
            next(f for f in self.objects[m[1]]["fields"] if f["name"] == m[2]).update(body)
            return 200, {}
        if m := re.fullmatch(r"/api/metadata/objects/(\w+)/actions", path):
            if method == "GET":
                return 200, self.actions.get(m[1], [])
            self.actions.setdefault(m[1], []).append(body)
            return 201, body
        if m := re.fullmatch(r"/api/objects/(\w+)", path):
            self.objects[m[1]].update(body)
            return 200, {}
        if path == "/api/relationships":
            if method == "GET":
                return 200, list(self.relationships.values())
            self.relationships[body["name"]] = body
            owner = body["target"] if body["type"] == "ONE_TO_MANY" else body["source"]
            self.objects[owner]["fields"].append({"name": body["fieldName"], "type": "RELATION"})
            return 201, body
        if path == "/api/roles":
            if method == "GET":
                return 200, list(self.roles.values())
            self.roles[body["name"]] = {**body, "permissions": []}
            return 201, {}
        if m := re.fullmatch(r"/api/roles/(\w+)/field-permissions", path):
            self.roles[m[1]]["fieldPermissions"] = body["fields"]
            return 200, {}
        if m := re.fullmatch(r"/api/roles/(\w+)/permissions", path):
            self.roles[m[1]]["permissions"] = [{**p, "allowed": True} for p in body["permissions"]]
            return 200, {}
        return 404, {"detail": f"no route {method} {raw_path}"}

    def assigned(self, teacher, offering_id):
        return teacher is not None and any(
            a["attributes"]["edicion_curso"] == offering_id and a["attributes"].get("usuario_id") == teacher["id"]
            for a in self.records.get("docente_edicion", [])
        )

    def find(self, obj, record_id):
        return next((r for r in self.records.get(obj, []) if r["id"] == record_id), None)

    def assessment(self, method, path, query, body, user):
        """El aula y la corrección, como sus comandos: el estudiante sobre su matrícula, el docente sobre su edición."""
        if user is None:
            return 401, {"detail": "no session"}
        evaluations = {e["attributes"]["leccion"]: e for e in self.records.get("evaluacion", [])}

        def questions(lesson):
            return [q for q in self.records.get("pregunta", []) if q["attributes"]["evaluacion"] == evaluations[lesson]["id"]]

        def mine(enrollment_id):
            enrollment = self.find("matricula", enrollment_id)
            return enrollment if enrollment and enrollment["attributes"]["estudiante_id"] == user["id"] else None

        if m := re.fullmatch(r"/api/lms/classroom/enrollments/([\w-]+)", path):
            if not mine(m[1]):
                return 404, {"detail": "not yours"}
            lessons = []
            for lesson in evaluations:
                taken = [a for a in self.attempts.values() if a["enrollmentId"] == m[1] and a["lessonId"] == lesson]
                opened = next((a["id"] for a in taken if a["status"] == "OPEN"), None)
                lessons.append({"id": lesson, "assessment": {"attemptsUsed": len(taken), "openAttemptId": opened}})
            return 200, {"units": [{"lessons": lessons}]}
        if m := re.fullmatch(r"/api/lms/classroom/enrollments/([\w-]+)/lessons/([\w-]+)/attempts", path):
            if not mine(m[1]) or m[2] not in evaluations:
                return 404, {"detail": "not yours"}
            opened = next((a for a in self.attempts.values() if a["enrollmentId"] == m[1] and a["lessonId"] == m[2] and a["status"] == "OPEN"), None)
            if opened:
                return 200, opened
            attempt = {"id": str(uuid.uuid4()), "enrollmentId": m[1], "lessonId": m[2], "studentId": user["id"], "status": "OPEN", "answers": [], "history": []}
            self.attempts[attempt["id"]] = attempt
            return 201, attempt
        if m := re.fullmatch(r"/api/lms/classroom/attempts/([\w-]+)(/answers|/submit)", path):
            attempt = self.attempts.get(m[1])
            if attempt is None or attempt["studentId"] != user["id"]:
                return 404, {"detail": "not yours"}
            if attempt["status"] != "OPEN":
                return 409, {"detail": "already submitted"}
            if m[2] == "/answers":
                ids = {q["id"]: q for q in questions(attempt["lessonId"])}
                for answer in body["answers"]:
                    question = ids.get(answer["questionId"])
                    options = {o["id"] for o in self.records.get("opcion", []) if question and o["attributes"]["pregunta"] == question["id"]}
                    if question is None or not set(answer.get("optionIds") or []) <= options:
                        return 400, {"detail": "answer does not fit its question"}
                attempt["answers"] = body["answers"]
            else:
                manual = [q for q in questions(attempt["lessonId"]) if q["attributes"]["tipo"] in ("ABIERTA", "ARCHIVO")]
                attempt["status"] = "SUBMITTED" if manual else "GRADED"
            return 200, attempt
        if m := re.fullmatch(r"/api/lms/teaching/offerings/([\w-]+)/attempts", path):
            offering = next((r for r in self.records.get("edicion_curso", []) if r["attributes"]["codigo"] == m[1]), None)
            if offering is None or not self.assigned(user, offering["id"]):
                return 404, {"detail": "not your offering"}
            wanted = query.get("status", "SUBMITTED")
            offering_of = lambda a: self.find("matricula", a["enrollmentId"])["attributes"]["edicion"]  # noqa: E731
            rows = [a for a in self.attempts.values() if a["status"] == wanted and offering_of(a) == offering["id"]]
            return 200, [{"attemptId": a["id"], "enrollmentId": a["enrollmentId"], "lessonId": a["lessonId"]} for a in rows]
        if m := re.fullmatch(r"/api/lms/teaching/attempts/([\w-]+)(/grades)?", path):
            attempt = self.attempts.get(m[1])
            if attempt is None or not self.assigned(user, self.find("matricula", attempt["enrollmentId"])["attributes"]["edicion"]):
                return 404, {"detail": "not your offering"}
            if m[2] is None:
                return 200, attempt
            question = next((q for q in questions(attempt["lessonId"]) if q["id"] == body["questionId"]), None)
            if attempt["status"] == "OPEN":
                return 409, {"detail": "not submitted"}
            if question is None or question["attributes"]["tipo"] not in ("ABIERTA", "ARCHIVO") or not 0 <= body["points"] <= question["attributes"]["puntos"]:
                return 400, {"detail": "invalid grade"}
            if any(h["questionId"] == question["id"] for h in attempt["history"]) and not body.get("reason"):
                return 409, {"detail": "changing a grade needs a reason"}
            attempt["history"].append({"questionId": question["id"], "points": body["points"], "comment": body.get("comment"), "authorId": user["id"]})
            manual = {q["id"] for q in questions(attempt["lessonId"]) if q["attributes"]["tipo"] in ("ABIERTA", "ARCHIVO")}
            if manual <= {h["questionId"] for h in attempt["history"]}:
                attempt["status"] = "GRADED"
            return 200, attempt
        return 404, {"detail": f"no route {method} {path}"}

    def create_session(self, token, code, body):
        # como el comando: la persona debe tener esa edición asignada
        teacher = self.tokens.get(token)
        offering = next((r for r in self.records.get("edicion_curso", []) if r["attributes"]["codigo"] == code), None)
        if not (offering and self.assigned(teacher, offering["id"])):
            return 404, {"detail": "not your offering"}
        session = {"id": str(uuid.uuid4()), "attributes": {"edicion": offering["id"], "titulo": body["title"], "leccion_id": body["lessonId"]}}
        self.records.setdefault("sesion", []).append(session)
        return 201, [session]

    def enroll(self, token, body):
        # el comando de matrícula: como la persona, con la versión de la edición publicada
        student = self.tokens.get(token)
        if student is None:
            return 401, {"detail": "no session"}
        offering = next((r for r in self.records.get("edicion_curso", []) if r["attributes"]["codigo"] == body["offering"]), None)
        if offering is None:
            return 404, {"detail": "no offering"}
        version = next(r for r in self.records["version_curso"] if r["id"] == offering["attributes"]["version_curso"])
        if version["attributes"]["estado"] != "PUBLICADA":
            return 409, {"detail": "version not published"}
        enrollment = {"id": str(uuid.uuid4()), "attributes": {"edicion": offering["id"], "version_curso": version["id"],
                                                               "estudiante_id": student["id"], "estado": "ACTIVA"}}
        self.records.setdefault("matricula", []).append(enrollment)
        return 201, enrollment

    def handle_records(self, method, obj, query, body):
        if obj not in self.objects:
            return 404, {"detail": f"unknown object {obj}"}
        if method == "POST" and self.objects[obj].get("apiOnly"):
            return 405, {"detail": f"{obj} is written only by its command"}
        if method == "POST" and obj == "recurso_leccion":
            # como EditorialWorkflowGuard: un recurso publicado, en el temario de una versión en borrador
            find = lambda o, i: next(r for r in self.records[o] if r["id"] == i)  # noqa: E731
            attributes = body["attributes"]
            lesson = find("leccion", attributes["leccion"])
            course = find("version_curso", find("unidad", lesson["attributes"]["unidad"])["attributes"]["version_curso"])
            if find("version_recurso", attributes["version_recurso"])["attributes"]["estado"] != "PUBLICADA":
                return 409, {"detail": "a lesson only uses published resource versions"}
            if course["attributes"]["estado"] != "BORRADOR":
                return 409, {"detail": "the curriculum of a published version does not change"}
        stored = self.records.setdefault(obj, [])
        if method == "GET":
            filters = {k: v for k, v in query.items() if k not in ("size", "page", "sort", "count", "after")}
            found = stored if self.ignore_filters else [r for r in stored if all(str(r["attributes"].get(k)) == v for k, v in filters.items())]
            # páginas por cursor, como Wasichai (keyset): after = índice de inicio
            size, start = int(query.get("size", 25)), int(query.get("after", 0))
            page = found[start : start + size]
            answer = {"content": page, "totalElements": len(found)}
            if start + size < len(found):
                answer["nextCursor"] = str(start + size)
            return 200, answer
        fields = {f["name"]: f for f in self.objects[obj]["fields"]}
        attributes = body["attributes"]
        if unknown := set(attributes) - set(fields):
            return 400, {"detail": f"unknown fields {sorted(unknown)}"}
        for rel in self.relationships.values():
            if rel["source"] == obj and rel["fieldName"] in attributes:
                if not any(r["id"] == attributes[rel["fieldName"]] for r in self.records.get(rel["target"], [])):
                    return 400, {"detail": f"{rel['fieldName']}: unknown {rel['target']} id"}
        record = {"id": str(uuid.uuid4()), "attributes": attributes}
        stored.append(record)
        return 201, record


class WorkflowTest(unittest.TestCase):
    """Las versiones demo recorren el flujo editorial como lo haría una persona (EditorialWorkflowGuard)."""

    def test_workflow_must_name_states_of_the_enum(self):
        data = copy.deepcopy(DATA)
        data["workflows"]["version_curso"]["path"] = ["BORRADOR", "PUBLICADISIMA"]
        with self.assertRaises(apply.ModelError):
            apply.validate_demo(data, MODEL, ROLES)


class ApplyDemoTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeWasichai()
        fake = self.fake

        class Handler(BaseHTTPRequestHandler):
            def _serve(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                is_json = (self.headers.get("Content-Type") or "").startswith("application/json")
                body = (json.loads(raw) if is_json else raw) if length else None
                status, answer = fake.handle(self.command, self.path, body, self.headers.get("Authorization"))
                raw = json.dumps(answer).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            do_GET = do_POST = do_PUT = do_PATCH = _serve

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.args = ["--url", f"http://127.0.0.1:{self.server.server_port}", "--email", "a@b.c", "--password", "p", "--demo"]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def run_main(self, password="clave-de-prueba-demo", args=None):
        env = {k: v for k, v in os.environ.items() if k != "WASICHAI_DEMO_PASSWORD"}
        if password is not None:
            env["WASICHAI_DEMO_PASSWORD"] = password
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = apply.main(args or self.args)
        return code, out.getvalue(), err.getvalue()

    def test_first_run_creates_users_and_records_with_resolved_relations(self):
        code, _, err = self.run_main()
        self.assertEqual(0, code, err)
        self.assertEqual({u["email"] for u in DATA["users"]}, {u["email"] for u in self.fake.users.values()})
        self.assertEqual({"clave-de-prueba-demo"}, {u["password"] for u in self.fake.users.values()})
        for obj in {r["object"] for r in DATA["records"]}:
            self.assertEqual(len(records(obj)), len(self.fake.records.get(obj, [])), obj)
        # los perfiles docentes apuntan a la cuenta de Wasichai de su docente
        user_ids = {u["email"]: u["id"] for u in self.fake.users.values()}
        profiles = {r["attributes"]["nombre_publico"]: r["attributes"]["usuario_id"] for r in self.fake.records["perfil_docente"]}
        self.assertEqual(user_ids["docente@demo.wasichai-learning.test"], profiles["Diego Docente (demo)"])

    def test_versions_are_born_drafts_and_walk_the_editorial_flow_after_their_curriculum(self):
        code, out, err = self.run_main()
        self.assertEqual(0, code, err)
        published = [r for r in DATA["records"] if r["object"] == "version_curso" and r["attributes"].get("estado") == "PUBLICADA"]
        walked = {}
        course_versions = {r["id"] for r in self.fake.records["version_curso"]}
        for record_id, state, body in self.fake.transitions:
            if record_id in course_versions:
                walked.setdefault(record_id, []).append((state, body))
        self.assertEqual(len(published), len(walked))
        for steps in walked.values():
            self.assertEqual(["EN_REVISION", "APROBADA", "PUBLICADA"], [s for s, _ in steps])
            # la fecha de publicación llega con la publicación, no antes
            self.assertTrue(all("publicada_en" not in b for s, b in steps[:-1]))
        # la aprobación es la decisión de la bandeja, no un PATCH del estado (ADR-0021)
        self.assertEqual(set(walked), set(self.fake.decisions))
        # el temario se creó mientras la versión era borrador: ningún POST de unidad/lección tras la primera transición
        first_patch = next(i for i, w in enumerate(self.fake.writes) if w.startswith("PATCH /api/objects/version_curso"))
        self.assertFalse(any(w.startswith(("POST /api/objects/unidad", "POST /api/objects/leccion")) for w in self.fake.writes[first_patch:]))
        drafts = [r for r in self.fake.records["version_curso"] if r["attributes"]["estado"] == "BORRADOR"]
        self.assertEqual(sum(1 for r in DATA["records"] if r["object"] == "version_curso" and r["attributes"].get("estado") == "BORRADOR"), len(drafts))

    def test_resources_get_their_file_and_are_published_before_a_lesson_uses_them(self):
        code, _, err = self.run_main()
        self.assertEqual(0, code, err)
        # el simulador rechaza (409) un recurso sin publicar en una lección o un temario ya publicado: llegar aquí lo prueba
        self.assertEqual(len(records("recurso_leccion")), len(self.fake.records["recurso_leccion"]))
        self.assertEqual({"lectura-entrar-y-salir.html", "guia-estados-del-anuncio.pdf", "recorrido-del-portal.webm", "presentacion-padron-con-filtros.pdf"}, {name for _, _, name in self.fake.uploads})
        self.assertTrue(all(r["attributes"]["estado"] == "PUBLICADA" for r in self.fake.records["version_recurso"]))
        self.assertTrue(all(r["attributes"].get("archivo") for r in self.fake.records["version_recurso"]))

    def test_dates_are_written_relative_to_today(self):
        self.assertEqual(0, self.run_main()[0])
        today = datetime.date.today()
        open_offering = next(r for r in self.fake.records["edicion_curso"] if r["attributes"]["codigo"] == "SRTM-ANU-101-A")
        self.assertEqual((today + datetime.timedelta(days=12)).isoformat(), open_offering["attributes"]["fecha_inicio"])
        self.assertEqual((today - datetime.timedelta(days=2)).isoformat(), open_offering["attributes"]["inscripcion_desde"])

    def test_the_demo_student_enrolls_through_the_command_as_themselves(self):
        code, _, err = self.run_main()
        self.assertEqual(0, code, err)
        student = next(u for u in self.fake.users.values() if u["email"] == "estudiante@demo.wasichai-learning.test")
        self.assertEqual([student["id"]] * 2, [e["attributes"]["estudiante_id"] for e in self.fake.records["matricula"]])
        self.assertIn("POST /api/lms/enrollments", self.fake.writes)
        self.assertNotIn("POST /api/objects/matricula/records", self.fake.writes)

    def test_the_short_course_session_is_created_by_its_assigned_teacher(self):
        code, _, err = self.run_main()
        self.assertEqual(0, code, err)
        (session,) = self.fake.records["sesion"]
        lesson = next(r for r in self.fake.records["leccion"] if r["attributes"]["titulo"] == "Sesión de revisión en grupo")
        self.assertEqual(lesson["id"], session["attributes"]["leccion_id"])
        self.assertNotIn("POST /api/objects/sesion/records", self.fake.writes)

    def test_the_demo_attempt_is_taken_by_the_student_and_graded_by_the_assigned_teacher(self):
        code, _, err = self.run_main()
        self.assertEqual(0, code, err)
        ids = {u["email"]: u["id"] for u in self.fake.users.values()}
        (attempt,) = self.fake.attempts.values()
        self.assertEqual("GRADED", attempt["status"])
        self.assertEqual(ids["estudiante@demo.wasichai-learning.test"], attempt["studentId"])
        self.assertEqual(3, len(attempt["answers"]))
        (grade,) = attempt["history"]
        self.assertEqual(ids["docente@demo.wasichai-learning.test"], grade["authorId"])
        self.assertEqual(4, grade["points"])
        # por los comandos del aula y de la corrección, nunca por la API de registros
        for route in ("/answers", "/submit", "/grades"):
            self.assertTrue(any(w.startswith(("PUT /api/lms/", "POST /api/lms/")) and w.endswith(route) for w in self.fake.writes), route)

    def test_an_attempt_left_open_or_ungraded_is_finished_on_the_next_run(self):
        self.assertEqual(0, self.run_main()[0])
        (attempt,) = self.fake.attempts.values()
        attempt.update({"status": "OPEN", "history": []})
        self.assertEqual(0, self.run_main()[0])
        self.assertEqual(1, len(self.fake.attempts))
        self.assertEqual(("GRADED", 1), (attempt["status"], len(attempt["history"])))

    def test_existing_records_are_found_beyond_the_first_page_even_if_the_server_ignores_the_filter(self):
        self.assertEqual(0, self.run_main()[0])
        # 250 lecciones de relleno delante: el registro buscado queda más allá de la primera página de 200
        filler = [{"id": f"x{i}", "attributes": {"titulo": f"relleno {i}"}} for i in range(250)]
        self.fake.records["leccion"] = filler + self.fake.records["leccion"]
        self.fake.ignore_filters = True
        writes = len(self.fake.writes)
        code, out, err = self.run_main()
        self.assertEqual(0, code, err)
        # como en la segunda ejecución normal: solo inicios de sesión, ningún registro demo creado de nuevo
        self.assertEqual([], data_writes(self.fake.writes[writes:]))

    def test_second_run_writes_nothing(self):
        self.assertEqual(0, self.run_main()[0])
        writes = len(self.fake.writes)
        code, out, _ = self.run_main()
        self.assertEqual(0, code)
        # la segunda ejecución solo inicia sesión: la del administrador y las de quienes comprueban el intento demo
        self.assertEqual([], data_writes(self.fake.writes[writes:]))
        self.assertIn("POST /api/auth/login", self.fake.writes[writes:])
        self.assertIn("Sin cambios", out)

    def test_existing_user_gets_the_declared_roles_without_a_new_account(self):
        self.assertEqual(0, self.run_main()[0])
        multi = next(u for u in self.fake.users.values() if u["email"] == "docente.revisor@demo.wasichai-learning.test")
        multi["roles"] = ["DOCENTE"]
        writes = len(self.fake.writes)
        self.assertEqual(0, self.run_main()[0])
        self.assertEqual([f"PUT /api/users/{multi['id']}/roles"], data_writes(self.fake.writes[writes:]))
        self.assertEqual(["DOCENTE", "REVISOR"], multi["roles"])

    def test_missing_demo_password_is_a_clear_error_before_any_call(self):
        code, _, err = self.run_main(password=None)
        self.assertEqual(2, code)
        self.assertIn("WASICHAI_DEMO_PASSWORD", err)
        self.assertEqual([], self.fake.writes)
        self.assertEqual(2, self.run_main(password="  ")[0])

    def test_without_demo_flag_no_demo_data_is_written(self):
        self.assertEqual(0, self.run_main(password=None, args=self.args[:-1])[0])
        self.assertEqual({}, self.fake.users)
        self.assertEqual({}, self.fake.records)

    def test_duplicated_natural_key_on_the_server_stops_the_run(self):
        self.assertEqual(0, self.run_main()[0])
        stored = self.fake.records["categoria"]
        stored.append(copy.deepcopy(stored[0]) | {"id": str(uuid.uuid4())})
        code, _, err = self.run_main()
        self.assertEqual(1, code)
        self.assertIn("misma clave natural", err)


if __name__ == "__main__":
    unittest.main()
