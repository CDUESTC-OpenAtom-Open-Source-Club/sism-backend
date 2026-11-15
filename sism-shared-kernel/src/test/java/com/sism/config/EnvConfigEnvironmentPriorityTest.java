package com.sism.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 回归测试：EnvConfig 静态工具里，显式环境变量的优先级必须高于 .env 文件。
 *
 * <p>过去 lookup() 先查 .env、查不到才查 System.getenv，导致 .env 里的本地库地址
 * 静默压过外部指定的目标库。现在反过来：环境变量优先，.env 只兜底。
 */
class EnvConfigEnvironmentPriorityTest {

    @AfterEach
    void tearDown() {
        EnvConfig.setDotenv(null);
    }

    @Test
    void environmentVariableShouldWinOverDotEnv() throws IOException {
        // HOME 是所有类 Unix 环境都真实存在的环境变量，用它来验证「环境变量优先」
        String realHome = System.getenv("HOME");
        assertNotNull(realHome, "测试环境应提供 HOME 环境变量");

        Path tempDir = Files.createTempDirectory("env-config-priority-test");
        try {
            Files.writeString(tempDir.resolve(".env"), "HOME=/from-dotenv\n", StandardCharsets.UTF_8);
            EnvConfig.setDotenv(Dotenv.configure()
                    .directory(tempDir.toString())
                    .ignoreIfMissing()
                    .load());

            assertEquals(realHome, EnvConfig.get("HOME"),
                    "显式环境变量必须压过 .env 里的同名字段");
        } finally {
            EnvConfig.setDotenv(null);
            Files.deleteIfExists(tempDir.resolve(".env"));
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void dotEnvShouldStillProvideValueWhenEnvironmentDoesNotDefineIt() throws IOException {
        Path tempDir = Files.createTempDirectory("env-config-priority-default-test");
        try {
            Files.writeString(
                    tempDir.resolve(".env"),
                    "SISM_ENV_CONFIG_FALLBACK_KEY=from-dotenv\n",
                    StandardCharsets.UTF_8
            );
            EnvConfig.setDotenv(Dotenv.configure()
                    .directory(tempDir.toString())
                    .ignoreIfMissing()
                    .load());

            assertEquals("from-dotenv", EnvConfig.get("SISM_ENV_CONFIG_FALLBACK_KEY"),
                    "未设置环境变量时，.env 仍应作为缺省值来源");
        } finally {
            EnvConfig.setDotenv(null);
            Files.deleteIfExists(tempDir.resolve(".env"));
            Files.deleteIfExists(tempDir);
        }
    }
}
