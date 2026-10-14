import { describe, expect, it } from 'vitest'
import { verdict } from './verdict'

function counts(critical = 0, warnings = 0, codeSmells = 0) {
  return { critical, warnings, codeSmells, goodPractices: 0 }
}

function scores(overall: number) {
  return { overall, security: overall, maintainability: overall, performance: overall }
}

describe('verdict', () => {
  it.each([
    [100, 'In good shape.'],
    [90, 'In good shape.'],
    [89, 'Solid, with things to fix.'],
    [75, 'Solid, with things to fix.'],
    [74, 'Needs work.'],
    [50, 'Needs work.'],
    [49, 'Needs serious attention.'],
    [0, 'Needs serious attention.'],
  ])('a score of %i reads "%s"', (score, headline) => {
    expect(verdict({ scores: scores(score), counts: counts() }).headline).toBe(headline)
  })

  it('puts critical issues first', () => {
    expect(verdict({ scores: scores(82), counts: counts(1, 4, 7) }).detail).toBe('Fix the 1 critical issue first.')
    expect(verdict({ scores: scores(40), counts: counts(3) }).detail).toBe('Fix the 3 critical issues first.')
  })

  it('points at warnings when there is nothing critical', () => {
    expect(verdict({ scores: scores(88), counts: counts(0, 2, 5) }).detail).toBe(
      'No critical issues. Start with the 2 warnings.',
    )
  })

  it('names the code smells when they are all that is left', () => {
    expect(verdict({ scores: scores(95), counts: counts(0, 0, 1) }).detail).toBe(
      'No critical issues or warnings; 1 code smell left.',
    )
  })

  it('says so when there is nothing to fix', () => {
    expect(verdict({ scores: scores(100), counts: counts() })).toEqual({
      headline: 'In good shape.',
      detail: 'No issues found.',
    })
  })
})
