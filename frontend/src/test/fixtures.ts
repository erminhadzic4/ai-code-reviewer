import type { Finding, ReviewResult, ReviewStatusResponse } from '../api/types'

export const REVIEW_ID = '7b0c6a52-1c1e-4bb5-9c4a-3f1d2a9e8c11'
export const SHA = '3f9a1c2e8b7d6c5a4f3e2d1c0b9a8f7e6d5c4b3a'

export function status(overrides: Partial<ReviewStatusResponse> = {}): ReviewStatusResponse {
  return {
    id: REVIEW_ID,
    repo: 'octo/orders-service',
    branch: null,
    commitSha: null,
    status: 'QUEUED',
    stage: null,
    chunksDone: 0,
    chunksTotal: 0,
    attempt: 0,
    maxAttempts: 3,
    retryAt: null,
    cacheHit: false,
    createdAt: '2026-10-14T09:00:00Z',
    startedAt: null,
    finishedAt: null,
    error: null,
    ...overrides,
  }
}

export function finding(overrides: Partial<Finding> = {}): Finding {
  return {
    id: 1,
    level: 'WARNING',
    category: 'MAINTAINABILITY',
    severity: 'MEDIUM',
    filePath: 'src/OrderService.java',
    lineStart: 10,
    lineEnd: 10,
    title: 'A finding',
    explanation: 'Why it matters.',
    suggestion: 'What to do.',
    suggestedCode: null,
    codeSnippet: 'line nine\nline ten\nline eleven',
    snippetStartLine: 9,
    ...overrides,
  }
}

export function result(overrides: Partial<ReviewResult> = {}): ReviewResult {
  return {
    scores: { overall: 82, security: 64, maintainability: 88, performance: 91 },
    counts: { critical: 0, warnings: 0, codeSmells: 0, goodPractices: 0 },
    stats: { filesAnalyzed: 41, filesSkipped: 3, filesFailed: 0, linesAnalyzed: 3120, findingsDiscarded: 0 },
    findings: [],
    goodPractices: [],
    ...overrides,
  }
}
