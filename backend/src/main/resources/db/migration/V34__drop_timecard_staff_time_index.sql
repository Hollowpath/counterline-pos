-- A staff member's current state is decided by their latest event by id (V33), and nothing else looks events up by
-- staff and time: the reports filter by outlet and time (timecard_event_outlet_occurred_idx). The index on
-- (staff_id, occurred_at DESC) served the old lookups by time and is no longer used; (staff_id, id DESC) also serves
-- the foreign key to staff.
DROP INDEX IF EXISTS timecard_event_staff_idx;
