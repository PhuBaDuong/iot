# IoT Smart Home Monitor — Authentication & Authorization Plan (OAuth 2.1 + JWT)

> **Created:** 2026-06-24 | **Aligns with:** Backend Phase 3 (Weeks 9–12) of `production_plan.md`

---

## 1. Overview

**Current state:** Zero security — no Spring Security dependency, no authentication, no authorization, hardcoded RabbitMQ credentials, all REST endpoints publicly accessible over HTTP.

**Target state:** Full OAuth 2.1 / OpenID Connect identity platform with JWT access tokens, role-based access control (RBAC), device credential management, and secured inter-service communication.

**Key components:**
1. **IAM Service** — new Spring Boot service (Spring Authorization Server) that issues and manages tokens
2. **Resource Server config** — added to all existing services to validate JWTs
3. **Spring Cloud Gateway** — single entry point for all external REST traffic
4. **RabbitMQ security** — TLS + per-service credentials + message signing
5. **Frontend auth** — login flow, token management, protected routes (covered in `frontend_plan.md`)

---

## 2. Architecture

### Token Flow

```
                          ┌──────────────────────────────┐
                          │       IAM Service            │
                          │   Spring Authorization       │
                          │        Server                │
                          │                              │
                          │  POST /oauth2/token          │
                          │  POST /oauth2/revoke         │
                          │  GET  /oauth2/jwks           │
                          │  GET  /userinfo              │
                          │  POST /api/auth/register     │
                          │  POST /api/devices/creds     │
                          │                              │
                          │  PostgreSQL: users, roles,   │
                          │  device_credentials,         │
                          │  refresh_tokens              │
                          └──────────┬───────────────────┘
                                     │ JWKS (public keys)
                    ┌────────────────┼────────────────────┐
                    ▼                ▼                     ▼
          ┌─────────────┐  ┌─────────────┐     ┌──────────────┐
          │   Spring     │  │  gateway    │     │  processing  │
          │   Cloud      │  │  service    │     │  service     │
          │   Gateway    │  │  :8082      │     │  :8083       │
          │   :8080      │  │             │     │              │
          │              │  │ Resource    │     │ Resource     │
          │ JWT validate │  │ Server      │     │ Server       │
          │ Rate limit   │  │ @PreAuth    │     │ @PreAuth     │
          │ Route        │  │             │     │              │
          └──────────────┘  └─────────────┘     └──────────────┘
                ▲
                │ HTTPS
          ┌─────────────┐
          │  Dashboard   │
          │  / Clients   │
          │  :5173       │
          └─────────────┘
```

### Authentication Flows

| Flow | Grant Type | Use Case |
|------|-----------|----------|
| User login (dashboard) | **Authorization Code + PKCE** | Browser SPA — most secure for public clients |
| Service-to-service | **Client Credentials** | Backend services calling each other |
| Device authentication | **Client Credentials** (with device API key) | IoT sensors publishing readings |
| Token refresh | **Refresh Token** | Silent token renewal without re-login |

> 🎓 **Why Authorization Code + PKCE (not Implicit)?** OAuth 2.1 deprecated the Implicit grant. PKCE (Proof Key for Code Exchange) prevents authorization code interception attacks. The SPA generates a random `code_verifier`, hashes it to `code_challenge`, sends the challenge to the auth server, then proves possession of the verifier when exchanging the code for tokens.

---

## 3. Technology Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| Authorization Server | **Spring Authorization Server 2.x** | First-party Spring support, OAuth 2.1 compliant, OIDC built-in |
| Resource Server | **spring-boot-starter-oauth2-resource-server** | Auto-validates JWTs via JWKS, integrates with SecurityFilterChain |
| Token format | **JWT (JWS with RS256)** | Stateless validation, standard claims, asymmetric keys |
| Key management | **RSA 2048-bit keypair** → rotate to Vault-managed keys in Phase 4 |
| Password encoding | **BCrypt** (strength 12) | Industry standard, adaptive cost factor |
| API Gateway | **Spring Cloud Gateway** | Reactive, JWT validation filter, rate limiting |
| Database | **PostgreSQL 16** | Users, roles, device credentials, registered clients |
| Session/Cache | **Redis 7** (from Phase 1) | Token blacklist, rate limiting counters, JWKS cache |

---

