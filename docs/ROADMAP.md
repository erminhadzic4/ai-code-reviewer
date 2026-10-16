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
      (lets M6 and M7 work without AI calls)
- [x] M4 Gemini provider: prompt, JSON schema, validation, line-number verification,
      chunking of large repos
- [x] M5 Scoring formula: scores computed from the findings by a pure function
- [x] M6 Async jobs: submit returns a job id, status polling, error handling, retry,
      saving analyses and findings, cache by commit SHA
- [x] M7 Frontend, following the approved frontend design mockup (dark theme: landing page
      and review report): login/register, submit form, progress screen, report dashboard,
      issue list with filters and diff-style suggestions
- [x] M7.5 Frontend redesign: restyle the frontend to the second approved design (dark gradient
      background, floating pill navigation, framed cards, interactive 3D preview on a new review
      screen). Visual changes and one new screen; routes, API calls, polling and auth behavior
      stay as they are, existing tests stay green, accessibility is kept (focus ring, skip link,
      reduced motion)
- [ ] M8 Polish
  - [x] API documentation with Swagger (springdoc)
  - [x] One-command local run: Dockerfiles, `docker compose`, `.env.example`; images built in CI
  - [x] Error states: unreachable backend, expired session, links from the failure screen
  - [x] Version 1.0.0, favicon matching the navigation logo
  - [x] Real review with Gemini on a small public repository
  - [x] Demo GIF
  - [x] README
  - [ ] Tag `v1.0`

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
- [ ] Turn off the API documentation (`springdoc.api-docs.enabled=false`,
      `springdoc.swagger-ui.enabled=false`)
- [ ] Paid advanced feature / plans (design for it, do not build until decided)

## Other ideas (not committed)
- [ ] Private repositories via GitHub OAuth
- [ ] Additional providers (OpenAI)
- [ ] Skip generated resource files such as `.resx` in the file filter if they become a
      problem: in the first real review they took more than a third of the input and drew no findings
