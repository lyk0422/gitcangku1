package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 主资源 schema.sql 的 H2（MODE=MySQL）可执行性校验：
 * 保证本地入口用默认 H2 内存库时能自动建表（含 COMMENT ON 语句、CLOB、AUTO_INCREMENT 与外键），
 * 不依赖外部 MySQL。数据随连接关闭（JVM 退出）丢失。
 */
class MainSchemaH2Test {

    @Test
    void mainSchemaScriptCreatesAllTablesAndEnforcesForeignKeyOnH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:main-schema-check;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));

            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));

            // 外键：未登记区域不能绑定 epoch
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO consent_grant (subject_key, purpose, epoch, status, region_code, request_id)"
                            + " VALUES ('subj-x', 'RESEARCH', 1, 'ACTIVE', 'CN', 'g-x')"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            Integer fkViolated = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-x'", Integer.class);
            assertThat(fkViolated).isEqualTo(0);

            jdbc.update("INSERT INTO residency_region (region_code, region_name, request_id) VALUES (?,?,?)",
                    "CN", "中国区", "reg-cn");
            jdbc.update("INSERT INTO consent_grant (subject_key, purpose, epoch, status, region_code, request_id)"
                    + " VALUES ('subj-1', 'RESEARCH', 1, 'ACTIVE', 'CN', 'g-1')");
            jdbc.update("INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload,"
                    + " region_code, request_id, consent_version) VALUES (?,?,?,?,?,?,?,?)",
                    "subj-1", "RESEARCH", 1, "rec-1", "payload-1", "CN", "w-1", 1);
            jdbc.update("UPDATE consent_record SET payload = NULL, erased_at = ?, erase_request_id = ?"
                    + " WHERE record_key = 'rec-1'", Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")), "e-1");

            Map<String, Object> erased = jdbc.queryForMap(
                    "SELECT payload, region_code, consent_version FROM consent_record WHERE record_key = 'rec-1'");
            assertThat(erased.get("payload")).isNull();
            assertThat(erased.get("region_code")).isEqualTo("CN");
            assertThat(((Number) erased.get("consent_version")).intValue()).isEqualTo(1);

            jdbc.update("INSERT INTO query_snapshot (snapshot_id, subject_key, purpose, requested_regions,"
                    + " params_fingerprint, object_count, response_body) VALUES (?,?,?,?,?,?,?)",
                    "q-1", "subj-1", "RESEARCH", "CN", "fp", 0, "{}");
            jdbc.update("INSERT INTO query_snapshot_item (snapshot_id, subject_key, purpose, record_key,"
                    + " epoch, region_code, consent_version, payload, ordinal) VALUES (?,?,?,?,?,?,?,?,?)",
                    "q-1", "subj-1", "RESEARCH", "rec-1", 1, "CN", 1, "payload-1", 0);

            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'",
                    String.class);
            assertThat(tables).contains("RESIDENCY_REGION", "CONSENT_GRANT", "CONSENT_RECORD",
                    "RESIDENCY_MIGRATION", "RESIDENCY_MIGRATION_ITEM", "ERASURE_REQUEST",
                    "LEGAL_HOLD", "QUERY_SNAPSHOT", "QUERY_SNAPSHOT_ITEM", "IDEMPOTENCY_REQUEST");
        }
    }
}
