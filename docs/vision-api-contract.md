# Vision Studio API contract

Asynchronous AI room redesign. The enum values below mirror the frontend's picker constants and must stay in sync with them (adding or renaming a value is a change on both sides).

## Endpoint and auth

- `POST /api/vision/transform` - `multipart/form-data`, role `CUSTOMER` only (`Authorization: Bearer <access token>`).
- `GET /api/vision/transform/{id}` - poll a job; owner only (`CUSTOMER`).

## Request fields (all flat multipart fields)

| Field | Type | Notes |
|---|---|---|
| `image` | file | Required. Real JPEG or PNG bytes (declared type may be jpeg/png/webp but the bytes decide), at most 10 MB; see Image preprocessing |
| `roomType` | enum | `LIVING_ROOM, BEDROOM, KITCHEN, BATHROOM, DINING_ROOM, HOME_OFFICE, OTHER` |
| `style` | enum | `MODERN_LUXURY, MINIMALIST, SCANDINAVIAN, CONTEMPORARY, TRADITIONAL_INDIAN, JAPANDI, INDUSTRIAL, BOHEMIAN, CLASSIC_LUXURY` |
| `colorPalette` | enum | `BEIGE_WALNUT, WHITE_OAK, SAGE_CREAM, GREY_WHITE, CHARCOAL_GOLD, EARTHY_NEUTRALS, CUSTOM` |
| `customColorDescription` | string, max 60 | Required when `colorPalette=CUSTOM`; rejected (400) with any other palette or none |
| `budgetTier` | enum | `BUDGET_FRIENDLY, MID_RANGE, PREMIUM_LUXURY` (optional) |
| `features` | enum, repeatable | `SOFA_SEATING, TV_UNIT, WARDROBE_STORAGE, LIGHTING, CURTAINS, FALSE_CEILING, INDOOR_PLANTS, WALL_PANELS, FLOORING, DECORATIVE_ELEMENTS`. Max 10 entries submitted (more than 10 is a 400, even if some repeat); duplicates collapse; omitting the param means an empty list |
| `preservation` | enum | `PRESERVE_ORIGINAL, BALANCED, CREATIVE` |
| `additionalInstructions` | string, max 300 | Free text; treated as data, always applied last |
| `stylePrompt` | string, max 300 | Legacy free-text mode (see below) |

Enum values are case-sensitive upper snake case. An unknown value is a 400.

## Modes and required fields

- Structured mode: used when ANY structured field (`roomType`, `style`, `colorPalette`, `customColorDescription`, `budgetTier`, `features`, `preservation`, `additionalInstructions`) is present and non-blank. Then `roomType`, `style` and `preservation` are required. `colorPalette` and `budgetTier` are optional.
- Legacy mode: no structured field present. `stylePrompt` is required and non-blank (<= 300). The request/response shapes and generation behaviour match the original API, with two additions that also apply to legacy requests: the duplicate guard and the stored request fingerprint (see Duplicate guard), and control/invisible characters being stripped from `stylePrompt`.
- In structured mode `stylePrompt` is optional; if supplied it is only stored as the job's label and does not affect the generated prompt.
- `preservation` controls how far the AI may deviate from the photo: `PRESERVE_ORIGINAL` stays closest, `CREATIVE` changes the most. The underlying image-strength values are placeholders that have not yet been calibrated against real photos, so the visible difference between levels may be tuned later without an API change.

## Responses

Submit: `202 Accepted`
```json
{ "id": "11111111-2222-3333-4444-555555555555", "status": "PROCESSING" }
```

Poll: `200`
```json
{ "id": "...", "status": "PROCESSING" }
{ "id": "...", "status": "COMPLETED", "resultImageUrl": "https://..." }
{ "id": "...", "status": "FAILED", "error": "We couldn't generate your design right now. Please try again." }
```
Null fields are omitted. Statuses: `PROCESSING`, `COMPLETED`, `FAILED`. An unknown or another user's id returns 404.

