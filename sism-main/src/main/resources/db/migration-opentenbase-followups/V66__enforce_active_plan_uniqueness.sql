DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT cycle_id, plan_level, created_by_org_id, target_org_id
        FROM public.plan
        WHERE COALESCE(is_deleted, false) = false
        GROUP BY cycle_id, plan_level, created_by_org_id, target_org_id
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION
            'Cannot enforce active plan uniqueness: found % duplicate active plan business keys. Clean the data before applying V66.',
            duplicate_count;
    END IF;
END $$;

-- OpenTenBase follow-up:
-- this business key remains audited here, but the distributed unique index is
-- downgraded to a plain index because the current OTB baseline does not yet
-- model plan with a distribution strategy that can satisfy this uniqueness rule.
CREATE INDEX IF NOT EXISTS ix_plan_active_business_key_otb
ON public.plan (cycle_id, plan_level, created_by_org_id, target_org_id);
