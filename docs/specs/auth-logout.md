# Logout: family-wide revoke

A **refresh family** is one login: a UUID (`refresh_tokens.family_id`) created at register or login and kept across
every rotation.

`POST /auth/logout {refreshToken}` (with an access token):

- **Found.** If the presented token belongs to the caller, every token in its family is revoked, current or already
  rotated. Presenting an old, rotated token works the same as presenting the current one.
- **Other families.** Other families of the same user (other devices or logins) are untouched.
- **Idempotent.** An unknown token, another user's token, or an already-revoked family returns `204` and changes
  nothing else. The `401` for a missing or invalid access token is unchanged.
- **Access tokens.** They are stateless and stay valid until expiry (15 min by default).

**Refresh after logout.** `POST /auth/refresh` with any token of the logged-out family is
`401 {code: "REFRESH_TOKEN_REUSED"}`. That is the same response a single-token logout gave before. The family is already
fully revoked, so the reuse path's family revoke changes nothing. **Status codes.** `/auth/refresh` returns HTTP `401` for every rejected refresh token: revoked by logout, reused after
rotation, unknown or expired. It never returns `400` or `403` for these. The Android client treats only a `401` from
refresh as "session over" and goes to the login screen. Tests pin each case.

Planned (docs/specs/notification-sending.md): logout will also delete the family's notification devices.
