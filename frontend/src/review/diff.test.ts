import { describe, expect, it } from 'vitest'
import { buildDiff, gutterWidth, type DiffRow } from './diff'

const SNIPPET = ['def find(name):', '    q = "SELECT * WHERE n=\'" + name + "\'"', '    return db.run(q)', ''].join('\n')

function rows(diff: DiffRow[]) {
  return diff.map((row) => `${row.kind} ${row.oldNumber ?? '-'} ${row.newNumber ?? '-'} ${row.text}`)
}

describe('buildDiff', () => {
  it('replaces the finding lines with the suggestion and renumbers what follows', () => {
    const diff = buildDiff({
      codeSnippet: SNIPPET,
      snippetStartLine: 41,
      lineStart: 42,
      lineEnd: 42,
      suggestedCode: '    q = "SELECT * WHERE n = ?"\n    args = (name,)\n',
    })

    expect(rows(diff)).toEqual([
      'context 41 41 def find(name):',
      `removed 42 - ${SNIPPET.split('\n')[1]}`,
      'added - 42     q = "SELECT * WHERE n = ?"',
      'added - 43     args = (name,)',
      'context 43 44     return db.run(q)',
    ])
  })

  it('only marks the finding lines when there is no suggestion', () => {
    const diff = buildDiff({ codeSnippet: SNIPPET, snippetStartLine: 41, lineStart: 42, lineEnd: 43, suggestedCode: null })

    expect(diff.map((row) => [row.kind, row.oldNumber, row.newNumber])).toEqual([
      ['context', 41, 41],
      ['marked', 42, 42],
      ['marked', 43, 43],
    ])
  })

  it('adds the suggestion after the last line when the replaced lines end the snippet', () => {
    const diff = buildDiff({ codeSnippet: 'a\nb', snippetStartLine: 1, lineStart: 2, lineEnd: 2, suggestedCode: 'c' })

    expect(rows(diff)).toEqual(['context 1 1 a', 'removed 2 - b', 'added - 2 c'])
  })

  it('shows only the suggestion when the snippet is missing', () => {
    const diff = buildDiff({ codeSnippet: null, snippetStartLine: null, lineStart: 7, lineEnd: 7, suggestedCode: 'x\ny' })

    expect(rows(diff)).toEqual(['added - 7 x', 'added - 8 y'])
  })

  it('shows nothing for a finding without code', () => {
    expect(buildDiff({ codeSnippet: null, snippetStartLine: null, lineStart: null, lineEnd: null, suggestedCode: null })).toEqual([])
  })

  it('treats Windows line breaks like any other', () => {
    const diff = buildDiff({ codeSnippet: 'a\r\nb\r\n', snippetStartLine: 1, lineStart: null, lineEnd: null, suggestedCode: null })

    expect(rows(diff)).toEqual(['context 1 1 a', 'context 2 2 b'])
  })
})

describe('gutterWidth', () => {
  it('fits the largest line number', () => {
    const diff = buildDiff({ codeSnippet: 'a\nb', snippetStartLine: 999, lineStart: 1000, lineEnd: 1000, suggestedCode: 'c' })

    expect(gutterWidth(diff)).toBe(4)
  })

  it('is never narrower than two characters', () => {
    expect(gutterWidth(buildDiff({ codeSnippet: 'a', snippetStartLine: 1, lineStart: null, lineEnd: null, suggestedCode: null }))).toBe(2)
  })
})
