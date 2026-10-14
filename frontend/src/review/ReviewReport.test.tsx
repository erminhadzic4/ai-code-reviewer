import { fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReviewResult } from '../api/types'
import { writeToken } from '../auth/tokenStore'
import { fakeBackend, problem, type FakeAnswer } from '../test/api'
import { finding, result, REVIEW_ID, SHA, status } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const COMPLETED = status({
  status: 'COMPLETED',
  attempt: 1,
  branch: 'main',
  commitSha: SHA,
  finishedAt: new Date().toISOString(),
})

const REPORT = result({
  counts: { critical: 1, warnings: 2, codeSmells: 1, goodPractices: 1 },
  findings: [
    finding({ id: 1, level: 'WARNING', filePath: 'src/OrderService.java', lineStart: 77, lineEnd: 77, title: 'One query per order line' }),
    finding({
      id: 2,
      level: 'CRITICAL',
      category: 'SECURITY',
      filePath: 'src/UserRepository.java',
      lineStart: 42,
      lineEnd: 42,
      title: 'SQL query built from user input',
      explanation: 'The username goes straight into the query string.',
      suggestion: 'Use a parameterized query.',
      codeSnippet: 'find(String name) {\n  q = "..." + name;\n}',
      snippetStartLine: 41,
      suggestedCode: '  q = "... = ?";',
    }),
    finding({ id: 3, level: 'WARNING', filePath: 'src/OrderService.java', lineStart: 12, lineEnd: 14, title: 'Secret has a default value' }),
    finding({ id: 4, level: 'SMELL', filePath: 'src/OrderController.java', lineStart: 120, lineEnd: 260, title: 'Method is 140 lines long' }),
  ],
  goodPractices: [
    finding({ id: 9, level: 'GOOD_PRACTICE', severity: null, filePath: null, title: 'Clear layers', explanation: 'Controllers, services and repositories stay apart.' }),
  ],
})

function open(report: ReviewResult | FakeAnswer) {
  const answer = 'findings' in report ? { status: 200, body: report } : report
  return fakeBackend({
    'GET /api/auth/me': { status: 200, body: { id: 1, email: 'ana@example.com' } },
    [`GET /api/reviews/${REVIEW_ID}`]: { status: 200, body: COMPLETED },
    [`GET /api/reviews/${REVIEW_ID}/result`]: answer,
  })
}

