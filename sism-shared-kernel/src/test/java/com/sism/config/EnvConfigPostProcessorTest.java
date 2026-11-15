package com.sism.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 回归测试：显式传入的环境变量优先级必须高于 .env 文件。
 *
 * <p>背景：backend/.env 里的 DB_URL 指向本地开发库（sism），过去会静默压过外部
 * 传入的同名环境变量，导致「按环境变量指定库」无声失效。本测试锁住两条口径：
 * <ul>
 *   <li>传了环境变量 → 用环境变量（DB_URL / DB_USERNAME / DB_PASSWORD / JWT_SECRET）；</li>
 *   <li>没传环境变量 → 用 .env 里的值（本地开发默认库仍然可用）。</li>
 * </ul>
 */
class EnvConfigPostProcessorTest {

    private static final String DOTENV_DB_URL = "jdbc:postgresql://localhost:5432/sism";
    private static final String DOTENV_DB_USERNAME = "dotenv_user";
    private static final String DOTENV_DB_PASSWORD = "dotenv_password";
    private static final String DOTENV_JWT_SECRET = "dotenv-jwt-secret";

    private static final String EXTERNAL_DB_URL = "jdbc:postgresql://external-host:5432/otherdb";
    private static final String EXTERNAL_DB_USERNAME = "external_user";
    private static final String EXTERNAL_DB_PASSWORD = "external_password";
    private static final String EXTERNAL_JWT_SECRET = "external-jwt-secret";

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
    void dotEnvShouldNotBeInjectedForKeysAlreadyDefinedByEnvironment() {
        Map<String, Object> external = new LinkedHashMap<>();
        external.put("DB_URL", EXTERNAL_DB_URL);
        ConfigurableEnvironment environment = buildEnvironment(external);

        new EnvConfigPostProcessor().postProcessEnvironment(environment, null);

        MapPropertySource dotenv = (MapPropertySource) environment.getPropertySources().get("dotenv");
        assertNotNull(dotenv);
        assertNull(dotenv.getProperty("DB_URL"), ".env 不应再注入外部已定义的 DB_URL");
        assertNull(dotenv.getProperty("spring.datasource.url"), ".env 不应再注入外部已定义的 DB_URL 映射属性");
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
}
