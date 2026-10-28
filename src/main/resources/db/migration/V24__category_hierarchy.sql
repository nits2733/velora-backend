-- Two-level category taxonomy: top-level categories with optional subcategories.
-- Schema only; existing categories stay top-level (parent_id NULL). service_group is
-- stored on children too and kept equal to the parent's by the service layer.
ALTER TABLE categories
    ADD COLUMN parent_id BIGINT NULL REFERENCES categories (id);

ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_name_key;

ALTER TABLE categories
    ADD CONSTRAINT uq_categories_parent_name UNIQUE (parent_id, name);

CREATE UNIQUE INDEX uq_categories_top_level_name ON categories (name) WHERE parent_id IS NULL;

CREATE INDEX idx_categories_parent_id ON categories (parent_id);
