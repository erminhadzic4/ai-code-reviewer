// Shapes of the backend's JSON, as its DTOs define them.

export interface User {
  id: number
  email: string
}

export interface AuthResponse {
  accessToken: string
  /** Lifetime of the token in seconds. */
  expiresIn: number
  user: User
}

export type ReviewStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type ReviewStage = 'FETCHING' | 'ANALYZING' | 'SCORING'

export interface ReviewError {
  /** A stable name of the failure, such as REPO_NOT_FOUND. */
  code: string
  message: string
}

export interface ReviewStatusResponse {
  id: string
  /** owner/name as it was submitted. */
  repo: string
  branch: string | null
  commitSha: string | null
  status: ReviewStatus
  stage: ReviewStage | null
  chunksDone: number
  chunksTotal: number
  attempt: number
  maxAttempts: number
  retryAt: string | null
  cacheHit: boolean
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  /** Set for a failed review, and for a queued one whose last attempt failed. */
  error: ReviewError | null
}

export type FindingLevel = 'CRITICAL' | 'WARNING' | 'SMELL' | 'GOOD_PRACTICE'
export type FindingCategory = 'SECURITY' | 'MAINTAINABILITY' | 'PERFORMANCE'
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'

export interface Finding {
  id: number
  level: FindingLevel
  category: FindingCategory
  severity: Severity | null
  filePath: string | null
  lineStart: number | null
  lineEnd: number | null
  title: string
  explanation: string | null
  suggestion: string | null
  /** Replacement for the lines lineStart..lineEnd. */
  suggestedCode: string | null
  /** The lines the finding covers with their context, taken from the file. */
  codeSnippet: string | null
  snippetStartLine: number | null
}

export interface ReviewResult {
  scores: { overall: number; security: number; maintainability: number; performance: number }
  counts: { critical: number; warnings: number; codeSmells: number; goodPractices: number }
  stats: {
    filesAnalyzed: number
    filesSkipped: number
    filesFailed: number
    linesAnalyzed: number
    findingsDiscarded: number
  }
  findings: Finding[]
  goodPractices: Finding[]
}

export interface ReviewSummary {
  id: string
  repo: string
  branch: string | null
  commitSha: string | null
  status: ReviewStatus
  overallScore: number | null
  createdAt: string
  finishedAt: string | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
}
