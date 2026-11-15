package com.sism.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：显式传入的环境变量优先级必须高于 .env 文件，
 * 且「.env 键 → Spring 属性名」的映射必须始终成立。
 *
 * <p>背景一：backend/.env 里的 DB_URL 指向本地开发库（sism），过去会静默压过外部
 * 传入的同名环境变量，导致「按环境变量指定库」无声失效。本测试锁住两条口径：
 * <ul>
 *   <li>传了环境变量 → 用环境变量（DB_URL / DB_USERNAME / DB_PASSWORD / JWT_SECRET）；</li>
 *   <li>没传环境变量 → 用 .env 里的值（本地开发默认库仍然可用）。</li>
 * </ul>
 *
 * <p>背景二（2026-11-15 生产事故）：v1.4.2 部署后后端容器反复 Restarting(1)，报
 * {@code Production CORS origins must be configured via app.cors.allowed-origins}。
 * 根因链：
 * <ol>
 *   <li>生产 compose 走 {@code environment: ALLOWED_ORIGINS: ${ALLOWED_ORIGINS}}，
 *       容器进程环境里有 ALLOWED_ORIGINS；</li>
 *   <li>生产镜像里<b>没有</b> .env 文件，dotenv-java 3.0.0 会把<b>整个进程环境</b>
 *       当成 .env 条目返回（{@code dotenv.entries()} 里就有 ALLOWED_ORIGINS）；</li>
 *   <li>「外部已定义就整条跳过」的写法把 ALLOWED_ORIGINS 连同它的映射属性名
 *       app.cors.allowed-origins 一起跳过 → 映射目标无值 → ProdCorsConfig 抛异常
 *       → 容器启动失败。</li>
 * </ol>
 *
 * <p>修复口径：<b>值取外部的，映射照做</b>——外部定义了就用外部值填映射后的属性名，
 * 没定义才用 .env 的值。
 */
class EnvConfigPostProcessorTest {

    private static final String DOTENV_DB_URL = "jdbc:postgresql://localhost:5432/sism";
    private static final String DOTENV_DB_USERNAME = "dotenv_user";
    private static final String DOTENV_DB_PASSWORD = "dotenv_password";
    private static final String DOTENV_JWT_SECRET = "dotenv-jwt-secret";
    private static final String DOTENV_ORIGINS = "http://localhost:5173";

    private static final String EXTERNAL_DB_URL = "jdbc:postgresql://external-host:5432/otherdb";
    private static final String EXTERNAL_DB_USERNAME = "external_user";
    private static final String EXTERNAL_DB_PASSWORD = "external_password";
    private static final String EXTERNAL_JWT_SECRET = "external-jwt-secret";
    private static final String EXTERNAL_ORIGINS = "https://sism.blackevil.cn,https://blackevil.cn";

