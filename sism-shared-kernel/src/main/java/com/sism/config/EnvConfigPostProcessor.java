package com.sism.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EnvironmentPostProcessor for loading .env file into Spring Boot environment
 *
 * This class loads the .env file from the project root directory and injects
 * its values into the Spring Boot environment. This allows application.yml to
 * use placeholders like ${DB_URL} that will be resolved from .env file.
 *
 * <p>优先级约定：显式环境变量 &gt; application.yml &gt; .env 文件。
 * 因此 .env 只作为「缺省值提供者」：凡是外部环境变量已经显式定义的键
 * （DB_URL / DB_USERNAME / DB_PASSWORD / JWT_SECRET 等）都取外部值，
 * 避免 .env 里的本地开发库地址静默压过外部指定的目标库。
 *
 * <p>2026-11-15 生产事故修复（v1.4.2 后端反复重启）：
 * <ul>
 *   <li>dotenv-java 3.0.0 在目标目录<b>没有 .env 文件</b>时（生产容器就是这种
 *       情况：镜像里没有 .env），会把<b>整个进程环境</b>当作 .env 条目返回；</li>
 *   <li>此前「外部已定义就整条跳过」的写法，会把 ALLOWED_ORIGINS 连同它的映射
 *       属性名 app.cors.allowed-origins 一起跳过，于是 ProdCorsConfig 读不到
 *       app.cors.allowed-origins，直接抛
 *       “Production CORS origins must be configured via app.cors.allowed-origins”，
 *       容器启动失败并进入 Restarting(1) 循环；</li>
 *   <li>现在<b>映射永远发生</b>：外部已定义时用外部值去填映射后的属性名，
 *       外部没定义时才用 .env 的值。既保住「显式环境变量优先」，又保证
 *       app.cors.allowed-origins / spring.datasource.url 等映射目标一定可读。</li>
 * </ul>
 *
 * Registration: META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor
 *
 * @author SISM Team
 * @since 1.0.0
 */
public class EnvConfigPostProcessor implements EnvironmentPostProcessor {

    private static final String DOTENV_PROPERTY_SOURCE_NAME = "dotenv";

    /**
     * .env 键 → Spring 属性名的已知映射表。
     *
     * <p>Spring 的宽松绑定只认 {@code APP_CORS_ALLOWED_ORIGINS} 这种「属性名大写」
     * 形式，认不出 {@code ALLOWED_ORIGINS}；所以这些自定义别名必须由本类显式映射。
     * 顺序有意义：映射到同一个属性名的多个来源键里，靠前的优先（原生 Spring 命名优先）。
     */
    private static final Map<String, String> KNOWN_PROPERTY_MAPPINGS = createKnownPropertyMappings();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            // Resolve directory containing .env file.
            // user.dir may be sism-backend/ (root run) or sism-backend/sism-main/ (module run).
            // We search current dir first, then parent, to cover both cases.
            File projectDir = resolveEnvDirectory();

            // Load .env file
            Dotenv dotenv = Dotenv.configure()
                    .directory(projectDir.getAbsolutePath())
                    .ignoreIfMissing()
                    .load();

