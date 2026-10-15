import type { Finding } from '../api/types'

/** A finding in the shape the backend returns, shown wherever the app explains what a report looks like. */
export const EXAMPLE_FINDING: Finding = {
  id: 0,
  level: 'CRITICAL',
  category: 'SECURITY',
  severity: 'CRITICAL',
  filePath: 'src/main/java/shop/UserRepository.java',
  lineStart: 42,
  lineEnd: 43,
  title: 'SQL query built from user input',
  explanation: 'The username goes straight into the query string.',
  suggestion: 'Use a parameterized query.',
  suggestedCode: '    String q = "SELECT * FROM users WHERE name = ?";\n    return jdbc.queryForObject(q, mapper, username);',
  codeSnippet: [
    '  public User find(String username) {',
    '    String q = "SELECT * FROM users WHERE name=\'" + username + "\'";',
    '    return jdbc.queryForObject(q, mapper);',
    '  }',
  ].join('\n'),
  snippetStartLine: 41,
}
