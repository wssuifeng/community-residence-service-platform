-- C9 daily metrics use one row per community, date and metric for atomic upserts.
-- The documented uniqueness rule was absent from the original V1 migration.
ALTER TABLE statistics_snapshot
    ADD UNIQUE KEY uk_community_date_metric (community_id, snapshot_date, metric_type);
