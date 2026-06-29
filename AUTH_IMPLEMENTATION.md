# Authentication & Authorization — Implementation Deep Dive

**Last updated:** 2026-06-25

This document explains, in full detail, how every piece of the authentication and authorization mechanism was implemented, how the pieces connect at runtime, and what happens at each step of a request.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [The IAM Service (Authorization Server)](#2-the-iam-service-authorization-server)
   - 2.1 [Database Schema & Entities](#21-database-schema--entities)
   - 2.2 [User Authentication (UserDetailsService)](#22-user-authentication-userdetailsservice)
   - 2.3 [OAuth2 Protocol Filter Chain (Filter Chain #1)](#23-oauth2-protocol-filter-chain-filter-chain-1)
   - 2.4 [Default Security Filter Chain (Filter Chain #2)](#24-default-security-filter-chain-filter-chain-2)
   - 2.5 [RSA Key Pair & JWKS Endpoint](#25-rsa-key-pair--jwks-endpoint)
   - 2.6 [Token Customizer — Custom JWT Claims](#26-token-customizer--custom-jwt-claims)
   - 2.7 [Registered OAuth2 Clients](#27-registered-oauth2-clients)
   - 2.8 [Bootstrap Data Seeding](#28-bootstrap-data-seeding)
   - 2.9 [User Management REST API](#29-user-management-rest-api)
3. [Shared Role Mapping (iot-common)](#3-shared-role-mapping-iot-common)
4. [Resource Server Configuration](#4-resource-server-configuration)
   - 4.1 [Gateway Service](#41-gateway-service)
   - 4.2 [Processing Service](#42-processing-service)
   - 4.3 [Sensor Simulator Service](#43-sensor-simulator-service)
   - 4.4 [Unsecured Services (Known Gap)](#44-unsecured-services-known-gap)
5. [Runtime Flows — Step by Step](#5-runtime-flows--step-by-step)
   - 5.1 [Flow A: Dashboard User Login (Authorization Code + PKCE)](#51-flow-a-dashboard-user-login-authorization-code--pkce)
   - 5.2 [Flow B: Service-to-Service (Client Credentials)](#52-flow-b-service-to-service-client-credentials)
   - 5.3 [Flow C: API Request with Bearer Token](#53-flow-c-api-request-with-bearer-token)
6. [RBAC Matrix — Complete Endpoint Rules](#6-rbac-matrix--complete-endpoint-rules)
7. [Configuration & Wiring](#7-configuration--wiring)
8. [Test Strategy](#8-test-strategy)
9. [Spring Boot 4 / Security 7 Adaptations](#9-spring-boot-4--security-7-adaptations)
10. [Security Design Decisions](#10-security-design-decisions)

---

## 1. Architecture Overview

The system uses **OAuth 2.1 / OpenID Connect** with **RS256-signed JWTs** for stateless authentication. There are two logical roles in the architecture:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        AUTHORIZATION SERVER                             │
│                                                                          │
│   iam-service (port 9000)                                                │
│   ┌──────────────────────────────────────────────────────────────────┐   │
│   │  Spring Authorization Server                                      │   │
│   │  • Issues RS256-signed JWTs at POST /oauth2/token                 │   │
│   │  • Publishes public keys at GET /oauth2/jwks                      │   │
│   │  • Hosts Authorization Code + PKCE flow at GET /oauth2/authorize  │   │
│   │  • Manages users/roles in PostgreSQL (smarthome_iam DB)           │   │
│   │  • Adds custom "roles"/"username"/"email" claims to access tokens │   │
│   └──────────────────────────────────────────────────────────────────┘   │
└──────────────────────────┬───────────────────────────────────────────────┘
                           │
          Token issuance   │   JWKS download (lazy, cached)
                           │
┌──────────────────────────▼───────────────────────────────────────────────┐
│                        RESOURCE SERVERS                                   │
│                                                                           │
│   gateway-service (8082)  │  processing-service (8083)                    │
│   simulator-service (8081)│                                               │
│   ┌───────────────────────────────────────────────────────────────────┐   │
│   │  spring-boot-starter-oauth2-resource-server                       │   │
│   │  • Downloads IAM's JWKS on first request, caches & auto-refreshes │   │
│   │  • Validates RS256 signature, expiry, issuer on every HTTP request│   │
│   │  • Maps JWT "roles" claim → ROLE_* authorities (JwtAuthConverter) │   │
│   │  • SecurityFilterChain enforces hasRole() / hasAnyRole() rules    │   │
│   │  • Stateless: no sessions, no CSRF                                │   │
│   └───────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
```

**Key principle:** Resource servers never contact the IAM service at request time. They download the IAM's public key set once (lazily, on the first inbound JWT), cache it, and validate signatures locally. This means there is no runtime coupling — if IAM goes down, services with cached keys continue to validate tokens.

---

## 2. The IAM Service (Authorization Server)

**Module:** `iam-service` · **Port:** 9000 · **Package:** `com.smarthome.iam`

The IAM service is built on **Spring Authorization Server** (part of Spring Security 7.1) and acts as the single identity provider for the entire platform. It is a standalone Spring Boot 4.0.1 application with its own PostgreSQL database (`smarthome_iam`).

### 2.1 Database Schema & Entities

The IAM service uses three tables, all managed by Hibernate (`ddl-auto: update`) and seeded on startup by `DataInitializer`.

**`roles` table:**

| Column | Type | Constraints |
|---|---|---|
| `id` | `BIGSERIAL` | Primary key |
| `name` | `VARCHAR(50)` | Unique, not null — stores `ADMIN`, `OPERATOR`, `VIEWER` (no `ROLE_` prefix) |
| `description` | `VARCHAR(255)` | Human-readable description |

**`users` table:**

| Column | Type | Constraints |
|---|---|---|
| `id` | `BIGSERIAL` | Primary key |
| `username` | `VARCHAR(100)` | Unique, not null |
| `email` | `VARCHAR(255)` | Unique, not null |
| `password_hash` | `VARCHAR(255)` | Not null — BCrypt hash (e.g. `{bcrypt}$2a$10$...`) |
| `enabled` | `BOOLEAN` | Default `true` |
| `account_locked` | `BOOLEAN` | Default `false` |
| `created_at` | `TIMESTAMPTZ` | Set to `Instant.now()` on creation |

**`user_roles` join table:**

| Column | Type | Constraints |
|---|---|---|
| `user_id` | `BIGINT` | FK → `users.id` |
| `role_id` | `BIGINT` | FK → `roles.id` |

**JPA Entities:**

The `User` entity (`iam-service/.../entity/User.java`) maps to the `users` table. Roles are fetched **eagerly** via a `@ManyToMany(fetch = FetchType.EAGER)` relationship through the `user_roles` join table, because `CustomUserDetailsService` needs the roles immediately during authentication to build the authority list.

The `Role` entity (`iam-service/.../entity/Role.java`) maps to the `roles` table. Role names are stored *without* the `ROLE_` prefix — the prefix is added at two points: (1) when building Spring Security authorities in `CustomUserDetailsService`, and (2) when the `JwtAuthConverterFactory` reads the `roles` claim on resource servers.

**Repositories:** `UserRepository` provides `findByUsername()`, `existsByUsername()`, `existsByEmail()`. `RoleRepository` provides `findByName()`.

---

### 2.2 User Authentication (UserDetailsService)

**File:** `iam-service/.../service/CustomUserDetailsService.java`

When a user authenticates (via the form login page during an Authorization Code flow), Spring Security's `DaoAuthenticationProvider` calls `CustomUserDetailsService.loadUserByUsername()`:

1. Looks up the `User` entity by username (with roles eagerly loaded).
2. Throws `UsernameNotFoundException` if not found.
3. Maps each assigned `Role` to a `SimpleGrantedAuthority("ROLE_" + role.getName())`.
4. Returns a `CustomUserDetails` instance containing: `id`, `username`, `email`, `passwordHash`, `enabled`, `!accountLocked`, and the list of authorities.

**`CustomUserDetails`** (`iam-service/.../service/CustomUserDetails.java`) implements `UserDetails` and adds `id` and `email` fields. These extra fields are critical because the **token customizer** (§2.6) reads them from the authenticated principal to inject custom claims into the access token.

The `DaoAuthenticationProvider` then:
- Compares the submitted password against `passwordHash` using the delegating `PasswordEncoder` (BCrypt).
- Checks `isEnabled()`, `isAccountNonLocked()`, `isAccountNonExpired()`, `isCredentialsNonExpired()`.
- On success, creates an `Authentication` object with the `CustomUserDetails` as its principal.

---

### 2.3 OAuth2 Protocol Filter Chain (Filter Chain #1)

**File:** `iam-service/.../config/AuthorizationServerConfig.java`

This is the highest-precedence filter chain (`@Order(1)`). It configures the Spring Authorization Server protocol endpoints:

```java
http
    .oauth2AuthorizationServer(authorizationServer -> {
        http.securityMatcher(authorizationServer.getEndpointsMatcher());
        authorizationServer.oidc(Customizer.withDefaults());
    })
    .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
    .exceptionHandling(exceptions -> exceptions
        .defaultAuthenticationEntryPointFor(
            new LoginUrlAuthenticationEntryPoint("/login"),
            new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
    .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
```

**What this does:**

1. `oauth2AuthorizationServer(...)` — registers the protocol endpoints:
   - `POST /oauth2/token` — token endpoint (exchanges auth codes, issues client_credentials tokens, refreshes tokens)
   - `GET /oauth2/authorize` — authorization endpoint (starts the Authorization Code + PKCE flow)
   - `GET /oauth2/jwks` — JSON Web Key Set (public keys for token verification)
   - `GET /userinfo` — OIDC user info endpoint
   - `GET /.well-known/oauth-authorization-server` — server metadata discovery

2. `securityMatcher(authorizationServer.getEndpointsMatcher())` — this chain ONLY handles requests matching the protocol endpoints; everything else falls through to filter chain #2.

3. `oidc(Customizer.withDefaults())` — enables OpenID Connect support (ID tokens, userinfo endpoint).

4. `exceptionHandling(...)` — if a browser requests a protocol endpoint without being authenticated, redirect to `/login` (the form login page from chain #2). Non-browser clients get a 401.

5. `oauth2ResourceServer(jwt(...))` — the protocol endpoints themselves accept bearer tokens (e.g., the `/userinfo` endpoint requires the access token).

**Spring Boot auto-configuration:** The `AuthorizationServerSettings` bean is NOT defined explicitly. Spring Boot's `OAuth2AuthorizationServerAutoConfiguration` creates it automatically, reading `spring.security.oauth2.authorizationserver.issuer` from `application.yml` — which is set to `${IAM_ISSUER:http://localhost:9000}`.

---

### 2.4 Default Security Filter Chain (Filter Chain #2)

**File:** `iam-service/.../config/SecurityConfig.java`

This is the lower-precedence chain (`@Order(2)`) that handles all requests NOT matched by the protocol chain:

```java
http
    .authorizeHttpRequests(authorize -> authorize
        .requestMatchers("/actuator/health", "/actuator/prometheus", "/actuator/info").permitAll()
        .anyRequest().authenticated())
    .formLogin(Customizer.withDefaults())
    .cors(cors -> cors.configurationSource(corsConfigurationSource()))
    .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
```

**What this does:**

1. **Actuator endpoints** (`/actuator/health`, `/actuator/prometheus`, `/actuator/info`) are public — needed for container healthchecks and Prometheus scraping.
2. **Everything else** requires authentication.
3. **Form login** is enabled with defaults — Spring Security auto-generates a `/login` page. This is the page browsers are redirected to during the Authorization Code flow (step 4 of §2.3).
4. **CORS** allows `http://localhost:5173` (Vite dev server) and `http://localhost:3000` (alternative dev port) with credentials.
5. **CSRF** is disabled for `/api/**` paths (the admin REST API is stateless JSON), but remains active for the form login page.
6. **Sessions** are `IF_REQUIRED` — the form login flow needs a session to track the user between the login form submit and the authorization consent/redirect, but the `/api/**` REST endpoints are effectively stateless.

**Password encoder:** A `DelegatingPasswordEncoder` is defined as a bean. It defaults to BCrypt for encoding new passwords but can decode any format prefixed with `{bcrypt}`, `{noop}`, etc. Client secrets in `RegisteredClientConfig` use `{noop}` (plaintext dev placeholder); user passwords use `{bcrypt}`.

---

### 2.5 RSA Key Pair & JWKS Endpoint

**File:** `iam-service/.../config/AuthorizationServerConfig.java` — `jwkSource()` bean

On startup, the IAM service generates a fresh 2048-bit RSA key pair:

```java
KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
generator.initialize(2048);
KeyPair keyPair = generator.generateKeyPair();
```

This key pair is wrapped in a Nimbus `RSAKey` (with a random key ID) and stored in an `ImmutableJWKSet`. The resulting `JWKSource<SecurityContext>` bean serves two purposes:

1. **Signing:** The Authorization Server uses the private key to RS256-sign every access token (JWT).
2. **JWKS endpoint:** The public key is served at `GET /oauth2/jwks` so resource servers can download it and verify token signatures locally.

**Important:** The key is generated in-memory on each restart, which means all previously issued tokens become invalid after a restart. This is acceptable for development; production would use a persistent key store.

The `JwtDecoder` bean (needed for the protocol chain's own resource-server config at `/userinfo`) is auto-configured by Spring Boot's `OAuth2AuthorizationServerJwtAutoConfiguration` from this same `JWKSource` — no explicit `JwtDecoder` bean is defined.

---

### 2.6 Token Customizer — Custom JWT Claims

**File:** `iam-service/.../config/AuthorizationServerConfig.java` — `tokenCustomizer()` bean

This is the critical piece that bridges the IAM database to the JWT claims that resource servers rely on. The customizer intercepts every access token before it is signed and adds custom claims:

```java
@Bean
public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
    return context -> {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return; // only customize access tokens, not refresh/id tokens
        }
        Authentication principal = context.getPrincipal();
        if (principal != null && principal.getPrincipal() instanceof CustomUserDetails user) {
            // USER LOGIN (authorization_code grant):
            // Extract role names from the ROLE_* authorities
            List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))  // "ROLE_ADMIN" → "ADMIN"
                .toList();
            context.getClaims().claim("roles", roles);      // ["ADMIN", "OPERATOR"]
            context.getClaims().claim("username", user.getUsername());
            context.getClaims().claim("email", user.getEmail());
        } else {
            // CLIENT CREDENTIALS (service-to-service):
            // Derive roles from authorized scopes prefixed "role:"
            Set<String> scopes = context.getAuthorizedScopes();
            List<String> roles = scopes.stream()
                .filter(s -> s.startsWith("role:"))
                .map(s -> s.substring("role:".length()).toUpperCase())
                .toList();
            if (!roles.isEmpty()) {
                context.getClaims().claim("roles", roles);
            }
        }
    };
}
```

**Two code paths:**

| Grant type | Principal type | How roles are derived |
|---|---|---|
| `authorization_code` | `CustomUserDetails` | From the user's `ROLE_*` authorities (which came from the DB via `CustomUserDetailsService`) |
| `client_credentials` | OAuth2 client (not a user) | From authorized scopes prefixed `role:` (e.g. scope `role:admin` → role `ADMIN`). Currently the service clients only request `service:internal`, so no `roles` claim is added for them |

**Resulting JWT claims (for a user login):**
```json
{
  "iss": "http://iam-service:9000",
  "sub": "admin",
  "aud": "smarthome-dashboard",
  "exp": 1750000000,
  "iat": 1749999100,
  "roles": ["ADMIN"],
  "username": "admin",
  "email": "admin@smarthome.local",
  "scope": "openid profile dashboard:read dashboard:write"
}
```

---

### 2.7 Registered OAuth2 Clients

**File:** `iam-service/.../config/RegisteredClientConfig.java`

Four clients are registered in an `InMemoryRegisteredClientRepository`:

#### Dashboard SPA Client

| Property | Value |
|---|---|
| `clientId` | `smarthome-dashboard` |
| Authentication method | `NONE` (public client — no client secret) |
| Grant types | `AUTHORIZATION_CODE` + `REFRESH_TOKEN` |
| Redirect URIs | `http://localhost:5173/callback`, `http://localhost:3000/callback` |
| Post-logout redirect | `http://localhost:5173/` |
| Scopes | `openid`, `profile`, `dashboard:read`, `dashboard:write` |
| PKCE | **Required** (`requireProofKey: true`) |
| Consent | Not required (`requireAuthorizationConsent: false`) |
| Access token TTL | 15 minutes |
| Refresh token TTL | 7 days (single-use: `reuseRefreshTokens: false`) |

#### Service Clients (x3)

Three identical service clients for inter-service authentication (`gateway-service`, `processing-service`, `sensor-simulator-service`):

| Property | Value |
|---|---|
| `clientId` | `gateway-service` / `processing-service` / `sensor-simulator-service` |
| Authentication method | `CLIENT_SECRET_BASIC` (HTTP Basic with clientId:secret) |
| Grant type | `CLIENT_CREDENTIALS` |
| Secret | `{noop}service-secret` (dev placeholder, overridable via `iam.clients.service-secret` env var) |
| Scope | `service:internal` |
| Access token TTL | 1 hour |

The `{noop}` prefix means the secret is stored in plaintext (acceptable for dev; production should use `{bcrypt}` with env-injected hashed values).

---

### 2.8 Bootstrap Data Seeding

**File:** `iam-service/.../config/DataInitializer.java`

Implements `ApplicationRunner` and runs on every startup inside a `@Transactional` block:

1. **Ensures the three roles exist** (idempotent — checks `roleRepository.findByName()` before inserting):
   - `ADMIN` — "Full system access — manage users, devices, configuration"
   - `OPERATOR` — "Operational access — view data, manage devices, trigger simulator"
   - `VIEWER` — "Read-only access — view dashboards, alerts, statistics"

2. **Seeds the bootstrap admin user** (only if `userRepository.existsByUsername(adminUsername)` is false):
   - Username: `${iam.bootstrap.admin-username:admin}`
   - Email: `${iam.bootstrap.admin-email:admin@smarthome.local}`
   - Password: `passwordEncoder.encode(${iam.bootstrap.admin-password:admin123})` → stored as BCrypt hash
   - Assigned roles: `{ADMIN}`

All three config values are externalized via `@Value` annotations, so production can inject different credentials through environment variables.

---

### 2.9 User Management REST API

**File:** `iam-service/.../controller/UserController.java`

The `UserController` is annotated with `@PreAuthorize("hasRole('ADMIN')")` at the class level, meaning **every endpoint requires the ADMIN role** (enforced by `@EnableMethodSecurity` in `SecurityConfig`).

| Endpoint | Method | Auth | Body | Response |
|---|---|---|---|---|
| `/api/users` | `POST` | ADMIN | `RegisterRequest` (username, email, password, roles) | `UserResponse` |
| `/api/users` | `GET` | ADMIN | — | `List<UserResponse>` |
| `/api/users/{id}` | `DELETE` | ADMIN | — | 204 No Content |

`RegisterRequest` is a Java record with Bean Validation constraints:
- `@NotBlank username`
- `@NotBlank @Email email`
- `@NotBlank @Size(min = 8) password`
- `@NotEmpty Set<String> roles`

`UserService.register()` hashes the password with `PasswordEncoder.encode()`, resolves role names to `Role` entities, persists the user, and returns a `UserResponse` record (id, username, email, enabled, accountLocked, roles, createdAt).

---

## 3. Shared Role Mapping (iot-common)

**File:** `iot-common/.../security/JwtAuthConverterFactory.java`

This is a stateless utility class in the shared library that every resource server uses to convert JWT claims into Spring Security authorities.

**The problem it solves:** Spring Security's default `JwtAuthenticationConverter` maps the `scope` / `scp` claim to `SCOPE_*` authorities. But the IAM service puts roles in a custom `roles` claim (e.g. `["ADMIN", "OPERATOR"]`). Without a custom converter, `hasRole("ADMIN")` would never match.

**How it works:**

```java
public static JwtAuthenticationConverter rolesConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) return List.of();
        return roles.stream()
            .filter(Objects::nonNull)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
            .collect(Collectors.toList());
    });
    return converter;
}
```

**Transformation:** JWT claim `"roles": ["ADMIN", "VIEWER"]` → Spring Security authorities `[ROLE_ADMIN, ROLE_VIEWER]`.

**Spring Security 7 note:** The framework also injects a default `FACTOR_BEARER` authority (part of the new multi-factor authentication support). This authority is additive — it does not interfere with `hasRole()` checks, which only look for `ROLE_` prefixed authorities. Tests account for this by filtering on `ROLE_` prefix when asserting.

**Dependency management:** The security dependency (`spring-security-oauth2-resource-server`) is declared as **optional** in `iot-common/pom.xml`, so modules that don't need security (like `history-service`) don't transitively pull in the security stack.

---

## 4. Resource Server Configuration

Each secured service follows the same pattern: a `SecurityFilterChain` bean with `oauth2ResourceServer(jwt(...))` configured with the shared `JwtAuthConverterFactory.rolesConverter()`.

### Common Configuration Pattern

Every resource server:
1. Is **stateless** (`SessionCreationPolicy.STATELESS`) — no HTTP sessions.
2. Disables **CSRF** (not needed for stateless token-based auth).
3. Leaves **actuator endpoints public** (`/actuator/health`, `/actuator/health/**`, `/actuator/prometheus`, `/actuator/info`) for container healthchecks and Prometheus.
4. Validates JWTs via **`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`** configured in `application.yml` to `${IAM_ISSUER}/oauth2/jwks`.
5. Uses **`JwtAuthConverterFactory.rolesConverter()`** to map the custom `roles` claim.
6. Enables `@EnableMethodSecurity` for `@PreAuthorize` support.

**JWKS caching behavior:** The `JwtDecoder` bean auto-configured by Spring Boot downloads the JWKS from the IAM service **lazily** (on the first inbound JWT, not at startup). It then caches the key set and refreshes it approximately every 5 minutes. This means:
- **No startup coupling:** Services start independently of the IAM service.
- **No per-request network call:** Validation is a local cryptographic operation after the initial download.
- **Graceful degradation:** If IAM is temporarily unavailable, cached keys continue working.

### 4.1 Gateway Service

**File:** `gateway-service/.../config/SecurityConfig.java`

```java
.requestMatchers(HttpMethod.PUT, "/api/gateway/thresholds/**").hasRole("ADMIN")
.requestMatchers(HttpMethod.GET, "/api/gateway/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
.anyRequest().authenticated()
```

| Endpoint | Method | Required Role(s) |
|---|---|---|
| `GET /api/gateway/stats` | GET | ADMIN, OPERATOR, or VIEWER |
| `PUT /api/gateway/thresholds/{sensorType}` | PUT | ADMIN only |
| Actuator (`/actuator/health`, etc.) | GET | Public |

**Design rationale:** Threshold updates alter the anomaly detection behavior for the entire platform, so they require ADMIN. Reading stats is safe for any authenticated user.

**AMQP note:** The `SensorDataListener` consumes messages from RabbitMQ. HTTP security does NOT affect AMQP message processing — the listener runs in a separate thread pool managed by Spring AMQP, completely outside the servlet filter chain.

### 4.2 Processing Service

**File:** `processing-service/.../config/SecurityConfig.java`

```java
.requestMatchers(HttpMethod.GET, "/api/analytics/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
.anyRequest().authenticated()
```

| Endpoint | Method | Required Role(s) |
|---|---|---|
| `GET /api/analytics/statistics` | GET | ADMIN, OPERATOR, or VIEWER |
| `GET /api/analytics/statistics/{sensorId}` | GET | ADMIN, OPERATOR, or VIEWER |
| `GET /api/analytics/summary` | GET | ADMIN, OPERATOR, or VIEWER |
| `GET /api/analytics/alerts` | GET | ADMIN, OPERATOR, or VIEWER |
| Actuator | GET | Public |

All analytics endpoints are read-only, so any authenticated role has access.

### 4.3 Sensor Simulator Service

**File:** `sensor-simulator-service/.../config/SecurityConfig.java`

```java
.requestMatchers(HttpMethod.GET, "/api/simulator/status").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
.requestMatchers(HttpMethod.POST, "/api/simulator/**").hasAnyRole("ADMIN", "OPERATOR")
.anyRequest().authenticated()
```

| Endpoint | Method | Required Role(s) |
|---|---|---|
| `GET /api/simulator/status` | GET | ADMIN, OPERATOR, or VIEWER |
| `POST /api/simulator/start` | POST | ADMIN or OPERATOR |
| `POST /api/simulator/stop` | POST | ADMIN or OPERATOR |
| `POST /api/simulator/trigger` | POST | ADMIN or OPERATOR |
| Actuator | GET | Public |

**Design rationale:** Starting/stopping the simulator affects data ingestion for the entire platform, so it requires operational privileges. VIEWER can only observe the current state.

### 4.4 Unsecured Services (Known Gap)

`device-registry-service` (port 8084) and `history-service` (port 8085) do **not** currently have `spring-boot-starter-oauth2-resource-server` or `SecurityConfig`. Their REST endpoints (`/api/devices/**`, `/api/history/**`) are open. This is a known gap from Phase 3A (which was scoped to the original "3 services") and is planned for remediation.

---

## 5. Runtime Flows — Step by Step

### 5.1 Flow A: Dashboard User Login (Authorization Code + PKCE)

This flow is for the planned React SPA dashboard (`smarthome-dashboard` client).

```
 Browser (SPA)                    IAM Service (9000)              Resource Server (e.g. 8082)
      │                                │                                │
  1.  │── GET /oauth2/authorize ──────>│                                │
      │   ?response_type=code          │                                │
      │   &client_id=smarthome-dashboard                                │
      │   &redirect_uri=http://localhost:5173/callback                  │
      │   &scope=openid+profile+dashboard:read                          │
      │   &code_challenge=<SHA256>     │                                │
      │   &code_challenge_method=S256  │                                │
      │                                │                                │
  2.  │<─── 302 Redirect to /login ────│                                │
      │                                │                                │
  3.  │── POST /login ────────────────>│                                │
      │   username=admin               │                                │
      │   password=admin123            │                                │
      │                                │                                │
      │   [DaoAuthenticationProvider]   │                                │
      │   loadUserByUsername("admin")   │                                │
      │     → User { roles: [ADMIN] }  │                                │
      │     → CustomUserDetails        │                                │
      │   passwordEncoder.matches()    │                                │
      │     → ✓ BCrypt match           │                                │
      │                                │                                │
  4.  │<── 302 Redirect to callback ───│                                │
      │   ?code=<authorization_code>   │                                │
      │                                │                                │
  5.  │── POST /oauth2/token ─────────>│                                │
      │   grant_type=authorization_code│                                │
      │   code=<authorization_code>    │                                │
      │   redirect_uri=<same>          │                                │
      │   code_verifier=<PKCE_plain>   │                                │
      │                                │                                │
      │   [Token Customizer]           │                                │
      │   principal instanceof         │                                │
      │     CustomUserDetails ✓        │                                │
      │   → claim("roles", ["ADMIN"])  │                                │
      │   → claim("username", "admin") │                                │
      │   → claim("email", "admin@..") │                                │
      │   [Sign with RSA private key]  │                                │
      │                                │                                │
  6.  │<── 200 { access_token, ────────│                                │
      │         refresh_token,         │                                │
      │         id_token }             │                                │
      │                                │                                │
  7.  │── GET /api/gateway/stats ──────────────────────────────────────>│
      │   Authorization: Bearer <access_token>                          │
      │                                                                 │
      │   [SecurityFilterChain]                                         │
      │   1. Extract Bearer token from header                           │
      │   2. Download JWKS from http://iam-service:9000/oauth2/jwks     │
      │      (first request only; cached after)                         │
      │   3. Verify RS256 signature with IAM public key                 │
      │   4. Check exp > now, iss == IAM_ISSUER                         │
      │   5. JwtAuthConverterFactory.rolesConverter():                   │
      │      claim "roles": ["ADMIN"] → [ROLE_ADMIN]                    │
      │   6. hasAnyRole("ADMIN","OPERATOR","VIEWER") → ✓ ROLE_ADMIN     │
      │                                                                 │
  8.  │<── 200 { received: 42, ... } ──────────────────────────────────│
```

### 5.2 Flow B: Service-to-Service (Client Credentials)

Used by backend services to authenticate to each other (currently configured but not actively used until Phase 3B).

```
 gateway-service                  IAM Service (9000)
      │                                │
  1.  │── POST /oauth2/token ─────────>│
      │   Authorization: Basic         │
      │     base64(gateway-service:    │
      │           service-secret)      │
      │   grant_type=client_credentials│
      │   scope=service:internal       │
      │                                │
      │   [Token Customizer]           │
      │   principal is NOT             │
      │     CustomUserDetails          │
      │   → scopes: [service:internal] │
      │   → no "role:" prefix → no     │
      │     roles claim added          │
      │   [Sign with RSA private key]  │
      │                                │
  2.  │<── 200 { access_token,         │
      │         token_type: "Bearer" } │
```

The resulting token has `scope: "service:internal"` but no `roles` claim (service clients don't request `role:*` scopes). This is by design — inter-service calls in Phase 3B will use a different authorization strategy (e.g., scope-based or a dedicated service role).

### 5.3 Flow C: API Request with Bearer Token

This is the per-request validation path inside every resource server.

```
HTTP Request
  Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwOi8v...
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  BearerTokenAuthenticationFilter                             │
│  1. Extracts the JWT string from the Authorization header    │
│  2. Passes it to the JwtDecoder                              │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  NimbusJwtDecoder (auto-configured)                          │
│  1. Downloads JWKS from IAM (cached after first call)        │
│  2. Matches the token's "kid" header to a key in the JWKS   │
│  3. Verifies the RS256 signature using the RSA public key    │
│  4. Validates: exp > now, iss == configured issuer-uri       │
│  5. Returns a validated Jwt object with all claims           │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  JwtAuthenticationConverter (from JwtAuthConverterFactory)    │
│  1. Reads claim "roles": ["ADMIN", "VIEWER"]                 │
│  2. Maps to: [ROLE_ADMIN, ROLE_VIEWER]                       │
│  3. Spring Security 7 adds: FACTOR_BEARER (default)          │
│  4. Creates JwtAuthenticationToken with these authorities     │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│  AuthorizationFilter (HttpSecurity rules)                    │
│  Evaluates the SecurityFilterChain's authorize rules:        │
│  • permitAll() paths → always pass                           │
│  • hasRole("ADMIN") → checks for ROLE_ADMIN in authorities   │
│  • hasAnyRole("ADMIN","OPERATOR","VIEWER") → any match       │
│  • authenticated() → token is valid                          │
│                                                              │
│  Result: 200 OK / 403 Forbidden / 401 Unauthorized           │
└─────────────────────────────────────────────────────────────┘
```

**When does 401 vs 403 occur?**
- **401 Unauthorized:** No `Authorization` header, or the token is malformed / expired / has an invalid signature / wrong issuer.
- **403 Forbidden:** The token is valid but the user's roles don't match the required role(s) for the endpoint.

---

## 6. RBAC Matrix — Complete Endpoint Rules

| Service | Endpoint | Method | Public | VIEWER | OPERATOR | ADMIN |
|---|---|---|---|---|---|---|
| **gateway** | `/api/gateway/stats` | GET | ✗ | ✓ | ✓ | ✓ |
| **gateway** | `/api/gateway/thresholds/{type}` | PUT | ✗ | ✗ | ✗ | ✓ |
| **processing** | `/api/analytics/statistics` | GET | ✗ | ✓ | ✓ | ✓ |
| **processing** | `/api/analytics/statistics/{id}` | GET | ✗ | ✓ | ✓ | ✓ |
| **processing** | `/api/analytics/summary` | GET | ✗ | ✓ | ✓ | ✓ |
| **processing** | `/api/analytics/alerts` | GET | ✗ | ✓ | ✓ | ✓ |
| **simulator** | `/api/simulator/status` | GET | ✗ | ✓ | ✓ | ✓ |
| **simulator** | `/api/simulator/start` | POST | ✗ | ✗ | ✓ | ✓ |
| **simulator** | `/api/simulator/stop` | POST | ✗ | ✗ | ✓ | ✓ |
| **simulator** | `/api/simulator/trigger` | POST | ✗ | ✗ | ✓ | ✓ |
| **iam** | `/oauth2/token` | POST | ✓ | — | — | — |
| **iam** | `/oauth2/authorize` | GET | ✓* | — | — | — |
| **iam** | `/oauth2/jwks` | GET | ✓ | — | — | — |
| **iam** | `/api/users` | POST | ✗ | ✗ | ✗ | ✓ |
| **iam** | `/api/users` | GET | ✗ | ✗ | ✗ | ✓ |
| **iam** | `/api/users/{id}` | DELETE | ✗ | ✗ | ✗ | ✓ |
| **all** | `/actuator/health` | GET | ✓ | — | — | — |
| **all** | `/actuator/prometheus` | GET | ✓ | — | — | — |

*\* `/oauth2/authorize` requires authentication but redirects to the login form — it's "public" in the sense that unauthenticated browsers are redirected, not rejected.*

---

## 7. Configuration & Wiring

### IAM Service (`iam-service/src/main/resources/application.yml`)

```yaml
server:
  port: 9000
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:smarthome_iam}
  security:
    oauth2:
      authorizationserver:
        issuer: ${IAM_ISSUER:http://localhost:9000}
```

The `issuer` is the base URL that appears as the `iss` claim in every token. In Docker Compose it is set to `http://iam-service:9000` (the in-cluster hostname) so that resource servers — which resolve the `jwk-set-uri` relative to the issuer — can reach the JWKS endpoint over the Docker network.

### Resource Servers (e.g., `gateway-service/src/main/resources/application.yml`)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${IAM_ISSUER:http://localhost:9000}
          jwk-set-uri: ${IAM_ISSUER:http://localhost:9000}/oauth2/jwks
```

Both `issuer-uri` and `jwk-set-uri` are set. The `jwk-set-uri` tells the `NimbusJwtDecoder` where to download the public keys. The `issuer-uri` is used for `iss` claim validation — the token's `iss` must match this value.

### Docker Compose Wiring

In `docker-compose.yml`, every app service receives `IAM_ISSUER: http://iam-service:9000` as an environment variable. The IAM service itself uses this as its own issuer URL, ensuring the `iss` claim in tokens matches what resource servers expect.

### Database Initialization

`db/timescaledb/init/01-create-databases.sql` creates the `smarthome_iam` database on the shared TimescaleDB/Postgres instance. Hibernate manages the schema (`ddl-auto: update`), and `DataInitializer` seeds the reference data.

---

## 8. Test Strategy

### Unit Tests — `@WebMvcTest` Security Slices

Each resource server has a `*SecurityTest` class that uses Spring Boot's `@WebMvcTest` test slice with `@Import(SecurityConfig.class)`:

**Pattern:**
1. Mock the `JwtDecoder` bean (the `jwt()` post-processor bypasses actual decoding).
2. Mock any service dependencies the controller requires.
3. Test **three scenarios per endpoint**:
   - **No token** → expect `401 Unauthorized`.
   - **Token with insufficient role** → expect `403 Forbidden`.
   - **Token with sufficient role** → expect `200 OK`.

**Gateway tests** (`GatewaySecurityTest`): 4 tests covering read (any role) and write (ADMIN only) on thresholds.

**Processing tests** (`ProcessingSecurityTest`): 2 tests covering anonymous rejection and VIEWER access to analytics.

**Simulator tests** (`SimulatorSecurityTest`): 4 tests covering anonymous rejection, VIEWER read-only, VIEWER write-denied, and OPERATOR write-allowed.

### Unit Test — Shared Converter

`JwtAuthConverterFactoryTest` verifies the converter independently:
- A JWT with `"roles": ["ADMIN", "VIEWER"]` produces `ROLE_ADMIN` and `ROLE_VIEWER` authorities.
- A JWT without a `roles` claim produces no `ROLE_*` authorities.
- Tests filter on the `ROLE_` prefix to ignore the Spring Security 7 `FACTOR_BEARER` authority.

### Integration Test — IAM Service

`IamServiceIntegrationTest` uses **Testcontainers** to boot the full IAM service against a real Postgres:

1. **JWKS test:** `GET /oauth2/jwks` returns a non-null `keys` array (proving the RSA key was generated and published).
2. **Token test:** `POST /oauth2/token` with HTTP Basic auth (`gateway-service:service-secret`) and `grant_type=client_credentials` returns a `Bearer` access token (proving the token endpoint, client registration, and signing pipeline work end-to-end).

The test is guarded with `@Testcontainers(disabledWithoutDocker = true)` so it skips cleanly in environments without Docker.

---

## 9. Spring Boot 4 / Security 7 Adaptations

| Issue | Boot 3 / Security 6 | Boot 4 / Security 7 (as implemented) |
|---|---|---|
| Authorization Server config | `OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http)` | Removed. Use `http.oauth2AuthorizationServer(...)` DSL |
| `JwtDecoder` bean | `OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)` | Auto-configured by `OAuth2AuthorizationServerJwtAutoConfiguration` from the `JWKSource` bean |
| `AuthorizationServerSettings` | Explicit `@Bean` definition | Auto-configured from `spring.security.oauth2.authorizationserver.issuer` |
| `OAuth2TokenType` import | `org.springframework.security.oauth2.server.authorization.OAuth2TokenType` | Same (moved in 7.0 to `...server.authorization`) |
| `@WebMvcTest` import | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (in `spring-boot-starter-webmvc-test` artifact) |
| Default JWT authorities | Only `SCOPE_*` authorities | Additionally adds `FACTOR_BEARER` authority (multi-factor support). Tests must filter for `ROLE_` prefix |
| `TestRestTemplate` | In `spring-boot-test` | Relocated; IAM integration test uses `RestClient` (from `spring-web`) instead |

---

## 10. Security Design Decisions

1. **Lazy JWKS fetch (fail-open):** Resource servers don't block on startup waiting for the IAM service. If IAM is down, services start fine but cannot validate tokens until JWKS is fetched. Once fetched, keys are cached for ~5 minutes.

2. **Custom `roles` claim (not `scope`):** OAuth2 scopes (`scope`/`scp`) represent what a client is *allowed to do on behalf of a user*, not who the user *is*. Roles are an identity property, so they belong in a custom claim. This also avoids the `SCOPE_*` prefix collision.

3. **Role names without `ROLE_` prefix in DB/JWT:** The raw names (`ADMIN`, `OPERATOR`, `VIEWER`) are simpler and more portable. The `ROLE_` prefix is added at the security boundary (in `CustomUserDetailsService` and `JwtAuthConverterFactory`), which is the only place Spring Security cares about it.

4. **In-memory `RegisteredClientRepository`:** Client registrations are static and dev-oriented. A JDBC-backed repository is planned for Phase 4. This keeps the 3A implementation simple and avoids additional schema complexity.

5. **In-memory RSA key pair:** Generated on each startup. Acceptable for development; production would persist keys or use a key management service (HSM/Vault).

6. **Stateless resource servers, stateful IAM:** Resource servers are fully stateless (no sessions, no CSRF). The IAM service uses `IF_REQUIRED` sessions because the Authorization Code flow needs a session between the login form and the redirect.

7. **AMQP unaffected:** HTTP security (filter chains) only applies to the servlet container. RabbitMQ listeners run in their own thread pool and are not subject to JWT validation. Message-level security (HMAC signing) is planned for Phase 3C.

---

## File Index

| File | Purpose |
|---|---|
| `iam-service/.../config/AuthorizationServerConfig.java` | Protocol filter chain #1, JWK source, token customizer |
| `iam-service/.../config/SecurityConfig.java` | Default filter chain #2, form login, password encoder, CORS |
| `iam-service/.../config/RegisteredClientConfig.java` | In-memory client registrations (dashboard + 3 services) |
| `iam-service/.../config/DataInitializer.java` | Idempotent role/admin seeding on startup |
| `iam-service/.../entity/User.java` | JPA user entity with BCrypt password + eager roles |
| `iam-service/.../entity/Role.java` | JPA role entity (ADMIN, OPERATOR, VIEWER) |
| `iam-service/.../service/CustomUserDetailsService.java` | Loads users from DB, maps roles to authorities |
| `iam-service/.../service/CustomUserDetails.java` | UserDetails impl with id/email for token customizer |
| `iam-service/.../service/UserService.java` | User CRUD + role assignment |
| `iam-service/.../controller/UserController.java` | Admin-only user management REST API |
| `iam-service/.../repository/UserRepository.java` | JPA: findByUsername, existsByUsername, existsByEmail |
| `iam-service/.../repository/RoleRepository.java` | JPA: findByName |
| `iam-service/src/main/resources/application.yml` | Port 9000, DB, issuer, actuator, tracing |
| `iot-common/.../security/JwtAuthConverterFactory.java` | Shared JWT `roles` → `ROLE_*` converter |
| `gateway-service/.../config/SecurityConfig.java` | Resource server: GET any role, PUT ADMIN |
| `processing-service/.../config/SecurityConfig.java` | Resource server: GET any role |
| `sensor-simulator-service/.../config/SecurityConfig.java` | Resource server: GET any role, POST ADMIN/OPERATOR |
| `gateway-service/src/main/resources/application.yml` | `issuer-uri` + `jwk-set-uri` config |
| `gateway-service/.../security/GatewaySecurityTest.java` | 401/403/200 @WebMvcTest |
| `gateway-service/.../security/JwtAuthConverterFactoryTest.java` | Role claim → authority mapping |
| `processing-service/.../security/ProcessingSecurityTest.java` | 401/200 @WebMvcTest |
| `sensor-simulator-service/.../security/SimulatorSecurityTest.java` | 401/403/200 @WebMvcTest |
| `iam-service/.../IamServiceIntegrationTest.java` | JWKS + client_credentials smoke test |
| `db/timescaledb/init/01-create-databases.sql` | Creates `smarthome_iam` database |
