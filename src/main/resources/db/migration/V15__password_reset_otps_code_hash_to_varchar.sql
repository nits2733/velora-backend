-- V14 declared code_hash as CHAR(64); the entity maps it as @Column(length = 64), which
-- Hibernate expects to be VARCHAR - the exact same mistake V10 already made and fixed
-- for token_hash. VARCHAR is correct regardless: CHAR blank-pads every value to the full
-- width, and a padded hash would not match the digest computed at lookup time. The table
-- is empty at this point - V14 created it - so this rewrites nothing.

ALTER TABLE password_reset_otps
    ALTER COLUMN code_hash TYPE VARCHAR(64);