    private Path tempDir;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("env-config-post-processor-test");
        Files.writeString(
                tempDir.resolve(".env"),
                "DB_URL=" + DOTENV_DB_URL + "\n"
                        + "DB_USERNAME=" + DOTENV_DB_USERNAME + "\n"
                        + "DB_PASSWORD=" + DOTENV_DB_PASSWORD + "\n"
                        + "JWT_SECRET=" + DOTENV_JWT_SECRET + "\n",
                StandardCharsets.UTF_8
        );
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setProperty("user.dir", originalUserDir);
        Files.deleteIfExists(tempDir.resolve(".env"));
        Files.deleteIfExists(tempDir);
    }

    @Test
    void dotEnvShouldProvideDefaultsWhenEnvironmentDoesNotDefineThem() {
        ConfigurableEnvironment environment = buildEnvironment(Map.of());

        new EnvConfigPostProcessor().postProcessEnvironment(environment, null);

        assertEquals(DOTENV_DB_URL, environment.getProperty("spring.datasource.url"));
        assertEquals(DOTENV_DB_USERNAME, environment.getProperty("spring.datasource.username"));
        assertEquals(DOTENV_DB_PASSWORD, environment.getProperty("spring.datasource.password"));
        assertEquals(DOTENV_JWT_SECRET, environment.getProperty("JWT_SECRET"));
    }

    @Test
    void environmentVariablesShouldWinOverDotEnv() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("DB_URL", EXTERNAL_DB_URL);
        external.put("DB_USERNAME", EXTERNAL_DB_USERNAME);
        external.put("DB_PASSWORD", EXTERNAL_DB_PASSWORD);
        external.put("JWT_SECRET", EXTERNAL_JWT_SECRET);
        ConfigurableEnvironment environment = buildEnvironment(external);

        new EnvConfigPostProcessor().postProcessEnvironment(environment, null);

        assertEquals(EXTERNAL_DB_URL, environment.getProperty("spring.datasource.url"));
        assertEquals(EXTERNAL_DB_USERNAME, environment.getProperty("spring.datasource.username"));
        assertEquals(EXTERNAL_DB_PASSWORD, environment.getProperty("spring.datasource.password"));
        assertEquals(EXTERNAL_JWT_SECRET, environment.getProperty("JWT_SECRET"));
    }

    @Test
    void externallyDefinedKeysShouldNeverFallBackToDotEnvValues() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("DB_URL", EXTERNAL_DB_URL);
        ConfigurableEnvironment environment = buildEnvironment(external);

        new EnvConfigPostProcessor().postProcessEnvironment(environment, null);

        MapPropertySource dotenv = (MapPropertySource) environment.getPropertySources().get("dotenv");
        assertNotNull(dotenv);
        assertEquals(EXTERNAL_DB_URL, dotenv.getProperty("DB_URL"),
                ".env 的值不得覆盖外部定义，注入的必须是外部值");
        assertEquals(EXTERNAL_DB_URL, dotenv.getProperty("spring.datasource.url"),
                "映射属性名必须存在且取外部值（漏掉映射会让 app.cors.allowed-origins 之类的属性丢值）");
        // 未被外部定义的键仍然由 .env 提供缺省值
        assertEquals(DOTENV_DB_USERNAME, dotenv.getProperty("DB_USERNAME"));
    }

    @Test
    void blankEnvironmentValueShouldNotSuppressDotEnvInjection() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("DB_URL", "   ");
        ConfigurableEnvironment environment = buildEnvironment(external);

        new EnvConfigPostProcessor().postProcessEnvironment(environment, null);

        MapPropertySource dotenv = (MapPropertySource) environment.getPropertySources().get("dotenv");
        assertNotNull(dotenv);
        assertEquals(DOTENV_DB_URL, dotenv.getProperty("DB_URL"),
                "空白的环境变量视为未设置，.env 仍应提供缺省值");
    }

    /**
     * compose 场景：ALLOWED_ORIGINS 由外部环境变量提供，
     * 必须能映射到 app.cors.allowed-origins。
     */
    @Test
    void externalAllowedOriginsShouldBeMappedToAppCorsAllowedOrigins() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("ALLOWED_ORIGINS", EXTERNAL_ORIGINS);
        ConfigurableEnvironment environment = buildEnvironment(external);

        new EnvConfigPostProcessor().postProcessEnvironment(environment, null);

        assertEquals(EXTERNAL_ORIGINS, environment.getProperty("app.cors.allowed-origins"),
                "外部传入的 ALLOWED_ORIGINS 必须映射到 app.cors.allowed-origins");
    }

    /**
     * compose 场景（容器里没有 .env 文件，dotenv 条目里没有 ALLOWED_ORIGINS）：
     * 外部定义的值同样必须落到映射后的属性名上。
     */
    @Test
    void externalAllowedOriginsShouldBeMappedEvenWithoutDotEnvEntry() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("ALLOWED_ORIGINS", EXTERNAL_ORIGINS);
        ConfigurableEnvironment environment = buildEnvironment(external);

        new EnvConfigPostProcessor().applyDotenvEntries(environment, Map.of());

        assertEquals(EXTERNAL_ORIGINS, environment.getProperty("app.cors.allowed-origins"),
                "dotenv 条目里没有该键时，外部定义也要落到映射后的属性名");
    }

    /**
     * 本地场景：ALLOWED_ORIGINS 只写在 .env 文件里 → 同样能读到并映射。
     */
    @Test
    void dotEnvAllowedOriginsShouldBeMappedWhenEnvironmentDoesNotDefineIt() {
        ConfigurableEnvironment environment = buildEnvironment(Map.of());

        new EnvConfigPostProcessor().applyDotenvEntries(
                environment, Map.of("ALLOWED_ORIGINS", DOTENV_ORIGINS));

        assertEquals(DOTENV_ORIGINS, environment.getProperty("app.cors.allowed-origins"),
                ".env 里的 ALLOWED_ORIGINS 也必须映射到 app.cors.allowed-origins");
    }

    /**
     * 真正复现本次事故：外部已定义 ALLOWED_ORIGINS 时，prod profile 下
     * ProdCorsConfig 必须能建出 CorsFilter（修复前这里直接抛 IllegalStateException，
     * 容器进入 Restarting(1) 循环）。
     */
    @Test
    void prodCorsFilterShouldNotThrowWhenAllowedOriginsComeFromEnvironment() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("ALLOWED_ORIGINS", EXTERNAL_ORIGINS);
        ConfigurableEnvironment environment = buildEnvironment(external);

        // 模拟生产容器：镜像里没有 .env，dotenv 条目回落到整个进程环境
        new EnvConfigPostProcessor().applyDotenvEntries(
                environment, Map.of("ALLOWED_ORIGINS", EXTERNAL_ORIGINS));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            environment.setActiveProfiles("prod");
            context.register(ProdCorsConfig.class);
            context.refresh();

            CorsFilter filter = context.getBean(CorsFilter.class);
            assertNotNull(filter, "prod profile 下必须能建出 CorsFilter");

            UrlBasedCorsConfigurationSource source =
                    (UrlBasedCorsConfigurationSource) ReflectionTestUtils.getField(filter, "configSource");
            assertNotNull(source, "CorsFilter 必须持有 CORS 配置源");
            CorsConfiguration configuration = source.getCorsConfigurations().get("/**");
            assertNotNull(configuration, "必须注册 /** 的 CORS 配置");
            assertEquals(List.of("https://sism.blackevil.cn", "https://blackevil.cn"),
                    configuration.getAllowedOrigins(),
                    "CORS 白名单必须来自外部传入的 ALLOWED_ORIGINS");
        }
    }

    /**
     * prod profile 且确实没有 ALLOWED_ORIGINS → 仍然必须报错（别把校验放松掉）。
     */
    @Test
    void prodCorsFilterShouldStillFailWhenAllowedOriginsAreMissing() {
        ConfigurableEnvironment environment = buildEnvironment(Map.of());

        new EnvConfigPostProcessor().applyDotenvEntries(environment, Map.of());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            environment.setActiveProfiles("prod");
            context.register(ProdCorsConfig.class);

            Exception failure = assertThrows(Exception.class, context::refresh,
                    "缺少 ALLOWED_ORIGINS 时 prod 必须启动失败（生产 CORS 白名单不可为空）");
            assertTrue(rootCauseMessage(failure).contains("app.cors.allowed-origins"),
                    "异常信息应指明缺少 app.cors.allowed-origins，实际: " + rootCauseMessage(failure));
        }
    }

    /**
     * JWT_SECRET 外部定义 → 映射到 app.jwt.secret（应用代码读的是这个属性名）。
     */
    @Test
    void externalJwtSecretShouldBeMappedToAppJwtSecret() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("JWT_SECRET", EXTERNAL_JWT_SECRET);
        ConfigurableEnvironment environment = buildEnvironment(external);

        new EnvConfigPostProcessor().postProcessEnvironment(environment, null);

        assertEquals(EXTERNAL_JWT_SECRET, environment.getProperty("app.jwt.secret"),
                "外部 JWT_SECRET 必须映射到 app.jwt.secret");
    }

    /**
     * 构造一个带 application.yml 占位符的环境，并按需注入「外部环境变量」。
     *
     * <p>外部环境变量通过替换 systemEnvironment 属性源模拟，与真实进程环境等价。
     */
    private ConfigurableEnvironment buildEnvironment(Map<String, Object> externalEnvironment) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        externalEnvironment
                )
        );
        // 与 application.yml 一致：spring.datasource.url: ${DB_URL}
        environment.getPropertySources().addLast(new MapPropertySource(
                "applicationConfig",
                Map.of(
                        "spring.datasource.url", "${DB_URL}",
                        "spring.datasource.username", "${DB_USERNAME}",
                        "spring.datasource.password", "${DB_PASSWORD}"
                )
        ));
        return environment;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        StringBuilder messages = new StringBuilder();
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append(" | ");
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}