## 4. IAM Service — New Microservice

### 4.1 Project Setup

```
iam-service/
├── pom.xml
└── src/main/
    ├── java/com/smarthome/iam/
    │   ├── IamServiceApplication.java
    │   ├── config/
    │   │   ├── AuthorizationServerConfig.java    # Spring Auth Server beans
    │   │   ├── SecurityConfig.java               # Login page, CSRF, CORS
    │   │   └── KeyConfig.java                    # RSA keypair management
    │   ├── entity/
    │   │   ├── User.java                         # JPA entity
    │   │   ├── Role.java                         # ADMIN, OPERATOR, VIEWER
    │   │   ├── DeviceCredential.java             # API key + secret per device
    │   │   └── RegisteredClient.java             # OAuth2 registered clients
    │   ├── repository/
    │   │   ├── UserRepository.java
    │   │   ├── RoleRepository.java
    │   │   └── DeviceCredentialRepository.java
    │   ├── service/
    │   │   ├── CustomUserDetailsService.java     # Loads users from PostgreSQL
    │   │   ├── DeviceCredentialService.java      # CRUD for device API keys
    │   │   └── UserService.java                  # Registration, role assignment
    │   ├── controller/
    │   │   ├── UserController.java               # Admin: manage users/roles
    │   │   └── DeviceCredentialController.java   # Provision device credentials
    │   └── dto/
    │       ├── RegisterRequest.java
    │       ├── UserResponse.java
    │       └── DeviceCredentialResponse.java
    └── resources/
        ├── application.yml
        ├── schema.sql                            # DDL for users, roles, etc.
        └── data.sql                              # Seed admin user + default roles
```

### 4.2 Database Schema

```sql
-- Users & Roles (RBAC)
CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) UNIQUE NOT NULL,   -- ADMIN, OPERATOR, VIEWER
    description VARCHAR(255)
);

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) UNIQUE NOT NULL,
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,        -- BCrypt
    enabled         BOOLEAN DEFAULT true,
    account_locked  BOOLEAN DEFAULT false,
    created_at      TIMESTAMPTZ DEFAULT now(),

-- Device Credentials (IoT device authentication)
CREATE TABLE device_credentials (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(100) UNIQUE NOT NULL,  -- matches sensorId
    api_key         VARCHAR(255) UNIQUE NOT NULL,   -- UUID, used as client_id
    api_secret_hash VARCHAR(255) NOT NULL,           -- BCrypt, used as client_secret
    scopes          VARCHAR(500) DEFAULT 'sensor:publish',
    enabled         BOOLEAN DEFAULT true,
    issued_at       TIMESTAMPTZ DEFAULT now(),
    expires_at      TIMESTAMPTZ,                     -- NULL = never expires
    issued_by       BIGINT REFERENCES users(id)
);

-- OAuth2 Registered Clients (Spring Auth Server managed)
-- Spring Authorization Server stores these automatically via JdbcRegisteredClientRepository

-- Refresh Token tracking (for revocation)
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    token_hash  VARCHAR(255) UNIQUE NOT NULL,   -- SHA-256 hash (never store raw)
    user_id     BIGINT REFERENCES users(id),
    device_id   VARCHAR(100),                    -- NULL for user tokens
    issued_at   TIMESTAMPTZ DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN DEFAULT false,
    revoked_at  TIMESTAMPTZ
);

-- Audit log
CREATE TABLE auth_events (
    id          BIGSERIAL PRIMARY KEY,
    event_type  VARCHAR(50) NOT NULL,   -- LOGIN, LOGOUT, TOKEN_REFRESH, TOKEN_REVOKE, LOGIN_FAILED
    principal   VARCHAR(255) NOT NULL,  -- username or device_id
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    success     BOOLEAN NOT NULL,
    details     TEXT,
    created_at  TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_auth_events_principal ON auth_events(principal, created_at);

-- Seed data
INSERT INTO roles (name, description) VALUES
    ('ADMIN',    'Full system access — manage users, devices, configuration'),
    ('OPERATOR', 'Operational access — view data, manage devices, trigger simulator'),
    ('VIEWER',   'Read-only access — view dashboards, alerts, statistics');

INSERT INTO users (username, email, password_hash) VALUES
    ('admin', 'admin@smarthome.local', '$2a$12$...');  -- BCrypt hash of 'admin123'

INSERT INTO user_roles (user_id, role_id) VALUES (1, 1);  -- admin → ADMIN role
```

