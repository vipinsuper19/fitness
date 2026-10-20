# Fitness

Android fitness app with a free-tier backend: workout plans and tracking, manual nutrition logging, goals, progress and an XP ledger. Lean MVP first (PRD P0).

## Layout
```
android/    Kotlin + Jetpack Compose app (Gradle multi-module)
backend/    NestJS + Prisma API (PostgreSQL)
contracts/  OpenAPI spec (source of truth) and JSON schemas
.github/workflows/  path-filtered CI for android and backend
```

## Build
- Android (JDK 21, Android SDK): `cd android && ./gradlew assembleDebug testDebugUnitTest`
- Backend (Node 20+): `cd backend && npm ci && npm test && npm run build`

Constraints: free tiers only, no secrets in the repo, no Play Store publishing.
