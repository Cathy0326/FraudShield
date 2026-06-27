# Security

This document records security issues found in this project, how they were
diagnosed, fixed, and what would catch them earlier next time. Kept here
rather than buried in commit messages because the reasoning is the
reusable part — the bug itself is project-specific.

## Reporting

This is a personal/educational project. If you find an issue, open an
issue or PR rather than a public disclosure.

---

## Incidents

### 1. Privilege escalation via `/auth/register` (fixed in [#21](https://github.com/Cathy0326/FraudShield/pull/21))

**What:** `POST /auth/register` is a `permitAll()` endpoint (anyone can
self-register — by design, for normal user signup). The endpoint accepted
a caller-supplied `role` field with no restriction:

```java
// RegisterRequest.java
private String username;
private String password;
private String role;   // caller-controlled, unchecked
```

```java
// AuthController.java (before)
@PostMapping("/register")
public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
    // TODO: restrict ROLE_ADMIN registration to existing admins in production
    AppUser user = authService.register(
            request.getUsername(), request.getPassword(), request.getRole());
    ...
}
```

Anyone could `POST /auth/register {"username":"x","password":"y","role":"ROLE_ADMIN"}`
and receive a fully privileged admin account — no authentication required.
The `TODO` comment shows this was a known gap, never closed.

**How it was found:** a deliberate pass over every `permitAll()` route in
`SecurityConfig`, asking "what's the worst input an anonymous caller could
send here, and does anything downstream trust it." `/auth/login` is fine
(credentials are verified against stored hashes); `/auth/register` was the
outlier — the one endpoint that both accepts unauthenticated input *and*
writes a privilege level.

**The fix — and the interesting part:** the endpoint has to stay public
for normal signup, so the fix can't simply require authentication on the
whole route. Instead it asks a narrower question: *is this specific
caller already an admin?* `/auth/register` being `permitAll()` only means
Spring won't reject the request before it reaches the controller — it does
**not** mean `SecurityContextHolder` is empty. `JwtAuthenticationFilter`
runs on every request regardless of the endpoint's authorization
requirement (auth rules are evaluated later in the filter chain), and
populates the security context whenever a valid Bearer token is present.
So a logged-in admin calling this same endpoint is distinguishable from an
anonymous walk-up caller, even though both requests are "publicly
permitted":

```java
@PostMapping("/register")
public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest request) {
    if (ADMIN_ROLE.equals(request.getRole()) && !callerIsAdmin()) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an existing admin can register a new admin account");
    }
    AppUser user = authService.register(
            request.getUsername(), request.getPassword(), request.getRole());
    ...
}

private boolean callerIsAdmin() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> ADMIN_ROLE.equals(a.getAuthority()));
}
```

Result: anonymous self-signup for normal roles still works unauthenticated
(unchanged behavior); requesting `ROLE_ADMIN` now requires sending a valid
admin JWT, otherwise `403 Forbidden`.

**What would have caught this sooner:** a test asserting that
`POST /auth/register` with `role=ROLE_ADMIN` and no `Authorization` header
returns `403`. None existed — `permitAll()` routes had no negative-path
tests at all, only happy-path login tests. General lesson: every
`permitAll()` endpoint that performs a privileged write needs its own
"can an anonymous caller abuse this" test, not just a happy-path test.

---

### 2. Leaked Azure Event Hubs root connection string

**What:** the Kafka-compatible Event Hubs connection used the namespace's
**RootManageSharedAccessKey** — a key with full manage/send/listen rights
over every Event Hub in the namespace — passed directly as the Kafka SASL
password (`KAFKA_SASL_JAAS_CONFIG`). This key briefly existed in a context
where it could have been exposed (env var visible in deployment tooling
output / shell history during manual Azure setup).

**Why root keys are the wrong tool here:** the application only ever needs
to *send and receive* on one specific Event Hub (`order-events`) — it never
needs to manage the namespace, create new Event Hubs, or touch any other
hub that might later share the namespace. A root key is a single
credential whose blast radius is "every Event Hub this namespace will ever
contain, including ones that don't exist yet." Using it for a single
microservice violates least privilege for no actual benefit — the
narrower key is no harder to wire up.

**The fix:**
1. Created a new Shared Access Policy scoped to the single
   `order-events` Event Hub, granted only `Send` + `Listen` (no `Manage`).
2. Rotated `KAFKA_SASL_JAAS_CONFIG` (in the Azure Container App secret and
   local `.env`) to the new policy's connection string.
3. Regenerated/revoked the old **RootManageSharedAccessKey** primary key
   at the namespace level, invalidating the leaked credential immediately
   rather than waiting for a future rotation cycle.
4. Re-ran the deployment and confirmed producer/consumer connectivity
   end-to-end with the new key before considering the rotation complete.

**What would have caught this sooner:** scoped per-hub SAS policies should
have been the default from the first Event Hubs setup, not a retrofit —
root keys are the path of least resistance when following Azure's quick-
start docs, but quick-start defaults optimize for "get it working," not
for production blast radius. General lesson: when wiring a new managed
service for the first time, the question to ask isn't "what credential
lets this work" but "what's the narrowest credential that lets this
work" — the secure version is rarely more code, just a different default
to reach for.

---

## General takeaways

- **`permitAll()` is not the same as "no security logic needed here."** It
  means "Spring won't block this before the controller" — anything the
  controller does with caller input still needs to reason about trust.
  `SecurityContextHolder` can still be populated on a public route if the
  caller sent a valid token, and that's a legitimate way to distinguish
  "anonymous" from "authenticated-but-on-a-public-endpoint" without
  weakening the endpoint's public access.
- **Negative-path tests matter as much as happy-path tests for security-
  sensitive endpoints.** "Login works" and "an anonymous caller can't
  mint an admin account" are both required, and the second one is easy to
  forget because nothing breaks in normal use until someone tries to
  exploit it.
- **Default to the narrowest credential a service actually needs**, not
  the one fastest to copy from a quick-start guide. The extra setup cost
  for a scoped key vs. a root key is minutes; the blast-radius difference
  if either leaks is the whole namespace vs. one queue.