### 4.3 RBAC — Roles & Permissions

| Role | Permissions | Dashboard Access |
|------|------------|------------------|
| **ADMIN** | Full CRUD on users, devices, config. Start/stop simulator. View all data. Revoke tokens. | All pages + admin panel |
| **OPERATOR** | View all data. Start/stop simulator. Manage devices (CRUD). Trigger readings. | All pages except user management |
| **VIEWER** | Read-only. View dashboards, sensors, alerts, statistics. | Dashboard, Sensors, Alerts (read-only) |

Permission mapping to endpoints:

| Endpoint Pattern | ADMIN | OPERATOR | VIEWER |
|-----------------|-------|----------|--------|
| `GET /api/analytics/**` | ✅ | ✅ | ✅ |
| `GET /api/gateway/**` | ✅ | ✅ | ✅ |
| `GET /api/simulator/status` | ✅ | ✅ | ✅ |
| `POST /api/simulator/**` | ✅ | ✅ | ❌ |
| `GET /api/devices/**` | ✅ | ✅ | ✅ |
| `POST/PUT/DELETE /api/devices/**` | ✅ | ✅ | ❌ |
| `GET /api/users/**` | ✅ | ❌ | ❌ |
| `POST/PUT/DELETE /api/users/**` | ✅ | ❌ | ❌ |
| `POST /api/auth/register` | ✅ | ❌ | ❌ |

### 4.4 JWT Token Structure

**Access Token (short-lived: 15 minutes):**
```json
{
  "iss": "http://iam-service:9000",
  "sub": "user-uuid-here",
  "aud": "smarthome-api",
  "exp": 1756100400,
  "iat": 1756099500,
  "jti": "unique-token-id",
  "scope": "openid profile",
  "roles": ["OPERATOR"],
  "username": "john.doe",
  "email": "john@example.com"
}
```

**Device Token (for IoT sensors):**
```json
{
  "iss": "http://iam-service:9000",
  "sub": "temp-sensor-001",
  "aud": "smarthome-api",
  "exp": 1756100400,
  "iat": 1756099500,
  "scope": "sensor:publish",
  "device_type": "TEMPERATURE",
  "location": "living-room"
}
```

> 🎓 **Why short-lived access tokens?** If a token is stolen, the damage window is limited to 15 minutes. The client uses a refresh token (stored securely) to get new access tokens without re-entering credentials. Refresh tokens can be revoked server-side.

### 4.5 Key Configuration Classes

**AuthorizationServerConfig.java:**
```java
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
            .oidc(Customizer.withDefaults());  // Enable OIDC

        http.exceptionHandling(e -> e
            .defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
            ));

        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        // Dashboard SPA — public client with PKCE
        RegisteredClient dashboardClient = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("smarthome-dashboard")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)  // Public client
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri("http://localhost:5173/callback")
            .redirectUri("http://localhost:3000/callback")
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .scope("dashboard:read")
            .scope("dashboard:write")
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)          // Enforce PKCE
                .requireAuthorizationConsent(false)
                .build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(15))
                .refreshTokenTimeToLive(Duration.ofDays(7))
                .reuseRefreshTokens(false)      // Rotate refresh tokens
                .build())
            .build();

        // Backend services — confidential client with client_credentials
        RegisteredClient gatewayClient = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("gateway-service")
            .clientSecret("{bcrypt}$2a$12$...")  // BCrypt-encoded secret
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("service:internal")
            .tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofHours(1))
                .build())
            .build();

        JdbcRegisteredClientRepository repo = new JdbcRegisteredClientRepository(jdbc);
        repo.save(dashboardClient);
        repo.save(gatewayClient);
        return repo;
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(
            UserRepository userRepo) {
        return context -> {
            if (context.getTokenType().getValue().equals("access_token")) {
                Authentication principal = context.getPrincipal();
                // Add custom claims: roles, username, email
                if (principal.getPrincipal() instanceof CustomUserDetails user) {
                    context.getClaims().claim("roles",
                        user.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .filter(a -> a.startsWith("ROLE_"))
                            .map(a -> a.substring(5))
                            .toList());
                    context.getClaims().claim("username", user.getUsername());
                    context.getClaims().claim("email", user.getEmail());
                }
            }
        };
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID(UUID.randomUUID().toString())
            .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

> 🎓 **RSA Key Pair:** The IAM service signs JWTs with a private RSA key. All other services fetch the **public key** via the JWKS endpoint (`/oauth2/jwks`) and validate token signatures locally — no network call to IAM on every request. This is why JWT auth scales well.

**CustomUserDetailsService.java:**
```java
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return new CustomUserDetails(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getPasswordHash(),
            user.isEnabled(),
            !user.isAccountLocked(),
            user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                .toList()
        );
    }
}
```

### 4.6 IAM Service application.yml

```yaml
server:
  port: 9000

