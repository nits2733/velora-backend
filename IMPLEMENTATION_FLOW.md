# Velora Backend — Implementation Flow & Learning Guide

This document explains **everything implemented so far** in the Velora backend, in the
order a developer would naturally learn it: starting the app, understanding the folder
structure, then following one real request (register → login → call a protected
endpoint) all the way through the code.

It assumes you already know basic Java and Spring Boot syntax (annotations, classes,
interfaces). Every section explains **what** a piece is, **why** it exists, and **how**
it works here, with small examples.

---

## Table of Contents

1. [Project Purpose & Tech Stack](#1-project-purpose--tech-stack)
2. [Project Structure](#2-project-structure)
3. [Application Startup Flow](#3-application-startup-flow)
4. [Configuration Layer](#4-configuration-layer)
5. [Database Design](#5-database-design)
6. [Entities (JPA Layer)](#6-entities-jpa-layer)
7. [DTOs — Why We Never Return Entities Directly](#7-dtos--why-we-never-return-entities-directly)
8. [Repositories (Data Access Layer)](#8-repositories-data-access-layer)
9. [Security Architecture (Big Picture)](#9-security-architecture-big-picture)
10. [Authentication Flow — Register](#10-authentication-flow--register)
11. [Authentication Flow — Login](#11-authentication-flow--login)
12. [How a Protected Request Is Authenticated (JWT Filter)](#12-how-a-protected-request-is-authenticated-jwt-filter)
13. [Authorization — Roles and `@PreAuthorize`](#13-authorization--roles-and-preauthorize)
14. [Full Request Lifecycle (Controller → Service → Repository → DB)](#14-full-request-lifecycle-controller--service--repository--db)
15. [Feature Walkthrough: Portfolio Catalog (Search/Filter/Pagination)](#15-feature-walkthrough-portfolio-catalog-searchfilterpagination)
16. [Feature Walkthrough: Bookings (State Machine)](#16-feature-walkthrough-bookings-state-machine)
17. [Feature Walkthrough: Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching)
18. [Feature Walkthrough: Quotations (Post-Consultation Estimate)](#18-feature-walkthrough-quotations-post-consultation-estimate)
19. [Feature Walkthrough: Reviews & Ratings](#19-feature-walkthrough-reviews--ratings)
20. [Feature Walkthrough: User Profile (Role-Conditional Data)](#20-feature-walkthrough-user-profile-role-conditional-data)
21. [Validation Flow](#21-validation-flow)
22. [Exception Handling Flow](#22-exception-handling-flow)
23. [API Documentation (Swagger/OpenAPI)](#23-api-documentation-swaggeropenapi)
24. [Environment & Secrets Management](#24-environment--secrets-management)
25. [Quick Reference: All Endpoints](#25-quick-reference-all-endpoints)
26. [Sessions, Logout & Password Recovery](#26-sessions-logout--password-recovery)
27. [Appendix: End-to-End Flow Diagrams (No Code)](#27-appendix-end-to-end-flow-diagrams-no-code)

---

## 1. Project Purpose & Tech Stack

Velora is the backend API for a home-services app. It supports two customer journeys
under one shared domain model:

- **Full Home Services** — a designer-led, end-to-end interior-design project.
- **Individual Services** — a standalone trade job (painting, plumbing, electrical,
  carpentry, false ceiling, modular kitchen).

Velora is **admin-controlled assignment, not a marketplace**: a customer never browses,
searches, or names a professional directly, for either request type. Every booking
starts `PENDING_ASSIGNMENT`; an admin reviews it and assigns a professional.

Three kinds of users interact with the system:

- **Customers** — submit a booking request (no professional/portfolio pick involved),
  receive/respond to line-item quotations after a consultation, and leave a rating once
  the work is done.
- **Professionals** — one account type covering every trade (interior designer,
  painter, plumber, electrician, carpenter, ...). They maintain a profile and portfolio
  (conceptually; portfolio upload itself is not yet built), toggle their own
  availability, manage the bookings customers make with them, and turn a booking into a
  scoped quotation (line items + total) once the consultation has happened.
- **Admins** — the only role that can't self-register (see
  [Authentication Flow — Register](#10-authentication-flow--register)) — review
  bookings awaiting assignment (every Individual Service request, plus any Full Home
  Services request that asked Velora to choose), request a ranked list of candidate
  professionals for one, and assign the pick.
- All three share one `users` table, distinguished by a `role` column.

A booking's purpose is to get a professional and customer talking; a **quotation** is
what turns that conversation into a priced, actionable scope of work the customer can
accept or reject. See
[Feature Walkthrough: Quotations](#18-feature-walkthrough-quotations-post-consultation-estimate).
A professional isn't always picked by the customer directly — see
[Feature Walkthrough: Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching)
for the "let Velora choose" path, and
[Feature Walkthrough: Reviews & Ratings](#19-feature-walkthrough-reviews--ratings) for
how a completed booking's outcome feeds back into that same matching logic.

**Tech stack:**

| Concern              | Technology                                  |
|----------------------|----------------------------------------------|
| Language / runtime   | Java 21                                      |
| Framework            | Spring Boot 3.3.4                            |
| Web layer            | Spring MVC (`spring-boot-starter-web`)       |
| Data access           | Spring Data JPA + Hibernate                  |
| Database              | PostgreSQL (hosted on Neon, a serverless Postgres provider) |
| Migrations            | Flyway                                       |
| Security              | Spring Security 6 (stateless, JWT-based)     |
| Token format          | JWT (via `jjwt` library)                     |
| Validation            | Jakarta Bean Validation (`spring-boot-starter-validation`) |
| API docs              | springdoc-openapi (Swagger UI)               |
| Boilerplate reduction | Lombok                                       |
| Build tool            | Maven                                        |

---

## 2. Project Structure

```
src/main/java/com/velora/backend/
├── VeloraBackendApplication.java   # Entry point (main method)
├── config/                          # App-wide configuration & settings classes
│   ├── SecurityConfig.java          # Security rules, CORS, password encoder
│   ├── JwtProperties.java           # Binds app.jwt.* properties, validates on startup
│   ├── AuthProperties.java          # Binds app.auth.* - refresh + reset token lifetimes
│   ├── JpaAuditingConfig.java       # Enables @CreatedDate/@LastModifiedDate
│   ├── CorsProperties.java          # Binds app.cors.* properties
│   └── OpenApiConfig.java           # Swagger/OpenAPI setup
├── controller/                      # REST endpoints (the "front door")
│   ├── AuthController.java          # /api/auth/**
│   ├── UserController.java          # /api/users/**
│   ├── PortfolioItemController.java # /api/portfolio/**
│   ├── CategoryController.java      # /api/categories/**
│   ├── ProfessionalController.java  # /api/professionals/** (directory, profile, reviews)
│   ├── BookingController.java       # /api/bookings/** (incl. /assign, /recommendations)
│   ├── QuotationController.java     # /api/bookings/{bookingId}/quotation
│   └── ReviewController.java        # /api/bookings/{bookingId}/review
├── service/                          # Business logic (the "brain")
│   ├── AuthService.java               # Register, login, refresh, logout
│   ├── RefreshTokenService.java       # Issues/rotates/revokes long-lived sessions
│   ├── PasswordService.java           # Forgot, reset and change password
│   ├── UserService.java
│   ├── PortfolioItemService.java
│   ├── CategoryService.java
│   ├── ProfessionalService.java       # Public professional profile lookup
│   ├── BookingService.java
│   ├── ProfessionalMatchingService.java # Scores/ranks candidate professionals
│   ├── QuotationService.java
│   └── ReviewService.java
├── repository/                       # Data access (talks to the database)
│   ├── UserRepository.java
│   ├── ProfessionalProfileRepository.java
│   ├── PortfolioItemRepository.java
│   ├── PortfolioItemSpecifications.java  # Dynamic query building for search/filter
│   ├── CategoryRepository.java
│   ├── BookingRepository.java
│   ├── QuotationRepository.java
│   └── ReviewRepository.java
├── entity/                            # JPA entities = database tables as Java classes
│   ├── User.java, Role.java          # Role: CUSTOMER, PROFESSIONAL, ADMIN
│   ├── ProfessionalProfile.java, AvailabilityStatus.java
│   ├── Category.java, ServiceGroup.java   # ServiceGroup: HOME_PROJECT, INDIVIDUAL_SERVICE
│   ├── PortfolioItem.java            # Generic work-sample entity, every trade
│   ├── InteriorDesignDetails.java    # Optional 1-to-1 satellite off PortfolioItem
│   ├── Booking.java, BookingStatus.java, RequestType.java
│   │      # BookingStatus incl. PENDING_ASSIGNMENT; RequestType: FULL_HOME_PROJECT, INDIVIDUAL_SERVICE
│   ├── Quotation.java, QuotationLineItem.java, QuotationStatus.java
│   └── Review.java
├── dto/                               # Data Transfer Objects = what the API sends/receives
│   ├── auth/        (RegisterRequest, LoginRequest, AuthResponse)
│   ├── user/        (UserProfileResponse, UpdateProfileRequest, ProfessionalProfileResponse)
│   ├── portfolio/   (PortfolioItemResponse, PortfolioItemSummaryResponse, CategoryResponse)
│   ├── professional/(ProfessionalPublicProfileResponse)
│   ├── booking/     (BookingRequest, BookingResponse, BookingStatusUpdateRequest,
│   │                 AssignProfessionalRequest, ProfessionalMatchResponse)
│   ├── quotation/   (SaveQuotationRequest, QuotationLineItemRequest,
│   │                 QuotationResponse, QuotationLineItemResponse)
│   └── review/      (CreateReviewRequest, ReviewResponse)
├── mapper/                            # Converts entities <-> DTOs
│   ├── UserMapper.java
│   ├── PortfolioItemMapper.java
│   ├── ProfessionalMapper.java
│   ├── BookingMapper.java
│   ├── QuotationMapper.java
│   └── ReviewMapper.java
├── security/                          # JWT + Spring Security plumbing
│   ├── JwtService.java               # Create/parse/validate tokens
│   ├── JwtAuthFilter.java            # Runs on every request, checks the token
│   ├── CustomUserDetailsService.java # Loads a User from the DB for Spring Security
│   ├── UserPrincipal.java            # Adapts our User entity to Spring Security's UserDetails
│   └── RestSecurityErrorHandler.java # Returns JSON 401 / 403 for filter-chain failures
├── exception/                         # Custom exceptions + centralized error handling
│   ├── GlobalExceptionHandler.java   # Catches exceptions app-wide, builds error JSON
│   ├── ApiErrorResponse.java         # Shape of every error response
│   ├── ResourceNotFoundException.java
│   ├── DuplicateResourceException.java
│   ├── InvalidStateTransitionException.java
│   └── UnauthorizedActionException.java
└── util/
    └── PageResponse.java              # Generic wrapper for paginated API responses

src/main/resources/
├── application.yml                   # Main config (reads from env vars)
├── application-local.yml             # Overrides for local dev (`local` profile)
└── db/migration/
    ├── V1__init_schema.sql                       # Creates all tables (original names: designer_profiles, designs)
    ├── V2__seed_data.sql                         # Seed categories, a demo designer, sample designs
    ├── V3__quotations.sql                        # Adds quotations + quotation_line_items tables
    ├── V4__admin_and_designer_assignment.sql     # ADMIN role, nullable booking.designer_id, seeds one admin
    ├── V5__ratings_and_availability.sql          # Adds reviews table; availability/rating columns; booking matching columns
    ├── V6__widen_review_rating_column.sql        # Fixes a column-type mismatch found after V5
    ├── V7__rename_designer_to_professional_and_add_request_types.sql
    │      # DESIGNER→PROFESSIONAL, designer_profiles→professional_profiles, every
    │      # designer_id column→professional_id; adds bookings.request_type and
    │      # categories.service_group; seeds the six Individual Service categories
    └── V8__generalize_design_to_portfolio_item.sql
           # designs→portfolio_items, bookings.design_id→portfolio_item_id;
           # splits style_tag/price_estimate into a new interior_design_details table
```

**Why this layered structure?**
Each layer has exactly one job, and only talks to the layer directly below it:

```
Controller  →  Service  →  Repository  →  Database
   (HTTP)      (business        (data
                 rules)          access)
```

This is the standard **layered architecture**. It keeps HTTP concerns (status codes,
JSON) out of business logic, and keeps business logic out of SQL/JPA details. It also
makes each layer independently testable.

---

## 3. Application Startup Flow

When you run `mvn spring-boot:run`, here's what happens, roughly in order:

1. **`VeloraBackendApplication.main()`** runs `SpringApplication.run(...)`.
   - `@SpringBootApplication` triggers component scanning (finds all `@Component`,
     `@Service`, `@Repository`, `@RestController`, `@Configuration` classes under
     `com.velora.backend`) and auto-configuration (Spring Boot guesses sensible
     defaults based on what's on the classpath — e.g. seeing `postgresql` + `flyway-core`
     on the classpath makes it auto-configure a Flyway migration runner).
   - `@EnableJpaAuditing` turns on automatic population of `@CreatedDate` /
     `@LastModifiedDate` fields on entities (see [Entities](#6-entities-jpa-layer)).

2. **Configuration properties are bound.** Spring reads `application.yml` (and
   `application-local.yml` if the `local` profile is active), resolving placeholders
   like `${DB_URL:jdbc:postgresql://localhost:5432/velora}` from environment variables
   (falling back to the default after the colon if the env var isn't set).
   - `JwtProperties` and `CorsProperties` are `@ConfigurationProperties` beans that
     capture `app.jwt.*` and `app.cors.*` values.
   - `JwtProperties.validate()` runs via `@PostConstruct` — if `JWT_SECRET` is missing
     or too short, **the app refuses to start**. This is a deliberate safety guard:
     it's better to fail loudly at boot than to silently sign tokens with a weak/empty key.

3. **Flyway runs database migrations** before JPA touches the database. It looks in
   `classpath:db/migration` for versioned SQL files (`V1__init_schema.sql`, etc.),
   checks a `flyway_schema_history` table to see which have already run, and applies
   any new ones in order. This is why the tables already exist by the time Hibernate
   starts validating the schema.

4. **Hibernate (JPA) validates the schema.** `spring.jpa.hibernate.ddl-auto: validate`
   means Hibernate does **not** create/alter tables itself — it only checks that the
   entity classes match what Flyway already created, and fails startup if they don't
   match. (This is the correct production-safe setting: Flyway owns the schema, JPA
   just uses it.)

5. **Spring Security's filter chain is built** (see `SecurityConfig`), wiring in the
   JWT filter, CORS rules, and which URLs are public vs. protected.

6. **Embedded Tomcat starts** on the configured port (`8080` by default), and the app
   is ready to accept HTTP requests.

If any step fails (bad DB credentials, missing JWT secret, schema mismatch), the whole
startup fails — Spring Boot's philosophy is "fail fast, fail loud" rather than starting
in a broken half-working state.

---

## 4. Configuration Layer

**What:** Plain Java classes annotated with `@ConfigurationProperties` that give
type-safe access to groups of settings from `application.yml`, instead of littering
`@Value("${...}")` everywhere.

**Why:** Centralizes related settings, validates them once at startup, and lets IDEs
autocomplete property names.

**How it works here:**

```yaml
app:
  jwt:
    secret: ${JWT_SECRET:}
    expiration-ms: ${JWT_EXPIRATION_MS:900000}
    issuer: velora-backend
  auth:
    refresh-token-ttl: ${REFRESH_TOKEN_TTL:30d}
    password-reset-ttl: ${PASSWORD_RESET_TTL:30m}
```

maps directly onto:

```java
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {
    private String secret;
    private long expirationMs;
    private String issuer;
    // getters/setters...
}
```

Similarly `CorsProperties` binds `app.cors.allowed-origins` (a comma-separated string)
and exposes it as a clean `List<String>` via `allowedOriginsList()`.

`OpenApiConfig` is a bit different — it's a `@Configuration` class that defines a
`@Bean` describing the Swagger UI's metadata (title, version) and tells it that every
endpoint can accept a `Bearer <token>` header (a security scheme named `bearerAuth`).

---

## 5. Database Design

**Why Postgres + Flyway:** Postgres is a solid relational database well-suited to the
relational nature of this domain (users have profiles, portfolio items belong to
professionals, bookings link customers to professionals to portfolio items). Flyway
gives us **version-controlled, repeatable schema changes** — every environment (your
laptop, a teammate's laptop, production) applies the exact same SQL in the exact same
order, tracked in a `flyway_schema_history` table.

**Schema (current, after `V8`):**

```
users                       professional_profiles
┌─────────────────┐         ┌───────────────────────┐
│ id (PK)         │◄───────┐│ id (PK)                │
│ email (unique)  │        └│ user_id (FK, unique)   │ 1-to-1
│ password_hash   │         │ bio                    │
│ full_name       │         │ years_experience       │
│ phone           │         │ specialization         │
│ role            │         │ city                   │
│ created_at      │         │ availability_status    │  ← V5
│ updated_at      │         │ average_rating         │  ← V5 (recomputed from reviews)
└─────────────────┘         │ rating_count            │  ← V5
        ▲   ▲                └───────────────────────┘
        │   │
        │   └───────────────────────┐
        │                           │
categories                portfolio_items │        bookings
┌────────────────┐        ┌──────────┴──────┐    ┌──────────────────────────┐
│ id (PK)        │◄───────│ category_id (FK) │    │ id (PK)                   │
│ name (unique)  │       1│ professional_id  │───►│ customer_id (FK)──────────┼─► users
│ description    │  to    │  (FK)            │    │ professional_id (FK, null)┼─► users   ← V4: nullable
│ service_group  │← V7   many                │    │ portfolio_item_id (null)  │─► portfolio_items ← V8: renamed
└────────────────┘        │ id (PK)          │    │ category_id (FK, null)    │─► categories  ← V5
        ▲                 │ title            │    │ request_type              │  ← V7
        │                 │ description      │    │ preferred_style           │  ← V5
        │                 │ cover_image_url  │    │ budget                    │  ← V5
        │                 │ created_at       │    │ location                  │  ← V5
        │                 │ updated_at       │    │ scheduled_at              │
        │                 └────────┬─────────┘    │ status                    │  ← V4: + PENDING_ASSIGNMENT
        │                          │ 1-to-0/1 (V8) │ notes                     │
        │                          ▼               │ created_at                │
        │              interior_design_details      │ updated_at                │
        │              ┌─────────────────────┐      └──────────┬───────────────┘
        │              │ id (PK)              │                 │
        └──────────────┤ portfolio_item_id    │  ┌──────────────┼────────────────────────┐
        (only for       │  (FK, unique)        │  │ 1-to-1 (booking_id UNIQUE)             │ 1-to-1 (booking_id UNIQUE, V5)
         interior-       │ style_tag            │  ▼                                        ▼
         design items)   │ price_estimate       │ quotations                        reviews
                         └─────────────────────┘ ┌─────────────────────┐            ┌──────────────────┐
                                                   │ id (PK)              │            │ id (PK)            │
                                                   │ booking_id (FK, unique) │         │ booking_id (FK, unique) │
                                                   │ status                │            │ customer_id (FK) │
                                                   │ total_amount          │            │ professional_id (FK) │← V7
                                                   │ notes                 │            │ rating (1-5)      │
                                                   │ created_at            │            │ comment            │
                                                   │ updated_at            │            │ created_at         │
                                                   └──────────┬────────────┘           └──────────────────┘
                                                              │ 1-to-many
                                                              ▼
                                                   quotation_line_items
                                                   ┌──────────────────────┐
                                                   │ id (PK)               │
                                                   │ quotation_id (FK)     │
                                                   │ description           │
                                                   │ quantity              │
                                                   │ unit                  │
                                                   │ unit_price            │
                                                   │ amount                │
                                                   │ sort_order            │
                                                   └──────────────────────┘
```

**Relationships:**

| Table                | Relationship                                                     |
|-----------------------|--------------------------------------------------------------------|
| `users` ↔ `professional_profiles` | One-to-one. Only users with `role = PROFESSIONAL` get a row here. Table was `designer_profiles` before `V7`. |
| `categories` ↔ `portfolio_items`  | One-to-many. Every portfolio item belongs to exactly one category. Table was `designs` before `V8`. |
| `users` ↔ `portfolio_items`        | One-to-many (as professional). A professional can publish many portfolio items. |
| `portfolio_items` ↔ `interior_design_details` | One-to-**zero-or-one**, added in `V8`. `portfolio_item_id UNIQUE` — populated only when the item is interior-design work (style + price estimate); absent for every other trade's items. |
| `categories` ↔ `service_group`     | Every category is tagged, added in `V7`, either `HOME_PROJECT` (an interior-project room type) or `INDIVIDUAL_SERVICE` (a standalone trade). `Booking.requestType` must match the category's group. |
| `users` ↔ `bookings`               | One-to-many, **twice** — once as `customer_id`, once as `professional_id`. `professional_id` became **nullable** in `V4` — a booking can exist with no professional at all while awaiting assignment. |
| `portfolio_items` ↔ `bookings`     | Optional. A booking can reference a specific portfolio item, or be a general consultation (`portfolio_item_id` is nullable). |
| `categories` ↔ `bookings`          | Optional, added in `V5`. Only populated on the "let Velora choose" path (or always, for Individual Service) — it's one of the signals `ProfessionalMatchingService` scores candidates against, alongside the also-new `preferred_style`/`budget`/`location` columns. |
| `bookings` ↔ `quotations`          | One-to-one, at most one quotation per booking (`booking_id UNIQUE`). Added in `V3__quotations.sql` — this is the artifact a professional produces after a consultation: a priced, itemized scope the customer can accept or reject. |
| `quotations` ↔ `quotation_line_items` | One-to-many. Each line item is one priced row (e.g. "Modular kitchen — ₹50,000"); `total_amount` on the quotation is the sum of all its line items' `amount`. |
| `bookings` ↔ `reviews`             | One-to-one, at most one review per booking (`booking_id UNIQUE`). Added in `V5` — only creatable once a booking is `COMPLETED`. |
| `users` ↔ `reviews`                | One-to-many, **twice** — once as `customer_id` (who wrote it), once as `professional_id` (who it's about) — same two-FKs-to-the-same-table pattern `bookings` already uses. |

**Constraints worth noting:**
- `role` and `status` columns use SQL `CHECK` constraints as a database-level safety net,
  in addition to Java enum validation. `users.role` allows `CUSTOMER`, `PROFESSIONAL`,
  or `ADMIN` (widened in `V4` to add `ADMIN`, then `DESIGNER` renamed to `PROFESSIONAL`
  in `V7`); `bookings.status` also allows `PENDING_ASSIGNMENT` (`V4`);
  `bookings.request_type` (new in `V7`) allows `FULL_HOME_PROJECT`/`INDIVIDUAL_SERVICE`;
  `categories.service_group` (new in `V7`) allows `HOME_PROJECT`/`INDIVIDUAL_SERVICE`;
  `quotations.status` allows `DRAFT`/`SENT`/`ACCEPTED`/`REJECTED`;
  `professional_profiles.availability_status` (new in `V5`) allows `AVAILABLE`/`UNAVAILABLE`;
  `reviews.rating` (new in `V5`) is constrained to `BETWEEN 1 AND 5`.
- Indexes exist on frequently-filtered columns: `portfolio_items.category_id`,
  `portfolio_items.professional_id`, `interior_design_details.style_tag` (moved off
  `portfolio_items` in `V8`), `bookings.customer_id`, `bookings.professional_id`,
  `bookings.status`, `quotation_line_items.quotation_id`, `reviews.professional_id`.
  These make the catalog search and booking list queries fast as data grows.
- **A migration mistake and its forward-only fix:** `V5` originally declared
  `reviews.rating` as `SMALLINT`, but the `Review` entity's `Integer rating` field maps
  to Postgres `INTEGER` by default — Hibernate's schema *validation* (never
  auto-correction, see [Startup Flow](#3-application-startup-flow)) caught the mismatch
  at boot with a clear error. Since `V5` was already applied to the database, the fix
  is `V6__widen_review_rating_column.sql` (`ALTER COLUMN rating TYPE INTEGER`) — a small,
  real example of why Flyway migrations are **never edited after they've run**: you
  always write the next one forward instead, even to fix a mistake in the last one.
- **Two later renames, same "never edit an applied migration" discipline:** `V7`
  renamed the professional-identity concept (`DESIGNER`→`PROFESSIONAL`,
  `designer_profiles`→`professional_profiles`, every `designer_id` column→
  `professional_id`) because a plumber or electrician isn't a "designer" just because
  they share the same account type as one, and added the Full Home Services / Individual
  Service split. `V8` generalized the portfolio entity (`designs`→`portfolio_items`)
  and split interior-design-specific fields (`style_tag`, `price_estimate`) into the new
  optional `interior_design_details` satellite table, so a painter's or plumber's
  portfolio item isn't forced to carry fields that only make sense for interior design.
  Both were applied as new, forward-only migrations — `V1`-`V6` were never touched.

---

## 6. Entities (JPA Layer)

**What:** An **entity** is a Java class annotated `@Entity` that JPA/Hibernate maps
onto a database table. Each field maps to a column; each instance maps to a row.

**Why:** Instead of writing raw SQL everywhere, we describe the shape of our data once
in Java, and Hibernate generates the SQL for us (via the repository layer — see next
section).

**Example — `User.java`:**

```java
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    private Role role;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
```

Key annotations explained:
- `@Id` + `@GeneratedValue(strategy = IDENTITY)` — primary key, auto-incremented by
  Postgres (`BIGSERIAL`).
- `@Enumerated(EnumType.STRING)` — stores the enum's **name** (`"CUSTOMER"`) in the
  column, not its ordinal number. This is important: if you ever reorder the enum
  constants, ordinal storage would silently corrupt existing data. String storage is safe.
- `@CreatedDate` / `@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)`
  — combined with `@EnableJpaAuditing` on the main application class, Hibernate
  automatically stamps these fields on insert/update. You never set them manually.
- Lombok's `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` generate
  all the getters/setters/constructors so we don't hand-write boilerplate. `@Builder`
  in particular gives us a fluent way to construct entities: `User.builder().email(...).build()`.

**The base entity + optional satellite pattern — `PortfolioItem` + `InteriorDesignDetails`:**

```java
@Entity
@Table(name = "portfolio_items")
public class PortfolioItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String description;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "professional_id", nullable = false)
    private User professional;

    private String coverImageUrl;

    @OneToOne(mappedBy = "portfolioItem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private InteriorDesignDetails interiorDesignDetails;   // null for a non-interior-design item

    // createdAt / updatedAt as usual
}

@Entity
@Table(name = "interior_design_details")
public class InteriorDesignDetails {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "portfolio_item_id", nullable = false, unique = true)
    private PortfolioItem portfolioItem;   // owning side — holds the FK

    private String styleTag;
    private BigDecimal priceEstimate;
}
```

**Why this shape, not one flat table or a JSON blob:** every trade's portfolio item
shares the same core fields (title, description, category, cover image), but only
interior-design work has a rich enough, structured-enough style/price concept worth
filtering and sorting on (see
[Portfolio Catalog](#15-feature-walkthrough-portfolio-catalog-searchfilterpagination)).
Rather than forcing every `PortfolioItem` to carry `styleTag`/`priceEstimate` columns
that are meaningless for a plumber's or electrician's work, those two fields live on a
separate, **optional** 1-to-1 entity — a painter's portfolio item simply has no
`InteriorDesignDetails` row at all. This is the exact same shape already used for
`User` + optional `ProfessionalProfile` (a satellite row that only exists if relevant to
that particular row), not a new pattern invented for this feature. `cascade =
CascadeType.ALL, orphanRemoval = true` on the `PortfolioItem` side means the details row
has no independent lifecycle of its own — it's created, updated, and deleted entirely
through its parent `PortfolioItem`, the same convention `Quotation.lineItems` already
established (see below).

**Other relationships between entities:**

- `ProfessionalProfile.user` — `@OneToOne` pointing back to `User`, with `@JoinColumn(name = "user_id")`.
- `PortfolioItem.category` and `PortfolioItem.professional` — `@ManyToOne(fetch = FetchType.LAZY)`. Lazy
  fetch means Hibernate does **not** load the related `Category`/`User` from the
  database until you actually call `.getCategory()` — avoiding unnecessary joins when
  you don't need that data.
- `Booking.customer`, `Booking.professional`, `Booking.portfolioItem` — all `@ManyToOne(LAZY)`.
  Note `Booking` has **two** `@ManyToOne` relationships to the *same* `User` entity
  (customer and professional) — this is why both are explicitly named via `@JoinColumn`.
  `Booking.requestType` (`@Enumerated(STRING)`, added in `V7`) is the only field that
  determines which validation branch `BookingService.createBooking` takes (see
  [Bookings](#16-feature-walkthrough-bookings-state-machine)).
- `Category.serviceGroup` (`@Enumerated(STRING)`, added in `V7`) tags every category as
  `HOME_PROJECT` or `INDIVIDUAL_SERVICE` — this is what `BookingService` checks a
  booking's chosen category against its `requestType`.
- `Quotation.booking` — `@OneToOne`, mirroring `ProfessionalProfile.user`.
- `Quotation.lineItems` — `@OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)`.
  Because a quotation's line items have no independent existence (they only make sense
  as part of one quotation), `cascade = ALL` means saving/deleting the `Quotation`
  automatically saves/deletes its `QuotationLineItem` rows too, and `orphanRemoval =
  true` means simply removing an item from the in-memory `lineItems` list (as
  `QuotationService` does when replacing a draft's items) deletes that row from the
  database — no manual `quotationLineItemRepository.delete(...)` call needed. This is
  why there's no separate repository for `QuotationLineItem` at all; it's managed
  entirely through its parent. `InteriorDesignDetails` reuses this exact same
  cascade/orphan-removal idiom for the same reason.
- `Review.booking`, `Review.customer`, `Review.professional` — same shape as
  `Quotation.booking` and `Booking`'s dual `User` relationships respectively: one
  `@OneToOne` back to the booking it's about, and two `@ManyToOne` references to `User`
  (who wrote it, who it's about), both explicitly named via `@JoinColumn` for the same
  reason `Booking` needs it.

**A field that changed meaning, not just type:** `Booking.professional` (then
`Booking.designer`) was originally `@JoinColumn(nullable = false)` — every booking had
to name a professional up front. Once the "let Velora assign one" path was added, this
became `nullable = true` (matching the relaxed DB constraint from `V4`), and every
place in the codebase that called `booking.getProfessional().getId()` unconditionally
had to be found and null-guarded (`BookingMapper`, `BookingService`, `QuotationService`)
— a good example of how a single entity-level relaxation ripples out to every consumer
of that field, and why grepping for every call site before making a field nullable
matters.

---

## 7. DTOs — Why We Never Return Entities Directly

**What:** DTOs (Data Transfer Objects) are simple, immutable records that define
**exactly** what data goes over the wire — for a request coming in, or a response
going out. In this project, every DTO is a Java `record` (all fields final,
auto-generated `equals`/`hashCode`/`toString`/accessors — perfect for immutable data
shapes).

**Why we never return `@Entity` objects directly from a controller:**

1. **Security** — entities have sensitive fields (e.g. `User.passwordHash`). If you
   serialize the entity straight to JSON, you risk leaking it.
2. **Lazy-loading crashes** — a `@ManyToOne(LAZY)` field like `PortfolioItem.professional`
   is a Hibernate proxy. Serializing it outside a transaction throws
   `LazyInitializationException`. DTOs sidestep this by only exposing the flattened
   fields you actually fetched (e.g. `professionalName: String` instead of the whole `User`).
3. **API stability** — you can freely rename/restructure entity fields without
   breaking the public API contract, as long as the DTO shape stays the same.

**Example — request DTO with validation, `RegisterRequest.java`:**

```java
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 100) @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$")
        String password,
        @NotBlank @Size(max = 150) String fullName,
        @Size(max = 20) String phone,
        @NotNull Role role
) {}
```

**Example — response DTO, `AuthResponse.java`:**

```java
public record AuthResponse(
        String accessToken,
        String tokenType,
        UserSummary user
) {
    public record UserSummary(Long id, String email, String fullName, Role role) {}

    public static AuthResponse of(String token, Long userId, String email, String fullName, Role role) {
        return new AuthResponse(token, "Bearer", new UserSummary(userId, email, fullName, role));
    }
}
```

Notice the user-identifying fields are grouped under a nested `user` object rather than
sitting flat alongside `accessToken`/`tokenType`. This keeps the response shape
self-documenting — "token stuff" and "who this token belongs to" are visually and
structurally separate — and gives room to add more account fields later without
cluttering the top level. `UserSummary` is declared as a **nested record**, the same
technique used for `ApiErrorResponse.FieldViolation` (see
[Exception Handling Flow](#22-exception-handling-flow)) — a small DTO that only ever
makes sense in the context of its parent.

The `of(...)` static factory method is just a convenience constructor used by
`AuthService` so callers don't need to remember the literal string `"Bearer"`.

**Converting between entity and DTO — Mappers:**

A **mapper** (`UserMapper`, `PortfolioItemMapper`, `BookingMapper`) is a small
`@Component` whose only job is translating entity → DTO (and occasionally DTO → entity,
though here that's mostly done inline in the services via `.builder()`). Example from
`PortfolioItemMapper`:

```java
public PortfolioItemResponse toResponse(PortfolioItem item) {
    InteriorDesignDetails details = item.getInteriorDesignDetails();
    return new PortfolioItemResponse(
            item.getId(), item.getTitle(), item.getDescription(),
            toCategoryResponse(item.getCategory()),
            item.getProfessional().getId(), item.getProfessional().getFullName(),
            item.getCoverImageUrl(),
            details != null ? details.getPriceEstimate() : null,
            details != null ? details.getStyleTag() : null,
            item.getCreatedAt()
    );
}
```

Two things worth noting: it calls `item.getProfessional().getFullName()` — this
triggers the lazy load **while still inside the service's `@Transactional` method**,
which is why it works safely here (see
[Transactions](#14-full-request-lifecycle-controller--service--repository--db)). And
the null-check on `getInteriorDesignDetails()` is the mapper-level expression of the
same "optional satellite" idea from [Entities](#6-entities-jpa-layer) — a non-interior
item simply produces `null` for `priceEstimate`/`styleTag` in the DTO, and since
`application.yml` sets `spring.jackson.default-property-inclusion: non_null` (see
[User Profile](#20-feature-walkthrough-user-profile-role-conditional-data)), those two
keys are omitted from the JSON entirely rather than showing up as `null`.

**Newer DTOs follow the same rules, nothing new to learn:** `ProfessionalMatchResponse`
(one ranked candidate — see
[Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching)),
`ProfessionalPublicProfileResponse`, and `CreateReviewRequest`/`ReviewResponse` (see
[Reviews & Ratings](#19-feature-walkthrough-reviews--ratings)) are all still plain
records — flat data, validated with the same Bean Validation annotations, mapped by a
small dedicated mapper (`ProfessionalMapper`, `ReviewMapper`). No new DTO pattern was
introduced for any of this.

---

## 8. Repositories (Data Access Layer)

**What:** A repository is a Java **interface** extending Spring Data JPA's
`JpaRepository<EntityType, IdType>`. You don't write an implementation — Spring
generates one at runtime using dynamic proxies.

**Why:** Eliminates hand-written boilerplate CRUD code (`save`, `findById`, `findAll`,
`delete`, ...) entirely. You only write method *signatures* for anything beyond basic
CRUD, and Spring Data parses the method name to build the query.

**Example — `UserRepository.java`:**

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

Spring Data reads `findByEmail` and generates:
`SELECT * FROM users WHERE email = ?` — no implementation needed. This is called a
**derived query method** — the method name itself *is* the query definition.

**Pagination — `BookingRepository.java`:**

```java
Page<Booking> findByCustomerId(Long customerId, Pageable pageable);
```

Passing a `Pageable` (page number + size + sort) makes Spring Data automatically
generate a `LIMIT ... OFFSET ...` query and also run a `COUNT(*)` query to know the
total number of matching rows — both wrapped up in the returned `Page<T>` object
(which has `.getContent()`, `.getTotalElements()`, `.getTotalPages()`, etc.).

**Lookup by a foreign key — `QuotationRepository.java`:**

```java
public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    Optional<Quotation> findByBookingId(Long bookingId);
}
```

Since a quotation is one-to-one with its booking, every quotation lookup in
`QuotationService` goes through the booking id rather than the quotation's own id — the
booking id is the natural key a controller/client actually has on hand (see
[Feature Walkthrough: Quotations](#18-feature-walkthrough-quotations-post-consultation-estimate)).

**Dynamic queries — `PortfolioItemSpecifications.java` + `JpaSpecificationExecutor`:**

The portfolio catalog needs *optional* filters (category, search text, style) that can
be combined in any combination. Derived query methods can't express "filter by these
three things, but only if they're provided." For that we use the **Specification**
pattern:

```java
public interface PortfolioItemRepository extends JpaRepository<PortfolioItem, Long>,
        JpaSpecificationExecutor<PortfolioItem> {
}
```

```java
public static Specification<PortfolioItem> hasCategory(Long categoryId) {
    return (root, query, cb) -> categoryId == null
            ? null                                            // no filter applied
            : cb.equal(root.get("category").get("id"), categoryId);
}

public static Specification<PortfolioItem> hasStyle(String styleTag) {
    return (root, query, cb) -> (styleTag == null || styleTag.isBlank())
            ? null
            : cb.equal(cb.lower(root.join("interiorDesignDetails", JoinType.LEFT).get("styleTag")), styleTag.toLowerCase());
}
```

A `Specification<T>` is just a lambda that builds a JPA Criteria predicate. Returning
`null` means "don't filter on this condition at all." `hasStyle` uses a **left join**
to the optional `interiorDesignDetails` association rather than an inner join — an
inner join would silently and incorrectly exclude every non-interior-design item from
the *entire* query the moment any style filter was combined with other predicates; the
left join instead means a non-interior item simply has no `styleTag` to match against,
so it's correctly excluded only when a style filter is actually active, and correctly
included when it isn't. `PortfolioItemService` combines three of these with `.and(...)`:

```java
Specification<PortfolioItem> spec = Specification
        .where(PortfolioItemSpecifications.hasCategory(categoryId))
        .and(PortfolioItemSpecifications.matchesSearch(search))
        .and(PortfolioItemSpecifications.hasStyle(style));

portfolioItemRepository.findAll(spec, pageable);
```

This builds one SQL query with only the WHERE clauses that were actually requested —
much cleaner than writing a dozen overloaded repository methods for every filter
combination.

**Sorting through an optional association — `priceEstimate`:** the catalog's
`sortBy=priceEstimate` query parameter now has to sort by a column that lives on the
optional `InteriorDesignDetails` satellite, not on `PortfolioItem` itself. Spring
Data's `Sort`/`Pageable` supports nested property paths natively —
`Sort.by("interiorDesignDetails.priceEstimate")` — which Hibernate translates into the
necessary join automatically, without writing a `@Query`.
`PortfolioItemController.sanitizeSortField` maps the public, client-facing
`priceEstimate` value to that internal nested path, so the API's query-parameter name
didn't need to change even though the underlying column moved tables in `V8`.

**Counting instead of joining — the matching feature's repository additions:**

```java
long countByProfessionalIdAndCategoryId(Long professionalId, Long categoryId);
long countByProfessionalIdAndInteriorDesignDetailsStyleTagIgnoreCase(Long professionalId, String styleTag);
long countByProfessionalIdAndStatusIn(Long professionalId, Collection<BookingStatus> statuses);
```

`ProfessionalMatchingService` (see
[Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching))
needs simple yes/no and how-many signals per candidate — "does this professional have
any portfolio pieces in this category," "how many active bookings are they juggling
right now" — and a `count` derived method expresses exactly that without pulling back
full rows just to check `.size()` in Java. Note
`countByProfessionalIdAndInteriorDesignDetailsStyleTagIgnoreCase` — Spring Data derived
methods can traverse a `@OneToOne` association by its field name
(`interiorDesignDetails.styleTag`) exactly like traversing any other relationship, so
even a count across the optional satellite table stays a plain derived method, no
`@Query` introduced.

**Deliberately *not* using a `@Query` for rating averages:** `ReviewService` needs a
professional's average rating. The tempting shortcut is a native aggregate query
(`SELECT AVG(rating) ...`), but this codebase has zero `@Query`/native SQL anywhere —
every repository here is derived-method or `Specification`-based. Introducing the
*first* raw query for one feature would be a bigger stylistic inconsistency than the
alternative: `ReviewRepository.findByProfessionalId(professionalId)` loads that
professional's reviews (never a large list at this scale) and `ReviewService` averages
them with a plain Java stream. Same repository style, everywhere, no exceptions.

---

## 9. Security Architecture (Big Picture)

Before diving into the register/login flow, here's the overall shape of the security
system, since several pieces need to be understood together:

```
                     ┌─────────────────────────────────────────┐
Incoming HTTP        │            Spring Security Filter Chain  │
request  ──────────► │                                           │
                     │  1. CORS check                            │
                     │  2. JwtAuthFilter  ◄── reads Authorization │
                     │     (custom)          header, validates    │
                     │                        JWT, sets            │
                     │                        SecurityContext      │
                     │  3. Authorization check                     │
                     │     (public URL? role required?)            │
                     └───────────────┬───────────────────────────┘
                                     │ if authorized
                                     ▼
                              Your @RestController
```

**Key components and their roles:**

| Component | Role |
|---|---|
| `SecurityConfig` | Declares the rules: which URLs are public, which need auth, CORS policy, password hashing algorithm. |
| `JwtService` | Pure JWT logic: create a token, read claims out of a token, check if a token is still valid. Knows nothing about HTTP. |
| `JwtAuthFilter` | Runs once per request. Pulls the token out of the `Authorization` header, asks `JwtService` if it's valid, and if so tells Spring Security "this request is from this authenticated user." |
| `CustomUserDetailsService` | Given an email, loads the matching `User` from the database and wraps it as a `UserPrincipal`. Used both during login (password check) and by the JWT filter (to reload user details on every request). |
| `UserPrincipal` | Adapts our `User` entity to Spring Security's `UserDetails` interface, without polluting the entity itself with framework-specific code. |
| `RestSecurityErrorHandler` | Implements both of Spring Security's error SPIs. As the `AuthenticationEntryPoint` it runs when an **unauthenticated** user hits a protected endpoint, returning a clean JSON 401 instead of Spring Security's default HTML login page. As the `AccessDeniedHandler` it runs when an **authenticated but unauthorized** user (wrong role) hits a restricted endpoint, returning a clean JSON 403. |

**Why stateless (JWT) instead of sessions?**
`SecurityConfig` sets `sessionCreationPolicy(SessionCreationPolicy.STATELESS)`. This
means the server keeps **no session memory** between requests — every request must
prove who it is by presenting a valid JWT. This fits a mobile-app backend well: no
sticky sessions, easy to scale horizontally (any server instance can validate any
token, since the token itself carries the identity + signature).

**Public GET endpoints today, from `SecurityConfig.PUBLIC_GET_ENDPOINTS`:**
`/api/categories/**`, `/files/**` — anyone can list categories and fetch served files
without a token. `/api/portfolio/**` and `/api/professionals/**` are deliberately **not**
public: the admin-controlled assignment model means customers never browse or search
professionals or portfolios, so both are gated `@PreAuthorize("hasRole('ADMIN')")` at
the controller instead — every other endpoint requires a token.

---

## 10. Authentication Flow — Register

**Endpoint:** `POST /api/auth/register` (public — no token needed)

**Step by step:**

1. **Controller** (`AuthController.register`) receives the JSON body, and Spring
   automatically deserializes it into a `RegisterRequest` record.
   `@Valid` triggers Bean Validation on the fields (see [Validation](#21-validation-flow))
   *before* the method body even runs. If validation fails, the method is never called
   — Spring throws `MethodArgumentNotValidException` instead (caught globally, see
   [Exception Handling](#22-exception-handling-flow)).

2. **Controller delegates to `AuthService.register(request)`.** Controllers should
   never contain business logic — their only job is: deserialize input, call the
   service, wrap the result in an HTTP response.

3. **`AuthService.register`:**
   ```java
   if (request.role() == Role.ADMIN) {
       throw new IllegalArgumentException("Cannot self-register as an admin account");
   }

   String normalizedEmail = request.email().trim().toLowerCase();

   if (userRepository.existsByEmail(normalizedEmail)) {
       throw new DuplicateResourceException("An account with this email already exists");
   }
   ```
   The `ADMIN` check runs first, before anything else, and is unconditional — it
   doesn't matter what else is in the request, an admin account can never come out of
   this endpoint. This is the only reason an admin account exists at all today: one was
   inserted directly by `V4__admin_and_designer_assignment.sql` as a bootstrap account,
   since the API itself offers no other way to create one. Email is normalized (trimmed
   + lowercased) so `Test@Example.com` and `test@example.com` are treated as the same
   account. Duplicate check happens next — throwing a custom exception that the global
   handler turns into `409 Conflict`.

4. **Password hashing.**
   ```java
   .passwordHash(passwordEncoder.encode(request.password()))
   ```
   `passwordEncoder` is a `BCryptPasswordEncoder` bean (defined in `SecurityConfig`).
   **We never store the raw password** — BCrypt is a one-way hash algorithm
   specifically designed for passwords (slow by design, to resist brute-forcing, and
   includes a random salt automatically).

5. **Save the user.** `userRepository.save(user)` — since `id` is `null` on a new
   entity, JPA knows to `INSERT` rather than `UPDATE`, and Postgres assigns the next
   auto-increment id.

6. **If the role is `PROFESSIONAL`, also create a `ProfessionalProfile` row** (empty, to
   be filled in later via the profile-update endpoint):
   ```java
   if (user.getRole() == Role.PROFESSIONAL) {
       ProfessionalProfile profile = ProfessionalProfile.builder().user(user).build();
       professionalProfileRepository.save(profile);
   }
   ```
   This runs identically for every trade — an interior designer, a painter, a plumber
   all end up with the same shape of profile row; only its free-text `bio`/
   `specialization` content ever distinguishes one professional's trade from another's.
   Customers never get a `ProfessionalProfile` row — this is why the
   `professionalProfile` field in profile responses is `null`/absent for customers (see
   [User Profile Walkthrough](#20-feature-walkthrough-user-profile-role-conditional-data)).

7. **Generate a JWT immediately** so the new user is logged in right after
   registering (no separate login step required):
   ```java
   UserPrincipal principal = UserPrincipal.fromEntity(user);
   String token = jwtService.generateToken(principal);
   ```

8. **Return `201 Created`** with an `AuthResponse` containing the token + basic user info.

```
Client                Controller           AuthService              DB
  │  POST /register      │                     │                    │
  ├──────────────────────►                     │                    │
  │                       │  register(request)  │                   │
  │                       ├─────────────────────►                   │
  │                       │                     │ existsByEmail?    │
  │                       │                     ├───────────────────►
  │                       │                     │◄───────────────────
  │                       │                     │ encode password   │
  │                       │                     │ save(user)        │
  │                       │                     ├───────────────────►
  │                       │                     │◄───────────────────
  │                       │                     │ generateToken()   │
  │                       │◄─────────────────────                   │
  │◄──────────────────────  201 + AuthResponse                      │
```

---

## 11. Authentication Flow — Login

**Endpoint:** `POST /api/auth/login` (public)

```java
public AuthResponse login(LoginRequest request) {
    String normalizedEmail = request.email().trim().toLowerCase();

    authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(normalizedEmail, request.password()));

    User user = userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new IllegalStateException("User authenticated but not found: " + normalizedEmail));

    UserPrincipal principal = UserPrincipal.fromEntity(user);
    String token = jwtService.generateToken(principal);

    return AuthResponse.of(token, user.getId(), user.getEmail(), user.getFullName(), user.getRole());
}
```

**What's happening under the hood when `authenticationManager.authenticate(...)` is called:**

1. Spring Security's `AuthenticationManager` delegates to the `DaoAuthenticationProvider`
   bean (configured in `SecurityConfig`).
2. That provider calls `CustomUserDetailsService.loadUserByUsername(email)` to fetch
   the user from the DB and wrap it as a `UserPrincipal`.
3. It then compares the raw password from the request against `userPrincipal.getPassword()`
   (the BCrypt hash) using the same `PasswordEncoder` — `passwordEncoder.matches(raw, hash)`.
4. If they don't match, it throws `BadCredentialsException` — which propagates up out
   of `login()`, out of the controller, and is caught by `GlobalExceptionHandler`,
   which returns `401 Unauthorized` with the message *"Invalid email or password"*
   (deliberately vague — never reveal whether the email or the password was wrong,
   to avoid helping attackers enumerate valid accounts).
5. If they match, authentication succeeds silently (no exception), and `login()`
   continues to generate a fresh JWT.

**Why not just do the password check manually?** Delegating to
`AuthenticationManager`/`DaoAuthenticationProvider` reuses Spring Security's
well-tested, timing-attack-resistant comparison logic, and keeps the auth logic
consistent with the rest of the framework (rather than reinventing it).

---

## 12. How a Protected Request Is Authenticated (JWT Filter)

Once a client has a token from register/login, every subsequent request to a
protected endpoint must include it:

```
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJ0ZXN0...
```

**`JwtAuthFilter`** (extends `OncePerRequestFilter`, guaranteeing it runs exactly once
per request no matter how the servlet container dispatches it) does this on **every**
incoming HTTP request, before it reaches any controller:

```java
String authHeader = request.getHeader("Authorization");

if (authHeader == null || !authHeader.startsWith("Bearer ")) {
    filterChain.doFilter(request, response);   // no token -> just continue,
    return;                                     // let the authorization rules decide
}

String token = authHeader.substring("Bearer ".length());

try {
    String email = jwtService.extractEmail(token);

    if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        if (jwtService.isTokenValid(token, userDetails.getUsername())) {
            var authToken = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }
    }
} catch (JwtException | IllegalArgumentException ex) {
    // invalid/expired token -> leave request unauthenticated, let the entry point 401 it
}

filterChain.doFilter(request, response);
```

Step by step:

1. **No `Authorization` header, or it doesn't start with `Bearer `?** → skip straight
   to the next filter. This request will be treated as **anonymous**. If the endpoint
   requires auth, it'll get rejected later by the authorization rules (→ 401 via
   `RestSecurityErrorHandler`). If the endpoint is public (e.g. `/api/portfolio`), it proceeds fine.

2. **Extract the email from the token's claims** (`JwtService.extractEmail` — reads
   the JWT's `subject` claim, which we set to the user's email at token creation time).

3. **Reload the user from the database** via `CustomUserDetailsService`. This is
   important: we don't just trust the token's claims blindly — we re-fetch the current
   user record so that, e.g., a role change or account deletion takes effect
   immediately rather than only once the old token expires.

4. **Validate the token**: `JwtService.isTokenValid` checks the signature already
   verified during parsing, that the subject matches the reloaded user's email, and
   that it hasn't expired.

5. **If valid, tell Spring Security "this request is authenticated"** by placing an
   `Authentication` object into `SecurityContextHolder`. Everything downstream
   (`@PreAuthorize`, `@AuthenticationPrincipal`) reads from this context for the
   remainder of the request.

6. **Any JWT parsing error** (malformed, expired, bad signature) is caught and
   swallowed — the request simply continues as unauthenticated. We don't leak parsing
   error details to the client; the standard 401 response takes over instead.

**`JwtService` — the actual token mechanics:**

```java
public String generateToken(UserPrincipal principal) {
    Instant now = Instant.now();
    Instant expiry = now.plusMillis(jwtProperties.getExpirationMs());

    return Jwts.builder()
            .subject(principal.getUsername())      // the user's email
            .issuer(jwtProperties.getIssuer())      // "velora-backend"
            .claim("userId", principal.getId())
            .claim("role", principal.getRole().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(signingKey())                 // HMAC-SHA signature
            .compact();
}
```

The token is a **signed**, not encrypted, JWT — anyone can decode and read its
contents (it's just base64), but nobody can forge or tamper with it without knowing
the secret key, because `signWith(signingKey())` produces a cryptographic signature
over the payload that `isTokenValid` re-checks on every request
(`Jwts.parser().verifyWith(signingKey())...`). If a single byte of the payload
changes, the signature check fails and parsing throws a `JwtException`.

**How `@AuthenticationPrincipal UserPrincipal principal` works in controllers:**

Once `JwtAuthFilter` has populated `SecurityContextHolder`, Spring MVC can inject the
currently authenticated user directly into any controller method parameter:

```java
@GetMapping("/profile")
public ResponseEntity<UserProfileResponse> getProfile(@AuthenticationPrincipal UserPrincipal principal) {
    return ResponseEntity.ok(userService.getProfile(principal.getId()));
}
```

No manual "get the current user" boilerplate needed — Spring resolves it from the
security context automatically.

---

## 13. Authorization — Roles and `@PreAuthorize`

**Authentication** answers "who are you?" **Authorization** answers "are you allowed
to do this?" This project uses two authorization mechanisms together:

**1. URL-pattern-based rules (`SecurityConfig`)** — coarse-grained, applied to entire
groups of endpoints:

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()                 // /api/auth/**, swagger, actuator/health
        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll() // GET /api/portfolio/**, /api/categories/**, /api/professionals/**
        .anyRequest().authenticated())                                  // everything else needs a valid token
```

Notice `/api/portfolio/**` is public **only for GET** — anyone can browse the catalog
without logging in, but (there is currently no write endpoint for portfolio items at
all — see [Design Notes / Extensibility](../README.md#design-notes--extensibility) in
the README — so this distinction is forward-looking for when one is added).

**2. Method-level role checks (`@PreAuthorize`)** — fine-grained, applied to a single
controller method, checked *after* the URL-level rule already confirmed the user is
authenticated:

```java
@PostMapping
@PreAuthorize("hasRole('CUSTOMER')")
public ResponseEntity<BookingResponse> create(...) { ... }
```

```java
@PatchMapping("/{id}/status")
@PreAuthorize("hasRole('PROFESSIONAL')")
public ResponseEntity<BookingResponse> updateStatus(...) { ... }
```

```java
@PatchMapping("/{id}/assign")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<BookingResponse> assign(...) { ... }
```

The `ADMIN` role added in `V4` slots into this exact same mechanism — no new
authorization concept, just a third string `@PreAuthorize` can check for. Since nothing
in `RegisterRequest` can ever produce an `ADMIN` account (see
[Authentication Flow — Register](#10-authentication-flow--register)), the one admin
account in the system was seeded directly by a migration, not created through the API.

`@EnableMethodSecurity` (in `SecurityConfig`) turns this annotation on globally.
`hasRole('CUSTOMER')` actually checks for the authority `"ROLE_CUSTOMER"` — Spring
Security's convention is to prefix role names with `ROLE_`, which is exactly what
`UserPrincipal.getAuthorities()` produces:

```java
@Override
public List<GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
}
```

**What happens if the role check fails?** Spring Security throws
`AccessDeniedException`, which is caught by `RestSecurityErrorHandler` (registered in
`SecurityConfig` as the `accessDeniedHandler`) and turned into a clean JSON `403
Forbidden` response — never Spring's default HTML error page.

**A third layer of authorization — ownership checks in the service layer.** Role
checks alone aren't enough for some rules (e.g. "a customer can only cancel *their
own* booking, not anyone's booking"). This kind of check can't be expressed as a
simple role annotation because it depends on data, not just role — so it lives inside
`BookingService`:

```java
public BookingResponse cancel(Long customerId, Long bookingId) {
    Booking booking = findBooking(bookingId);

    if (!booking.getCustomer().getId().equals(customerId)) {
        throw new UnauthorizedActionException("Only the customer who made this booking can cancel it");
    }
    ...
}
```

So the full authorization picture for booking-cancellation is: *authenticated* (JWT
filter) → *has the CUSTOMER role* (`@PreAuthorize`) → *owns this specific booking*
(service-layer check). Three layers, each catching a different kind of mistake.

---

## 14. Full Request Lifecycle (Controller → Service → Repository → DB)

Let's trace one complete authenticated request end-to-end:
**`GET /api/users/profile`** with a valid `Authorization: Bearer <token>` header.

```
1. HTTP request arrives at embedded Tomcat
        │
        ▼
2. Spring Security filter chain
     - CORS filter checks Origin header
     - JwtAuthFilter reads token, validates it, populates SecurityContext
     - Authorization check: "/api/users/**" isn't in PUBLIC_ENDPOINTS -> requires authentication
       (SecurityContext has a valid Authentication -> passes)
        │
        ▼
3. Spring MVC dispatches to UserController.getProfile(...)
     - @AuthenticationPrincipal resolves the current UserPrincipal from SecurityContext
        │
        ▼
4. UserController calls userService.getProfile(principal.getId())
        │
        ▼
5. UserService.getProfile (annotated @Transactional(readOnly = true))
     - Opens a DB transaction
     - Calls userRepository.findById(userId) -> throws ResourceNotFoundException if absent
     - Role check: only fetch ProfessionalProfile if role == PROFESSIONAL
     - Calls userMapper.toProfileResponse(user, professionalProfile)
     - Commits (read-only) transaction
        │
        ▼
6. UserMapper builds a UserProfileResponse record from entity fields
        │
        ▼
7. Back in UserController: ResponseEntity.ok(response)
        │
        ▼
8. Spring MVC serializes the DTO record to JSON (via Jackson)
        │
        ▼
9. HTTP 200 response sent back to the client
```

**Why `@Transactional` matters here:** JPA lazy-loaded fields (like
`ProfessionalProfile.user`, or any `@ManyToOne`) can only be fetched *while a database
session/transaction is still open*. `@Transactional` on the service method keeps that
session open for the whole method body — so by the time the mapper calls something
like `item.getProfessional().getFullName()`, the lazy proxy can still reach the database
to fill itself in. Once the method returns and the transaction closes, trying to touch
an unloaded lazy field would throw `LazyInitializationException`. This is precisely
why entity-to-DTO mapping always happens **inside** the service layer, never in the
controller.

`readOnly = true` is a hint to Hibernate that no writes will happen in this
transaction, letting it apply some performance optimizations (e.g. skipping dirty
checking).

---

## 15. Feature Walkthrough: Portfolio Catalog (Search/Filter/Pagination)

**Purpose:** Let customers (and anonymous visitors — this is a **public** endpoint)
browse, search, and filter the catalog of work samples professionals have shared —
interior-design concepts, before/after painting jobs, completed plumbing/electrical/
carpentry work, and so on.

**Endpoint:** `GET /api/portfolio?category=2&search=modern&style=minimalist&page=0&size=20&sortBy=priceEstimate&direction=asc`

**Flow:**

1. `PortfolioItemController.search` receives all query params (all optional except
   paging defaults). It clamps `size` to a maximum of 50 (`MAX_PAGE_SIZE`) so a client
   can't request an absurdly large page and hammer the database:
   ```java
   int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
   ```
2. It whitelists the sortable fields, and also maps the client-facing `priceEstimate`
   value onto its real, nested storage location (`interiorDesignDetails.priceEstimate`
   since `V8` moved that column off `PortfolioItem` itself):
   ```java
   private String sanitizeSortField(String sortBy) {
       return switch (sortBy) {
           case "priceEstimate" -> "interiorDesignDetails.priceEstimate";
           case "title" -> "title";
           default -> "createdAt";
       };
   }
   ```
3. Builds a `Pageable` (page number, size, sort direction+field) and calls
   `portfolioItemService.search(category, search, style, pageable)`.
4. `PortfolioItemService` combines three `Specification<PortfolioItem>` predicates (see
   [Repositories](#8-repositories-data-access-layer)) — category filter, free-text
   search (title/description `LIKE`), and style-tag filter (a left join through the
   optional `interiorDesignDetails` association — see
   [Repositories](#8-repositories-data-access-layer) for why it must be a left join, not
   an inner one) — each of which is a no-op if its corresponding parameter is
   `null`/blank.
5. `portfolioItemRepository.findAll(spec, pageable)` runs one SQL query combining
   whichever filters were actually provided, applies `LIMIT`/`OFFSET` for the page, and
   a separate `COUNT` query for the total.
6. Each `PortfolioItem` entity is mapped to a lightweight `PortfolioItemSummaryResponse`
   (just the catalog-card fields — id, title, category name, cover image, and, if this
   item has interior-design details, price and style — not the full description) to
   keep list responses small. A non-interior-design item's summary simply omits
   `priceEstimate`/`styleTag` from the JSON.
7. The `Page<PortfolioItemSummaryResponse>` is wrapped into a `PageResponse<T>` — a
   generic record exposing `content`, `page`, `size`, `totalElements`, `totalPages`,
   `last` — so every paginated endpoint in the API (portfolio, bookings) returns the
   exact same envelope shape, regardless of what's inside `content`.

**Getting one portfolio item's full detail:** `GET /api/portfolio/{id}` uses the
fuller `PortfolioItemResponse` DTO (includes description, full category object,
professional name, plus style/price if this item has interior-design details), via
`portfolioItemService.getById(id)`, throwing `ResourceNotFoundException` → `404` if
the id doesn't exist.

**A related, equally public lookup — `GET /api/professionals/{id}`:** the catalog
tells you *what* a professional has made; `ProfessionalController`/`ProfessionalService`
tells you *who* they are — bio, years of experience, specialization, city, current
availability, and their aggregate rating (see
[Reviews & Ratings](#19-feature-walkthrough-reviews--ratings) for where that number
comes from). `ProfessionalService.getPublicProfile`:

```java
User user = userRepository.findById(professionalId)
        .filter(u -> u.getRole() == Role.PROFESSIONAL)
        .orElseThrow(() -> new ResourceNotFoundException("Professional not found: " + professionalId));
```

The `.filter(...)` is doing double duty: a wrong id and a *right* id that just isn't a
professional both end up `404 Not Found` — from the outside, "no such public
professional profile" is the correct, single response either way; the caller doesn't
need to know *why* it doesn't exist. `"/api/professionals/**"` sits in
`SecurityConfig.PUBLIC_GET_ENDPOINTS` alongside `/api/portfolio/**` and
`/api/categories/**` — same public-browsing intent.

**The directory — `GET /api/professionals`:** the searchable list that feeds those
single-profile lookups. Same machinery as the portfolio catalog, deliberately:
`ProfessionalProfileSpecifications` mirrors `PortfolioItemSpecifications` (every filter
returns `null` when its parameter is absent, so an unsupplied query parameter adds no
predicate rather than a match-everything one), results come back in the same
`PageResponse` envelope, and page size is capped at the same 50.

| Parameter | Effect |
|---|---|
| `search` | free-text over the professional's name, specialization and bio |
| `specialization` | substring match (`like`) — "kitchen" finds "Modular Kitchen" |
| `city` | exact match, case-insensitive |
| `availability` | `AVAILABLE` / `UNAVAILABLE` |
| `minExperience` | at least this many years |
| `minRating` | at least this average — **excludes unrated professionals**, since a null average cannot be claimed to clear any bar |
| `sortBy` | `averageRating` (default, desc), `yearsExperience`, `fullName`, `createdAt` — anything else falls back to the default rather than erroring |

Two details worth pinning:

1. **`ProfessionalSummaryResponse.id` is the *user* id, not the profile row id.** The
   directory queries `ProfessionalProfile` (that's where every filterable field lives),
   but the id a caller gets back has to be the one that works in
   `GET /api/professionals/{id}`, `POST /api/bookings` and admin assignment. Returning
   `profile.getId()` would hand out an id nothing else in the API accepts.
2. **`isProfessional()` is applied unconditionally**, not as an optional filter. A profile
   row should only ever belong to a `PROFESSIONAL` account, but this endpoint is public
   and unauthenticated, so the role is asserted rather than assumed — the same guard
   `getPublicProfile` already applies to single lookups.

One non-obvious security point, pinned by `PublicEndpointPatternTest`: the security rule
is written `/api/professionals/**`, and the directory sits at the bare
`/api/professionals` with no trailing segment. That works because `/**` matches *zero* or
more segments — but it's the kind of assumption that fails silently as a `401` on a
supposedly public endpoint, so it's asserted against Spring's real `PathPatternParser`
rather than trusted.

**Write path — upload:** `POST /api/portfolio`, `@PreAuthorize("hasRole('PROFESSIONAL')")`,
takes a `SavePortfolioItemRequest` (`title`, `description`, `categoryId`,
`coverImageUrl`, optional `styleTag`, optional `priceEstimate`). `PortfolioItemService.create`
looks up the caller (from `UserPrincipal.getId()`, not a client-supplied id — a
professional can only upload under their own account) and the category, builds the
`PortfolioItem`, and — only if `styleTag` or `priceEstimate` was supplied — attaches a
`InteriorDesignDetails` built with `.portfolioItem(item)` set so the owning side of the
`@OneToOne` is populated before save; `CascadeType.ALL` on `PortfolioItem.interiorDesignDetails`
persists both rows in one `save()` call. Omitting both fields leaves a plain item with no
satellite row at all — the same shape a seeded plumber/painter item already has.

**Write path — edit:** `PUT /api/portfolio/{id}`, same role guard, same
`SavePortfolioItemRequest` body. One request record serves both verbs because an update
here is a **full replacement, not a patch** — every field is resent, so there is no
"was this field omitted or deliberately cleared?" ambiguity to resolve. That's what makes
the interior-design satellite correctable in both directions:

| Sent | Existing satellite | Result |
|---|---|---|
| style and/or price | none | satellite attached |
| style and/or price | exists | satellite updated in place |
| neither | exists | satellite **deleted** (`orphanRemoval = true`) |
| neither | none | stays a plain item |

`PortfolioItemService.applyInteriorDesignDetails` is shared by `create` and `update`, so
both paths resolve those four cases identically.

**Write path — delete:** `DELETE /api/portfolio/{id}` → `204 No Content`, same role guard.
The delete is a hard delete (the satellite goes with it via `CascadeType.ALL` +
`orphanRemoval`), but it's **refused with `409 Conflict` while any booking still
references the item** (`bookingRepository.existsByPortfolioItemId`). A booking records the
work sample the customer was interested in; that reference is part of the booking's
history, so it outranks the professional's wish to remove the item. The check is explicit
rather than left to the database's foreign key so the caller gets a real explanation
instead of a generic integrity-violation conflict.

Both endpoints go through `PortfolioItemService.loadOwnItem`, which is where the
ownership rule lives: unknown id → `404`, someone else's item → `403`
(`UnauthorizedActionException`). Note this is ownership enforced in the *service*, on top
of the role check `@PreAuthorize` already did in the controller — `hasRole('PROFESSIONAL')`
only proves you're *a* professional, never that you're *this item's* professional.

---

## 16. Feature Walkthrough: Bookings (State Machine)

**Purpose:** Let a customer book either a Full Home Services project (with a specific
professional they picked themselves, or with no professional at all if they'd rather
Velora assign one) or a standalone Individual Service job (always assigned by Velora),
and let both sides (plus, for the assignment path, an admin) manage the booking's
lifecycle.

**Booking states:** `PENDING_ASSIGNMENT → PENDING → CONFIRMED → COMPLETED`, with
`CANCELLED` reachable from `PENDING_ASSIGNMENT`, `PENDING`, or `CONFIRMED`. Once
`CANCELLED` or `COMPLETED`, a booking is frozen — no further transitions allowed.
`PENDING_ASSIGNMENT` is where **every** booking starts, Full Home Services and
Individual Service alike — there is no direct-pick path at all, the customer never
names a professional. `PENDING` is only reached once an admin assigns one via
`PATCH /api/bookings/{id}/assign`.

```
        ┌────────────────────┐  admin assigns    ┌─────────┐  professional confirms ┌───────────┐  professional marks done ┌───────────┐
        │ PENDING_ASSIGNMENT │──────────────────►│ PENDING │───────────────────────►│ CONFIRMED │──────────────────────────►│ COMPLETED │
        └──────────┬─────────┘                   └────┬────┘                       └─────┬─────┘                          └───────────┘
                   │  customer cancels                 │  customer cancels               │  customer cancels
                   ▼                                    ▼                                 ▼
             ┌───────────┐                        ┌───────────┐                     ┌───────────┐
             │ CANCELLED │                        │ CANCELLED │                     │ CANCELLED │   (same terminal state, all three arrows)
             └───────────┘                        └───────────┘                     └───────────┘
```

**Creating a booking** — `POST /api/bookings`, restricted to `@PreAuthorize("hasRole('CUSTOMER')")`:

```java
public BookingResponse createBooking(Long customerId, BookingRequest request) {
    User customer = userRepository.findById(customerId)...

    if (request.requestType() == RequestType.INDIVIDUAL_SERVICE && request.categoryId() == null) {
        throw new IllegalArgumentException("Individual service requests must specify a category");
    }

    if (request.budgetMin() != null && request.budgetMax() != null
            && request.budgetMin().compareTo(request.budgetMax()) > 0) {
        throw new IllegalArgumentException("budgetMin cannot be greater than budgetMax");
    }

    Category category = null;
    if (request.categoryId() != null) {
        category = categoryRepository.findById(request.categoryId())...
        ServiceGroup expectedGroup = request.requestType() == RequestType.INDIVIDUAL_SERVICE
                ? ServiceGroup.INDIVIDUAL_SERVICE : ServiceGroup.HOME_PROJECT;
        if (category.getServiceGroup() != expectedGroup) {
            throw new IllegalArgumentException("Selected category does not match the request type");
        }
    }

    Booking booking = Booking.builder()
            .customer(customer)
            .category(category).requestType(request.requestType())
            .preferredStyle(request.preferredStyle())
            .budgetMin(request.budgetMin()).budgetMax(request.budgetMax())
            .location(request.location())
            .scheduledAt(request.scheduledAt())
            .status(BookingStatus.PENDING_ASSIGNMENT)
            .notes(request.notes())
            .build();

    return bookingMapper.toResponse(bookingRepository.save(booking));
}
```

Notice the extra business-rule validation that can't be expressed via `@Valid`
annotations alone (they require cross-referencing the database): requiring a category
on an Individual Service request, cross-checking a supplied category against the
request type's expected `ServiceGroup` (a Full Home Services booking can't reference a
standalone trade category, and vice versa), and rejecting a mismatched budget range.
No `professionalId`/`portfolioItemId` exists on `BookingRequest` at all — a customer
cannot name a professional or point at a specific portfolio item; `category`/
`preferredStyle`/`budgetMin`/`budgetMax`/`location` are purely the signals
`ProfessionalMatchingService` scores candidates against (see
[Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching)).

**The state machine itself** — `validateTransition`:

```java
private void validateTransition(BookingStatus current, BookingStatus next) {
    boolean allowed = switch (current) {
        case PENDING -> next == BookingStatus.CONFIRMED || next == BookingStatus.CANCELLED;
        case CONFIRMED -> next == BookingStatus.COMPLETED || next == BookingStatus.CANCELLED;
        case PENDING_ASSIGNMENT, CANCELLED, COMPLETED -> false;   // terminal, or must be assigned first
    };

    if (!allowed) {
        throw new InvalidStateTransitionException("Cannot transition booking from " + current + " to " + next);
    }
}
```

This uses a Java **switch expression** (not statement) — each `case` *returns* a
value directly, and the compiler enforces all enum cases are handled (exhaustiveness
checking), so when `PENDING_ASSIGNMENT` was added as a new `BookingStatus` value, this
switch **stopped compiling** until it was given its own arm — a concrete example of
that exhaustiveness guarantee catching an incomplete change at compile time, before it
ever became a runtime bug. `PENDING_ASSIGNMENT` maps to `false` for every `next`: a
professional can't be the one to call this method on a booking they're not assigned to
in the first place, so there's genuinely no valid transition to allow from here —
moving out of `PENDING_ASSIGNMENT` only ever happens via `assignProfessional` (see next
section), never through this method. `InvalidStateTransitionException` → mapped to `409
Conflict` by the global handler.

**Who can do what:**

| Action | Endpoint | Role required | Extra ownership check |
|---|---|---|---|
| Create booking | `POST /api/bookings` | CUSTOMER | — |
| List my bookings | `GET /api/bookings` | any authenticated | filtered by `principal.getId()` — customers see bookings where they're the customer, professionals see ones where they're the professional (`BookingService.getBookingsForUser` branches on `role`) |
| Get one booking | `GET /api/bookings/{id}` | any authenticated | must be the customer *or* the assigned professional, if any (`assertParticipant`, null-safe since `professional` can be absent) |
| Cancel | `PATCH /api/bookings/{id}/cancel` | CUSTOMER | must be *the* customer on that booking, and it must still be cancellable (includes `PENDING_ASSIGNMENT`) |
| Update status | `PATCH /api/bookings/{id}/status` | PROFESSIONAL | must be *the* assigned professional (booking must have one), and the transition must be valid |
| Assign a professional | `PATCH /api/bookings/{id}/assign` | ADMIN | booking must be `PENDING_ASSIGNMENT` with no professional yet — see [Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching) |

---

## 17. Feature Walkthrough: Professional Assignment & Matching

**Purpose:** A booking with no `professionalId` sits in `PENDING_ASSIGNMENT` with
nobody attached to act on it — either because it's an Individual Service request
(always assigned by Velora) or a Full Home Services request that asked Velora to
choose. This is the feature that fills that gap — an admin asks the system to rank
candidate professionals for one specific booking, picks one, and the booking rejoins
the ordinary lifecycle exactly as if that professional had been booked directly. It's
a **ranked recommendation, not a silent auto-assign** — the admin still makes the final
call, deliberately, so a bad or unavailable match never gets committed without a human
confirming it.

**Step 0 — the queue:** `GET /api/bookings/awaiting-assignment?page=&size=`,
`@PreAuthorize("hasRole('ADMIN')")`, paginated, sorted `createdAt` **ascending** so the
longest-waiting booking is first — this is a work queue, not a browsing list, and the
oldest unassigned request is the one most in need of attention.

This is what makes the rest of this section reachable at all. `GET /api/bookings` answers
*"which bookings are mine?"* — it branches on the caller's role between
`findByCustomerId` and `findByProfessionalId`, and an admin is a participant in neither,
so an admin calling it gets an empty page. Without a queue endpoint an admin would need to
already know a booking id to assign it, which is no workflow at all.
`BookingService.getBookingsAwaitingAssignment` is therefore kept separate rather than
folded into `getBookingsForUser`: different question, different audience, no caller scoping.

**Endpoint:** `GET /api/bookings/{id}/recommendations`, `@PreAuthorize("hasRole('ADMIN')")`.

**`ProfessionalMatchingService.recommend`:**

```java
Booking booking = bookingRepository.findById(bookingId)...

if (booking.getStatus() != BookingStatus.PENDING_ASSIGNMENT) {
    throw new InvalidStateTransitionException("This booking is not awaiting professional assignment");
}

List<ProfessionalProfile> candidates = professionalProfileRepository.findByAvailabilityStatus(AvailabilityStatus.AVAILABLE);

return candidates.stream()
        .map(profile -> score(profile, booking))
        .sorted(Comparator.comparingInt(ProfessionalMatchResponse::score).reversed()
                .thenComparingLong(match -> activeBookingCount(match.professionalId())))
        .limit(MAX_RESULTS)
        .toList();
```

This scores every available professional identically regardless of trade — a painter
and an interior designer both go through the exact same `score(...)` method; nothing
in the matching algorithm branches on what kind of work the professional does.

Two things worth noting before the scoring itself:
1. **Unavailable professionals are excluded before any scoring happens**, not scored
   low — `professionalProfileRepository.findByAvailabilityStatus(AVAILABLE)` is the
   very first thing that runs. A professional who's toggled themselves off (see
   [Profile Flow](#20-feature-walkthrough-user-profile-role-conditional-data)) never
   even enters the candidate pool.
2. **`Comparator.thenComparingLong` only runs its second key extractor on a tie** in
   the first key — this is a genuine Java behavior, not a Velora-specific choice, and
   it's exactly why the tie-break (fewer active bookings wins) only ever calls
   `activeBookingCount` when two candidates land on the exact same score.

**The scoring formula** — deterministic, transparent, out of 100 points, no ML:

| Factor | Points | What it checks |
|---|---|---|
| Specialization / category match | 30 | Does `ProfessionalProfile.specialization` mention the booking's category name or preferred style (case-insensitive)? |
| Portfolio evidence — category | 10 | Does this professional's own portfolio (`PortfolioItem` rows) contain any work in the booking's category? (`countByProfessionalIdAndCategoryId`) |
| Portfolio evidence — style | 10 | Does this professional's own portfolio contain any *interior-design* work tagged with the booking's preferred style? (`countByProfessionalIdAndInteriorDesignDetailsStyleTagIgnoreCase`, traversing the optional `InteriorDesignDetails` satellite) |
| Experience | 15 | `min(yearsExperience, 10) / 10.0 * 15` — more experience helps, but caps out rather than letting a 30-year veteran dominate every score. |
| Location | 15 | Does `ProfessionalProfile.city` match the booking's `location`? |
| Rating | 15 | `(averageRating ?? 3.0) / 5.0 * 15` — a professional with zero reviews yet gets the neutral midpoint, not a zero. A brand-new professional being permanently unmatchable would be a bad incentive structure. |
| Budget fit | 5 | Is the booking's `budget` at least 70% of the average `priceEstimate` across this professional's own *interior-design* portfolio items (via `InteriorDesignDetails`)? A professional whose entire portfolio is non-interior-design work simply has no price data points and scores 0 here — graceful degradation, not a crash or a fabricated number. |

Every factor is a plain `if`/arithmetic check in Java — no external scoring library, no
weights file, no trained model. Anyone reading `ProfessionalMatchingService` can see
exactly why a candidate got the score they did, which matters for a feature admins are
expected to trust and act on.

**`budgetScore` reading through the optional satellite:**

```java
private int budgetScore(Long professionalId, Booking booking) {
    if (booking.getBudget() == null) {
        return 0;
    }
    List<PortfolioItem> items = portfolioItemRepository.findByProfessionalId(professionalId);
    List<BigDecimal> prices = items.stream()
            .map(PortfolioItem::getInteriorDesignDetails)
            .filter(details -> details != null)
            .map(InteriorDesignDetails::getPriceEstimate)
            .filter(price -> price != null)
            .toList();
    if (prices.isEmpty()) {
        return 0;
    }
    BigDecimal average = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(new BigDecimal(prices.size()), 2, RoundingMode.HALF_UP);
    BigDecimal affordableThreshold = average.multiply(BUDGET_FIT_RATIO);
    return booking.getBudget().compareTo(affordableThreshold) >= 0 ? 5 : 0;
}
```

Before the `Design`→`PortfolioItem` generalization, this read `Design::getPriceEstimate`
directly off every portfolio row. Since that field moved onto the optional
`InteriorDesignDetails` satellite (see [Entities](#6-entities-jpa-layer)), the stream
now filters out any `PortfolioItem` with no details row *before* trying to read a
price — a professional whose portfolio is entirely, say, plumbing and carpentry work
simply contributes zero data points and this factor stays `0`, exactly the same
"empty portfolio, no price signal" outcome the code already handled before this
feature existed. No new failure mode was introduced by generalizing the entity.

**Assigning the pick** reuses the endpoint that already existed for manual assignment
(unchanged by this feature at all):

```java
public BookingResponse assignProfessional(Long bookingId, Long professionalId) {
    Booking booking = findBooking(bookingId);
    if (booking.getProfessional() != null || booking.getStatus() != BookingStatus.PENDING_ASSIGNMENT) {
        throw new InvalidStateTransitionException("This booking is not awaiting professional assignment");
    }
    User professional = userRepository.findById(professionalId)...
    if (professional.getRole() != Role.PROFESSIONAL) {
        throw new IllegalArgumentException("Selected user is not a professional");
    }
    booking.setProfessional(professional);
    booking.setStatus(BookingStatus.PENDING);
    return bookingMapper.toResponse(bookingRepository.save(booking));
}
```

This is deliberate: `/recommendations` only ever *reads* and scores, never writes
anything. The admin's actual decision is committed through the exact same `/assign`
call an admin would use to hand-pick any professional with no ranking involved at all —
the ranking is purely an aid layered on top of an already-existing capability, not a
new write path with its own rules.

---

## 18. Feature Walkthrough: Quotations (Post-Consultation Estimate)

**Purpose:** A `Booking` only gets a professional and customer talking — it carries no
price. A **quotation** is the artifact that turns that consultation into a concrete,
itemized scope of work with a total cost, which the customer then explicitly accepts
or rejects. This applies identically to both request types — a plumbing job and a
full interior-design project both get quoted the same way, through the same entity and
endpoints.

**Data shape:** one `Quotation` per `Booking` (`booking_id UNIQUE` — no revision
history in this version), holding a list of `QuotationLineItem`s (`description`,
optional `quantity`/`unit`/`unitPrice` for a fully itemized row, and a required
`amount`). `totalAmount` on the quotation is always the sum of its line items'
`amount`s, recomputed server-side every time the line items change — never trusted
from client input.

**State machine:** `DRAFT → SENT → ACCEPTED` or `DRAFT → SENT → REJECTED`. Unlike
`BookingStatus`, there's no branch back to an editable state — once `SENT`, the
professional can no longer edit it, and once `ACCEPTED`/`REJECTED`, it's terminal (same
"no way out of a terminal state" philosophy as `BookingStatus.CANCELLED`/`COMPLETED`).

```
        ┌───────┐  professional sends ┌──────┐  customer accepts  ┌──────────┐
        │ DRAFT │────────────────────►│ SENT │───────────────────►│ ACCEPTED │
        └───────┘                     └──┬───┘                    └──────────┘
     (professional can keep                │  customer rejects
      editing line items                  ▼
      while still DRAFT)              ┌──────────┐
                                       │ REJECTED │
                                       └──────────┘
```

**Endpoints** — all nested under the booking they belong to
(`/api/bookings/{bookingId}/quotation`), following the same nested-resource pattern
`BookingController` uses for `/{id}/cancel` and `/{id}/status`:

| Action | Endpoint | Role required | Extra ownership check |
|---|---|---|---|
| Save/replace draft | `PUT /api/bookings/{bookingId}/quotation` | PROFESSIONAL | must be *the* professional assigned to that booking; quotation must currently be `DRAFT` (or not exist yet) |
| Send to customer | `POST /api/bookings/{bookingId}/quotation/send` | PROFESSIONAL | same professional check; quotation must be `DRAFT` and have at least one line item |
| Get quotation | `GET /api/bookings/{bookingId}/quotation` | any authenticated | must be the customer *or* the professional on that booking |
| Accept | `PATCH /api/bookings/{bookingId}/quotation/accept` | CUSTOMER | must be *the* customer on that booking; quotation must be `SENT` |
| Reject | `PATCH /api/bookings/{bookingId}/quotation/reject` | CUSTOMER | must be *the* customer on that booking; quotation must be `SENT` |

**Saving a draft — `QuotationService.saveDraft`:**

```java
Quotation quotation = quotationRepository.findByBookingId(bookingId)
        .orElseGet(() -> Quotation.builder()
                .booking(booking).status(QuotationStatus.DRAFT).totalAmount(BigDecimal.ZERO).build());

if (quotation.getId() != null && quotation.getStatus() != QuotationStatus.DRAFT) {
    throw new InvalidStateTransitionException(
            "Cannot edit a quotation once it has been sent, accepted, or rejected");
}

quotation.setNotes(request.notes());
replaceLineItems(quotation, request.lineItems());   // clears + rebuilds the list, recomputes totalAmount
```

Two things worth noticing:
1. `PUT` here means **create-or-replace-the-whole-draft**, not a partial update like
   `UserService.updateProfile`. Every call sends the *complete* line-item list; the
   service clears the existing list and rebuilds it (`quotation.getLineItems().clear()`
   then re-adds), relying on `orphanRemoval = true` (see [Entities](#6-entities-jpa-layer))
   to actually delete the old rows. This is simpler to reason about than diffing
   old-vs-new line items, at the cost of the client always resending the full list.
2. The `if (quotation.getId() != null && status != DRAFT)` check is what makes a
   sent/accepted/rejected quotation immutable — same defensive pattern as
   `BookingService.validateTransition` refusing to move a booking out of a terminal state.

**Sending — `QuotationService.send`:**

```java
if (quotation.getStatus() != QuotationStatus.DRAFT) {
    throw new InvalidStateTransitionException("Only a draft quotation can be sent");
}
if (quotation.getLineItems().isEmpty()) {
    throw new IllegalArgumentException("Cannot send a quotation with no line items");
}
quotation.setStatus(QuotationStatus.SENT);
```

The empty-line-items check is a good example of a rule that can't live in Bean
Validation on the DTO (see [Validation Flow](#21-validation-flow)) — `SaveQuotationRequest`
deliberately allows an empty `lineItems` list so a professional can save an in-progress
draft with nothing in it yet, but *sending* an empty quotation to a customer would be
meaningless, so that check only fires here, at the point where it actually matters.

**Accepting/rejecting — `QuotationService.respond` (shared by both):**

```java
if (!quotation.getBooking().getCustomer().getId().equals(customerId)) {
    throw new UnauthorizedActionException("Only the customer on this booking can respond to its quotation");
}
if (quotation.getStatus() != QuotationStatus.SENT) {
    throw new InvalidStateTransitionException("Only a sent quotation can be accepted or rejected");
}
quotation.setStatus(newStatus);   // ACCEPTED or REJECTED
```

Both `accept` and `reject` funnel through this one private method with just the target
status swapped — since the ownership check and the "must currently be SENT" check are
identical for both actions, duplicating them across two methods would just be two
copies of the same bug waiting to diverge.

**No new exception types, no new `GlobalExceptionHandler` entries.** The whole feature
reuses `ResourceNotFoundException` (404 — booking or quotation doesn't exist),
`UnauthorizedActionException` (403 — wrong participant), `InvalidStateTransitionException`
(409 — illegal status move), and plain `IllegalArgumentException` (400 — empty line
items on send), exactly the same set `BookingService` already relies on (see
[Exception Handling Flow](#22-exception-handling-flow)). New features in this codebase
default to reusing the existing exception vocabulary rather than inventing new ones.

---

## 19. Feature Walkthrough: Reviews & Ratings

**Purpose:** Once a booking is genuinely finished, the customer can leave a rating and
comment on the professional they worked with. This closes a loop the rest of the system
depends on: [Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching)
scores candidates partly on their aggregate rating — without this feature, that score
would be permanently meaningless (every professional stuck at the same neutral default
forever).

**Data shape:** one `Review` per `Booking` (`booking_id UNIQUE`, same one-per-booking
pattern `Quotation` already uses), holding `rating` (1-5, enforced by a DB `CHECK` as
well as `@Min`/`@Max` on the request DTO) and an optional `comment`. The professional's
`averageRating`/`ratingCount` on `ProfessionalProfile` are a **denormalized, recomputed
cache** — never trusted as a running tally, always fully recalculated from every review
that professional has, each time a new one is added.

**`ReviewService.createReview`:**

```java
Booking booking = bookingRepository.findById(bookingId)...

if (!booking.getCustomer().getId().equals(customerId)) {
    throw new UnauthorizedActionException("Only the customer on this booking can leave a review");
}
if (booking.getStatus() != BookingStatus.COMPLETED) {
    throw new InvalidStateTransitionException("Can only review a completed booking");
}
if (reviewRepository.existsByBookingId(bookingId)) {
    throw new DuplicateResourceException("This booking has already been reviewed");
}

Review review = Review.builder()
        .booking(booking).customer(booking.getCustomer()).professional(booking.getProfessional())
        .rating(request.rating()).comment(request.comment())
        .build();
review = reviewRepository.save(review);

recomputeProfessionalRating(booking.getProfessional().getId());
```

Three checks, in order, each mapping to a distinct, correct HTTP status: wrong customer
→ 403, wrong booking stage → 409, already reviewed → 409 (as a `DuplicateResourceException`,
same one `AuthService.register` uses for a duplicate email — a review and an email
account are very different things, but "this unique thing already exists" is exactly
the same shape of problem either time).

**Recomputing the aggregate — deliberately not an incremental running average:**

```java
private void recomputeProfessionalRating(Long professionalId) {
    List<Review> reviews = reviewRepository.findByProfessionalId(professionalId);

    ProfessionalProfile profile = professionalProfileRepository.findByUserId(professionalId)...

    BigDecimal average = reviews.stream()
            .map(r -> BigDecimal.valueOf(r.getRating()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(new BigDecimal(reviews.size()), 2, RoundingMode.HALF_UP);

    profile.setAverageRating(average);
    profile.setRatingCount(reviews.size());
    professionalProfileRepository.save(profile);
}
```

It would be cheaper to just do `(oldAverage * oldCount + newRating) / (oldCount + 1)`
and skip reloading every review — but that incremental approach silently accumulates
floating-point/rounding drift over many reviews, and gives no easy way to recover the
"true" number if something ever went wrong with one update. Reloading every review and
recalculating from scratch is more database work per review, but it's always
*correct*, and at the number of reviews a single professional realistically accumulates,
that cost is negligible. Correctness over micro-optimization, same instinct as
choosing Java-side averaging over a database `@Query` (see
[Repositories](#8-repositories-data-access-layer)).

**Endpoint and access:** `POST /api/bookings/{bookingId}/review`,
`@PreAuthorize("hasRole('CUSTOMER')")`, mirroring `QuotationController`'s
nested-under-the-booking URL style. Nothing new in `GlobalExceptionHandler` — every
exception this feature throws is one that already existed for `Booking`/`Quotation`.
**Read path — `GET /api/professionals/{id}/reviews`:** public, paginated, newest first.
As predicted when the write path was built, the rows already stored everything needed, so
this required no change to `createReview` at all — a repository overload, a mapper method
and an endpoint.

Three decisions worth pinning:

1. **`PublicReviewResponse`, not `ReviewResponse`.** The existing response carries
   `bookingId`, which is fine when handing a review back to the customer who just wrote
   it, and wrong when handing it to an anonymous stranger browsing the directory. The
   public shape drops it and adds `customerName` instead, since an attributed review is
   what makes it credible. Two shapes over one aggregate, split by audience — the same
   reason `ProfessionalSummaryResponse` and `ProfessionalPublicProfileResponse` coexist.
2. **`findByProfessionalId` is deliberately overloaded**, not replaced. The `List` version
   still backs `recomputeProfessionalRating`, which genuinely needs *every* review to
   average them; the `Page` version is the one that faces a client. Making the aggregate
   read a paginated slice would silently compute the average of the first 20 reviews.
3. **No reviews is an empty page, not a `404`.** A professional nobody has reviewed yet
   exists and is bookable; only an unknown id — or one belonging to a customer — is a
   `404`, using the same `.filter(role == PROFESSIONAL)` guard as `getPublicProfile` so
   both endpoints agree on what "no such professional" means.

The endpoint lives on `ProfessionalController` rather than `ReviewController` because
`ReviewController` is mapped at `/api/bookings/{bookingId}/review` — controllers here are
organised by URL root, and this one is `/api/professionals/**`, already public in
`SecurityConfig`.

---

## 20. Feature Walkthrough: User Profile (Role-Conditional Data)

**Purpose:** Every user (customer or professional) can view/update their own profile
via one shared endpoint pair — `GET /api/users/profile` and `PUT /api/users/profile` —
rather than having separate customer-profile and professional-profile endpoints. This
one shape works for every trade — a plumber's and an interior designer's profile
response are structurally identical, only the free-text `bio`/`specialization` content
differs.

**Why one shared response shape works for both roles:**

```java
public record UserProfileResponse(
        Long id, String email, String fullName, String phone, Role role,
        Instant createdAt,
        ProfessionalProfileResponse professionalProfile   // null for customers
) {}
```

`UserService.getProfile` only bothers looking up a `ProfessionalProfile` row **if** the
user's role is `PROFESSIONAL`:

```java
ProfessionalProfile professionalProfile = user.getRole() == Role.PROFESSIONAL
        ? professionalProfileRepository.findByUserId(userId).orElse(null)
        : null;
```

For a customer, `professionalProfile` is always `null`. Because `application.yml` sets:

```yaml
spring.jackson.default-property-inclusion: non_null
```

Jackson (the JSON serializer) **omits any field whose value is `null`** entirely from
the JSON output — so a customer's profile response simply doesn't contain a
`professionalProfile` key at all, rather than including a confusing
`"professionalProfile": null`. This is the exact same mechanism that makes a
non-interior-design `PortfolioItem`'s `styleTag`/`priceEstimate` disappear from its
JSON response too (see [DTOs](#7-dtos--why-we-never-return-entities-directly)) — one
Jackson setting, reused for every "this field only applies sometimes" case in the API.

**Updating a profile — partial updates:**

```java
public record UpdateProfileRequest(
        @Size(max = 150) String fullName, @Size(max = 20) String phone,
        @Size(max = 2000) String bio, @Min(0) @Max(80) Integer yearsExperience,
        @Size(max = 150) String specialization, @Size(max = 100) String city,
        AvailabilityStatus availabilityStatus
) {}
```

`availabilityStatus` slotted into this same partial-update pattern with no special
handling — it's just one more professional-only field, applied the same way `bio`/
`specialization`/etc. already are: only if non-null, only if the account is actually a
`PROFESSIONAL`. This is the *entire* implementation of "availability" in this system
today — a self-managed on/off flag a professional flips whenever they choose, not a
real calendar with time slots. [Professional Assignment & Matching](#17-feature-walkthrough-professional-assignment--matching)
is the only thing that reads it, and it does so with a simple equality filter
(`findByAvailabilityStatus(AVAILABLE)`), not anything scheduling-aware.

Notice what's deliberately **absent** from `UpdateProfileRequest`: `averageRating` and
`ratingCount`. Those two fields appear on `ProfessionalProfileResponse` (read side) but
have no corresponding settable field anywhere — the only code path that ever writes
them is `ReviewService.recomputeProfessionalRating` (see
[Reviews & Ratings](#19-feature-walkthrough-reviews--ratings)). A professional can
change their own bio; they cannot touch their own rating, by construction, not just by
convention — there's simply no field in the request DTO for it to arrive through.

Every field is optional (no `@NotBlank`/`@NotNull`) — the client only sends the fields
it wants to change. `UserService.updateProfile` checks each one individually
before applying it:

```java
if (request.fullName() != null && !request.fullName().isBlank()) {
    user.setFullName(request.fullName().trim());
}
if (request.phone() != null) {
    user.setPhone(request.phone());
}
```

and only touches professional-specific fields (`bio`, `yearsExperience`,
`specialization`, `city`, `availabilityStatus`) if the user is actually a
`PROFESSIONAL` — creating their `ProfessionalProfile` row lazily on first update if one
doesn't already exist (`orElseGet(() -> ProfessionalProfile.builder()...)`). This means
a customer sending `bio` in the request body has it silently ignored — it's simply
never read, because the whole professional-fields block is skipped for their role.

---

## 21. Validation Flow

**What:** Jakarta Bean Validation (the `@NotBlank`, `@Email`, `@Size`, `@Pattern`,
`@Min`, `@Max`, `@Future`, `@NotNull` annotations you see on DTO fields) is a
standard Java spec for declaring data constraints directly on the data class, instead
of writing manual `if` checks in every controller.

**Why:** Keeps validation rules colocated with the field they constrain (self-documenting),
and lets Spring run them **automatically** before your code ever executes — you never
see invalid data inside a service method.

**How it's wired up:**

```java
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
```

- `@RequestBody` tells Spring MVC to deserialize the incoming JSON into a
  `RegisterRequest`.
- `@Valid` tells Spring to run Bean Validation on that object **immediately
  afterward**, before invoking the method body.
- If any constraint fails, Spring throws `MethodArgumentNotValidException` — the
  controller method body **never runs** — and the exception is caught globally (see
  next section).

**Example constraint from `RegisterRequest`:**

```java
@NotBlank @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
@Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
        message = "Password must contain at least one letter and one digit")
String password
```

Three stacked constraints on one field: not blank, length range (with a custom error
message), and a regex requiring at least one letter and one digit (a lookahead-based
pattern — reads as "the whole string must contain a letter somewhere *and* a digit
somewhere").

**Beyond field-level validation:** some rules can't be expressed as annotations
because they depend on other data (e.g. "does this professional id actually belong to
a PROFESSIONAL role user?", "does this email already exist?", "does this category's
service group actually match this booking's request type?"). Those live as explicit
checks inside the **service layer**, throwing custom exceptions like
`DuplicateResourceException` or plain `IllegalArgumentException` — see next section
for how those become HTTP responses too.

---

## 22. Exception Handling Flow

**What:** `GlobalExceptionHandler`, annotated `@RestControllerAdvice`, is a single
class that intercepts exceptions thrown **anywhere** in any controller/service call
chain and converts them into a consistent JSON error shape — so you never write
try/catch blocks in individual controllers.

**Why:** Without this, an unhandled exception would either leak a raw stack trace to
the client, or return Spring Boot's generic whitelabel error page — neither of which
is useful for an API consumer (a mobile app, in this case).

**The universal error shape — `ApiErrorResponse`:**

```java
public record ApiErrorResponse(
        Instant timestamp, int status, String error, String message,
        String path, List<FieldViolation> fieldErrors
) {
    public record FieldViolation(String field, String message) {}
}
```

Every error response — no matter what went wrong — has this exact shape. `fieldErrors`
is only populated for validation failures (see below); otherwise it's `null` (and, per
the Jackson config, omitted from the JSON entirely).

**The mapping table** (`GlobalExceptionHandler`'s `@ExceptionHandler` methods):

| Exception thrown | HTTP status | When it happens |
|---|---|---|
| `ResourceNotFoundException` | 404 Not Found | Looking up a user/portfolio item/booking by id that doesn't exist |
| `DuplicateResourceException` | 409 Conflict | Registering with an email that's already taken, or leaving a second review on the same booking |
| `UnauthorizedActionException` | 403 Forbidden | Acting on a resource you don't own (e.g. cancelling someone else's booking, reviewing a booking that isn't yours) |
| `InvalidStateTransitionException` | 409 Conflict | Illegal booking status change (e.g. CONFIRMED → PENDING), assigning a professional to a booking that isn't `PENDING_ASSIGNMENT`, requesting recommendations for a booking that isn't awaiting assignment, or reviewing a booking that isn't `COMPLETED` |
| `BadCredentialsException` (Spring Security) | 401 Unauthorized | Wrong email/password at login |
| `AccessDeniedException` (Spring Security) | 403 Forbidden | `@PreAuthorize` role check failed |
| `DataIntegrityViolationException` (Spring/JPA) | 409 Conflict | A DB constraint was violated (e.g. race-condition duplicate) |
| `MethodArgumentNotValidException` | 400 Bad Request | `@Valid` bean validation failed — includes a `fieldErrors` list, one entry per invalid field |
| `IllegalArgumentException` | 400 Bad Request | Ad-hoc business-rule violations thrown directly in services (e.g. "Selected user is not a professional", "Selected category does not match the request type") |
| `MethodArgumentTypeMismatchException` | 400 Bad Request | A path variable or query parameter that can't be converted to its declared type — `/api/portfolio/abc`, `?availability=MAYBE`. Reports the offending parameter, and for enums the permitted values |
| `HttpMessageNotReadableException` | 400 Bad Request | A malformed or unparseable JSON body |
| Anything else (`Exception`) | 500 Internal Server Error | Unexpected/unmapped errors — message is deliberately generic ("An unexpected error occurred") so internal details never leak to clients |

**Example — how a validation failure becomes a response:**

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
    List<ApiErrorResponse.FieldViolation> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiErrorResponse.FieldViolation(fe.getField(), messageOf(fe)))
            .toList();
    return build(HttpStatus.BAD_REQUEST, "Validation failed", req, fieldErrors);
}
```

If you register with a too-short password and a missing email, the client receives:

```json
{
  "timestamp": "2026-08-01T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/auth/register",
  "fieldErrors": [
    { "field": "email", "message": "must not be blank" },
    { "field": "password", "message": "Password must be between 8 and 100 characters" }
  ]
}
```

— one entry per failing field, exactly matching the `@Size(message = "...")` you wrote
on the DTO.

**Why the last two rows exist:** both were added because the web-layer tests found them
missing. A request like `?availability=MAYBE` never reaches a controller method — Spring
fails to bind the parameter and throws before invocation — so with no handler registered
it fell through to the catch-all `Exception` mapping and came back as a **500**, telling
the client the server was broken when the request was simply wrong. No service test could
have caught it: the failure happens in argument resolution, above the layer those tests
exercise. Same for a malformed JSON body and for a non-numeric path variable like
`/api/portfolio/abc`.

**Two layers of 401/403 handling, and why both exist:** You'll notice 401/403 are
produced in *two* different places — `GlobalExceptionHandler` (for exceptions thrown
during normal request processing, e.g. bad login credentials) **and**
`RestSecurityErrorHandler` (for failures that happen *inside the
security filter chain itself*, before the request ever reaches a controller — e.g. no
token at all, or role check failure). Spring's exception-handling advice
(`@RestControllerAdvice`) only intercepts exceptions from the MVC dispatch process; it
cannot catch something that Spring Security's filters already handled and short-circuited
before reaching MVC. That's why both exist, and why they're written to produce the
exact same `ApiErrorResponse` JSON shape — so from the client's point of view, there's
no visible difference in error format regardless of which layer caught the problem.

---

## 23. API Documentation (Swagger/OpenAPI)

**What:** `springdoc-openapi-starter-webmvc-ui` automatically scans all
`@RestController` classes/methods and generates a live, interactive API
specification — no manually-maintained documentation file to go stale.

**Why:** Lets any developer (or the mobile app team) explore and test every endpoint
directly from a browser, see exact request/response shapes, and try calls with a real
JWT — all generated from the actual code, so it can never drift out of sync with
reality.

**Where to find it (once the app is running):**
- Interactive UI: `http://localhost:8080/swagger-ui/index.html` (or `/swagger-ui.html`)
- Raw OpenAPI JSON spec: `http://localhost:8080/v3/api-docs`

**How endpoints get documented:** `@Tag` on the controller class groups related
endpoints together in the UI; `@Operation(summary = "...")` on each method gives it a
human-readable description. Example from `AuthController`:

```java
@Tag(name = "Auth", description = "Registration and login")
public class AuthController {
    @PostMapping("/register")
    @Operation(summary = "Register a new customer or professional account")
    public ResponseEntity<AuthResponse> register(...) { ... }
}
```

**Authenticating in Swagger UI:** `OpenApiConfig` registers a `bearerAuth` security
scheme, which adds an "Authorize" button in the Swagger UI. After logging in via
`/api/auth/login` (through the UI itself), paste the returned `accessToken` into that
button, and every subsequent "Try it out" call in the UI automatically includes the
`Authorization: Bearer <token>` header — letting you test protected endpoints directly
in the browser.

Both `/swagger-ui/**` and `/v3/api-docs/**` are listed in `SecurityConfig`'s
`PUBLIC_ENDPOINTS`, so anyone can view the documentation without a token (only
*calling* protected endpoints through it requires one).

---

## 24. Environment & Secrets Management

**Why environment variables instead of hardcoded config:** Database credentials, JWT
signing secrets, and CORS origins differ between local development, staging, and
production. Hardcoding any of them into `application.yml` would mean committing
secrets to source control (a serious security risk) and would make it impossible to
run the same build against different environments.

**How it works:**

```yaml
datasource:
  url: ${DB_URL:jdbc:postgresql://localhost:5432/velora}
  username: ${DB_USERNAME:velora}
  password: ${DB_PASSWORD:velora}
```

The `${ENV_VAR:default}` syntax means: *use the environment variable if it's set,
otherwise fall back to the value after the colon.* This lets local development "just
work" with sensible defaults while production can override every value via real
environment variables.

**`.env` file + `spring-dotenv`:** Rather than requiring every developer to manually
`export` a dozen environment variables before running the app, this project uses the
`spring-dotenv` library (added to `pom.xml`), which automatically loads a `.env` file
from the project root at startup and exposes its key-value pairs as Spring properties
— exactly as if they'd been exported as real OS environment variables. `.env` is
listed in `.gitignore`, so real secrets never get committed; `.env.example` documents
which variables are expected (with placeholder/dummy values) so a new developer knows
exactly what to fill in.

**Currently configured for this project:** the database points at a **Neon**-hosted
Postgres instance (`DB_URL=jdbc:postgresql://<neon-host>/neondb?sslmode=require`) —
Neon requires SSL for all connections, hence `sslmode=require` in the URL. This
replaced an earlier local Docker/Postgres setup that was removed once Neon was
adopted, to avoid local port conflicts with any locally-installed Postgres instance.

**The `local` Spring profile (`application-local.yml`):** activated by setting
`spring.profiles.active=local` (or `-Dspring-boot.run.profiles=local` when running via
Maven). It layers on top of `application.yml` and currently: provides a safe built-in
dev-only JWT secret (so you're not forced to generate one just to run the app
locally), and bumps logging to `DEBUG` (including raw SQL logging via
`org.hibernate.SQL: DEBUG`) for easier local troubleshooting.

---

## 25. Quick Reference: All Endpoints

| Method | Path | Auth required | Role restriction | Purpose |
|---|---|---|---|---|
| POST | `/api/auth/register` | No | — | Create a new account (customer or professional — never admin), returns an access + refresh token |
| POST | `/api/auth/login` | No | — | Authenticate with email/password, returns an access + refresh token |
| POST | `/api/auth/refresh` | No¹ | — | Exchange a refresh token for a new pair; the presented one is revoked |
| POST | `/api/auth/logout` | No¹ | — | End this session — idempotent, an unknown token still returns 204 |
| POST | `/api/auth/logout-all` | Yes | — | End every session for the current user |
| POST | `/api/auth/password/forgot` | No | — | Request a reset token; always 202, even for unknown emails |
| POST | `/api/auth/password/reset` | No¹ | — | Set a new password with a reset token (single use, revokes all sessions) |
| POST | `/api/auth/password/change` | Yes | — | Change your own password, current password required (revokes all sessions) |
| GET | `/api/users/profile` | Yes | — | Get the current user's own profile (includes availability/rating for professionals) |
| PUT | `/api/users/profile` | Yes | — | Update the current user's own profile, incl. availability toggle (partial update) |
| GET | `/api/portfolio` | Yes | ADMIN | Search/browse the portfolio catalog (paginated, filterable) — assignment context, not customer browsing |
| GET | `/api/portfolio/{id}` | Yes | ADMIN | Get one portfolio item's full details |
| POST | `/api/portfolio` | Yes | PROFESSIONAL | Upload a new portfolio item under the caller's own account |
| PUT | `/api/portfolio/{id}` | Yes | PROFESSIONAL | Replace one of your own portfolio items (full replacement, not a patch) |
| DELETE | `/api/portfolio/{id}` | Yes | PROFESSIONAL | Delete one of your own portfolio items (blocked while a booking references it) |
| GET | `/api/categories` | No | — | List all categories (each tagged HOME_PROJECT or INDIVIDUAL_SERVICE) |
| GET | `/api/professionals` | Yes | ADMIN | Search/browse the professional directory (paginated, filterable) — used only for admin assignment |
| GET | `/api/professionals/{id}` | Yes | ADMIN | Get one professional's profile (bio, experience, availability, rating) |
| GET | `/api/professionals/{id}/reviews` | Yes | ADMIN | List that professional's reviews, newest first (paginated) |
| POST | `/api/bookings` | Yes | CUSTOMER | Book a Full Home Services project or an Individual Service request — always starts `PENDING_ASSIGNMENT`, never a chosen professional |
| GET | `/api/bookings` | Yes | — | List the current user's bookings (customer or professional view) |
| GET | `/api/bookings/{id}` | Yes | — | Get one booking's details (must be a participant) |
| PATCH | `/api/bookings/{id}/cancel` | Yes | CUSTOMER | Cancel your own booking (PENDING_ASSIGNMENT/PENDING/CONFIRMED) |
| PATCH | `/api/bookings/{id}/status` | Yes | PROFESSIONAL | Advance a booking's status (only if you're the assigned professional) |
| GET | `/api/bookings/awaiting-assignment` | Yes | ADMIN | The admin work queue: bookings still awaiting a professional, oldest first |
| GET | `/api/bookings/{id}/recommendations` | Yes | ADMIN | Rank candidate professionals for a booking awaiting assignment |
| PATCH | `/api/bookings/{id}/assign` | Yes | ADMIN | Assign a professional to a booking awaiting assignment |
| PUT | `/api/bookings/{bookingId}/quotation` | Yes | PROFESSIONAL | Create or replace a draft quotation for a booking (assigned professional only) |
| POST | `/api/bookings/{bookingId}/quotation/send` | Yes | PROFESSIONAL | Send a draft quotation to the customer |
| GET | `/api/bookings/{bookingId}/quotation` | Yes | — | Get the quotation for a booking (must be a participant) |
| PATCH | `/api/bookings/{bookingId}/quotation/accept` | Yes | CUSTOMER | Accept a sent quotation |
| PATCH | `/api/bookings/{bookingId}/quotation/reject` | Yes | CUSTOMER | Reject a sent quotation |
| POST | `/api/bookings/{bookingId}/review` | Yes | CUSTOMER | Leave a 1-5 rating + comment for a completed booking (once only) |
| GET | `/actuator/health` | No | — | Health check (used to confirm the app is up) |
| GET | `/swagger-ui/index.html` | No | — | Interactive API documentation |
| GET | `/v3/api-docs` | No | — | Raw OpenAPI JSON spec |

¹ No `Authorization` header required — the refresh or reset token in the request body is
itself the credential. These are listed individually in `SecurityConfig.PUBLIC_ENDPOINTS`
rather than covered by an `/api/auth/**` wildcard, so that `logout-all` and
`password/change` stay private. See [§26](#26-sessions-logout--password-recovery).

---

## 26. Sessions, Logout & Password Recovery

**Why this exists:** a JWT can't be taken back. Once signed, it's valid until it expires,
no matter what happens to the account behind it. That's fine for a web app where the
token lives 15 minutes and the tab gets closed; it's not fine for a phone that expects to
stay signed in for weeks. Two token types solve it:

| | Access token | Refresh token |
|---|---|---|
| Format | Signed JWT | 256 random bits, Base64url |
| Lifetime | 15 min (`JWT_EXPIRATION_MS`) | 30 days (`REFRESH_TOKEN_TTL`) |
| Stored server-side? | No — stateless | Yes, as a SHA-256 digest |
| Revocable? | **No** | Yes |
| Sent as | `Authorization: Bearer …` | Request body, only to `/auth/refresh` and `/auth/logout` |

The access token stays short precisely *because* it can't be revoked — its lifetime is the
window a stolen one keeps working. Everything that needs to be cancellable lives in
`refresh_tokens`, where a row can be struck out.

**Why the tokens are hashed:** `refresh_tokens.token_hash` and
`password_reset_tokens.token_hash` hold a SHA-256 digest, never the token. A database dump
therefore can't be replayed against the API. Plain SHA-256 is correct here — unlike a
password, these are 256 bits of `SecureRandom` output, so there is nothing to brute-force,
and lookup has to be deterministic. `SecureTokenGenerator` owns both halves.

**Rotation:** `RefreshTokenService.consumeAndRotate` revokes the presented token *and*
issues a new one on every refresh. A refresh token is single-use, so a stolen one stops
working the moment the legitimate client next refreshes. Unknown, expired and
already-revoked tokens all fail identically — the client learns nothing from which case it
hit.

**Logout is deliberately forgiving.** `revoke` does nothing if the token isn't found: the
caller's intent is "end this session", and a token that was already revoked, or never
valid, satisfies that. Returning `404` would tell an attacker which tokens exist and would
make a client's cleanup-on-logout fail for no useful reason.

**Password changes end every session.** Both `resetPassword` and `changePassword` finish
with `revokeAllForUser`. A password change is exactly the moment you want an attacker's
stolen session to stop working — including, deliberately, the caller's own other devices.

**`changePassword` re-checks the current password** even though the caller already holds a
valid access token. A token proves the session authenticated at some point; it doesn't
prove the person holding the unlocked phone right now knows the password.

**Forgot-password never says whether the account exists.** `requestReset` returns normally
either way and `202 Accepted` is the response for any syntactically valid email — the same
reasoning as the vague login error in [§11](#11-authentication-flow--login). A second
request invalidates the first outstanding token, so only one link is ever live.

**Delivery is not built.** `PasswordResetNotifier` is an interface; the only implementation,
`LoggingPasswordResetNotifier`, writes the token to the log. The security-bearing parts —
generation, hashing, expiry, single use, session revocation — are complete; sending the
email is a one-bean swap that changes nothing above it. **A reset token in a log file is a
reset token available to anyone who can read logs**, so this must not reach production
as-is.

**The security config stopped using a wildcard.** `/api/auth/**` used to be `permitAll`.
Two of the new endpoints — `logout-all` and `password/change` — identify the caller by
their access token, and the wildcard would have silently published them. `PUBLIC_ENDPOINTS`
now lists the auth routes one by one; each is public because it carries its own credential
(a password, a refresh token, or a reset token), and anything new under `/api/auth` is
private until someone deliberately adds it to that list.

---

## 27. Appendix: End-to-End Flow Diagrams (No Code)

Every flow above, redrawn as a decision tree with no class names, no annotations, and no
Java — including the error branches. Useful for onboarding a non-backend reader, for
reviewing the business rules without the framework noise, and as a checklist when
changing one of these flows. Each diagram links back to the section that explains the
same flow in code.

### 27.1 Startup → [§3](#3-application-startup-flow)

Configuration loads, the JWT secret is validated, Flyway migrates, JPA verifies the
schema, security is wired, the server listens. Any failure aborts startup — nothing ever
runs in a half-broken state.

```
App process starts
        │
        ▼
Load config from .env / environment variables
        │
        ▼
Validate JWT secret
        │
        ├── Missing or too short
        │         │
        │         ▼
        │   Abort startup (fail fast, clear error)
        │
        ▼
Connect to database
        │
        ▼
Flyway: compare migration history vs. migration files
        │
        ▼
Apply any pending migrations, in order
        │
        ▼
JPA validates entity classes match actual DB schema
        │
        ├── Mismatch
        │      │
        │      ▼
        │   Abort startup
        │
        ▼
Wire up security rules
  • Public vs. protected URLs
  • CORS policy
  • Password hashing strategy
        │
        ▼
Start web server, begin listening
        │
        ▼
Application ready to accept requests
```

### 27.2 Registration → [§10](#10-authentication-flow--register)

Admin accounts can never be self-registered — that's a hard block, not a convention. A
professional additionally gets an empty profile record created and linked at signup, so
there's no separate "set up your professional profile" step later.

```
Client sends email, password, fullName, phone, role
        │
        ▼
Is the requested role "admin"?
        │
        ├── Yes
        │      │
        │      ▼
        │   400 Bad Request (admin accounts can't self-register)
        │
        ▼
Validate input shape (format, length, required fields)
        │
        ├── Invalid
        │      │
        │      ▼
        │   400 Bad Request (field-by-field errors)
        │
        ▼
Normalize email (trim + lowercase)
        │
        ▼
Does an account with this email already exist?
        │
        ├── Yes
        │      │
        │      ▼
        │   409 Conflict
        │
        ▼
Hash the password
        │
        ▼
Save new user record
        │
        ├── role == PROFESSIONAL
        │         │
        │         ▼
        │   Create empty professional profile, linked to user
        │   (availability defaults to "available")
        │
        ▼
Generate signed JWT access token
        │
        ▼
201 Created + access token + basic user info
```

### 27.3 Login → [§11](#11-authentication-flow--login)

The rejection message is deliberately vague about *which* half was wrong — saying "no
such email" would let an attacker enumerate which addresses have accounts.

```
Client sends email + password
        │
        ▼
Normalize email
        │
        ▼
Look up account by email
        │
        ▼
Compare submitted password against stored hash
        │
        ├── No account found, or password doesn't match
        │         │
        │         ▼
        │   401 "Invalid email or password"
        │   (deliberately vague — never says which part was wrong)
        │
        ▼
Password matches
        │
        ▼
Generate a fresh signed JWT access token
        │
        ▼
200 OK + access token + basic user info
        │
        ▼
Client stores token, attaches it to future requests
```

### 27.3b Sessions & Password Recovery → [§26](#26-sessions-logout--password-recovery)

Refresh tokens are single-use: spending one revokes it and mints another. Everything that
touches a password ends every session the account has.

```
Access token expired (client gets a 401)
        │
        ▼
POST /api/auth/refresh with the stored refresh token
        │
        ▼
Hash it, look for a matching row
        │
        ├── No row, already revoked, or past its expiry
        │         │
        │         ▼
        │   401 (all three cases look identical from outside)
        │   → client must log in again
        │
        ▼
Revoke the presented token (single use)
        │
        ▼
Issue a NEW access token + a NEW refresh token
        │
        ▼
200 OK — client replaces both


Logout (this device)                Logout everywhere
        │                                   │
        ▼                                   ▼
Revoke the presented token          Requires a valid access token
 (unknown token → still 204;                │
  the goal is "be logged out")              ▼
        │                           Revoke every row for this user
        ▼                                   │
   204 No Content                           ▼
                                     204 No Content


Forgot password
        │
        ▼
Look up the email
        │
        ├── No such account → do nothing at all
        │
        ├── Account exists  → invalidate any outstanding token,
        │                     store a hash of a fresh one,
        │                     hand the raw token to the notifier
        │
        ▼
202 Accepted — identical either way, so the response
 never reveals which emails have accounts


Reset password (with token)          Change password (logged in)
        │                                     │
        ▼                                     ▼
Hash the token, find a usable row      Verify the CURRENT password
        │                                     │
        ├── Missing/expired/spent             ├── Wrong → 401
        │         │                           │
        │         ▼                           │
        │       401                           │
        ▼                                     ▼
Save the new password hash             Save the new password hash
        │                                     │
        ▼                                     ▼
Mark the token used (single use)              │
        │                                     │
        └──────────────┬──────────────────────┘
                       ▼
        Revoke EVERY refresh token for this user
         (any stolen session dies with the password)
                       │
                       ▼
                 204 No Content
```

### 27.4 Authenticated Request → [§12](#12-how-a-protected-request-is-authenticated-jwt-filter), [§13](#13-authorization--roles-and-preauthorize)

Runs on every single request. A bad token is never an error by itself — it just leaves
the request anonymous, and the authorization rules decide whether that's fatal. The user
is reloaded from the database on every request, so a deleted account or a changed role
takes effect immediately rather than when the token eventually expires.

```
Client sends request
        │
        ▼
Read Authorization header
        │
        ├── Missing or invalid format
        │         │
        │         ▼
        │   Treat as Anonymous
        │
        ▼
Extract Bearer token
        │
        ▼
Read email from JWT
        │
        ▼
Load latest user from database
        │
        ▼
Validate:
  • Signature
  • Expiry
  • User exists
  • Email matches
        │
   ┌────┴────┐
   │         │
 Invalid   Valid
   │         │
   ▼         ▼
Anonymous  Authenticated User
             │
             ▼
Check endpoint security
             │
     ┌───────┴────────┐
     │                │
 Public          Login Required
     │                │
     ▼                ▼
Allow         Authenticated?
                    │
            ┌───────┴────────┐
            │                │
           No               Yes
            │                │
            ▼                ▼
     401 Unauthorized   Check Role
                              │
                     ┌────────┴────────┐
                     │                 │
                Wrong Role       Correct Role
                     │                 │
                     ▼                 ▼
             403 Forbidden      Controller Executes
```

### 27.5 General Request Lifecycle → [§14](#14-full-request-lifecycle-controller--service--repository--db)

The shape every feature request follows. Shape validation happens before the database is
ever touched; data-dependent rules (ownership, existence, uniqueness) happen one layer
deeper.

```
Request arrives
        │
        ▼
Security checkpoint (if endpoint is protected — see 27.4)
        │
        ▼
Read input: path variables / query params / JSON body
        │
        ▼
Validate input shape
        │
        ├── Invalid
        │      │
        │      ▼
        │   400 Bad Request (detailed field errors)
        │
        ▼
Hand off to business logic layer, with caller identity
        │
        ▼
Business logic:
  • Fetch needed records
  • Apply ownership / status / role / uniqueness rules
        │
        ├── Rule violated
        │      │
        │      ▼
        │   Specific error (404 / 403 / 409 / ...)
        │
        ▼
Perform the change, or read the data
        │
        ▼
Map entity/entities → response DTO
  (internal-only fields like password hashes never included)
        │
        ▼
Return response with appropriate status code
```

### 27.6 Portfolio Catalog Browsing → [§15](#15-feature-walkthrough-portfolio-catalog-searchfilterpagination)

Fully public. Every filter is optional and independently applied. Style and price exist
only on interior-design items — a painter's or plumber's item omits those fields
entirely rather than showing them empty.

```
Client requests GET /api/portfolio
 (optional: category, search, style, page, size, sortBy, direction)
        │
        ▼
Clamp page size to the allowed maximum
        │
        ▼
Whitelist the sort field (unknown field → falls back to default;
 "priceEstimate" resolves through the optional interior-design details)
        │
        ▼
Build filters — only for parameters actually supplied
        │
        ├── category given → filter by category
        ├── search given   → filter by title/description match
        ├── style given    → filter by style tag (interior-design items only)
        │      (any combination, or none at all)
        │
        ▼
Run one combined query, plus a total-count query
        │
        ▼
Map each result to a lightweight summary (card-sized fields;
 price/style included only if the item has interior-design details)
        │
        ▼
Wrap results in a page envelope (content + paging metadata)
        │
        ▼
200 OK


Client requests GET /api/portfolio/{id}
        │
        ▼
Look up portfolio item by id
        │
        ├── Not found
        │      │
        │      ▼
        │   404 Not Found
        │
        ▼
Map to full detail response (description, category, professional,
 plus style/price if this item has interior-design details)
        │
        ▼
200 OK


Client requests GET /api/professionals  (public, no token needed)
 (optional: search, specialization, city, availability,
  minExperience, minRating, page, size, sortBy, direction)
        │
        ▼
Clamp page size, whitelist the sort field (unknown → highest-rated first)
        │
        ▼
Always restrict to PROFESSIONAL accounts, then add only the filters supplied
        │
        ├── search given         → name / specialization / bio match
        ├── specialization given → substring match
        ├── city given           → exact match, case-insensitive
        ├── availability given   → AVAILABLE or UNAVAILABLE
        ├── minExperience given  → at least N years
        ├── minRating given      → at least N average (unrated are excluded)
        │      (any combination, or none at all)
        │
        ▼
Map each result to a directory card (no bio; id is the USER id,
 so it works in every other professional endpoint)
        │
        ▼
Wrap results in a page envelope (content + paging metadata)
        │
        ▼
200 OK


Client requests GET /api/professionals/{id}  (public, no token needed)
        │
        ▼
Look up user by id
        │
        ├── Not found, or not actually a professional account
        │      │
        │      ▼
        │   404 Not Found
        │
        ▼
Load that professional's profile (bio, experience, specialization,
city, availability, aggregate rating)
        │
        ▼
200 OK


Professional edits or deletes one of their items
 (PUT /api/portfolio/{id} — full replacement, or DELETE /api/portfolio/{id})
        │
        ▼
Look up portfolio item by id
        │
        ├── Not found
        │      │
        │      ▼
        │   404 Not Found
        │
        ▼
Is the caller the professional who owns this item?
        │
        ├── No
        │      │
        │      ▼
        │   403 Forbidden (being a professional isn't enough — must be THIS item's)
        │
        ▼
   ┌────┴─────┐
   │          │
 Edit       Delete
   │          │
   │          ▼
   │    Is any booking still pointing at this item?
   │          │
   │          ├── Yes → 409 Conflict (part of that booking's history)
   │          ▼
   │    Delete the item (interior-design details go with it)
   │          │
   │          ▼
   │        204 No Content
   ▼
Overwrite every field with what was sent
 (title, description, category, cover image)
        │
        ▼
Interior-design details: style and/or price sent?
        │
        ├── Yes → attach if missing, otherwise update in place
        │
        ├── No  → drop the details row if it existed
        │
        ▼
200 OK
```

### 27.7 Booking → [§16](#16-feature-walkthrough-bookings-state-machine)

Every booking starts `PENDING_ASSIGNMENT`, regardless of request type — a customer
never names a professional (there is no `professionalId`/`portfolioItemId` on
`BookingRequest` at all). Both request types converge on the same lifecycle once an
admin attaches a professional via `PATCH /api/bookings/{id}/assign`.

```
Customer requests a new booking
 (requestType: FULL_HOME_PROJECT or INDIVIDUAL_SERVICE, scheduledAt, optional notes,
  categoryId/preferredStyle/budgetMin/budgetMax/location — the signals
  ProfessionalMatchingService will score candidates against)
        │
        ▼
Is requestType == INDIVIDUAL_SERVICE?
        │
        ├── Yes → Was a categoryId given?
        │              │
        │              ├── No → 400 Bad Request (category required)
        │              ▼
        │         Does the category belong to the INDIVIDUAL_SERVICE group?
        │              │
        │              ├── No → 400 Bad Request
        │              ▼
        │         Create booking, status PENDING_ASSIGNMENT
        │
        ├── No (FULL_HOME_PROJECT) → Does the category (if any) belong to the HOME_PROJECT group?
        │              │
        │              ├── No → 400 Bad Request
        │              ▼
        │         Is budgetMin > budgetMax?
        │              │
        │              ├── Yes → 400 Bad Request
        │              ▼
        │         Create booking, status PENDING_ASSIGNMENT
        │
        ▼
201 Created


Booking action requested (view / cancel / update status)
        │
        ▼
Load booking by id
        │
        ├── Not found
        │      │
        │      ▼
        │   404 Not Found
        │
        ▼
Is the requester an allowed participant?
 • view      → must be the customer OR the assigned professional (if any)
 • cancel    → must be THE customer on it
 • status    → must be THE assigned professional (booking must have one)
        │
        ├── No
        │      │
        │      ▼
        │   403 Forbidden
        │
        ▼
(For cancel/status) Is this transition allowed from the current status?
  PENDING_ASSIGNMENT → nothing directly (must be assigned first)
  PENDING            → CONFIRMED or CANCELLED
  CONFIRMED          → COMPLETED or CANCELLED
  CANCELLED / COMPLETED → nothing (final)
        │
        ├── Not allowed
        │      │
        │      ▼
        │   409 Conflict
        │
        ▼
Apply the change
        │
        ▼
200 OK
```

### 27.8 Professional Assignment & Matching → [§17](#17-feature-walkthrough-professional-assignment--matching)

Scoring is a recommendation, never an automatic assignment — an admin still decides.
Unavailable professionals are excluded before scoring, and the same 100-point formula
applies to every trade.

```
Admin opens the work queue
 (GET /api/bookings/awaiting-assignment)
        │
        ▼
List every booking still in PENDING_ASSIGNMENT, oldest first
 (paginated — not scoped to the admin, who is a participant in none of them)
        │
        ▼
Admin picks one booking from the queue
        │
        ▼
Admin requests rankings for that booking
        │
        ▼
Is this booking currently PENDING_ASSIGNMENT?
        │
        ├── No
        │      │
        │      ▼
        │   409 Conflict
        │
        ▼
Load every professional currently marked AVAILABLE
        │
        ▼
Score each one against the booking's stated preferences:
  • specialization / category / style match           (30)
  • their own portfolio has relevant category work      (10)
  • their own portfolio has relevant style work          (10, interior-design items only)
  • years of experience (capped)                        (15)
  • project location matches their city                 (15)
  • aggregate rating (unrated professionals neutral)     (15)
  • budget realistically fits their typical pricing       (5, interior-design portfolio only)
        │
        ▼
Sort highest score first
 (tie → prefer whoever has fewer active bookings right now)
        │
        ▼
Return ranked list to the admin — nothing assigned yet
        │
        ▼
Admin picks one candidate
        │
        ▼
Assign that professional to the booking
 (booking rejoins the normal lifecycle — same as a direct pick)
        │
        ▼
200 OK
```

### 27.9 Quotation → [§18](#18-feature-walkthrough-quotations-post-consultation-estimate)

A booking never carries a price; the quotation does. The total is always recomputed from
the line items, never accepted from the client, and a sent quotation is frozen — there's
no unsend or revise step in this version.

```
Professional builds a quotation for a booking
 (description + amount per line item, optionally quantity/unit/unit price too)
        │
        ▼
Is the requester the professional assigned to this booking?
        │
        ├── No
        │      │
        │      ▼
        │   403 Forbidden
        │
        ▼
Has this quotation already been sent, accepted, or rejected?
        │
        ├── Yes
        │      │
        │      ▼
        │   409 Conflict (can no longer be edited)
        │
        ▼
Replace the entire line-item list with what was just submitted
        │
        ▼
Recalculate the total from the line items (never trusted from the client)
        │
        ▼
200 OK — saved as DRAFT


Professional sends the quotation to the customer
        │
        ▼
Is it currently a draft?
        │
        ├── No
        │      │
        │      ▼
        │   409 Conflict
        │
        ▼
Does it have at least one line item?
        │
        ├── No
        │      │
        │      ▼
        │   400 Bad Request
        │
        ▼
Mark as SENT
        │
        ▼
200 OK


Customer accepts or rejects the quotation
        │
        ▼
Is the requester the customer on this booking?
        │
        ├── No
        │      │
        │      ▼
        │   403 Forbidden
        │
        ▼
Is the quotation currently SENT?
        │
        ├── No
        │      │
        │      ▼
        │   409 Conflict
        │
        ▼
Mark as ACCEPTED or REJECTED (final either way)
        │
        ▼
200 OK
```

### 27.10 Review & Rating → [§19](#19-feature-walkthrough-reviews--ratings)

One review per booking, ever, and only after completion. Saving one immediately
recomputes the professional's aggregate from *all* their reviews — the same number the
public profile shows and the matching formula scores against.

```
Customer submits a review for a booking
 (1-5 rating, optional comment)
        │
        ▼
Is the requester the customer who made this booking?
        │
        ├── No
        │      │
        │      ▼
        │   403 Forbidden
        │
        ▼
Is the booking's status COMPLETED?
        │
        ├── No
        │      │
        │      ▼
        │   409 Conflict
        │
        ▼
Has this booking already been reviewed?
        │
        ├── Yes
        │      │
        │      ▼
        │   409 Conflict
        │
        ▼
Save the review
        │
        ▼
Reload every review this professional has ever received
        │
        ▼
Recompute their average rating and review count
        │
        ▼
Save the updated aggregate onto the professional's profile
 (this is what the public profile shows, and what
  professional-matching scores against going forward)
        │
        ▼
201 Created


Anyone reads a professional's reviews
 (GET /api/professionals/{id}/reviews — public, no token needed)
        │
        ▼
Look up user by id
        │
        ├── Not found, or not actually a professional account
        │      │
        │      ▼
        │   404 Not Found
        │
        ▼
Load their reviews, newest first, one page at a time
        │
        ├── None yet → empty page (NOT a 404 — an unreviewed professional
        │              still exists and is still bookable)
        │
        ▼
Map each to a public card
 (rating, comment, reviewer name, date — never the booking id)
        │
        ▼
200 OK
```

### 27.11 Profile → [§20](#20-feature-walkthrough-user-profile-role-conditional-data)

Always the caller's own profile — identity comes from the token, never from input.
Professional-only fields sent by a customer are silently ignored, and rating/review count
are read-only here; only the review flow ever changes them.

```
GET /api/users/profile
        │
        ▼
Load current user (identity taken from the token, never from input)
        │
        ▼
Is role == PROFESSIONAL?
        │
        ├── Yes → also load professional profile record
        │
        ▼
Build response
 (professionalProfile section included only for professionals)
        │
        ▼
200 OK


PUT /api/users/profile (only the fields the client wants to change)
        │
        ▼
Apply basic fields if present (fullName, phone)
        │
        ▼
Is role == PROFESSIONAL?
        │
        ├── No  → any professional-specific fields sent are silently ignored
        │
        ├── Yes → load professional profile, or create one if it doesn't exist yet
        │             │
        │             ▼
        │        Apply any supplied professional fields
        │        (bio, yearsExperience, specialization, city, availabilityStatus)
        │
        ▼
Save changes
        │
        ▼
200 OK + updated profile
```

### 27.12 Validation → [§21](#21-validation-flow)

Every failing field is reported at once, not just the first, so a client can fix
everything in one pass. This layer only inspects the shape of the data — it knows nothing
about what's in the database.

```
Request body received
        │
        ▼
Run field-level checks
  • required / non-empty
  • correct format (e.g. email)
  • length within allowed range
  • value within allowed range
  • pattern match (e.g. password complexity)
        │
        ├── One or more checks fail
        │         │
        │         ▼
        │   Collect EVERY failing field (not just the first)
        │         │
        │         ▼
        │   400 Bad Request + full list of field errors
        │
        ▼
All checks pass
        │
        ▼
Continue to business logic
 (data-dependent rules — uniqueness, ownership, existence, service-group match —
  are checked there, not at this stage)
```

### 27.13 Error Handling → [§22](#22-exception-handling-flow)

One error shape for the entire API. Failures caught in the security filter chain and
failures raised deep in business logic are deliberately indistinguishable from the
outside.

```
Something goes wrong, anywhere in the system
        │
        ▼
Caught by one central error handler
        │
        ▼
Match against known situations
        │
        ├── Record doesn't exist          → 404 Not Found
        ├── Duplicate data (e.g. email)   → 409 Conflict
        ├── Not your resource             → 403 Forbidden
        ├── Illegal state change          → 409 Conflict
        ├── Wrong login credentials       → 401 Unauthorized
        ├── Missing/invalid token         → 401 Unauthorized
        ├── Logged in, wrong role         → 403 Forbidden
        ├── Malformed input               → 400 Bad Request + field list
        ├── Anything unmapped/unexpected  → 500 Internal Error (generic message)
        │
        ▼
Build the one standard error shape
 (timestamp, status, error label, message, path, fieldErrors)
        │
        ▼
Send to client
 (looks identical whether caught at the security checkpoint
  or deep inside business logic)
```

### 27.14 Documentation → [§23](#23-api-documentation-swaggeropenapi)

Generated from the real code, so it can't drift from what the API actually does. The docs
pages are public; calling protected endpoints through them still needs a real token.

```
Developer opens the API documentation page
        │
        ▼
Browse the auto-generated list of endpoints
 (input shape, response shape, error cases — for each one)
        │
        ▼
Log in through the documentation page itself
        │
        ▼
Copy the returned access token
        │
        ▼
Paste it into the page's "Authorize" step
        │
        ▼
Every subsequent "try it out" call
 automatically includes that token
        │
        ▼
Protected endpoints can be tested directly from the browser
```

### 27.15 Environment & Configuration → [§24](#24-environment--secrets-management)

Nothing sensitive is ever written into the codebase — it all arrives from outside, so the
same build runs anywhere. The `local` profile layers on dev-only conveniences and never
affects other environments.

```
Application needs a configuration value
        │
        ▼
Look for a matching environment variable
        │
        ├── Set (via real env var, or a local .env file)
        │         │
        │         ▼
        │   Use that value
        │
        ├── Not set
        │         │
        │         ▼
        │   Fall back to a built-in default, if one exists
        │
        ▼
Is the "local" development profile active?
        │
        ├── Yes
        │      │
        │      ▼
        │   Layer on dev-only defaults (e.g. placeholder JWT secret)
        │   and much more detailed / verbose logging
        │
        ▼
Final configuration is ready before startup continues
```

---

*This document reflects the implementation through the Design→PortfolioItem
generalization phase (`V8`). As new features are added (a portfolio-item write path,
project execution, milestones, payments, etc.), extend this file section by section
rather than starting a new one, so it stays the single source of truth for onboarding
and self-review.*
