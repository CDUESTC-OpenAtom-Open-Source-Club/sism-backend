package com.sism.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
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
 * （DB_URL / DB_USERNAME / DB_PASSWORD / JWT_SECRET 等）都不再从 .env 注入，
 * 避免 .env 里的本地开发库地址静默压过外部指定的目标库。
 *
 * Registration: META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor
 *
 * @author SISM Team
 * @since 1.0.0
 */
public class EnvConfigPostProcessor implements EnvironmentPostProcessor {

    private static final String DOTENV_PROPERTY_SOURCE_NAME = "dotenv";

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
            Map<String, Object> envMap = new HashMap<>();
            List<String> overriddenByEnvironment = new ArrayList<>();
            dotenv.entries().forEach(entry -> {
                String originalKey = entry.getKey();
                // 2026-11-15：外部显式传入的环境变量优先级高于 .env 文件。
                // 该键已经由环境提供时，.env 里的同名字段不再注入（连同其映射属性名），
                // 避免 .env 静默压过外部环境变量。
                if (isExplicitlyConfigured(environment, originalKey)) {
                    overriddenByEnvironment.add(originalKey);
                    return;
                }
                String mappedKey = mapEnvVariableToProperty(originalKey);
                // Always put the mapped Spring property name
                envMap.put(mappedKey, entry.getValue());
                // Also preserve the original .env key so that ${DB_URL:default}
                // placeholders in application.yml resolve correctly
                if (!originalKey.equals(mappedKey)) {
                    envMap.put(originalKey, entry.getValue());
                }
            });

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

        } catch (Exception e) {
            System.err.println("Failed to load .env file: " + e.getMessage());
            // Continue without .env - application will use defaults or system properties
        }
    }

    /**
     * 判断某个键是否已经被外部环境变量（或系统属性）显式定义。
     *
     * <p>这里刻意走 Spring Environment 而不是 {@code System.getenv}：
     * <ul>
     *   <li>能识别系统属性（-DDB_URL=...）与 Spring 的宽松绑定
     *       （SPRING_DATASOURCE_URL -&gt; spring.datasource.url）；</li>
     *   <li>测试可以替换 systemEnvironment 属性源来稳定复现「外部传入」场景。</li>
     * </ul>
     *
     * @param environment Spring 环境
     * @param key         .env 中的原始键名
     * @return 外部已显式定义（非空白）时返回 true
     */
    private boolean isExplicitlyConfigured(ConfigurableEnvironment environment, String key) {
        String value = environment.getProperty(key);
        return value != null && !value.isBlank();
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
        return switch (envVar) {
            // Database configuration
            case "DB_URL" -> "spring.datasource.url";
            case "DB_USERNAME" -> "spring.datasource.username";
            case "DB_PASSWORD" -> "spring.datasource.password";

            // Hikari CP configuration
            case "DB_HIKARI_MAX_POOL_SIZE" -> "spring.datasource.hikari.maximum-pool-size";
            case "DB_HIKARI_MIN_IDLE" -> "spring.datasource.hikari.minimum-idle";
            case "DB_HIKARI_CONNECTION_TIMEOUT" -> "spring.datasource.hikari.connection-timeout";
            case "DB_HIKARI_VALIDATION_TIMEOUT" -> "spring.datasource.hikari.validation-timeout";
            case "DB_HIKARI_IDLE_TIMEOUT" -> "spring.datasource.hikari.idle-timeout";
            case "DB_HIKARI_MAX_LIFETIME" -> "spring.datasource.hikari.max-lifetime";
            case "DB_HIKARI_KEEPALIVE_TIME" -> "spring.datasource.hikari.keepalive-time";

            // Flyway configuration
            case "FLYWAY_ENABLED", "SPRING_FLYWAY_ENABLED" -> "spring.flyway.enabled";

            // Server configuration
            case "SERVER_PORT" -> "server.port";
            case "SERVER_CONTEXT_PATH" -> "server.servlet.context-path";

            // JWT configuration (custom properties)
            case "JWT_SECRET" -> "app.jwt.secret";
            case "JWT_EXPIRATION" -> "app.jwt.expiration";
            case "JWT_REFRESH_EXPIRATION" -> "app.jwt.refresh-expiration";

            // CORS configuration
            case "ALLOWED_ORIGINS" -> "app.cors.allowed-origins";
            case "CORS_ALLOWED_METHODS" -> "app.cors.allowed-methods";
            case "CORS_ALLOWED_HEADERS" -> "app.cors.allowed-headers";

            // Logging configuration
            case "LOG_LEVEL_ROOT" -> "logging.level.root";
            case "LOG_LEVEL_APP" -> "logging.level.com.sism";
            case "LOG_LEVEL_SPRING" -> "logging.level.org.springframework";
            case "LOG_LEVEL_SQL" -> "logging.level.org.hibernate.SQL";

            // Swagger configuration
            case "SWAGGER_ENABLED" -> "springdoc.swagger-ui.enabled";

            // Default: use as-is (for custom properties)
            default -> envVar.toLowerCase().replace('_', '.');
        };
    }
}