describe('ReviewReport', () => {
  beforeEach(() => {
    localStorage.clear()
    writeToken('valid')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the scores with a verdict that matches them', async () => {
    open(REPORT)
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText('Solid, with things to fix.')).toBeInTheDocument()
    expect(screen.getByText('Fix the 1 critical issue first.')).toBeInTheDocument()
    const scores = screen.getByRole('region', { name: 'Scores' })
    expect(within(scores).getByText('82')).toBeInTheDocument()
    expect(within(scores).getByText('Security').nextSibling).toHaveTextContent('64')
    expect(screen.getByText(/main · 3f9a1c2 · 41 files · 3,120 lines · finished just now/)).toBeInTheDocument()
  })

  it('groups findings by file, the file with the critical one first, and opens that one', async () => {
    open(REPORT)
    renderApp(`/reviews/${REVIEW_ID}`)

    const groups = await screen.findAllByRole('heading', { level: 3 })
    expect(groups.map((heading) => heading.textContent)).toEqual([
      'src/UserRepository.java1 finding',
      'src/OrderService.java2 findings',
      'src/OrderController.java1 finding',
    ])
    const critical = screen.getByRole('button', { name: /SQL query built from user input/ })
    expect(critical).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('The username goes straight into the query string.')).toBeInTheDocument()
    // Inside a file the findings follow their lines.
    const service = within(groups[1].closest('section')!).getAllByRole('button')
    expect(service.map((button) => button.textContent)).toEqual([
      expect.stringContaining('Secret has a default value:12–14'),
      expect.stringContaining('One query per order line:77'),
    ])
  })

  it('opens and closes a finding in place', async () => {
    open(REPORT)
    renderApp(`/reviews/${REVIEW_ID}`)
    const row = await screen.findByRole('button', { name: /Method is 140 lines long/ })

    fireEvent.click(row)
    expect(row).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('figure', { name: 'Code in OrderController.java:120–260' })).toBeInTheDocument()

    fireEvent.click(row)
    expect(row).toHaveAttribute('aria-expanded', 'false')
  })

  it('filters by level with the counters on the filter buttons', async () => {
    open(REPORT)
    renderApp(`/reviews/${REVIEW_ID}`)
    const filters = within(await screen.findByRole('group', { name: 'Show findings' }))
    expect(filters.getByRole('button', { name: 'All 4' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(filters.getByRole('button', { name: 'Warnings 2' }))

    expect(filters.getByRole('button', { name: 'Warnings 2' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByText('SQL query built from user input')).not.toBeInTheDocument()
    expect(screen.getByText('One query per order line')).toBeInTheDocument()
    expect(screen.getByText('Secret has a default value')).toBeInTheDocument()
  })

  it('says when a filter has nothing to show', async () => {
    open({ ...REPORT, counts: { ...REPORT.counts, codeSmells: 0 }, findings: REPORT.findings.filter((f) => f.level !== 'SMELL') })
    renderApp(`/reviews/${REVIEW_ID}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Smells 0' }))

    expect(screen.getByText('No code smells.')).toBeInTheDocument()
  })

  it('copies the suggested code', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    open(REPORT)
    renderApp(`/reviews/${REVIEW_ID}`)

    fireEvent.click(await screen.findByRole('button', { name: 'Copy fix' }))

    expect(await screen.findByText('Copied to the clipboard.')).toBeInTheDocument()
    expect(writeText).toHaveBeenCalledWith('  q = "... = ?";')
  })

  it('lists what is working', async () => {
    open(REPORT)
    renderApp(`/reviews/${REVIEW_ID}`)

    const good = await screen.findByRole('region', { name: 'What is working' })
    expect(within(good).getByText('Clear layers')).toBeInTheDocument()
  })

  it('says a report is partial and that findings were left out', async () => {
    open({ ...REPORT, stats: { ...REPORT.stats, filesFailed: 2, findingsDiscarded: 1 } })
    renderApp(`/reviews/${REVIEW_ID}`)

    const note = await screen.findByRole('note')
    expect(note).toHaveTextContent('Partial report: 2 files could not be reviewed and are not in the scores.')
    expect(note).toHaveTextContent('1 finding pointed at code that does not exist and was left out.')
  })

  it('says so when there is nothing to fix', async () => {
    open(result())
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText('No issues found in 41 files.')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Show findings' })).not.toBeInTheDocument()
  })

  it('renders a long report in part until asked for the rest', async () => {
    const many = Array.from({ length: 120 }, (_, i) =>
      finding({ id: i + 1, level: 'SMELL', filePath: `src/File${String(i).padStart(3, '0')}.java`, title: `Smell ${i + 1}` }),
    )
    open(result({ counts: { critical: 0, warnings: 0, codeSmells: 120, goodPractices: 0 }, findings: many }))
    renderApp(`/reviews/${REVIEW_ID}`)

    const showAll = await screen.findByRole('button', { name: 'Show all 120 findings' })
    expect(screen.getAllByRole('button', { expanded: false })).toHaveLength(100)

    fireEvent.click(showAll)

    expect(screen.getByText('Smell 120')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Show all/ })).not.toBeInTheDocument()
  })

  it('offers to load the report again when it fails', async () => {
    open(problem(500, 'An unexpected error occurred'))
    renderApp(`/reviews/${REVIEW_ID}`)

    expect(await screen.findByText('The server ran into a problem. Try again in a moment.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
