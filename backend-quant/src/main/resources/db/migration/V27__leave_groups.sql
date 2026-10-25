-- Split groups: a member can leave once they're settled. The row stays (with left_at) so the group's history,
-- expenses and shares still show who paid what.
ALTER TABLE split_members ADD COLUMN left_at TIMESTAMPTZ;
