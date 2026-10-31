-- A staff member's current state is their latest event by id (the order events were recorded in), not by the time on
-- the event: one index lookup per person.
CREATE INDEX timecard_event_staff_latest_idx ON timecard_event (staff_id, id DESC);
