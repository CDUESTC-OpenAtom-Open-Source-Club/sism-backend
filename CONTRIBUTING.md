# 贡献指南 (Contributing Guide)

欢迎为 SISM 后端项目做出贡献！为了保持代码质量和提交历史的清晰，请遵循以下规范。

## 📋 目录

- [开发环境设置](#开发环境设置)
- [分支管理策略](#分支管理策略)
- [提交规范](#提交规范)
- [代码规范](#代码规范)
- [Pull Request 流程](#pull-request-流程)
- [测试要求](#测试要求)
- [常见问题](#常见问题)

---

## 🏛️ 数据建模规范（issue #81 架构评审固化）

新增业务表/字段时必须过一遍以下检查清单（来源：五角色体验测试与架构债务 #81）：

1. **枚举/档位四层落点**：凡引入新的状态/档位枚举值，必须同时核对——
   前端下拉与归一白名单 → 接口 DTO 校验（@Pattern 等）→ 业务枚举（normalize）→ DB CHECK 约束。
   历史教训：进度等级三档上线时接口 @Pattern 与 alert_rule 约束漏改，导致档位存不进去。
2. **周期（cycle）归属**：新表若含业务周期语义，须冗余 cycle_id（避免 task→cycle 多跳）；
   评估写入时双写，读侧不再多跳。
3. **未审批态判空**：关联 audit_instance_id 的表，查询/展示代码必须处理 NULL（未发起审批），
   不得假设恒有审批流。
4. **软删除与孤儿**：父行软删后子行的展示策略必须明确（容忍显示+标记 或 级联处理），
   参照 D2 孤立指标容忍模式（indicator root 提升）。
5. **注释即文档**：表/列 COMMENT 描述当前用途，禁止写「VXX 删除了某字段」式变更流水账
   （反例：audit_flow_def，已在 V92 修正）。
6. **告警/通知生命周期**：凡会推送「待审批」类通知的流程，终态时必须关闭历史通知
   （参照 AlertResolvedEvent → deleteAlertNotifications 闭环），防止催办指向已过时状态。

---

## 🛠️ 开发环境设置

### 前置要求

- **Java**: 17 或更高版本
- **Maven**: 3.8 或更高版本
- **PostgreSQL**: 12 或更高版本
- **Git**: 2.30 或更高版本

### 初始设置

```bash
# 1. Fork 项目到你的 GitHub 账号

# 2. 克隆你的 fork
git clone https://github.com/YOUR_USERNAME/sism-backend.git
cd sism-backend

# 3. 添加上游仓库
git remote add upstream https://github.com/CDUESTC-OpenAtom-Club/sism-backend.git

# 4. 配置环境变量
cp .env.example .env
# 编辑 .env 文件，填入你的数据库配置

# 5. 初始化数据库
./mvnw flyway:migrate

# 如需本地 clean seeds
./database/scripts/reset-clean-seeds.sh

# 6. 运行项目
./mvnw spring-boot:run

# 7. 验证安装
curl http://localhost:8080/api/health
```

---

## 🌿 分支管理策略

### 分支命名规范

我们使用 **Git Flow** 工作流，分支命名遵循以下规范：

```
feature/功能描述       # 新功能开发
bugfix/问题描述        # Bug 修复
hotfix/紧急修复描述    # 生产环境紧急修复
refactor/重构描述      # 代码重构
docs/文档描述          # 文档更新
test/测试描述          # 测试相关
chore/杂项描述         # 构建、配置等杂项
```

### 分支命名示例

```bash
feature/add-user-authentication
bugfix/fix-indicator-calculation
hotfix/fix-critical-security-issue
refactor/optimize-database-queries
docs/update-api-documentation
test/add-integration-tests
chore/update-dependencies
```

### 工作流程

```bash
# 1. 确保 main 分支是最新的
git checkout main
git pull upstream main

# 2. 创建新分支（从 main 分支）
git checkout -b feature/your-feature-name

# 3. 进行开发工作
# ... 编写代码 ...

# 4. 提交更改（遵循提交规范）
git add .
git commit -m "feat: add user authentication"

# 5. 保持分支与上游同步
git fetch upstream
git rebase upstream/main

# 6. 推送到你的 fork
git push origin feature/your-feature-name

# 7. 在 GitHub 上创建 Pull Request
```

### ⚠️ 重要规则

1. **永远不要直接提交到 main 分支**
2. **始终从最新的 main 分支创建新分支**
3. **一个分支只做一件事**（一个功能或一个 bug 修复）
4. **定期同步上游 main 分支**
5. **使用 rebase 而不是 merge 来保持历史清晰**

---

## 📝 提交规范

我们使用 **Conventional Commits** 规范，确保提交历史清晰易读。

### 提交消息格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Type 类型

| Type | 说明 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat: add user login API` |
| `fix` | Bug 修复 | `fix: resolve null pointer in indicator service` |
| `docs` | 文档更新 | `docs: update API documentation` |
| `style` | 代码格式（不影响功能） | `style: format code with prettier` |
| `refactor` | 重构（不是新功能也不是修复） | `refactor: extract common validation logic` |
| `perf` | 性能优化 | `perf: optimize database query performance` |
| `test` | 测试相关 | `test: add unit tests for indicator service` |
| `build` | 构建系统或依赖更新 | `build: upgrade Spring Boot to 3.2.1` |
| `ci` | CI/CD 配置 | `ci: add GitHub Actions workflow` |
| `chore` | 其他杂项 | `chore: update .gitignore` |
| `revert` | 回滚提交 | `revert: revert commit abc123` |

### Scope 范围（可选）

指明提交影响的范围，例如：

- `auth` - 认证相关
- `indicator` - 指标相关
- `task` - 任务相关
- `api` - API 相关
- `db` - 数据库相关
- `config` - 配置相关

### Subject 主题

- 使用祈使句，现在时态："add" 而不是 "added" 或 "adds"
- 不要大写首字母
- 结尾不要加句号
- 限制在 50 个字符以内

### Body 正文（可选）

- 详细描述改动的原因和内容
- 每行限制在 72 个字符以内
- 可以包含多个段落

### Footer 页脚（可选）

- 关联 Issue：`Closes #123`
- 破坏性变更：`BREAKING CHANGE: description`

### 提交示例

#### 简单提交

```bash
git commit -m "feat: add user authentication endpoint"
```

#### 带 scope 的提交

```bash
git commit -m "fix(indicator): resolve calculation error for nested indicators"
```

#### 完整提交

```bash
git commit -m "feat(auth): implement JWT token refresh mechanism

- Add refresh token endpoint
- Implement token rotation strategy
- Add refresh token validation
- Update security configuration

Closes #45"
```

#### 破坏性变更

```bash
git commit -m "refactor(api)!: change indicator API response format

BREAKING CHANGE: The indicator API now returns a different response structure.
Old format: { data: {...} }
New format: { indicator: {...}, metadata: {...} }

Migration guide: Update all API clients to use the new response format.

Closes #67"
```

### ✅ 好的提交示例

```bash
feat: add user registration endpoint
fix: resolve memory leak in indicator service
docs: update README with installation instructions
test: add integration tests for authentication
refactor: extract validation logic to separate class
perf: optimize database query with proper indexing
```

### ❌ 不好的提交示例

```bash
update code                    # 太模糊
Fixed bug                      # 首字母大写，缺少类型
feat: Added new feature.       # 过去时态，有句号
WIP                           # 不应该提交未完成的工作
misc changes                   # 太模糊，缺少类型
```

---

## 💻 代码规范

### Java 代码风格

我们遵循 **Google Java Style Guide**，并使用 Lombok 减少样板代码。

#### 命名规范

```java
// 类名：大驼峰（PascalCase）
public class IndicatorService { }

// 方法名：小驼峰（camelCase）
public void calculateIndicator() { }

// 常量：全大写，下划线分隔
public static final int MAX_RETRY_COUNT = 3;

// 变量：小驼峰
private String userName;

// 包名：全小写
package com.sism.service;
```

#### 注解使用

```java
// 使用 Lombok 减少样板代码
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorVO {
    private Long id;
    private String name;
}

// Controller 注解
@RestController
@RequestMapping("/api/indicators")
@RequiredArgsConstructor
public class IndicatorController {
    private final IndicatorService indicatorService;
}

// Service 注解
@Service
@Transactional
@RequiredArgsConstructor
public class IndicatorServiceImpl implements IndicatorService {
    private final IndicatorRepository indicatorRepository;
}
```

#### 代码组织

```java
@Service
public class IndicatorService {
    // 1. 常量
    private static final int DEFAULT_PAGE_SIZE = 20;
    
    // 2. 依赖注入
    private final IndicatorRepository indicatorRepository;
    
    // 3. 公共方法
    public IndicatorVO getIndicator(Long id) {
        // ...
    }
    
    // 4. 私有方法
    private void validateIndicator(Indicator indicator) {
        // ...
    }
}
```

### 注释规范

```java
/**
 * 指标服务实现类
 * 
 * <p>提供指标的 CRUD 操作和业务逻辑处理
 * 
 * @author Your Name
 * @since 1.0.0
 */
@Service
public class IndicatorServiceImpl implements IndicatorService {
    
    /**
     * 根据 ID 获取指标
     * 
     * @param id 指标 ID
     * @return 指标视图对象
     * @throws ResourceNotFoundException 当指标不存在时
     */
    public IndicatorVO getIndicator(Long id) {
        // 实现逻辑
    }
}
```

### 异常处理

```java
// 使用自定义异常
throw new ResourceNotFoundException("Indicator not found with id: " + id);

// 不要捕获通用异常
// ❌ 不好
try {
    // ...
} catch (Exception e) {
    // ...
}

// ✅ 好
try {
    // ...
} catch (DataAccessException e) {
    log.error("Database error", e);
    throw new ServiceException("Failed to access database", e);
}
```

---

## 🔀 Pull Request 流程

### 创建 PR 前的检查清单

- [ ] 代码已通过本地测试：`./mvnw test`
- [ ] 代码已通过编译：`./mvnw clean compile`
- [ ] 已添加必要的单元测试
- [ ] 已更新相关文档
- [ ] 提交消息遵循规范
- [ ] 分支已与上游 main 同步
- [ ] 已解决所有冲突

### PR 标题格式

PR 标题应该遵循提交消息规范：

```
feat: add user authentication
fix: resolve indicator calculation bug
docs: update API documentation
```

### PR 描述模板

```markdown
## 📝 变更说明

简要描述这个 PR 做了什么。

## 🎯 相关 Issue

Closes #123

## 🔄 变更类型

- [ ] 新功能 (feature)
- [ ] Bug 修复 (bugfix)
- [ ] 重构 (refactor)
- [ ] 文档更新 (docs)
- [ ] 测试 (test)
- [ ] 其他 (chore)

## 📋 变更清单

- 添加了用户认证 API
- 实现了 JWT token 生成
- 添加了单元测试

## 🧪 测试

描述如何测试这些变更：

```bash
# 运行单元测试
./mvnw test

# 手动测试步骤
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test123"}'
```

## 📸 截图（如适用）

如果有 UI 变更，请添加截图。

## ⚠️ 破坏性变更

如果有破坏性变更，请详细说明：

- 变更内容
- 迁移指南
- 影响范围

## ✅ 检查清单

- [ ] 代码已通过所有测试
- [ ] 已添加必要的单元测试
- [ ] 已更新相关文档
- [ ] 提交消息遵循规范
- [ ] 代码遵循项目规范
```

### PR 审查流程

1. **提交 PR** - 创建 Pull Request
2. **自动检查** - CI/CD 自动运行测试
3. **代码审查** - 至少需要 1 位审查者批准
4. **修改反馈** - 根据审查意见修改代码
5. **合并** - 审查通过后由维护者合并

### PR 合并策略

我们使用 **Squash and Merge** 策略：

- 所有提交会被压缩成一个提交
- 保持 main 分支历史清晰
- 合并后自动删除分支

---

## 🧪 测试要求

### 测试覆盖率要求

- **新功能**: 必须有单元测试，覆盖率 ≥ 80%
- **Bug 修复**: 必须有回归测试
- **重构**: 确保现有测试通过

### 运行测试

```bash
# 运行所有测试
./mvnw test

# 运行特定测试类
./mvnw test -Dtest=IndicatorServiceTest

# 运行特定测试方法
./mvnw test -Dtest=IndicatorServiceTest#testGetIndicator

# 生成测试覆盖率报告
./mvnw test jacoco:report
# 查看报告: target/site/jacoco/index.html
```

### 测试命名规范

```java
@Test
void methodName_condition_expectedResult() {
    // Arrange
    Indicator indicator = new Indicator();
    indicator.setName("Test");
    
    // Act
    IndicatorVO result = indicatorService.create(indicator);
    
    // Assert
    assertThat(result.getName()).isEqualTo("Test");
}
```

### 测试示例

```java
@SpringBootTest
@Transactional
class IndicatorServiceTest {
    
    @Autowired
    private IndicatorService indicatorService;
    
    @Test
    void getIndicator_whenExists_returnsIndicator() {
        // Arrange
        Long indicatorId = 1L;
        
        // Act
        IndicatorVO result = indicatorService.getIndicator(indicatorId);
        
        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(indicatorId);
    }
    
    @Test
    void getIndicator_whenNotExists_throwsException() {
        // Arrange
        Long nonExistentId = 999L;
        
        // Act & Assert
        assertThatThrownBy(() -> indicatorService.getIndicator(nonExistentId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Indicator not found");
    }
}
```

---

## 🔄 保持同步

### 定期同步上游仓库

```bash
# 1. 获取上游更新
git fetch upstream

# 2. 切换到 main 分支
git checkout main

# 3. 合并上游 main
git merge upstream/main

# 4. 推送到你的 fork
git push origin main

# 5. 更新你的功能分支
git checkout feature/your-feature
git rebase main
```

### 解决冲突

```bash
# 1. 尝试 rebase
git rebase upstream/main

# 2. 如果有冲突，解决冲突
# 编辑冲突文件，解决冲突标记

# 3. 标记冲突已解决
git add .

# 4. 继续 rebase
git rebase --continue

# 5. 强制推送（因为历史已改变）
git push origin feature/your-feature --force-with-lease
```

---

## ❓ 常见问题

### Q: 我应该使用 merge 还是 rebase？

**A:** 使用 **rebase** 来保持历史清晰。

```bash
# ✅ 推荐
git rebase upstream/main

# ❌ 不推荐（会产生合并提交）
git merge upstream/main
```

### Q: 我不小心提交到了 main 分支怎么办？

**A:** 创建新分支并重置 main：

```bash
# 1. 创建新分支保存你的工作
git branch feature/my-work

# 2. 重置 main 到上游
git checkout main
git reset --hard upstream/main

# 3. 切换到你的工作分支
git checkout feature/my-work
```

### Q: 如何修改最后一次提交？

**A:** 使用 `--amend`：

```bash
# 修改提交消息
git commit --amend -m "feat: new commit message"

# 添加遗漏的文件
git add forgotten-file.java
git commit --amend --no-edit
```

### Q: 如何撤销已推送的提交？

**A:** 使用 `revert`（不要使用 `reset`）：

```bash
# 创建一个新的提交来撤销之前的提交
git revert <commit-hash>
git push origin feature/your-feature
```

### Q: PR 被拒绝了怎么办？

**A:** 根据反馈修改代码：

```bash
# 1. 在你的分支上修改代码
git checkout feature/your-feature

# 2. 提交修改
git add .
git commit -m "fix: address review comments"

# 3. 推送更新
git push origin feature/your-feature

# PR 会自动更新
```

### Q: 如何处理大型重构？

**A:** 分解成小的 PR：

1. 每个 PR 只做一件事
2. 按逻辑顺序提交
3. 确保每个 PR 都可以独立审查
4. 在 PR 描述中说明整体计划

---

## 📞 获取帮助

如果你有任何问题：

1. 查看 [README.md](./README.md) 了解项目概览
2. 查看 [docs/](./docs/) 目录了解详细文档
3. 在 GitHub Issues 中搜索类似问题
4. 创建新的 Issue 提问

---

## 📜 许可证

通过贡献代码，你同意你的贡献将在与项目相同的许可证下发布。

---

## 🙏 感谢

感谢你为 SISM 项目做出贡献！每一个贡献都让项目变得更好。
