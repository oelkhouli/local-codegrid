# 9. Authentication, authorization and request limits

Passwords are hashed with BCrypt through Spring Security's maintained implementation. The application does not implement its own cryptographic primitive. Login creates a random session token; only its SHA-256 hash is stored in PostgreSQL. The browser receives an HttpOnly, SameSite=Strict cookie with an eight-hour expiry.

A separate CSRF value is returned to the authenticated UI and required for state-changing requests. The server also rejects an unexpected Origin. Cookies are scoped to the local deployment; enable Secure cookies when deploying behind HTTPS. The default listener binds to loopback and is not a public service.

Authentication answers who is calling. Authorization checks what that caller may access. Job reads, cancellation and WebSocket handshakes verify ownership. A guessed UUID must not expose another user's source, result or logs. Administrator-only endpoints manage nodes and read the audit trail.

Redis token buckets use server time inside one Lua script. Reading a bucket and updating it in separate commands would race across API replicas. Request frequency, outstanding-job quota, maximum request bytes and physical execution reservations are independent controls.

Audit records contain action, actor and target identifiers, not passwords or source code. The application database role is not a PostgreSQL superuser. It still owns application tables to support this small deployment's Flyway migrations, so the audit table is not tamper-proof against a compromised database role. A stronger design separates migration credentials and sends audits to a separate protected store.

**Exercise:** identify the exact ownership check for a job URL and a WebSocket. Test a missing CSRF token, an incorrect Origin, an unauthenticated internal request and a second user's job ID.

**Explain it:** “A private UI does not replace authorization. Every path to protected data checks the account, including the streaming path.”

Read `AuthService`, `ApiSecurity`, the HTTP integration test and `SECURITY.md` together.
