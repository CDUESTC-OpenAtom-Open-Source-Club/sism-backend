-- Keep the indicator sequence aligned with historical/manual inserts.
-- Without this, Hibernate may request an ID that already exists and fail
-- with duplicate key violations on indicator_pkey.
SELECT setval(
  'public.indicator_id_seq',
  COALESCE((SELECT MAX(id) FROM public.indicator), 1),
  true
);
