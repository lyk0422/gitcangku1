package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 校验主资源目录下的 schema.sql（含中文 COMMENT）可在嵌入式 H2 MySQL 兼容模式下直接执行，
 * 且 delegationKey 全局唯一、同起止点版本唯一等真实约束生效。
 *
 * <p>该测试不启动 Web 服务，使用随用例关闭的独立连接，保证本地“零外部依赖自动建表”入口可用。
 */
class MainSchemaH2Test {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    @Test
    void mainSchemaExecutesOnH2AndEnforcesUniqueConstraints() throws Exception {
        String url = "jdbc:h2:mem:main-schema-" + DB_SEQ.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("src", "main", "resources", "schema.sql")));

            try (Statement st = connection.createStatement()) {
                st.execute("INSERT INTO consent_grant (subject_key, purpose, epoch, status, expires_at, request_id)"
                        + " VALUES ('s1', 'RESEARCH', 1, 'ACTIVE', '2099-01-01 00:00:00', 'g1')");
                st.execute("INSERT INTO consent_delegation (delegation_key, subject_key, purpose, epoch,"
                        + " from_key, to_key, version, status, expires_at, request_id) VALUES"
                        + " ('dk1', 's1', 'RESEARCH', 1, 's1', 'p1', 1, 'ACTIVE', '2099-01-01 00:00:00', 'd1')");

                // delegationKey 全局唯一
                assertThatThrownBy(() -> st.execute("INSERT INTO consent_delegation (delegation_key, subject_key,"
                        + " purpose, epoch, from_key, to_key, version, status, expires_at, request_id) VALUES"
                        + " ('dk1', 's1', 'RESEARCH', 1, 's1', 'p2', 1, 'ACTIVE', '2099-01-01 00:00:00', 'd2')"))
                        .hasMessageContaining("Unique index or primary key violation")
                        .hasMessageContaining("'dk1'");

                // 同起止点可续建为下一版本
                st.execute("INSERT INTO consent_delegation (delegation_key, subject_key, purpose, epoch,"
                        + " from_key, to_key, version, status, expires_at, request_id) VALUES"
                        + " ('dk2', 's1', 'RESEARCH', 1, 's1', 'p1', 2, 'ACTIVE', '2099-01-01 00:00:00', 'd3')");

                // 同起止点同版本冲突
                assertThatThrownBy(() -> st.execute("INSERT INTO consent_delegation (delegation_key, subject_key,"
                        + " purpose, epoch, from_key, to_key, version, status, expires_at, request_id) VALUES"
                        + " ('dk3', 's1', 'RESEARCH', 1, 's1', 'p1', 1, 'ACTIVE', '2099-01-01 00:00:00', 'd4')"))
                        .hasMessageContaining("Unique index or primary key violation");

                // 中文 COMMENT 已落库
                try (ResultSet rs = st.executeQuery(
                        "SELECT REMARKS FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'consent_delegation'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).contains("委托边");
                }
            }
        }
    }
}