## Errors

All errors use the standard API error body (`status`, `error`, `message`, ...).

| Status | When |
|---|---|
| 400 | Validation: missing/blank required field, unknown enum value, over-length text, more than 10 `features` entries, custom colour rules, bad image type/size |
| 401 | Missing/invalid/expired access token |
| 403 | Authenticated but not a `CUSTOMER` |
| 404 | Poll for an unknown or foreign job id |
| 429 | Daily limit (5 per rolling 24h; FAILED jobs do not count) or 2 jobs already in flight |
| 503 | Generation queue is full (job marked FAILED), or too many images are being preprocessed at once (no job created); retry later |

## Duplicate guard

The guard applies to structured AND legacy requests, and every new job stores a `request_fingerprint` (SHA-256 over user, image bytes, canonical preferences or the literal `legacy`, and the trimmed `stylePrompt`).

An identical request repeated within 60 seconds does not create a new job: the response is `202` with the existing PROCESSING or COMPLETED job's `id` and its current `status`, and it consumes no daily quota. Notes for clients:

- The 202 body never carries `resultImageUrl`, even when the existing job is already COMPLETED. Always poll `GET /api/vision/transform/{id}` to get the result.
- A deliberate regeneration with identical inputs (same image, same preferences, same prompt) is blocked for 60 seconds only if the earlier job is PROCESSING or COMPLETED. FAILED jobs never block, so retrying a failure works immediately. Changing the image, any preference or the prompt creates a new job.
- The guard is DB-backed with an in-memory per-user lock. The lookup runs before the upload and again inside the lock right before the job is saved, so concurrent identical requests on one instance cannot create two jobs (a duplicate request may still leave one orphaned uploaded input image). Only a multi-instance deployment can still double-pass; closing that would need a DB advisory lock.

Order of checks on submit: text/preference validation, `mediaUploadService.validate(image)` (empty/declared type/size), read bytes and compute the fingerprint (original bytes; it includes the sanitised prompt and a SHA-256 of the image), duplicate lookup, daily limit (429), in-flight cap of 2 (429), preprocessing (400 on too small/too large/unreadable/unsupported, 503 when overloaded), upload of the preprocessed image, then inside the lock duplicate re-check, daily limit and in-flight cap again, save, and hand off to the processor.

## Privacy

`preferences` (JSON snapshot) and `built_prompt` store the customer's free text (`customColorDescription`, `additionalInstructions`, `stylePrompt`) indefinitely, and the built prompt is sent to Stability AI. `VisionJobResponse` never exposes them.

## Loading expectations

Typical generation takes about 10-20 seconds. Poll `GET /api/vision/transform/{id}` every 2-3 seconds and give up on the client side after about 2 minutes (the server fails jobs stuck for 20 minutes).

## Limits

- 5 successful or in-progress transformations per user per rolling 24 hours.
- At most 2 jobs in flight per user.
- Image: real JPEG or PNG, at most 10 MB, shorter side at least 512 px, at most 40 million pixels.

## Image preprocessing

Applies to EVERY request, structured and legacy (legacy `stylePrompt` requests are preprocessed and seeded too). The format is decided from the file's bytes, never from the declared `Content-Type`.

