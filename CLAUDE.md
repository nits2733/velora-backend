# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Summary

- Velora: backend for a home-services marketplace. Two journeys share one data model: **Full Home Services** (designer-led interior-design project) and **Individual Services** (standalone trade job — painting, plumbing, electrical, carpentry, false ceiling, modular kitchen).
- Flow: Booking → Quotation → Project Execution *(not built)* → Completion → Review.
- Architecture: classic layered monolith (Controller → Mapper → Service → Repository → Postgres).
- Status: MVP. Auth, booking, quotation, portfolio, professional matching, and reviews are built and tested. Project execution/scheduling, payments, notifications, and image upload are explicitly not built — see README "Explicitly out of scope so far".

## Tech Stack

- Java 21, Spring Boot 3.3 (web, data-jpa, security, validation, actuator)
- PostgreSQL hosted on Neon; Flyway owns the schema
- JWT auth (jjwt), BCrypt password hashing
- springdoc-openapi (Swagger UI)
- spring-dotenv (auto-loads `.env`)
- Maven build; Lombok
- Tests: JUnit 5, Mockito, AssertJ, spring-security-test, H2 (in-memory, no real DB needed for `mvn test`)

## Architecture

- Layered, one direction, never skipped: `Controller → Mapper → Service → Repository → PostgreSQL`.
- Request lifecycle: `JwtAuthFilter` (stateless, before `UsernamePasswordAuthenticationFilter`) → `SecurityConfig` route/role rules (`@PreAuthorize`) → Controller validates (`@Valid`) and maps DTO→entity via Mapper → Service applies business/state rules inside `@Transactional` → Repository (Spring Data derived methods / `Specification` joins only) → entity mapped back to response DTO.
- Errors: `@RestControllerAdvice` (`GlobalExceptionHandler`) converts every exception to one `ApiErrorResponse` shape; `RestSecurityErrorHandler` does the same for security-chain 401/403 (so even auth failures never fall through to Spring's default body).
- Design pattern of note: optional 1-to-1 "satellite" entities extend a generic entity without a metadata blob or JPA inheritance — see `User`+`ProfessionalProfile` and `PortfolioItem`+`InteriorDesignDetails`.
- Dependency direction: entity/repository know nothing about service; service knows nothing about controller/DTO; DTOs and mappers are the only thing crossing the service boundary.

## Folder Structure

```
src/main/java/com/velora/backend/
  controller/   HTTP endpoints, @PreAuthorize, request-shape only
  dto/          request/response records
  mapper/       entity <-> DTO conversion, no business logic
  service/      business rules, state transitions, @Transactional
  repository/   Spring Data JPA interfaces (derived methods / Specification only)
  entity/       JPA entities + enums
  security/     JWT filter/service, UserPrincipal, CustomUserDetailsService, RestSecurityErrorHandler
  config/       SecurityConfig, CORS/JWT/auth @ConfigurationProperties, OpenAPI, JPA auditing
  exception/    domain exceptions + GlobalExceptionHandler + ApiErrorResponse
  util/         PageResponse and similar small shared helpers

  entity/, service/, repository/, controller/, mapper/, and dto/ are each subpackaged by
  domain (auth, booking, portfolio, professional, quotation, review, user, notification,
  media, upload, favorites). service/ and controller/ additionally have a `common`
  subpackage for the rare piece genuinely shared across domains (e.g. RateLimiterService).
  security/, config/, exception/, util/ stay flat - they're not domain-scoped.
src/main/resources/db/migration/   Flyway V1..Vn, additive only (see Architecture Rules)
src/test/java/.../service/         business-rule tests (Mockito)
src/test/java/.../controller/      @WebMvcTest against the real security chain
```

Full endpoint list and module table: README.md. Deeper per-layer walkthrough + flow diagrams: `IMPLEMENTATION_FLOW.md`.

## Core Domain

- **User** (`CUSTOMER`/`PROFESSIONAL`/`ADMIN`) —(1:0..1)→ **ProfessionalProfile** (bio, specialization, city, availability, rating) if role is `PROFESSIONAL`.
- **Category** — classifies both `PortfolioItem` and `Booking`; tagged `HOME_PROJECT` or `INDIVIDUAL_SERVICE` (`ServiceGroup`).
- **PortfolioItem** —(1:0..1)→ **InteriorDesignDetails** (styleTag, priceEstimate) — populated only for interior-design work.
- **Booking**: customer + optionally-assigned professional + category, `requestType` (`FULL_HOME_PROJECT` | `INDIVIDUAL_SERVICE`), `status` state machine, optional reference to a `PortfolioItem`. FULL_HOME_PROJECT may name a professional directly or ask Velora to assign (admin gets ranked candidates from `ProfessionalMatchingService`); INDIVIDUAL_SERVICE is always Velora-assigned.
- **Booking** —(1:0..1)→ **Quotation** (draft → sent → accepted/rejected, with line items) and —(1:0..1)→ **Review** (1–5 rating + comment, only after COMPLETED, recomputes the professional's aggregate rating).
- **RefreshToken** / **PasswordResetToken**: opaque, stored only as SHA-256 digest, revocable/single-use — never the JWT itself.

## Coding Conventions

- Packages by layer, then by domain within `dto/` (e.g. `dto.booking`, `dto.quotation`).
- DTOs are Java records; bean validation annotations live on the DTO fields, checked via `@Valid` at the controller boundary.
- Entities never returned from controllers or accepted as request bodies — always DTO ⇄ entity via a Mapper.
- Transactions start at the service method (`@Transactional`), never in controller or repository.
- Every thrown domain exception maps to one HTTP status in `GlobalExceptionHandler`: `ResourceNotFoundException`→404, `DuplicateResourceException`→409, `UnauthorizedActionException`→403, `InvalidStateTransitionException`→409, `IllegalArgumentException`→400 (used for request-shape rules that aren't expressible as bean validation, e.g. cross-field rules in `BookingService.createBooking`).
- Login failures always return the same vague "Invalid email or password" (`BadCredentialsException` handler) to avoid account enumeration; every other 401 (`AuthenticationFailedException`) states what actually failed, since the caller already holds the rejected token.
- List endpoints are paginated (`page`/`size`, clamped to a `MAX_PAGE_SIZE = 50` in the controller) from day one.

## Development Guidelines

- Business logic and state-transition rules belong in `service/`, never in controllers or mappers.
- Cross-field/request-shape validation that bean validation can't express (e.g. "individual service requests must specify a category") belongs in the service method, thrown as `IllegalArgumentException`.
- Repositories stay derived-method/`Specification` only — if a query needs `@Query`/native SQL, that's a signal to reconsider the approach, not add one. Exception: bulk-update statements that can't be expressed as a derived method (see `RefreshTokenRepository.revokeAllForUser`, `PasswordResetTokenRepository.invalidateOutstandingForUser`).
- Controllers: route mapping, `@PreAuthorize`, `@Valid`, pull `principal.getId()`/`getRole()` from `@AuthenticationPrincipal UserPrincipal`, delegate everything else to the service.
- New Flyway migrations only — never edit an applied `V*` file, even to fix a mistake in it (see V6 and V10, which exist purely to correct earlier migrations).
- When adding an optional per-trade or per-role attribute set, prefer the satellite-entity pattern already used twice over widening a shared table or adding a JSON blob.

## Architecture Rules

- Controllers stay thin — no business logic, no direct repository access.
- Business logic only in services; services are the only `@Transactional` boundary.
- Never expose JPA entities directly in the API — DTOs both directions.
- Repositories: derived methods and `Specification` only for reads; `@Query` reserved for bulk-update statements that have no derived-method equivalent (existing precedent: `RefreshTokenRepository`, `PasswordResetTokenRepository`) — never native SQL.
- Flyway migrations are append-only; `ddl-auto` is `validate`, never rely on Hibernate auto-DDL.
- Every exception surfaces through `GlobalExceptionHandler`/`RestSecurityErrorHandler` into the one `ApiErrorResponse` shape — no ad hoc error bodies from controllers.

## Important Business Rules

- `AuthService.register` unconditionally rejects role `ADMIN` — the only admin account is DB-seeded (V4 migration); there is no self-registration path to admin.
- Professional matching score is deterministic, out of 100: specialization 30, portfolio evidence 20 (category 10 + style 10), experience 15 (capped at 10 years), location 15, rating 15 (unrated professionals default to a neutral 3.0, not zero), budget fit 5. It always yields a ranked list for an admin to confirm — never a silent auto-assignment.
- A booking can only reference a `PortfolioItem` when `professionalId` is also given explicitly.
- Professional "availability" is a self-managed on/off flag, not a real calendar — matching just filters on it.
- Quotation line items and the priced artifact only ever come from a `Quotation` — portfolio items are never directly purchasable.
- Deleting a `PortfolioItem` is refused with 409 while any booking still references it.

## Security Rules

- Stateless JWT auth (`SessionCreationPolicy.STATELESS`); access token is short-lived and cannot be revoked.
- Refresh token: long-lived, opaque, stored only as a SHA-256 digest, revocable, rotated on every use (`/auth/refresh`).
- Password reset uses the same opaque-token-digest pattern; single-use, revokes all sessions on success. `PasswordResetNotifier` currently only has a logging implementation (`LoggingPasswordResetNotifier`) — no real email delivery is wired up yet.
- `/auth/password/forgot` always returns 202 regardless of whether the email exists (no account enumeration).
- Public endpoints are listed explicitly in `SecurityConfig`, not wildcarded under `/api/auth/**` — `logout-all` and `password/change` sit under that prefix but require a token, so a wildcard would silently expose them.
- Role checks via `@PreAuthorize("hasRole(...)")` at the controller method; `@EnableMethodSecurity` is on.
- `JWT_SECRET` (32+ bytes) is required at startup with no silent default in real deployments; the `local` Spring profile supplies a placeholder for convenience only.
- Passwords hashed with BCrypt; never logged or returned in any DTO.

## Common Commands

```bash
mvn spring-boot:run                                     # run app (needs real .env — see .env.example)
mvn spring-boot:run -Dspring-boot.run.profiles=local     # run w/ placeholder JWT secret + verbose SQL logging
mvn test                                                 # run all tests (H2 in-memory, no real DB needed)
mvn test -Dtest=BookingServiceTest                       # single test class
mvn test -Dtest=BookingServiceTest#createBooking_rejectsX # single test method
```

Swagger UI: `/swagger-ui/index.html`. OpenAPI JSON: `/v3/api-docs`. Health: `/actuator/health`. No lint/format tooling configured.

## AI Instructions

- Follow the existing layered architecture and naming; reuse existing patterns (satellite entities, DTO records, exception-per-status) before introducing new ones.
- Prefer consistency over cleverness; keep changes localized to the layer/domain package being touched.
- Do not refactor unrelated code or files while completing a task.
- Do not add a new Flyway migration pattern, error-response shape, or query style without checking the existing convention first — if a new pattern is genuinely needed, explain why before introducing it.
- Update README.md/`IMPLEMENTATION_FLOW.md` when architecture or the API surface changes; don't let this file go stale with endpoint-level detail (keep that in README).
- Ask before making breaking architectural changes (new layer, dropping the zero-`@Query` rule, switching auth model, editing an applied migration).

## Repository Notes

- No test runs against a real Postgres instance — repository queries and Flyway migrations are only exercised by actually running the app against Neon (`mvn spring-boot:run`).
- Stray `run*.log`/`app.log` files at repo root are local run artifacts, not checked-in fixtures — don't treat them as project state.
- The demo professional account's email still says `designer@velora.com`: seeded before the `DESIGNER`→`PROFESSIONAL` rename (V7), only the `role` column changed.
- TODO: no CI config, linter, or formatter found in the repo — confirm with the user before assuming one exists.
