# Data Setup Guide

What real data this app needs before it's usable for real customers/professionals,
who provides it, and exactly how to add it. Nothing here is a code change — it's all
either an `.env` value or an API call against the running backend.

---

## 1. Security placeholders — fix before any real traffic

These aren't "data" in the business sense, but they're currently dev/demo values and
**must** change before real users touch the app.

| What | Currently | Why it matters | How to fix |
|---|---|---|---|
| `JWT_SECRET` (`.env`) | `dev-only-local-secret-key-change-me-please-32bytesmin` | Signs every access token. A known/weak secret lets anyone forge a valid login. | Generate a real one: `openssl rand -base64 48`. Restart the app after changing it (invalidates all existing sessions). |
| Admin account | `admin@velora.com` / `Admin@123`, seeded in `V4__admin_and_designer_assignment.sql` | Password is sitting in plaintext in a checked-in migration file. | Log in once, immediately `POST /api/auth/password/change` to a real password. Self-registration as `ADMIN` is blocked, so this seeded account (with its password changed) stays the only way in. |
| Demo professional | `designer@velora.com` / `Designer@123`, seeded in `V2__seed_data.sql` | Same exposure as the admin password. | Either change its password and repurpose it as a real professional, or leave it unused once real professionals are onboarded (don't delete it if any booking/portfolio item still references it — deletion is blocked anyway while referenced). |
| `CORS_ALLOWED_ORIGINS` (`.env`) | `localhost` dev ports only | Real frontend domain will be blocked by CORS otherwise. | Add your deployed frontend origin(s), comma-separated. |

---

## 2. Real business data — what, for whom, how

### 2.1 Categories
**For:** nobody adds these at runtime — they're fixed reference data.
**What:** the 13 seeded categories (7 home-project room types + 6 individual-service
trades) in `V2__seed_data.sql` / `V7__rename_designer_to_professional_and_add_request_types.sql`.
**How to change:** there's no create/update endpoint (`GET /api/categories` is
read-only, by design). If the real category list differs, that's a new Flyway
migration (`V19__...sql`, additive `INSERT`), not something to add through the API.

### 2.2 Professional accounts
**For:** every real professional (interior designer, painter, plumber, electrician,
carpenter, false-ceiling/modular-kitchen installer) who will take bookings.
**How:** each professional registers themselves — there's no bulk-import or
admin-creates-professional path today.

1. `POST /api/auth/register`
   ```json
   { "email": "pro@example.com", "password": "...", "fullName": "...", "phone": "...", "role": "PROFESSIONAL" }
   ```
2. They check their email for the OTP and `POST /api/auth/verify-email` with it — this activates the account and returns their first access/refresh token pair.
3. That's it for account creation. A blank `ProfessionalProfile` was auto-created alongside the account (see 2.3).

### 2.3 Professional profile details
**For:** the professional themselves, self-service, once logged in.
**How:** `PUT /api/users/profile` (same endpoint used by customers to edit their own name/phone/avatar — only the professional-specific fields below take effect for a `PROFESSIONAL` account):
```json
{
  "bio": "12 years designing modern Indian homes",
  "yearsExperience": 12,
  "specialization": "Modern Minimalist",
  "city": "Mumbai",
  "availabilityStatus": "AVAILABLE"
}
```
`availabilityStatus` is the on/off flag that determines whether they show up in
customer search and admin-assignment recommendations at all (`AVAILABLE` /
`UNAVAILABLE`) — this has to be set for a professional to ever get matched.

### 2.4 Portfolio items (work samples)
**For:** the professional, self-service, per item.
**What:** at least a few real work samples per professional — these are what
customers browse on Explore and what `ProfessionalMatchingService` uses as
"portfolio evidence" when scoring a match for admin assignment.

1. Upload a real cover image first (JPEG/PNG/WebP): `POST /api/media/upload`
   (multipart form field `file`, optional `folder`) → returns a Cloudinary URL.
2. Create the item: `POST /api/portfolio`
   ```json
   {
     "title": "Scandinavian Master Bedroom",
     "description": "...",
     "categoryId": 2,
     "coverImageUrl": "<url from step 1>",
     "styleTag": "scandinavian",
     "priceEstimate": 65000.00
   }
   ```
   `styleTag`/`priceEstimate` are interior-design-only — a painter/plumber/electrician/
   carpenter omits both and gets a plain portfolio item with no pricing satellite.
   `categoryId` must reference a real category id (`GET /api/categories`).

### 2.5 Google OAuth consent screen
**For:** whoever owns the Google Cloud project (likely you/admin, not a per-user
action).
**What:** the OAuth consent screen is currently in **Testing** mode — only accounts
explicitly added as test users in Google Cloud Console can complete "Continue with
Google" at all; everyone else gets `access_blocked`.
**How:** Google Cloud Console → APIs & Services → OAuth consent screen → either add
more test users (cap 100), or click **Publish App** to open it to any Google account
(no Google review needed for the basic email/profile scopes this app uses).

---

## 3. Already configured — verify, don't recreate

These `.env` values already look like real credentials (not placeholders) as of this
session — just confirm they're *your* production accounts, not a shared dev one:

- `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` — Neon Postgres, already a real hosted instance.
- `BREVO_EMAIL` / `BREVO_SMTP_KEY` / `BREVO_FROM_EMAIL` — sends OTP/2FA and password-reset emails.
- `CLOUDINARY_URL` — backs both `/api/media/upload` and portfolio cover images.
- `GOOGLE_CLIENT_ID` — Google Sign-In, added this session.

---

## 4. What needs no manual seeding at all

Bookings, quotations, reviews, notifications, favorites — these all accumulate
naturally as real customers and professionals use the app. Nothing to pre-populate.
