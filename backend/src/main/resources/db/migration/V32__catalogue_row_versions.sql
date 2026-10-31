-- Optimistic locking for the catalogue: an edit made from a copy older than the row is refused (409) instead of
-- overwriting a newer change. Existing rows start at version 0 (every row gets the default, so nothing is rewritten
-- and no application has to be stopped to run this).
ALTER TABLE quickgrid_tab    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE category         ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE item             ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE modifier_group   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE modifier_option  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
