# Flyway 数据库迁移指南

> **版本**: 1.0  
> **最后更新**: 2026-02-14  
> **状态**: 生产就绪

---

## 目录

1. [概述](#概述)
2. [快速开始](#快速开始)
3. [迁移文件命名规范](#迁移文件命名规范)
4. [常用命令](#常用命令)
5. [编写迁移脚本](#编写迁移脚本)
6. [最佳实践](#最佳实践)
7. [故障排除](#故障排除)
8. [CI/CD 集成](#cicd-集成)

---

## 概述

### 什么是 Flyway？

Flyway 是一个开源的数据库迁移工具，用于版本控制和管理数据库 schema 变更。它确保数据库结构的一致性和可追溯性。

### 为什么使用 Flyway？

- ✅ **版本控制**: 所有数据库变更都有版本记录
- ✅ **可重复性**: 迁移脚本可在任何环境重复执行
- ✅ **团队协作**: 避免手动 SQL 执行导致的不一致
- ✅ **回滚支持**: 可追踪和管理数据库状态
- ✅ **CI/CD 集成**: 自动化数据库部署流程

### 项目配置

**配置文件**: `src/main/resources/application.yml`

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1.0
    locations: classpath:db/migration
    validate-on-migrate: true
```

**Maven 插件**: `pom.xml`

```xml
<plugin>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-maven-plugin</artifactId>
    <version>10.4.1</version>
    <configuration>
        <url>${env.DB_URL}</url>
        <user>${env.DB_USERNAME}</user>
        <password>${env.DB_PASSWORD}</password>
        <locations>
            <location>filesystem:src/main/resources/db/migration</location>
        </locations>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.1</version>
        </dependency>
    </dependencies>
</plugin>
```

---

## 快速开始

### 1. 环境变量配置

创建 `.env` 文件（从 `.env.example` 复制）：

```bash
DB_URL=jdbc:postgresql://localhost:5432/strategic
DB_USERNAME=postgres
DB_PASSWORD=your_password
```

### 2. 检查迁移状态

```bash
./mvnw flyway:info
```

**输出示例**:
```
+-----------+---------+---------------------+------+---------------------+---------+
| Category  | Version | Description         | Type | Installed On        | State   |
+-----------+---------+---------------------+------+---------------------+---------+
| Versioned | 1.0     | Flyway Baseline     | SQL  | 2026-02-13 17:40:38 | Success |
| Versioned | 2       | add audit flow      | SQL  | 2026-02-13 22:18:16 | Success |
| Versioned | 3       | add warn level      | SQL  | 2026-02-13 22:18:18 | Success |
+-----------+---------+---------------------+------+---------------------+---------+
```

### 3. 验证迁移脚本

```bash
./mvnw flyway:validate
```

### 4. 应用迁移

```bash
./mvnw flyway:migrate
```

---

## 迁移文件命名规范

### 文件位置

```
src/main/resources/db/migration/
├── V1__baseline_schema.sql
├── V2__add_audit_flow_entities.sql
├── V3__add_warn_level_entity.sql
└── V4__your_new_migration.sql
```

### 命名格式

```
V{版本号}__{描述}.sql
```

**规则**:
- `V` - 固定前缀（大写）
- `{版本号}` - 递增的版本号（如 1, 2, 3 或 1.1, 1.2）
- `__` - 双下划线分隔符
- `{描述}` - 简短的英文描述，使用下划线连接单词
- `.sql` - 文件扩展名

**示例**:
- ✅ `V1__baseline_schema.sql`
- ✅ `V2__add_audit_flow_entities.sql`
- ✅ `V3.1__add_user_email_column.sql`
- ❌ `v1_baseline.sql` (小写 v)
- ❌ `V1_baseline.sql` (单下划线)
- ❌ `V1__baseline schema.sql` (空格)

---

## 常用命令

### Maven 命令

| 命令 | 说明 |
|------|------|
| `./mvnw flyway:info` | 查看迁移状态 |
| `./mvnw flyway:validate` | 验证迁移脚本 |
| `./mvnw flyway:migrate` | 应用迁移 |
| `./mvnw flyway:clean` | 清空数据库（⚠️ 危险操作） |
| `./mvnw flyway:baseline` | 设置基线版本 |
| `./mvnw flyway:repair` | 修复迁移历史表 |

### 环境变量传递

```bash
# 使用环境变量
export DB_URL=jdbc:postgresql://localhost:5432/strategic
export DB_USERNAME=postgres
export DB_PASSWORD=your_password
./mvnw flyway:migrate

# 或者直接传递参数
./mvnw flyway:migrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/strategic \
  -Dflyway.user=postgres \
  -Dflyway.password=your_password
```

---

## 编写迁移脚本

### 基本结构

```sql
-- V4__add_user_profile_table.sql
-- Description: Add user profile table for extended user information
-- Author: Your Name
-- Date: 2026-02-14

-- Create table
CREATE TABLE IF NOT EXISTS user_profile (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    avatar_url VARCHAR(500),
    bio TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_profile_user_id UNIQUE (user_id)
);

-- Create index
CREATE INDEX idx_user_profile_user_id ON user_profile(user_id);

-- Add comment
COMMENT ON TABLE user_profile IS 'User profile information';
COMMENT ON COLUMN user_profile.avatar_url IS 'User avatar URL';
```

### 幂等性迁移（推荐）

使用 PostgreSQL `DO` 块实现幂等性：

```sql
-- V5__add_email_column_to_user.sql

DO $$
BEGIN
    -- Check if column exists
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'sys_user' AND column_name = 'email'
    ) THEN
        -- Add column if not exists
        ALTER TABLE sys_user ADD COLUMN email VARCHAR(255);
        
        -- Add unique constraint
        ALTER TABLE sys_user ADD CONSTRAINT uk_sys_user_email UNIQUE (email);
        
        -- Add comment
        COMMENT ON COLUMN sys_user.email IS 'User email address';
        
        RAISE NOTICE 'Column email added to sys_user table';
    ELSE
        RAISE NOTICE 'Column email already exists in sys_user table';
    END IF;
END $$;
```

### 数据迁移示例

```sql
-- V6__migrate_old_status_values.sql

DO $$
BEGIN
    -- Update old status values to new format
    UPDATE indicator 
    SET status = 'ACTIVE' 
    WHERE status = 'active';
    
    UPDATE indicator 
    SET status = 'INACTIVE' 
    WHERE status = 'inactive';
    
    RAISE NOTICE 'Status values migrated successfully';
END $$;
```

---

## 最佳实践

### 1. 迁移脚本原则

✅ **DO**:
- 每个迁移文件只做一件事
- 使用幂等性脚本（可重复执行）
- 添加详细的注释和说明
- 在开发环境充分测试
- 使用事务（PostgreSQL 默认支持）
- 备份数据库后再执行

❌ **DON'T**:
- 不要修改已应用的迁移文件
- 不要在生产环境直接测试
- 不要使用 `DROP TABLE` 除非确定
- 不要在迁移中使用硬编码的数据
- 不要跳过版本号

### 2. 版本号管理

```
V1__baseline_schema.sql          # 基线 schema
V2__add_audit_flow_entities.sql  # 新功能
V3__add_warn_level_entity.sql    # 新功能
V4__fix_indicator_constraints.sql # 修复
V5__add_user_email.sql           # 增强
```

**版本号策略**:
- 主版本号：重大变更（V1, V2, V3）
- 子版本号：小改动（V3.1, V3.2）
- 使用递增的整数最简单

### 3. 测试流程

```bash
# 1. 在开发环境测试
./mvnw flyway:migrate

# 2. 验证迁移
./mvnw flyway:validate

# 3. 检查应用启动
./mvnw spring-boot:run

# 4. 运行测试
./mvnw test

# 5. 提交代码
git add src/main/resources/db/migration/V*.sql
git commit -m "feat: add new migration V4"
```

### 4. 回滚策略

Flyway 不直接支持回滚，但可以：

**方案 A: 创建反向迁移**
```sql
-- V7__add_column.sql
ALTER TABLE users ADD COLUMN phone VARCHAR(20);

-- V8__remove_column.sql (回滚 V7)
ALTER TABLE users DROP COLUMN phone;
```

**方案 B: 使用数据库备份**
```bash
# 备份
pg_dump -U postgres strategic > backup_before_v7.sql

# 恢复
psql -U postgres strategic < backup_before_v7.sql
```

---

## 故障排除

### 问题 1: 迁移失败

**错误信息**:
```
Migration V4__add_column.sql failed
SQL State  : 42P01
Error Code : 0
Message    : ERROR: relation "users" does not exist
```

**解决方案**:
1. 检查表名是否正确
2. 确认依赖的表已创建
3. 使用 `./mvnw flyway:repair` 修复历史表
4. 修正脚本后重新执行

### 问题 2: Checksum 不匹配

**错误信息**:
```
Migration checksum mismatch for migration version 2
```

**原因**: 已应用的迁移文件被修改

**解决方案**:
```bash
# 方案 1: 修复历史表（如果确定修改是正确的）
./mvnw flyway:repair

# 方案 2: 恢复原始文件
git checkout V2__add_audit_flow_entities.sql

# 方案 3: 创建新的迁移文件
# 不要修改已应用的文件，创建新的 V5__fix_previous_migration.sql
```

### 问题 3: 连接数据库失败

**错误信息**:
```
Unable to obtain connection from database
```

**检查清单**:
- [ ] 数据库服务是否运行
- [ ] `.env` 文件是否存在
- [ ] 数据库 URL、用户名、密码是否正确
- [ ] 防火墙是否允许连接
- [ ] PostgreSQL 是否允许远程连接

### 问题 4: 权限不足

**错误信息**:
```
ERROR: permission denied for table flyway_schema_history
```

**解决方案**:
```sql
-- 授予必要权限
GRANT ALL PRIVILEGES ON DATABASE strategic TO postgres;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO postgres;
```

---

## CI/CD 集成

### GitHub Actions 配置

已在 `.github/workflows/ci.yml` 中配置：

```yaml
- name: Validate Flyway migrations
  run: ./mvnw flyway:validate -B
  env:
    DB_URL: jdbc:postgresql://localhost:5432/sism_test
    DB_USERNAME: postgres
    DB_PASSWORD: postgres

- name: Apply migrations to test database
  run: ./mvnw flyway:migrate -B
  env:
    DB_URL: jdbc:postgresql://localhost:5432/sism_test
    DB_USERNAME: postgres
    DB_PASSWORD: postgres
```

### 部署流程

```mermaid
graph LR
    A[开发] --> B[提交代码]
    B --> C[CI: 验证迁移]
    C --> D[CI: 应用到测试库]
    D --> E[测试通过]
    E --> F[部署到生产]
    F --> G[自动应用迁移]
```

### 生产部署检查清单

- [ ] 备份生产数据库
- [ ] 在测试环境验证迁移
- [ ] 检查 Flyway 历史表状态
- [ ] 确认迁移脚本幂等性
- [ ] 准备回滚方案
- [ ] 通知团队维护窗口
- [ ] 执行迁移
- [ ] 验证应用功能
- [ ] 监控错误日志

---

## 附录

### A. 当前迁移历史

| 版本 | 描述 | 状态 | 应用时间 |
|------|------|------|----------|
| V1.0 | Flyway Baseline | ✅ Success | 2026-02-13 17:40:38 |
| V2 | Add audit flow entities | ✅ Success | 2026-02-13 22:18:16 |
| V3 | Add warn level entity | ✅ Success | 2026-02-13 22:18:18 |

### B. 相关文档

- [Flyway 官方文档](https://flywaydb.org/documentation/)
- [PostgreSQL 文档](https://www.postgresql.org/docs/)
- [项目 README](../README.md)

### C. 联系方式

如有问题，请联系：
- 技术负责人：SISM 开发团队
- 文档维护：Backend Team

---

**最后更新**: 2026-02-14  
**文档版本**: 1.0  
**维护者**: SISM Backend Team
## 当前规则

- 当前数据库结构已经冻结为新的 Flyway `V1` 基线
- 活跃迁移目录是 `sism-main/src/main/resources/db/migration/`
- 尽量不要改数据库结构，除非客户需求明确要求
- 如果不是客户明确需求，优先通过应用层、配置或种子数据解决问题
- 如果必须改结构，只新增新的前向迁移，不修改 `V1__baseline_current_schema.sql`
