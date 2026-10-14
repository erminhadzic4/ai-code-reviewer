import type { Finding } from '../api/types'

export type DiffRowKind = 'context' | 'marked' | 'removed' | 'added'

/**
 * One line of the diff view. A line has a number in the old file, the new file or both:
 * a removed line exists only in the old one, an added line only in the new one.
 */
export interface DiffRow {
  kind: DiffRowKind
  oldNumber: number | null
  newNumber: number | null
  text: string
}

type DiffSource = Pick<Finding, 'codeSnippet' | 'snippetStartLine' | 'lineStart' | 'lineEnd' | 'suggestedCode'>

/**
 * Builds the rows that show a finding in its file. The snippet is the code around the
 * finding as the backend took it from the file. With suggested code, the lines
 * lineStart..lineEnd are shown as removed and the suggestion as added in their place;
 * without it, those lines are only marked.
 */
export function buildDiff(source: DiffSource): DiffRow[] {
  const suggested = source.suggestedCode === null ? null : splitLines(source.suggestedCode)
  const { lineStart, lineEnd } = source

  if (source.codeSnippet === null || source.snippetStartLine === null) {
    return (suggested ?? []).map((text, i) => ({
      kind: 'added',
      oldNumber: null,
      newNumber: lineStart === null ? null : lineStart + i,
      text,
    }))
  }

  const snippet = splitLines(source.codeSnippet)
  const first = lineStart ?? Number.POSITIVE_INFINITY
  const last = lineEnd ?? lineStart ?? Number.NEGATIVE_INFINITY
  // How far the numbers of the new file move after the replaced lines.
  const shift = suggested === null ? 0 : suggested.length - (last - first + 1)

  const rows: DiffRow[] = []
  let addedInserted = false
  const insertSuggestion = () => {
    if (suggested !== null && !addedInserted) {
      suggested.forEach((text, i) => rows.push({ kind: 'added', oldNumber: null, newNumber: first + i, text }))
      addedInserted = true
    }
  }

  snippet.forEach((text, i) => {
    const number = source.snippetStartLine! + i
    if (number < first) {
      rows.push({ kind: 'context', oldNumber: number, newNumber: number, text })
    } else if (number <= last) {
      rows.push(
        suggested === null
          ? { kind: 'marked', oldNumber: number, newNumber: number, text }
          : { kind: 'removed', oldNumber: number, newNumber: null, text },
      )
    } else {
      insertSuggestion()
      rows.push({ kind: 'context', oldNumber: number, newNumber: number + shift, text })
    }
  })
  // The replaced lines reach the end of the snippet, or lie beyond it.
  insertSuggestion()
  return rows
}

/** Width of a line-number column, in characters, for the largest number shown. */
export function gutterWidth(rows: DiffRow[]): number {
  const largest = Math.max(0, ...rows.flatMap((row) => [row.oldNumber ?? 0, row.newNumber ?? 0]))
  return Math.max(2, String(largest).length)
}

function splitLines(code: string): string[] {
  const lines = code.replace(/\r\n?/g, '\n').split('\n')
  // A final line break ends the last line; it does not start another one.
  if (lines.length > 1 && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}
