import { describe, expect, it } from 'vitest'
import { finding } from '../test/fixtures'
import { applyFilter, groupByFile, lineLabel } from './findings'

describe('groupByFile', () => {
  it('puts the file with the most severe finding first, then sorts by path', () => {
    const groups = groupByFile([
      finding({ id: 1, filePath: 'b/Smells.java', level: 'SMELL' }),
      finding({ id: 2, filePath: 'z/Critical.java', level: 'CRITICAL' }),
      finding({ id: 3, filePath: 'a/Smells.java', level: 'SMELL' }),
      finding({ id: 4, filePath: 'm/Warning.java', level: 'WARNING' }),
    ])

    expect(groups.map((group) => group.filePath)).toEqual([
      'z/Critical.java',
      'm/Warning.java',
      'a/Smells.java',
      'b/Smells.java',
    ])
  })

  it('orders the findings of a file by line, those without a line last', () => {
    const [group] = groupByFile([
      finding({ id: 1, lineStart: null, lineEnd: null }),
      finding({ id: 2, lineStart: 80 }),
      finding({ id: 3, lineStart: 12 }),
    ])

    expect(group.findings.map((f) => f.id)).toEqual([3, 2, 1])
  })

  it('keeps findings about the whole repository in a group of their own at the end', () => {
    const groups = groupByFile([
      finding({ id: 1, filePath: null, level: 'CRITICAL' }),
      finding({ id: 2, filePath: 'App.java', level: 'SMELL' }),
    ])

    expect(groups.map((group) => group.filePath)).toEqual(['App.java', null])
  })

  it('returns no groups for no findings', () => {
    expect(groupByFile([])).toEqual([])
  })
})

describe('applyFilter', () => {
  const all = [finding({ id: 1, level: 'CRITICAL' }), finding({ id: 2, level: 'WARNING' }), finding({ id: 3, level: 'SMELL' })]

  it('keeps every finding for All', () => {
    expect(applyFilter(all, 'ALL')).toHaveLength(3)
  })

  it('keeps one level', () => {
    expect(applyFilter(all, 'WARNING').map((f) => f.id)).toEqual([2])
  })
})

describe('lineLabel', () => {
  it('names one line or a range', () => {
    expect(lineLabel({ lineStart: 42, lineEnd: 42 })).toBe(':42')
    expect(lineLabel({ lineStart: 42, lineEnd: null })).toBe(':42')
    expect(lineLabel({ lineStart: 42, lineEnd: 48 })).toBe(':42–48')
    expect(lineLabel({ lineStart: null, lineEnd: null })).toBe('')
  })
})
