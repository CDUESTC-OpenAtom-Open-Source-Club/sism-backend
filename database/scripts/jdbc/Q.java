import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;

/** 只读查询器: java -cp pg.jar:. Q "<sql>" — 打印结果集为 TSV。 */
public final class Q {
    public static void main(String[] args) throws Exception {
        String sql = args.length > 0 ? args[0] : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String jdbcUrl = System.getenv("DB_URL");
        String user = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder head = new StringBuilder();
            for (int i = 1; i <= n; i++) head.append(md.getColumnLabel(i)).append(i < n ? "\t" : "");
            System.out.println(head);
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) row.append(rs.getString(i)).append(i < n ? "\t" : "");
                System.out.println(row);
            }
        }
    }
}