            // Convert to Map for Spring PropertySource
            Map<String, String> entries = new LinkedHashMap<>();
            dotenv.entries().forEach(dotenvEntry -> entries.put(dotenvEntry.getKey(), dotenvEntry.getValue()));
            applyDotenvEntries(environment, entries);
        } catch (Exception e) {
            System.err.println("Failed to load .env file: " + e.getMessage());
            // Continue without .env - application will use defaults or system properties
        }
    }

    /**
     * 把 dotenv 条目注入 Spring Environment。
     *
     * <p>每个条目都会产出两个键：原始键（供 application.yml 里的 {@code ${DB_URL}} 占位符
     * 使用）和映射后的 Spring 属性名（供 {@code app.cors.allowed-origins} 这类
     * Spring 自己认不出来的属性使用）。
     *
     * <p>取值规则：外部环境变量（或系统属性）已经显式定义 → 用外部值；
     * 没有 → 用 .env 的值。两种情况都<b>必须</b>写入映射后的属性名，
     * 否则外部传入 {@code ALLOWED_ORIGINS} 时会漏掉 {@code app.cors.allowed-origins}，
     * 生产后端会因 CORS 校验失败而启动不了。
     *
     * @param environment Spring 环境
     * @param entries     dotenv 条目（.env 文件内容；文件不存在时是整个进程环境）
     */
    void applyDotenvEntries(ConfigurableEnvironment environment, Map<String, String> entries) {
        Map<String, Object> envMap = new HashMap<>();
        List<String> overriddenByEnvironment = new ArrayList<>();

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String originalKey = entry.getKey();
            String mappedKey = mapEnvVariableToProperty(originalKey);

            // 2026-11-15：外部显式传入的环境变量优先级高于 .env 文件。
            // 注意这里<b>不再整条跳过</b>：跳过会把映射后的属性名一起漏掉，
            // 外部传 ALLOWED_ORIGINS 时 app.cors.allowed-origins 就永远没有值，
            // 生产后端会直接启动失败（v1.4.2 事故）。
            // 正确做法是「值取外部的，映射照做」。
            String externalValue = externalValue(environment, originalKey);
            String effectiveValue = (externalValue != null) ? externalValue : entry.getValue();
            if (externalValue != null) {
                overriddenByEnvironment.add(originalKey);
            }

            // Always put the mapped Spring property name
            envMap.put(mappedKey, effectiveValue);
            // Also preserve the original .env key so that ${DB_URL:default}
            // placeholders in application.yml resolve correctly
            if (!originalKey.equals(mappedKey)) {
                envMap.put(originalKey, effectiveValue);
            }
        }

        // 已知映射键的兜底：即使该键不在 dotenv 条目里（例如本地有 .env 文件、
        // 而 ALLOWED_ORIGINS 由 compose/命令行外部传入），只要外部显式定义了，
        // 也必须把映射后的属性名补上——否则映射目标无值，CORS/JWT 等配置会炸。
        for (Map.Entry<String, String> known : KNOWN_PROPERTY_MAPPINGS.entrySet()) {
            String originalKey = known.getKey();
            String mappedKey = known.getValue();
            if (envMap.containsKey(mappedKey)) {
                continue;
            }
            String externalValue = externalValue(environment, originalKey);
            if (externalValue != null) {
                envMap.put(mappedKey, externalValue);
                if (!overriddenByEnvironment.contains(originalKey)) {
                    overriddenByEnvironment.add(originalKey);
                }
            }
        }

        // Add as property source with low priority (can be overridden by system properties)
        if (!envMap.isEmpty()) {
            MapPropertySource propertySource = new MapPropertySource(DOTENV_PROPERTY_SOURCE_NAME, envMap);
            environment.getPropertySources().addLast(propertySource);
            System.out.println("Loaded " + envMap.size() + " environment variables from .env file");
        }
        if (!overriddenByEnvironment.isEmpty()) {
            System.out.println("Kept externally provided environment variables (not overridden by .env): "
                    + String.join(", ", overriddenByEnvironment));
        }
    }

    /**
     * 读取某个键在外部环境变量 / 系统属性中的值。
     *
     * <p>这里刻意走 Spring Environment 而不是 {@code System.getenv}：
     * <ul>
     *   <li>能识别系统属性（-DDB_URL=...）与 Spring 的宽松绑定
     *       （SPRING_DATASOURCE_URL -&gt; spring.datasource.url）；</li>
     *   <li>测试可以替换 systemEnvironment 属性源来稳定复现「外部传入」场景。</li>
     * </ul>
     *
     * @param environment Spring 环境
     * @param key         键名（.env 原始键名或 Spring 属性名）
     * @return 外部已显式定义（非空白）时返回其值，否则返回 null
     */
    private String externalValue(ConfigurableEnvironment environment, String key) {
        String value = environment.getProperty(key);
        return (value != null && !value.isBlank()) ? value : null;
    }

    /**
     * Resolve the directory containing the .env file.
     * Handles multiple launch scenarios:
     * - Running from sism-backend/ (root): user.dir = sism-backend/, .env is here
     * - Running from sism-backend/sism-main/ (submodule): user.dir = sism-main/, .env is in parent
     * - Running packaged JAR from any directory: search upward
     */
    private File resolveEnvDirectory() {
        File cwd = new File(System.getProperty("user.dir"));

        // 1. Check current directory first
        if (new File(cwd, ".env").exists()) {
            System.out.println("[EnvConfig] Found .env in current dir: " + cwd.getAbsolutePath());
            return cwd;
        }

        // 2. Check parent directory (handles running from submodule like sism-main/)
        File parent = cwd.getParentFile();
        if (parent != null && new File(parent, ".env").exists()) {
            System.out.println("[EnvConfig] Found .env in parent dir: " + parent.getAbsolutePath());
            return parent;
        }

        // 3. Fallback to current directory (ignoreIfMissing will handle absence)
        System.out.println("[EnvConfig] .env not found in " + cwd.getAbsolutePath() + " or parent, using cwd as fallback");
        return cwd;
    }

    /**
     * Map .env variable names to Spring Boot property names
     *
     * Examples:
     * - DB_URL -> spring.datasource.url
     * - DB_USERNAME -> spring.datasource.username
     * - DB_PASSWORD -> spring.datasource.password
     * - JWT_SECRET -> app.jwt.secret (custom property)
     * - SPRING_FLYWAY_ENABLED -> spring.flyway.enabled
     *
     * @param envVar .env variable name
     * @return Spring Boot property name
     */
    private String mapEnvVariableToProperty(String envVar) {
        String known = KNOWN_PROPERTY_MAPPINGS.get(envVar);
        if (known != null) {
            return known;
        }
        // Default: use as-is (for custom properties)
        return envVar.toLowerCase().replace('_', '.');
    }

    /**
     * 构建「.env 键 → Spring 属性名」的已知映射表。
     *
     * <p>这些键是部署面（compose / entrypoint / 应用代码）真正会读的一批，
     * 必须在「外部显式传入」时也能落到映射后的属性名上。
     */
    private static Map<String, String> createKnownPropertyMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();

        // Database configuration
        mappings.put("DB_URL", "spring.datasource.url");
        mappings.put("DB_USERNAME", "spring.datasource.username");
        mappings.put("DB_PASSWORD", "spring.datasource.password");

        // Hikari CP configuration
        mappings.put("DB_HIKARI_MAX_POOL_SIZE", "spring.datasource.hikari.maximum-pool-size");
        mappings.put("DB_HIKARI_MIN_IDLE", "spring.datasource.hikari.minimum-idle");
        mappings.put("DB_HIKARI_CONNECTION_TIMEOUT", "spring.datasource.hikari.connection-timeout");
        mappings.put("DB_HIKARI_VALIDATION_TIMEOUT", "spring.datasource.hikari.validation-timeout");
        mappings.put("DB_HIKARI_IDLE_TIMEOUT", "spring.datasource.hikari.idle-timeout");
        mappings.put("DB_HIKARI_MAX_LIFETIME", "spring.datasource.hikari.max-lifetime");
        mappings.put("DB_HIKARI_KEEPALIVE_TIME", "spring.datasource.hikari.keepalive-time");

        // Flyway configuration（原生 Spring 命名优先）
        mappings.put("SPRING_FLYWAY_ENABLED", "spring.flyway.enabled");
        mappings.put("FLYWAY_ENABLED", "spring.flyway.enabled");

        // Server configuration
        mappings.put("SERVER_PORT", "server.port");
        mappings.put("SERVER_CONTEXT_PATH", "server.servlet.context-path");

        // JWT configuration (custom properties)
        mappings.put("JWT_SECRET", "app.jwt.secret");
        mappings.put("JWT_EXPIRATION", "app.jwt.expiration");
        mappings.put("JWT_REFRESH_EXPIRATION", "app.jwt.refresh-expiration");

        // CORS configuration
        mappings.put("ALLOWED_ORIGINS", "app.cors.allowed-origins");
        mappings.put("CORS_ALLOWED_METHODS", "app.cors.allowed-methods");
        mappings.put("CORS_ALLOWED_HEADERS", "app.cors.allowed-headers");

        // Logging configuration
        mappings.put("LOG_LEVEL_ROOT", "logging.level.root");
        mappings.put("LOG_LEVEL_APP", "logging.level.com.sism");
        mappings.put("LOG_LEVEL_SPRING", "logging.level.org.springframework");
        mappings.put("LOG_LEVEL_SQL", "logging.level.org.hibernate.SQL");

        // Swagger configuration
        mappings.put("SWAGGER_ENABLED", "springdoc.swagger-ui.enabled");

        return Collections.unmodifiableMap(mappings);
    }
}
