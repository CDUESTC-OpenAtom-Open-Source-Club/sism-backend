package com.sism.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：scripts/lib/load-env-file.sh 载入 .env 时不得覆盖外部显式环境变量。
 *
 * <p>这是「.env 静默压过外部环境变量」的真正触发点：start.sh 等脚本过去用
 * {@code set -a; source .env; set +a} 载入，source 会直接覆盖外部已经导出的
 * 同名变量，JVM 拿到的就是 .env 里的本地库地址，「按环境变量指定库」无声失效。
 */
class LoadEnvFileScriptTest {

    private static final String DOTENV_DB_URL = "jdbc:postgresql://localhost:5432/sism";
    private static final String DOTENV_DB_USERNAME = "dotenv_user";
    private static final String EXTERNAL_DB_URL = "jdbc:postgresql://external-host:5432/otherdb";
    private static final String DOTENV_ALLOWED_ORIGINS = "http://localhost:5173";
    private static final String EXTERNAL_ALLOWED_ORIGINS = "https://sism.blackevil.cn,https://blackevil.cn";

    @Test
    void externallyProvidedEnvVarShouldWinOverEnvFile() throws IOException, InterruptedException {
        Path helper = locateHelperScript();
        Path tempDir = Files.createTempDirectory("load-env-file-script-test");
        Path envFile = tempDir.resolve(".env");
        try {
            Files.writeString(
                    envFile,
                    "DB_URL=" + DOTENV_DB_URL + "\nDB_USERNAME=" + DOTENV_DB_USERNAME + "\n",
                    StandardCharsets.UTF_8
            );

            Map<String, String> external = new LinkedHashMap<>();
            external.put("DB_URL", EXTERNAL_DB_URL);

            String output = runBash(helper, envFile, external);

            assertTrue(output.contains("DB_URL=" + EXTERNAL_DB_URL),
                    "外部传入的 DB_URL 必须胜出，实际输出: " + output);
            assertTrue(output.contains("DB_USERNAME=" + DOTENV_DB_USERNAME),
                    "未设置的环境变量仍应回落到 .env，实际输出: " + output);
        } finally {
            Files.deleteIfExists(envFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void envFileShouldProvideDefaultsWhenNoEnvVarIsGiven() throws IOException, InterruptedException {
        Path helper = locateHelperScript();
        Path tempDir = Files.createTempDirectory("load-env-file-script-default-test");
        Path envFile = tempDir.resolve(".env");
        try {
            Files.writeString(
                    envFile,
                    "DB_URL=" + DOTENV_DB_URL + "\nDB_USERNAME=" + DOTENV_DB_USERNAME + "\n",
                    StandardCharsets.UTF_8
            );

            String output = runBash(helper, envFile, Map.of());

            assertTrue(output.contains("DB_URL=" + DOTENV_DB_URL),
                    "未传环境变量时应使用 .env 的值，实际输出: " + output);
            assertTrue(output.contains("DB_USERNAME=" + DOTENV_DB_USERNAME),
                    "未传环境变量时应使用 .env 的值，实际输出: " + output);
        } finally {
            Files.deleteIfExists(envFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * compose 场景：ALLOWED_ORIGINS 由外部传入（compose 的 environment 段），
     * 载入 .env 不得把它覆盖掉。
     *
     * <p>ALLOWED_ORIGINS 决定生产 CORS 白名单（app.cors.allowed-origins），
     * 一旦被 .env 里的旧值盖住，后端就会以「生产 CORS 白名单不对」启动。
     */
    @Test
    void externallyProvidedAllowedOriginsShouldWinOverEnvFile() throws IOException, InterruptedException {
        Path helper = locateHelperScript();
        Path tempDir = Files.createTempDirectory("load-env-file-script-cors-test");
        Path envFile = tempDir.resolve(".env");
        try {
            Files.writeString(
                    envFile,
                    "DB_URL=" + DOTENV_DB_URL + "\nALLOWED_ORIGINS=" + DOTENV_ALLOWED_ORIGINS + "\n",
                    StandardCharsets.UTF_8
            );

            Map<String, String> external = new LinkedHashMap<>();
            external.put("ALLOWED_ORIGINS", EXTERNAL_ALLOWED_ORIGINS);

            String output = runBash(helper, envFile, external);

            assertTrue(output.contains("ALLOWED_ORIGINS=" + EXTERNAL_ALLOWED_ORIGINS),
                    "外部传入的 ALLOWED_ORIGINS 必须胜出，实际输出: " + output);
            assertTrue(output.contains("DB_URL=" + DOTENV_DB_URL),
                    "未设置的环境变量仍应回落到 .env，实际输出: " + output);
        } finally {
            Files.deleteIfExists(envFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * 本地场景：ALLOWED_ORIGINS 只在 .env 里 → 载入后仍应可用。
     */
    @Test
    void envFileShouldProvideAllowedOriginsWhenNoEnvVarIsGiven() throws IOException, InterruptedException {
        Path helper = locateHelperScript();
        Path tempDir = Files.createTempDirectory("load-env-file-script-cors-default-test");
        Path envFile = tempDir.resolve(".env");
        try {
            Files.writeString(
                    envFile,
                    "ALLOWED_ORIGINS=" + DOTENV_ALLOWED_ORIGINS + "\n",
                    StandardCharsets.UTF_8
            );

            String output = runBash(helper, envFile, Map.of());

            assertTrue(output.contains("ALLOWED_ORIGINS=" + DOTENV_ALLOWED_ORIGINS),
                    "未传环境变量时应使用 .env 的 ALLOWED_ORIGINS，实际输出: " + output);
        } finally {
            Files.deleteIfExists(envFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * 执行 bash：source 载入脚本后打印 DB_URL / DB_USERNAME / ALLOWED_ORIGINS
     */
    private String runBash(Path helper, Path envFile, Map<String, String> externalEnv)
            throws IOException, InterruptedException {
        Assumptions.assumeTrue(isBashAvailable(), "当前环境没有 bash，跳过脚本回归测试");

        ProcessBuilder builder = new ProcessBuilder(
                "bash",
                "-c",
                "source \"$1\" \"$2\"; "
                        + "printf 'DB_URL=%s\\nDB_USERNAME=%s\\nALLOWED_ORIGINS=%s\\n' "
                        + "\"${DB_URL:-}\" \"${DB_USERNAME:-}\" \"${ALLOWED_ORIGINS:-}\"",
                "bash",
                helper.toString(),
                envFile.toString()
        );
        builder.environment().putAll(externalEnv);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "脚本执行超时");
        assertEquals(0, process.exitValue(), "脚本执行失败: " + output);
        return output;
    }

    private boolean isBashAvailable() {
        try {
            Process process = new ProcessBuilder("bash", "-c", "exit 0").start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void startScriptShouldLoadEnvFileThroughTheSharedHelper() throws IOException {
        Path backendRoot = locateHelperScript().getParent().getParent().getParent();
        Path startScript = backendRoot.resolve("start.sh");
        assertTrue(Files.isRegularFile(startScript), "未找到 start.sh: " + startScript);

        String content = Files.readString(startScript, StandardCharsets.UTF_8);
        assertTrue(content.contains("scripts/lib/load-env-file.sh"),
                "start.sh 必须通过 load-env-file.sh 载入 .env，否则会重新引入覆盖外部环境变量的问题");
        assertTrue(!content.contains("set -a\n# shellcheck disable=SC1090\nsource \"$ENV_FILE\""),
                "start.sh 不得再直接 source .env（会覆盖外部显式环境变量）");
    }

    /**
     * 从当前工作目录向上查找 scripts/lib/load-env-file.sh
     */
    private Path locateHelperScript() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("scripts").resolve("lib").resolve("load-env-file.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "未找到 scripts/lib/load-env-file.sh（从 " + System.getProperty("user.dir") + " 向上查找失败）");
    }
}