- Accepted: real JPEG and real PNG bytes only, whatever the declared type (`image/jpeg`, `image/jpg`, `image/png`, even `image/webp`). Anything else, including real WebP, is a 400 "Only JPEG and PNG photos are supported." (Java cannot decode WebP without a plugin, and passing it through would bypass the limits and keep GPS metadata.) This is a change for legacy clients that previously sent WebP.
- Limits (configurable): the shorter side must be at least `vision.min-short-side` (default 512 px) and the image at most `vision.max-input-pixels` (default 40 million pixels, decompression-bomb guard, read from the header before decoding). The messages use the configured values. A minimum short side is new for legacy requests: small images that used to be accepted now get a 400.
- Large photos are decoded with source subsampling when above about 4 MP (so the full raster is not held; images between 1 and 4 MP are fully decoded, about 35-40 MB peak per permit, which is why concurrency is capped), then downscaled with high-quality scaling to about `vision.target-pixels` (default 1,000,000, about 1 MP); images already below that are never upscaled.
- JPEG EXIF orientation (1-8) is applied, transparency is flattened onto white, and the result is re-encoded as a fresh JPEG (quality `vision.jpeg-quality`, default 0.92). All metadata is dropped: EXIF, GPS, comments and ICC colour profiles (wide-gamut photos may shift slightly). Only the cleaned image is uploaded to Cloudinary or sent to the provider.
- Corrupt or truncated images (including a JPEG cut off mid-scan) are rejected with 400 "The image could not be read.". CMYK/YCCK JPEGs that the JDK cannot decode are rejected the same way, so CMYK is unsupported.
- At most `vision.preprocess-concurrency` (default 2) images are preprocessed at once. A request that cannot get a slot within `vision.preprocess-wait` (default 5 s) gets 503 "The design service is busy right now. Please try again in a few minutes."; retry later. Out-of-memory while decoding is reported as 400 "Image is too large.".
- Deployment note: for the small (512 MB) instance a JVM setting such as `-XX:MaxRAMPercentage=70` is recommended.
- Clients should tell users to photograph an empty room without people in it, from a normal standing viewpoint.

## User-facing 400 messages

| Cause | Message |
|---|---|
| Shorter side below minimum | "Image is too small - the shorter side must be at least 512 pixels." (uses the configured minimum) |
| Over the pixel limit or out of memory | "Image is too large." |
| Corrupt, truncated or undecodable (incl. CMYK) | "The image could not be read." |
| Not JPEG/PNG bytes (incl. WebP) | "Only JPEG and PNG photos are supported." |
| Empty file / over 10 MB / declared type outside the generic media allow-list | Generic media validation messages ("Please select a valid non-empty file to upload", "File size ... exceeds maximum permitted limit ...", "Invalid file type ..."). The shared generic message lists WebP because other upload endpoints accept it; Vision does not: a file declared as WebP is accepted only if its bytes are really JPEG/PNG, and real WebP gets "Only JPEG and PNG photos are supported." |
| Missing structured fields | "roomType is required", "style is required", "preservation is required" |
| Neither preferences nor prompt | "stylePrompt is required when no design preferences are supplied" |
| Custom colour rules | "customColorDescription is required when colorPalette is CUSTOM", "customColorDescription is only allowed when colorPalette is CUSTOM" |
| Features | "At most 10 features are allowed", "features must not contain null values" |
| Text length | "stylePrompt must be at most 300 characters", "customColorDescription must be at most 60 characters", "additionalInstructions must be at most 300 characters" |

## Deterministic seed

The provider seed is derived from the request fingerprint (legacy and structured), so identical inputs give the same seed and, where the provider honours it, the same image. Caveat: an identical request made after the 60 s duplicate window returns the same image unless a preference (or the image or prompt) changes. To get a different variation, change any preference.

## Failure messages (job `error`)

| Cause | `error` |
|---|---|
| Provider content moderation / refusal | "We couldn't process this photo. Please try a different image." |
| Provider busy, out of credits, rate limited, 408, 5xx, timeout | "The design service is busy right now. Please try again in a few minutes." |
| Anything else | "We couldn't generate your design right now. Please try again." |

Provider text is never copied into `error`.

## Quality status

Prompt wording and the preservation strengths (0.30 / 0.50 / 0.70) are UNCALIBRATED hypotheses pending evaluation on real room photos. Nothing in the preprocessing, seed or prompt changes claims improved output quality. The negative prompt depends on `preservation`: PRESERVE_ORIGINAL and BALANCED include layout/opening terms, CREATIVE does not.
