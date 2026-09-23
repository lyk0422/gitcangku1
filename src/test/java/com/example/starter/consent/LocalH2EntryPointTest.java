package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 默认本地启动入口验证：直接使用主 application.yaml 的默认 H2（MODE=MySQL）连接串，
 * 执行生产 schema.sql 并验证唯一约束与批量查询依赖的 SELECT … FOR UPDATE 行锁可用。
 * 不启动 Web 服务；用例结束后 SHUTDOWN 释放命名内存库。
 */
class LocalH2EntryPointTest {

    private static final String DEFAULT_URL =
            "jdbc:h2:mem:local_entry_probe;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void productionSchemaAndForUpdateWorkOnDefaultH2Url() throws Exception {
        try (Connection connection = DriverManager.getConnection(DEFAULT_URL, "sa", "")) {
            String ddl = Files.readString(Path.of("src/main/resources/schema.sql"), StandardCharsets.UTF_8);
            List<String> statements = splitStatements(ddl);
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }

                statement.execute("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES ('subj-a', 'RESEARCH', 1, 'ACTIVE', 'g-1')");
                statement.execute("INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)"
                        + " VALUES ('subj-a', 'RESEARCH', 1, 'rec-1', 'payload-a1', 'w-1')");

                // 批量查询的代次裁决依赖 FOR UPDATE 当前读
                try (ResultSet rs = statement.executeQuery(
                        "SELECT epoch, status FROM consent_grant"
                                + " WHERE subject_key = 'subj-a' AND purpose = 'RESEARCH' AND epoch = 1 FOR UPDATE")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("epoch")).isEqualTo(1);
                    assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                }
            }

            // 主键唯一约束真实存在：重复授权代次必须被拒绝
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES ('subj-a', 'RESEARCH', 1, 'ACTIVE', 'g-dup')");
                throw new AssertionError("重复授权代次应违反唯一约束");
            } catch (java.sql.SQLException expected) {
                // H2 唯一约束冲突 SQLState 为 23505（类 23：完整性约束违反）
                assertThat(expected.getSQLState()).startsWith("23");
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    private List<String> splitStatements(String ddl) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : ddl.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            current.append(line).append(' ');
            if (line.endsWith(";")) {
                String sql = current.toString().strip();
                statements.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        return statements;
    }
}
