// Conventional Commits; asunto en español y sin límite de mayúsculas (sgspe usa la misma regla)
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'subject-case': [0],
    'body-max-line-length': [2, 'always', 100],
    'header-max-length': [2, 'always', 100]
  }
}
