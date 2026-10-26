import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 最小化 JDBC SQL 执行器：供本地开发在缺少 psql 时执行种子脚本。
 *
 * 用法：
 *   java -cp <postgresql-jdbc.jar>:. Exec <sql-file> [jdbc-url] [user] [password]
 *
 * 语义对齐 psql --file：
 *   - 逐条语句执行（以分号切分，忽略注释与字符串内的分号）
 *   - 忽略 psql 元命令（\i、\set、\c 等）——调用方需先完成 \i 展开
 *   - 遇错立即退出并打印语句序号
 */
public final class Exec {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Exec <sql-file> [jdbc-url] [user] [password]");
            System.exit(2);
        }

        Path sqlFile = Path.of(args[0]);
        String jdbcUrl = args.length > 1 ? args[1] : System.getenv("DB_URL");
        String user = args.length > 2 ? args[2] : System.getenv("DB_USERNAME");
        String password = args.length > 3 ? args[3] : System.getenv("DB_PASSWORD");

        if (jdbcUrl == null || user == null || password == null) {
            System.err.println("Missing JDBC url / user / password");
            System.exit(2);
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            System.err.println("Only PostgreSQL-wire JDBC URLs are supported: " + jdbcUrl);
            System.exit(2);
        }

        String content = Files.readString(sqlFile, StandardCharsets.UTF_8);
        String[] statements = splitStatements(content);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
             Statement statement = connection.createStatement()) {
            int executed = 0;
            for (String sql : statements) {
                try {
                    statement.execute(sql);
                    executed++;
                } catch (SQLException e) {
                    System.err.println("[exec] statement #" + (executed + 1) + " failed: " + e.getMessage());
                    System.err.println("[exec] sql head: " + sql.substring(0, Math.min(160, sql.length())).replaceAll("\\s+", " "));
                    throw e;
                }
            }
            System.out.println("[exec] executed " + executed + " statements from " + sqlFile.getFileName());
        }
    }

    /** 按分号切分语句；跳过注释行与 psql 元命令；不切分字符串字面量内的分号。 */
    private static String[] splitStatements(String content) {
        java.util.List<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        // PL/pgSQL dollar-quoting：$tag$ ... $tag$ 内部的分号不是语句分隔符
        String dollarTag = null;
        String[] lines = content.split("\n", -1);

        for (String line : lines) {
            String trimmed = line.trim();
            // 整行 psql 元命令（\i / \set / \c / \timing 等）直接跳过
            if (!inSingle && !inDouble && dollarTag == null && trimmed.startsWith("\\")) {
                continue;
            }
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);

                // 行注释：从 -- 到行尾整段跳过（注释里的分号不是语句分隔符）
                if (!inSingle && !inDouble && dollarTag == null && ch == '-' && i + 1 < line.length()
                        && line.charAt(i + 1) == '-') {
                    break;
                }

                if (dollarTag != null) {
                    // dollar-quoted 块内部：只在遇到闭合标签时退出
                    if (ch == '$' && matchesDollarTag(line, i, dollarTag)) {
                        current.append(line, i, i + dollarTag.length());
                        i += dollarTag.length() - 1;
                        dollarTag = null;
                        continue;
                    }
                    current.append(ch);
                    continue;
                }

                if (ch == '$' && !inSingle && !inDouble) {
                    String tag = readDollarTag(line, i);
                    if (tag != null) {
                        dollarTag = tag;
                        current.append(line, i, i + tag.length());
                        i += tag.length() - 1;
                        continue;
                    }
                }

                if (ch == '\'' && !inDouble) {
                    inSingle = !inSingle;
                } else if (ch == '"' && !inSingle) {
                    inDouble = !inDouble;
                }
                if (ch == ';' && !inSingle && !inDouble) {
                    addIfMeaningful(statements, current);
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
            }
            current.append('\n');
        }
        addIfMeaningful(statements, current);
        return statements.toArray(new String[0]);
    }

    /** 读取当前位置开始的 dollar-quote 标签（如 $$ 或 $body$）；不合法返回 null。 */
    private static String readDollarTag(String line, int index) {
        if (index + 1 >= line.length()) {
            return null;
        }
        int end = index + 1;
        if (line.charAt(end) == '$') {
            return "$$";
        }
        while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
            end++;
        }
        if (end < line.length() && line.charAt(end) == '$' && end > index + 1) {
            return line.substring(index, end + 1);
        }
        return null;
    }

    /** 判断 line 的 index 处是否是给定 dollar 标签的闭合。 */
    private static boolean matchesDollarTag(String line, int index, String tag) {
        return line.startsWith(tag, index);
    }

    private static void addIfMeaningful(java.util.List<String> statements, StringBuilder buffer) {
        String raw = buffer.toString();
        if (raw.trim().isEmpty()) {
            return;
        }
        // 逐行剥离行注释（-- ...），并跳过纯注释行。
        // 注意：PostgreSQL 的 -- 注释必须独占一行或跟在语句后；种子脚本里
        // 存在「-- 注释行」与后续内容黏连的情况，故整行剥离最稳妥。
        StringBuilder cleaned = new StringBuilder();
        boolean hasCode = false;
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            if (!trimmed.isEmpty()) {
                hasCode = true;
            }
            cleaned.append(line).append('\n');
        }
        if (hasCode) {
            statements.add(cleaned.toString().trim());
        }
    }
}
