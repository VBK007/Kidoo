# Kido admin console agent guidance

## Project context
- This is a Vite + React + TypeScript admin dashboard for the server-side `/api/admin` endpoints.
- The app is intentionally read-only; it should never call mutating APIs or change server state.
- Authentication is split across a parent account and an admin key. Keep both in `sessionStorage` only.
- The UI should be typed and conservative. Prefer small, focused changes over broad refactors.

## Working conventions
- Keep the current architecture: shared helpers in `src/format.ts`, API contracts in `src/api.ts`, and page-level concerns in `src/components/*`.
- Preserve existing dark-mode and dashboard styling patterns unless the task explicitly calls for a redesign.
- Respect the app's explicit security model: no admin key should be persisted beyond the current tab session.
- Prefer explicit TypeScript types and avoid introducing runtime-only casts when simple typing is possible.

## Validation
Before claiming the change is ready, run:
- `npm run lint`
- `npm run build`

If a task depends on the backend, assume the API is available on `http://localhost:8080` unless the environment variable `KIDO_API` or `VITE_API_BASE` is set.
