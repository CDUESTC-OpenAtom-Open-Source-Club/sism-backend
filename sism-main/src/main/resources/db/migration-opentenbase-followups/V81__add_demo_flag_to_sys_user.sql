-- V81: Add is_demo flag for demo/test accounts
-- Demo accounts bypass workflow approver scope checks for full-flow testing.
-- Default is false so existing accounts are unaffected.

ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN sys_user.is_demo IS '演示账号标记。设为 true 时，审批范围检查（ApproverResolver.matchesRoleScope）将被跳过，允许单账号走完所有审批节点。仅用于内部测试和演示。';