spring:
  application:
    name: iam-service
  datasource:
    url: jdbc:postgresql://localhost:5432/smarthome_iam
    username: ${DB_USERNAME:smarthome}
    password: ${DB_PASSWORD:smarthome_db_pass}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate    # Use schema.sql for DDL
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  sql:
    init:
      mode: always
  security:
    oauth2:
      authorizationserver:
        issuer: ${IAM_ISSUER:http://localhost:9000}

logging:
  level:
    org.springframework.security: DEBUG    # Remove in production
```

---

## 5. Securing Existing Services (Resource Server)

### 5.1 Dependencies (add to each service's pom.xml)

```xml
<!-- Add via: mvn dependency:add ... or manually -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

### 5.2 Resource Server SecurityConfig (each service)

**gateway-service — SecurityConfig.java:**
```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Health/actuator endpoints — public
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                // Gateway stats — any authenticated user
                .requestMatchers(HttpMethod.GET, "/api/gateway/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                // Admin-only config endpoints
                .requestMatchers(HttpMethod.PUT, "/api/gateway/thresholds/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
            )
            .csrf(csrf -> csrf.disable())   // Stateless API
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        // Extract roles from custom "roles" claim
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.of();
            return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
        });
        return converter;
    }
}
```

> 🎓 **JwtAuthenticationConverter:** Spring Security's resource server reads `scope` claims by default and converts them to `SCOPE_xxx` authorities. Our IAM puts roles in a custom `roles` claim, so we write a converter to map them to `ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_VIEWER` — matching `@PreAuthorize("hasRole('ADMIN')")`.

**processing-service — SecurityConfig.java:**
```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/analytics/**").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
    // Same jwtAuthConverter() as gateway
}
```

**sensor-simulator-service — SecurityConfig.java:**
```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/simulator/status").hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/api/simulator/**").hasAnyRole("ADMIN", "OPERATOR")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
    // Same jwtAuthConverter() as gateway
}
```

### 5.3 Resource Server application.yml addition (all services)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${IAM_ISSUER:http://localhost:9000}
          jwk-set-uri: ${IAM_ISSUER:http://localhost:9000}/oauth2/jwks
```

> 🎓 **jwk-set-uri:** On startup, each service fetches the IAM's public keys from this URL and caches them. When a request arrives with a JWT, the service validates the signature locally using the cached public key — no IAM call needed. Keys auto-refresh every 5 minutes (Spring default).

### 5.4 Method-Level Security (optional fine-grained control)

```java
// In AnalyticsController.java
@GetMapping("/statistics")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
public ResponseEntity<Collection<SensorStatistics>> getAllStatistics() { ... }

// In SimulatorController.java
@PostMapping("/start")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public ResponseEntity<Map<String, Object>> startSimulation() { ... }
```

---

## 6. Spring Cloud Gateway

### 6.1 New Module Setup

```
api-gateway/
├── pom.xml
└── src/main/
    ├── java/com/smarthome/gateway/api/
    │   ├── ApiGatewayApplication.java
    │   ├── config/
    │   │   ├── SecurityConfig.java          # JWT validation
    │   │   ├── RouteConfig.java             # Route definitions
    │   │   └── RateLimitConfig.java         # Redis-based rate limiting
    │   └── filter/
    │       └── JwtRelayFilter.java          # Forward JWT to downstream services
    └── resources/
        └── application.yml
```

### 6.2 Route Configuration

```yaml
# api-gateway/application.yml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        - id: iam-service
          uri: http://localhost:9000
          predicates:
            - Path=/oauth2/**, /login, /userinfo, /.well-known/**
        - id: simulator-service
          uri: http://localhost:8081
          predicates:
            - Path=/api/simulator/**
        - id: gateway-service
          uri: http://localhost:8082
          predicates:
            - Path=/api/gateway/**
        - id: processing-service
          uri: http://localhost:8083
          predicates:
            - Path=/api/analytics/**
      default-filters:
        - TokenRelay     # Forward JWT to downstream services
  security:
    oauth2:
      client:
        registration:
          smarthome-dashboard:
            provider: iam
            client-id: smarthome-dashboard
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid,profile,dashboard:read,dashboard:write
        provider:
          iam:
            issuer-uri: http://localhost:9000
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9000
```

### 6.3 Gateway Security Config

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .authorizeExchange(auth -> auth
                // Public: OAuth2 endpoints, health checks
                .pathMatchers("/oauth2/**", "/login", "/.well-known/**").permitAll()
                .pathMatchers("/actuator/health").permitAll()
                // All API routes require authentication
                .pathMatchers("/api/**").authenticated()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
```

> 🎓 **TokenRelay filter:** When the gateway validates a JWT, it automatically forwards it to downstream services in the `Authorization: Bearer <token>` header. Each downstream service independently validates the same JWT — defense in depth.

---

## 7. RabbitMQ Message Security

### 7.1 Message-Level JWT Propagation

```java
// In gateway-service: attach JWT to outbound AMQP messages
@Component
public class SecureMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishWithAuth(String exchange, String routingKey, Object payload, String jwt) {
        rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> {
            message.getMessageProperties().setHeader("Authorization", "Bearer " + jwt);
            message.getMessageProperties().setHeader("X-Correlation-Id", UUID.randomUUID().toString());
            return message;
        });
    }
}
```

### 7.2 Message Signature (HMAC-SHA256)

```java
// Sign messages to prevent tampering
public class MessageSigner {
    private final SecretKey hmacKey;  // Shared secret from Vault

    public String sign(byte[] payload) {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        return Base64.getEncoder().encodeToString(mac.doFinal(payload));
    }

    public boolean verify(byte[] payload, String signature) {
        return MessageDigest.isEqual(
            Base64.getDecoder().decode(signature),
            Mac.getInstance("HmacSHA256").doFinal(payload)
        );
    }
}
```

### 7.3 RabbitMQ TLS & Per-Service Credentials

```yaml
# docker-compose.yml — RabbitMQ with TLS
rabbitmq:
  image: rabbitmq:3.13-management
  environment:
    RABBITMQ_SSL_CERTFILE: /certs/server.pem
    RABBITMQ_SSL_KEYFILE: /certs/server-key.pem
    RABBITMQ_SSL_CACERTFILE: /certs/ca.pem
    RABBITMQ_SSL_VERIFY: verify_peer
    RABBITMQ_SSL_FAIL_IF_NO_PEER_CERT: "true"
  volumes:
    - ./certs:/certs:ro
    - ./rabbitmq-definitions.json:/etc/rabbitmq/definitions.json:ro
```

```json
// rabbitmq-definitions.json — per-service credentials & permissions
{
  "users": [
    { "name": "simulator-svc", "password_hash": "...", "tags": "" },
    { "name": "gateway-svc",   "password_hash": "...", "tags": "" },
    { "name": "processing-svc","password_hash": "...", "tags": "" }
  ],
  "permissions": [
    { "user": "simulator-svc", "vhost": "/",
      "configure": "",
      "write": "sensor\\.exchange",
      "read": "" },
    { "user": "gateway-svc", "vhost": "/",
      "configure": "",
      "write": "sensor\\.exchange|alerts\\.exchange",
      "read": "sensor\\.readings\\.queue" },
    { "user": "processing-svc", "vhost": "/",
      "configure": "",
      "write": "",
      "read": "processed\\.data\\.queue|alerts\\.queue" }
  ]
}
```

> 🎓 **Principle of least privilege:** Each service can only access the exchanges/queues it needs. The simulator can only write to `sensor.exchange`, never read. The processing service can only read, never write. If one service is compromised, lateral movement is limited.

---

## 8. Device Authentication Flow

IoT devices (sensors) authenticate using pre-provisioned API keys:

```
1. Admin provisions device credentials:
   POST /api/devices/creds { deviceId: "temp-sensor-001", type: "TEMPERATURE" }
   → Returns: { apiKey: "uuid-key", apiSecret: "generated-secret" }

2. Device requests access token:
   POST /oauth2/token
   Content-Type: application/x-www-form-urlencoded
   grant_type=client_credentials&client_id={apiKey}&client_secret={apiSecret}&scope=sensor:publish

   → Returns: { access_token: "eyJ...", token_type: "Bearer", expires_in: 3600 }

3. Device publishes readings with token:
   - REST: POST /api/readings (Authorization: Bearer eyJ...)
   - AMQP: Message header { "Authorization": "Bearer eyJ..." }

4. Gateway validates device token before processing the reading
```

> 🎓 **Why client_credentials for devices?** IoT devices are "headless" — no user to interact with a login page. They use pre-provisioned credentials (API key + secret) to get tokens via the client_credentials grant. The token includes `scope: sensor:publish` limiting what the device can do.

---

## 9. Implementation Phases

### Phase 3A — IAM Service & Basic Auth (Week 9–10)

| Step | Action | Effort |
|------|--------|--------|
| 3A.1 | Create `iam-service` Maven module with Spring Authorization Server | 1 day |
| 3A.2 | PostgreSQL schema: `users`, `roles`, `user_roles`, `device_credentials`, `auth_events` | 0.5 day |
| 3A.3 | `CustomUserDetailsService` + `UserService` + `UserController` (admin CRUD) | 1 day |
| 3A.4 | `AuthorizationServerConfig` — registered clients (dashboard + services), PKCE, token customizer | 1 day |
| 3A.5 | Seed data: admin user, 3 roles, dashboard client, service clients | 0.5 day |
| 3A.6 | Add `spring-boot-starter-oauth2-resource-server` to gateway, processing, simulator | 0.5 day |
| 3A.7 | `SecurityConfig` (SecurityFilterChain + JwtAuthenticationConverter) in each service | 1 day |
| 3A.8 | Update `application.yml` in all services with `jwt.issuer-uri` | 0.5 day |
| 3A.9 | Integration test: login → get JWT → call protected endpoint → verify 200/403 | 1 day |

### Phase 3B — API Gateway & Device Auth (Week 11)

| Step | Action | Effort |
|------|--------|--------|
| 3B.1 | Create `api-gateway` Maven module with Spring Cloud Gateway | 1 day |
| 3B.2 | Route config: proxy all `/api/**` to correct backends | 0.5 day |
| 3B.3 | JWT validation + TokenRelay filter | 0.5 day |
| 3B.4 | Redis rate limiting (requests per user per minute) | 0.5 day |
| 3B.5 | Device credential provisioning: `DeviceCredentialService` + controller | 1 day |
| 3B.6 | Device auth flow: client_credentials grant for devices | 0.5 day |
| 3B.7 | Update dashboard Vite proxy to point to gateway (`:8080`) only | 0.5 day |
| 3B.8 | End-to-end test: device auth → publish → gateway validates → processing receives | 1 day |

### Phase 3C — RabbitMQ Security & Hardening (Week 12)

| Step | Action | Effort |
|------|--------|--------|
| 3C.1 | Generate TLS certificates (self-signed for dev, CA-signed for prod) | 0.5 day |
| 3C.2 | Configure RabbitMQ TLS in docker-compose | 0.5 day |
| 3C.3 | Per-service RabbitMQ credentials + topic permissions | 0.5 day |
| 3C.4 | Message signing (HMAC-SHA256) on publish + verify on consume | 1 day |
| 3C.5 | JWT propagation in AMQP message headers | 0.5 day |
| 3C.6 | Auth audit logging service (log auth_events to PostgreSQL) | 1 day |
| 3C.7 | Security integration tests (unauthorized access → 401, wrong role → 403) | 1 day |

---

## 10. Testing Strategy

### Unit Tests
```java
// Test JWT token customizer adds roles
@Test
void tokenContainsRolesClaim() {
    JwtEncodingContext context = mockContextWithUser("admin", List.of("ADMIN"));
    tokenCustomizer.customize(context);
    assertThat(context.getClaims().build().getClaim("roles"))
        .isEqualTo(List.of("ADMIN"));
}
```

### Integration Tests (Testcontainers)
```java
@SpringBootTest
@Testcontainers
class SecurityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void unauthenticatedRequest_returns401() {
        webTestClient.get().uri("/api/analytics/summary")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void viewerCannotStartSimulator() {
        String viewerToken = getTokenForRole("VIEWER");
        webTestClient.post().uri("/api/simulator/start")
            .headers(h -> h.setBearerAuth(viewerToken))
            .exchange()
            .expectStatus().isForbidden();
    }

    @Test
    void operatorCanStartSimulator() {
        String operatorToken = getTokenForRole("OPERATOR");
        webTestClient.post().uri("/api/simulator/start")
            .headers(h -> h.setBearerAuth(operatorToken))
            .exchange()
            .expectStatus().isOk();
    }
}
```

### Security Checklist
- [ ] Unauthenticated requests to `/api/**` return 401
- [ ] VIEWER cannot POST to simulator or devices
- [ ] OPERATOR cannot manage users
- [ ] ADMIN can access everything
- [ ] Expired tokens return 401
- [ ] Invalid/tampered tokens return 401
- [ ] JWKS endpoint is publicly accessible
- [ ] Health/actuator endpoints work without auth
- [ ] Device credentials grant returns scoped token
- [ ] Refresh token rotation works (old refresh token invalidated)
- [ ] Token revocation works
- [ ] RabbitMQ per-service credentials enforced
- [ ] Unsigned AMQP messages are rejected

---

## 11. Files Changed Summary

| Action | Service | Files |
|--------|---------|-------|
| **CREATE** | iam-service (new) | ~15 files: Application, configs, entities, repos, services, controllers, DTOs, schema.sql, data.sql, application.yml |
| **CREATE** | api-gateway (new) | ~5 files: Application, SecurityConfig, RouteConfig, RateLimitConfig, application.yml |
| **ADD** | gateway-service | SecurityConfig.java, pom.xml (2 deps), application.yml (jwt config) |
| **ADD** | processing-service | SecurityConfig.java, pom.xml (2 deps), application.yml (jwt config) |
| **ADD** | sensor-simulator-service | SecurityConfig.java, pom.xml (2 deps), application.yml (jwt config) |
| **MODIFY** | docker-compose.yml | Add PostgreSQL, TLS certs, per-service RabbitMQ creds |
| **ADD** | iot-common | Shared JwtAuthConverter utility class |
| **MODIFY** | parent pom.xml | Add iam-service and api-gateway modules |

---

## 12. Estimated Effort

| Phase | Effort | Complexity |
|-------|--------|------------|
| 3A: IAM + Resource Servers | 7 days | High |
| 3B: API Gateway + Device Auth | 5 days | Medium |
| 3C: RabbitMQ Security + Hardening | 5 days | Medium |
| **Total** | **17 days** | |

---

## 13. Key Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| Spring Authorization Server (not Keycloak) | Stays in Spring ecosystem, lighter weight, educational — you learn OAuth internals |
| Authorization Code + PKCE (not Implicit) | OAuth 2.1 best practice for SPAs; prevents code interception attacks |
| JWT with RS256 (not HS256) | Asymmetric: services validate with public key, no shared secret needed |
| 15-min access tokens | Limits blast radius of stolen tokens; refresh token handles renewal |
| Refresh token rotation | Each refresh generates new refresh token, invalidating the old one |
| Redis token blacklist | Enables instant revocation before token expiry |
| Per-service RabbitMQ credentials | Principle of least privilege; limits blast radius of compromised service |
| HMAC-SHA256 message signing | Prevents message tampering in transit (defense in depth with TLS) |
| JwtAuthConverter in iot-common | Single implementation shared by all services; DRY |
| Spring Cloud Gateway (not nginx) | JWT validation built-in, reactive, same language as backend |

---

## 14. Rollback Plan

| Phase | Rollback |
|-------|----------|
| 3A | Remove SecurityConfig + resource-server deps from services. Delete iam-service module. |
| 3B | Delete api-gateway module. Revert Vite proxy to individual service ports. |
| 3C | Revert docker-compose to plain RabbitMQ. Remove message signing code. |

All phases are additive — existing service logic is unchanged. Security is layered on top via SecurityFilterChain (a separate bean, not modifying controllers).
