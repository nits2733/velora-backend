-- Cloudinary-backed category images, admin-managed via PATCH /api/categories/{id}/image.
-- Nullable: categories without a set image fall back to a client-side placeholder.

ALTER TABLE categories
    ADD COLUMN image_url VARCHAR(1000);
