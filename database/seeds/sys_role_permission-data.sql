-- sys_role_permission clean seed
-- Scope:
-- - Keep only the reviewed role-permission matrix.
-- - Rebuild by business relation (role_id, perm_id), not by dirty relation-table id.
-- - Merged roles inherit the union of their original permissions:
--   role 4 absorbs former college-leader seat, role 3 absorbs former strategy-final seat.

BEGIN;

INSERT INTO public.sys_role_permission (id, role_id, perm_id, created_at) VALUES
    (DEFAULT, 1, 1, NOW()),
    (DEFAULT, 1, 2, NOW()),
    (DEFAULT, 1, 3, NOW()),
    (DEFAULT, 1, 4, NOW()),
    (DEFAULT, 1, 5, NOW()),
    (DEFAULT, 1, 8, NOW()),
    (DEFAULT, 1, 10, NOW()),

    (DEFAULT, 2, 1, NOW()),
    (DEFAULT, 2, 3, NOW()),
    (DEFAULT, 2, 4, NOW()),
    (DEFAULT, 2, 9, NOW()),
    (DEFAULT, 2, 10, NOW()),
    (DEFAULT, 2, 11, NOW()),
    (DEFAULT, 2, 12, NOW()),

    (DEFAULT, 3, 1, NOW()),
    (DEFAULT, 3, 2, NOW()),
    (DEFAULT, 3, 3, NOW()),
    (DEFAULT, 3, 4, NOW()),
    (DEFAULT, 3, 6, NOW()),
    (DEFAULT, 3, 7, NOW()),
    (DEFAULT, 3, 9, NOW()),
    (DEFAULT, 3, 11, NOW()),
    (DEFAULT, 3, 12, NOW()),

    (DEFAULT, 4, 1, NOW()),
    (DEFAULT, 4, 2, NOW()),
    (DEFAULT, 4, 3, NOW()),
    (DEFAULT, 4, 4, NOW()),
    (DEFAULT, 4, 6, NOW()),
    (DEFAULT, 4, 7, NOW()),
    (DEFAULT, 4, 9, NOW()),
    (DEFAULT, 4, 11, NOW()),
    (DEFAULT, 4, 12, NOW()),

    (DEFAULT, 5, 1, NOW()),
    (DEFAULT, 5, 2, NOW()),
    (DEFAULT, 5, 3, NOW()),
    (DEFAULT, 5, 4, NOW()),
    (DEFAULT, 5, 5, NOW()),
    (DEFAULT, 5, 6, NOW()),
    (DEFAULT, 5, 7, NOW()),
    (DEFAULT, 5, 8, NOW()),
    (DEFAULT, 5, 9, NOW()),
    (DEFAULT, 5, 10, NOW()),
    (DEFAULT, 5, 11, NOW()),
    (DEFAULT, 5, 12, NOW());

DELETE FROM public.sys_role_permission
WHERE role_id NOT IN (1, 2, 3, 4, 5);

COMMIT;
