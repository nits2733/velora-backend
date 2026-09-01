-- Adds: booking budget range + preferred timeline, user avatar, booking inspiration
-- images, an append-only booking timeline-event audit trail, an in-app notification
-- inbox, and favorites (saved professionals / saved portfolio items).
--
-- The existing `bookings.budget` column is kept as-is (deprecated, not dropped) so
-- this is a purely additive change - no existing row or query breaks.

ALTER TABLE bookings
    ADD COLUMN budget_min NUMERIC(12, 2),
    ADD COLUMN budget_max NUMERIC(12, 2),
    ADD COLUMN preferred_timeline VARCHAR(30);

ALTER TABLE users
    ADD COLUMN avatar_url VARCHAR(1000);

CREATE TABLE booking_inspiration_images (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  BIGINT NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    image_url   VARCHAR(1000) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_booking_inspiration_images_booking_id ON booking_inspiration_images (booking_id);

-- Insert-only audit trail so the client can render a real status timeline instead
-- of deriving fake steps from a single `status` column.
CREATE TABLE booking_timeline_events (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  BIGINT NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    event_type  VARCHAR(40) NOT NULL,
    from_status VARCHAR(30),
    to_status   VARCHAR(30),
    note        VARCHAR(300),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_booking_timeline_events_booking_id ON booking_timeline_events (booking_id, created_at);

-- In-app notification inbox. `related_booking_id` is deliberately not a foreign key -
-- a notification is a disposable read-model, not a record that should ever block or
-- cascade against booking lifecycle changes.
CREATE TABLE notifications (
    id                  BIGSERIAL PRIMARY KEY,
    recipient_id        BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                VARCHAR(40) NOT NULL,
    title               VARCHAR(150) NOT NULL,
    body                VARCHAR(500) NOT NULL,
    related_booking_id  BIGINT,
    read                BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_recipient_unread ON notifications (recipient_id, read, created_at);

CREATE TABLE saved_professionals (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    professional_id  BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_professionals UNIQUE (customer_id, professional_id)
);

CREATE TABLE saved_portfolio_items (
    id                 BIGSERIAL PRIMARY KEY,
    customer_id        BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    portfolio_item_id  BIGINT NOT NULL REFERENCES portfolio_items (id) ON DELETE CASCADE,
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_portfolio_items UNIQUE (customer_id, portfolio_item_id)
);
