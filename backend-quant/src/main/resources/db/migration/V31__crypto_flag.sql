-- F-TRD-12 (D-035): read-only crypto prices from a free public feed, polled by the API only while this flag is on.
INSERT INTO feature_flags (name, enabled) VALUES ('crypto', FALSE) ON CONFLICT (name) DO NOTHING;
