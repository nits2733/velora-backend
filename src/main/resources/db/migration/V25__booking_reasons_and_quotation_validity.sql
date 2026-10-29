-- Optional free-text reasons, and an informational quotation validity date.
--
-- bookings.cancellation_reason: why the customer cancelled. Shown to the customer and admins,
--   never to the assigned professional.
-- quotations.rejection_reason: why the customer declined. Shown to both participants.
-- quotations.valid_until: until when the professional's prices hold. Informational only: nothing
--   blocks accepting after this date, and a sent quotation cannot be edited or re-issued.
--
-- All three are nullable, so this is additive: no existing row or query changes. TIMESTAMP matches
-- the other Instant columns on these tables.

ALTER TABLE bookings
    ADD COLUMN cancellation_reason VARCHAR(300);

ALTER TABLE quotations
    ADD COLUMN rejection_reason VARCHAR(300),
    ADD COLUMN valid_until      TIMESTAMP;
