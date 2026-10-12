# Roadmap

Rule: one milestone = one branch = one PR. Check items off when merged.
Scope for now: runs locally. Public deployment is a later option (see "Later").

## Day 0: foundations
- [x] Monorepo created (`backend/`, `frontend/`, `docs/`), first commit
- [x] `ROADMAP.md`, `DECISIONS.md` in place
- [x] Architecture plan saved to `docs/ARCHITECTURE.md`
- [x] Spring Boot skeleton with health endpoint
- [x] Vite + React + TypeScript skeleton
- [x] CI builds and tests backend and frontend

## V1: working review of a public repository
- [x] M1 Auth: registration, login, JWT, protected endpoints, tests
- [x] M2 GitHub client: file tree and contents for a public repo; filtering of
      `node_modules`, `target`, binaries, lock files, generated code; handle rate limits
- [x] M3 Review domain (tables and entities for reviews, analyses and findings) and
      `CodeReviewProvider` interface with a fake provider returning fixed findings
      (lets M5 to M7 work without AI calls)
- [ ] M4 Gemini provider: prompt, JSON schema, validation, line-number verification,
      chunking of large repos
- [ ] M5 Async jobs: submit returns a job id, status polling, error handling, retry
- [ ] M6 Scoring formula, saving analyses and findings, cache by commit SHA
- [ ] M7 Frontend, following the approved frontend design mockup (dark theme: landing page
      and review report): login/register, submit form, progress screen, report dashboard,
      issue list with filters and diff-style suggestions
- [ ] M8 Polish: Swagger, one-command local run (docker-compose, `.env.example`),
      error states, demo GIF, README, tag `v1.0`

## V2: deeper analysis
- [ ] Review of a single file with comments next to lines
- [ ] Dedicated security pass (hardcoded secrets, injection, unsafe dependencies)
- [ ] Complexity analysis (longest or most complex functions, duplication)
- [ ] Optional: C++ specific checks (leaks, RAII violations, raw pointers)
- [ ] Review history per repository with score over time, export to Markdown/JSON
- [ ] Tag `v2.0`

## V3: GitHub App
- [ ] GitHub App authentication (signed JWT, installation tokens)
- [ ] Webhook endpoint with signature verification
- [ ] Bot posts a summary and line comments on Pull Requests
- [ ] Optional status check that fails on critical findings
- [ ] Repo config file (`.codereview.yml`): ignore paths, focus areas
- [ ] Decide how the public webhook URL is provided at no cost (tunnel or free hosting)
- [ ] Tag `v3.0`

## Later: optional public deployment
Only if the project is published as a live demo. The free Gemini quota is shared by all
users of one key, so a public version needs protection first.
- [ ] Per-user usage limit (reviews per day)
- [ ] Global daily budget with a clear "capacity reached, try tomorrow" state
- [ ] Rate limiting per IP, email verification, free CAPTCHA on registration
- [ ] Pre-generated example reviews of well-known repositories on the landing page
- [ ] Optional bring-your-own-key: user supplies their own Gemini key
- [ ] Free hosting decision (cold starts, limits); Ollama stays local-only
- [ ] Paid advanced feature / plans (design for it, do not build until decided)

## Other ideas (not committed)
- [ ] Private repositories via GitHub OAuth
- [ ] Additional providers (OpenAI)
