import type { Finding, FindingLevel } from '../api/types'

export type Filter = 'ALL' | 'CRITICAL' | 'WARNING' | 'SMELL'

export interface FileGroup {
  /** null for findings about the repository as a whole. */
  filePath: string | null
  findings: Finding[]
}

const RANK: Record<FindingLevel, number> = { CRITICAL: 0, WARNING: 1, SMELL: 2, GOOD_PRACTICE: 3 }

export function applyFilter(findings: Finding[], filter: Filter): Finding[] {
  return filter === 'ALL' ? findings : findings.filter((finding) => finding.level === filter)
}

/**
 * Groups findings by file. Files with the most severe finding come first, then by path;
 * findings without a file come last. Inside a file, findings follow their line, and
 * findings without a line come after the others.
 */
export function groupByFile(findings: Finding[]): FileGroup[] {
  const byPath = new Map<string | null, Finding[]>()
  for (const finding of findings) {
    const list = byPath.get(finding.filePath) ?? []
    list.push(finding)
    byPath.set(finding.filePath, list)
  }

  const groups = [...byPath].map(([filePath, list]) => ({
    filePath,
    findings: [...list].sort(
      (a, b) =>
        (a.lineStart ?? Number.MAX_SAFE_INTEGER) - (b.lineStart ?? Number.MAX_SAFE_INTEGER) ||
        RANK[a.level] - RANK[b.level] ||
        a.id - b.id,
    ),
  }))

  const worst = (group: FileGroup) => Math.min(...group.findings.map((finding) => RANK[finding.level]))
  return groups.sort((a, b) => {
    if (a.filePath === null || b.filePath === null) {
      return a.filePath === null ? (b.filePath === null ? 0 : 1) : -1
    }
    return worst(a) - worst(b) || a.filePath.localeCompare(b.filePath)
  })
}

/** ":42" or ":42–48", the way an editor names a place in a file. */
export function lineLabel(finding: Pick<Finding, 'lineStart' | 'lineEnd'>): string {
  const { lineStart, lineEnd } = finding
  if (lineStart === null) {
    return ''
  }
  return lineEnd !== null && lineEnd !== lineStart ? `:${lineStart}–${lineEnd}` : `:${lineStart}`
}
