import type { ReviewResult } from '../api/types'

export interface Verdict {
  headline: string
  detail: string
}

function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

/**
 * The short verdict next to the overall score. It is chosen from fixed templates by the
 * score and the counters, never written by the model, so it always agrees with the numbers.
 */
export function verdict({ scores, counts }: Pick<ReviewResult, 'scores' | 'counts'>): Verdict {
  const score = scores.overall
  const headline =
    score >= 90
      ? 'In good shape.'
      : score >= 75
        ? 'Solid, with things to fix.'
        : score >= 50
          ? 'Needs work.'
          : 'Needs serious attention.'

  let detail: string
  if (counts.critical > 0) {
    detail = `Fix the ${count(counts.critical, 'critical issue', 'critical issues')} first.`
  } else if (counts.warnings > 0) {
    detail = `No critical issues. Start with the ${count(counts.warnings, 'warning', 'warnings')}.`
  } else if (counts.codeSmells > 0) {
    detail = `No critical issues or warnings; ${count(counts.codeSmells, 'code smell', 'code smells')} left.`
  } else {
    detail = 'No issues found.'
  }
  return { headline, detail }
}
