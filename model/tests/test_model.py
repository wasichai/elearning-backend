"""El modelo y los roles reales: válidos, y con la separación de deberes que exige la especificación (sección 7)."""

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import apply  # noqa: E402

MODEL = apply.load(HERE / "model.json")
ROLES = apply.load(HERE / "roles.json")


def holds(role: str, obj: str, action: str) -> bool:
    found = next(r for r in ROLES["roles"] if r["name"] == role)
    return any(g["object"] == obj and action in g["actions"] for g in found["permissions"])


class RealModelTest(unittest.TestCase):
    def test_model_and_roles_are_valid(self):
        apply.validate(MODEL, ROLES)

    def test_the_four_lms_roles_exist(self):
        self.assertEqual({"ESTUDIANTE", "DOCENTE", "CREADOR", "REVISOR"}, {r["name"] for r in ROLES["roles"]})

    def test_nobody_but_admin_publishes_or_retires(self):
        for role in ROLES["roles"]:
            for action in ("PUBLICAR", "RETIRAR"):
                self.assertFalse(holds(role["name"], "version_curso", action), f"{role['name']} no debe {action}")

    def test_creator_does_not_approve_and_reviewer_does_not_author(self):
        self.assertFalse(holds("CREADOR", "version_curso", "APROBAR"))
        # el revisor necesita UPDATE para aprobar por la API de registros; qué puede cambiar lo decide
        # EditorialWorkflowGuard (solo la transición que su acción permite, nunca el contenido de otro)
        for action in ("CREATE", "DELETE", "ENVIAR_A_REVISION"):
            self.assertFalse(holds("REVISOR", "version_curso", action))

    def test_teachers_do_not_edit_offerings_until_their_scope_exists(self):
        # F1: sin alcance por asignación, UPDATE sobre edicion_curso sería sobre cualquier edición
        self.assertFalse(holds("DOCENTE", "edicion_curso", "UPDATE"))

    def test_students_read_content_only_through_their_enrollments(self):
        # leen versiones, unidades y lecciones, pero ContentReadScope las limita a sus matrículas activas: sin VER_TODAS
        for obj in ("version_curso", "unidad", "leccion", "edicion_curso"):
            self.assertTrue(holds("ESTUDIANTE", obj, "READ"), obj)
            self.assertFalse(holds("ESTUDIANTE", obj, "VER_TODAS"), obj)
            for staff in ("DOCENTE", "CREADOR", "REVISOR"):
                self.assertTrue(holds(staff, obj, "VER_TODAS"), f"{staff} {obj}")
        for obj in ("perfil_docente", "docente_edicion"):
            self.assertFalse(holds("ESTUDIANTE", obj, "READ"), obj)

    def test_resources_follow_the_editorial_separation_of_duties(self):
        for role in ROLES["roles"]:
            self.assertFalse(holds(role["name"], "version_recurso", "PUBLICAR") or holds(role["name"], "version_recurso", "RETIRAR"), role["name"])
        self.assertFalse(holds("CREADOR", "version_recurso", "APROBAR"))
        for action in ("CREATE", "DELETE", "ENVIAR_A_REVISION"):
            self.assertFalse(holds("REVISOR", "version_recurso", action))
        for obj in ("recurso", "version_recurso", "recurso_leccion"):
            self.assertFalse(holds("ESTUDIANTE", obj, "VER_TODAS"), obj)

    def test_resources_carry_the_learning_resource_fields(self):
        # RF-CON-01: identificador estable, título, descripción, tipo, idioma y responsable; autor y estado en su versión
        def fields(name: str) -> set[str]:
            return {f["name"] for f in next(o for o in MODEL["objects"] if o["name"] == name)["fields"]}

        self.assertLessEqual({"codigo", "titulo", "descripcion", "tipo", "idioma", "responsable_editorial_id"}, fields("recurso"))
        self.assertLessEqual({"numero", "autor_id", "estado", "archivo"}, fields("version_recurso"))

    def test_students_never_read_assessment_content(self):
        # llevan las soluciones: el estudiante responde sobre la instantánea de su intento (ADR-0016)
        for obj in ("evaluacion", "pregunta", "opcion", "criterio_rubrica"):
            self.assertFalse(holds("ESTUDIANTE", obj, "READ"), obj)
            # el docente corrige con la instantánea del intento; las claves de todos los cursos no le hacen falta
            self.assertFalse(holds("DOCENTE", obj, "READ"), obj)
            self.assertTrue(holds("CREADOR", obj, "CREATE"), obj)

    def test_course_versions_have_every_modality_and_a_kind_of_duration(self):
        # RF-ACA-05: presencial, virtual síncrona, asíncrona o híbrida; por semanas o semestral (el rango, en CourseDurationGuard)
        self.assertEqual({"PRESENCIAL", "SINCRONA", "ASINCRONA", "HIBRIDA"}, set(MODEL["enums"]["modalidad"]))
        version = next(o for o in MODEL["objects"] if o["name"] == "version_curso")
        duration = next(f for f in version["fields"] if f["name"] == "duracion")
        # no es requerido: Wasichai añade un campo requerido con NOT NULL y sin DEFAULT, y eso falla (500) en una tabla que ya
        # tiene versiones. Vacío cuenta como SEMANAS (CourseDurationGuard), y el rango lo sigue exigiendo la guarda
        self.assertEqual(("tipo_duracion", "SEMANAS", False), (duration["enum"], duration["defaultValue"], duration.get("required", False)))
        self.assertEqual(["SEMANAS", "SEMESTRAL"], MODEL["enums"]["tipo_duracion"])

    def test_no_teacher_sees_every_student_before_assignment_scope(self):
        # revisión de la Fase 2: sin alcance por asignación (F3), VER_TODAS abriría matrículas y progreso de todos
        for obj in ("matricula", "progreso_leccion"):
            for role in ROLES["roles"]:
                self.assertFalse(holds(role["name"], obj, "VER_TODAS"), f"{role['name']} {obj}")

    def test_progress_is_written_only_by_the_classroom(self):
        progress = next(o for o in MODEL["objects"] if o["name"] == "progreso_leccion")
        self.assertTrue(progress.get("apiOnly"))
        self.assertIn(["matricula", "leccion"], progress["uniqueConstraints"])
        for role in ROLES["roles"]:
            if role["name"] != "ESTUDIANTE":
                self.assertFalse(holds(role["name"], "progreso_leccion", "CREATE") or holds(role["name"], "progreso_leccion", "UPDATE"), role["name"])

    def test_teacher_profiles_are_private_by_default(self):
        fields = {f["name"]: f for o in MODEL["objects"] if o["name"] == "perfil_docente" for f in o["fields"]}
        self.assertEqual("false", fields["perfil_publico"]["defaultValue"])

    def test_staff_do_not_read_the_teachers_user_id(self):
        # #65: el usuario_id une el perfil público con una cuenta; solo ADMIN lo necesita
        for role in ROLES["roles"]:
            if holds(role["name"], "perfil_docente", "READ"):
                hidden = [f for f in role.get("fieldPermissions", []) if f["object"] == "perfil_docente" and f["field"] == "usuario_id"]
                self.assertEqual([False], [f["read"] for f in hidden], role["name"])

    def test_students_only_read_except_enrolling_and_their_progress(self):
        # matricula y progreso_leccion son apiOnly: esas escrituras solo pasan por los comandos del aula
        student = next(r for r in ROLES["roles"] if r["name"] == "ESTUDIANTE")
        writes = {"matricula": {"READ", "CREATE", "UPDATE"}, "progreso_leccion": {"READ", "CREATE", "UPDATE"}, "entrega": {"READ", "CREATE", "UPDATE"}}
        for grant in student["permissions"]:
            expected = writes.get(grant["object"], {"READ"})
            self.assertEqual(expected, set(grant["actions"]), grant["object"])

    def test_enrollments_are_written_only_by_the_command(self):
        matricula = next(o for o in MODEL["objects"] if o["name"] == "matricula")
        self.assertTrue(matricula["apiOnly"])
        self.assertFalse(holds("ESTUDIANTE", "matricula", "VER_TODAS"))
        # ESTUDIANTE tiene UPDATE solo para que su comando de certificado la finalice: apiOnly cierra la API genérica
        self.assertFalse(holds("ESTUDIANTE", "matricula", "DELETE"))
        for role in ("DOCENTE", "CREADOR", "REVISOR"):
            self.assertFalse(holds(role, "matricula", "UPDATE") or holds(role, "matricula", "DELETE"), role)

    def test_review_comments_are_written_only_by_the_review_inbox(self):
        # ADR-0018 §2: el comentario lo escribe la bandeja del revisor, ligado a su versión; el creador solo lo lee
        comment = next(o for o in MODEL["objects"] if o["name"] == "comentario_revision")
        self.assertTrue(comment.get("apiOnly"))
        link = next(r for r in MODEL["relationships"] if r["source"] == "comentario_revision")
        self.assertEqual(("version_curso", True), (link["target"], link.get("required", False)))
        self.assertTrue(holds("REVISOR", "comentario_revision", "CREATE"))
        self.assertTrue(holds("CREADOR", "comentario_revision", "READ"))
        self.assertFalse(holds("CREADOR", "comentario_revision", "CREATE") or holds("CREADOR", "comentario_revision", "UPDATE"))
        for role in ("ESTUDIANTE", "DOCENTE"):
            self.assertFalse(holds(role, "comentario_revision", "READ"), role)

    def test_generated_artifacts_identify_their_exact_source(self):
        # RF-CON-03, ADR-0018 §7: la fuente y el artefacto no se sobrescriben; el artefacto guarda su versión de recurso, su
        # fuente, el hash de ambos y el generador con su versión. Solo lo escribe el trabajo de generación (apiOnly)
        source = next(o for o in MODEL["objects"] if o["name"] == "fuente_recurso")
        artifact = next(o for o in MODEL["objects"] if o["name"] == "artefacto_generado")
        self.assertTrue(source.get("appendOnly"))
        self.assertTrue(artifact.get("appendOnly") and artifact.get("apiOnly"))
        source_file = next(f for f in source["fields"] if f["name"] == "archivo")
        self.assertEqual(["text/plain"], source_file["contentTypes"], "la fuente es texto: nunca un binario")
        fields = {f["name"] for f in artifact["fields"]}
        self.assertLessEqual({"formato", "archivo", "sha256", "hash_fuente", "generador", "version_generador", "trabajo_id"}, fields)
        links = {r["fieldName"]: (r["target"], r.get("required", False)) for r in MODEL["relationships"] if r["source"] == "artefacto_generado"}
        self.assertEqual({"version_recurso": ("version_recurso", True), "fuente_recurso": ("fuente_recurso", True)}, links)
        self.assertEqual(["HTML", "PDF"], MODEL["enums"]["formato_artefacto"])
        self.assertTrue(holds("CREADOR", "fuente_recurso", "CREATE"))
        for role in ROLES["roles"]:
            for action in ("CREATE", "UPDATE", "DELETE"):
                self.assertFalse(holds(role["name"], "artefacto_generado", action), f"{role['name']} {action}")
        for role in ("ESTUDIANTE", "DOCENTE"):
            self.assertFalse(holds(role, "fuente_recurso", "READ") or holds(role, "artefacto_generado", "READ"), role)

    def test_versions_start_as_draft_and_illustrative(self):
        version = next(o for o in MODEL["objects"] if o["name"] == "version_curso")
        fields = {f["name"]: f for f in version["fields"]}
        self.assertEqual("BORRADOR", fields["estado"]["defaultValue"])
        self.assertEqual("true", fields["contenido_ilustrativo"]["defaultValue"])
        self.assertNotIn("defaultValue", fields["creditos_aprobados"], "los créditos aprobados los pone una persona")

    # RF-ACA-08: la versión declara los cursos que exige haber aprobado; los comprueba el comando de matrícula. es un campo
    # de la versión, así que se congela con ella al publicarse y nadie sin acceso a la versión lo lee
    def test_versions_declare_the_courses_they_require(self):
        version = next(o for o in MODEL["objects"] if o["name"] == "version_curso")
        field = next(f for f in version["fields"] if f["name"] == "cursos_previos")
        self.assertEqual("LONG_TEXT", field["type"])
        self.assertFalse(field.get("required", False))

    def test_curriculum_is_separate_from_offering_and_schedule(self):
        # el temario (curso/versión) no tiene fechas; la edición sí, con su zona horaria
        course = {f["name"] for o in MODEL["objects"] if o["name"] in ("curso", "version_curso") for f in o["fields"]}
        self.assertFalse({"fecha_inicio", "fecha_fin", "zona_horaria"} & course)
        offering = {f["name"] for o in MODEL["objects"] if o["name"] == "edicion_curso" for f in o["fields"]}
        self.assertTrue({"fecha_inicio", "fecha_fin", "zona_horaria"} <= offering)


if __name__ == "__main__":
    unittest.main()
