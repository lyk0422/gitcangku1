package com.example.starter.salinity.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在真实 H2（MODE=MySQL）上直接验证唯一约束：采样编号、requestId、采纳槽位、封存槽位。
 * 这些结论不能用 mock/Map 替代。
 */
@SpringBootTest
class SalinityConstraintH2Test {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Timestamp now() {
        return Timestamp.from(Instant.parse("2026-09-30T00:00:00Z"));
    }

    private String seedVoyage() {
        String code = "CON-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO voyages (code, status, sealed_at_utc, created_at_utc) VALUES (?, 'OPEN', NULL, ?)",
                code, now());
        return code;
    }

    private void insertSample(String voyage, String no, String req, boolean adopted, int version) {
        jdbcTemplate.update("""
                        INSERT INTO samples
                            (voyage_code, station, depth_m, salinity_psu, collected_at_utc,
                             sample_no, request_id, adopted, version_no, created_at_utc)
                        VALUES (?, 'S', 0, 35.000, ?, ?, ?, ?, ?, ?)
                        """,
                voyage, now(), no, req, adopted, version, now());
    }

    @Test
    @DisplayName("采样编号唯一约束实测：重复编号插入抛 DuplicateKeyException")
    void sampleNoUnique() {
        String voyage = seedVoyage();
        insertSample(voyage, "NO-U1", "REQ-U1", false, 1);
        assertThatThrownBy(() -> insertSample(voyage, "NO-U1", "REQ-U2", false, 2))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("requestId 唯一约束实测")
    void requestIdUnique() {
        String voyage = seedVoyage();
        insertSample(voyage, "NO-A", "REQ-SHARED", false, 1);
        assertThatThrownBy(() -> insertSample(voyage, "NO-B", "REQ-SHARED", false, 2))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("采纳槽位唯一约束实测：同一航次/站位/深度只能有一条采纳版本，未采纳多条不冲突")
    void adoptedSlotUnique() {
        String voyage = seedVoyage();
        insertSample(voyage, "NO-1", "REQ-1", false, 1);
        insertSample(voyage, "NO-2", "REQ-2", false, 2);
        insertSample(voyage, "NO-3", "REQ-3", true, 3);
        assertThatThrownBy(() -> insertSample(voyage, "NO-4", "REQ-4", true, 4))
                .isInstanceOf(DuplicateKeyException.class);

        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM samples
                        WHERE voyage_code = ? AND station = 'S' AND depth_m = 0 AND adopted = TRUE
                        """,
                Integer.class, voyage);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("封存剖面槽位唯一约束实测：同航次站位深度不能固化两条")
    void sealedPositionUnique() {
        String voyage = seedVoyage();
        jdbcTemplate.update("""
                        INSERT INTO sealed_profiles
                            (voyage_code, station, depth_m, sample_id, sample_no,
                             salinity_psu, collected_at_utc, sealed_at_utc)
                        VALUES (?, 'S', 0, 1, 'NO-1', 35.000, ?, ?)
                        """,
                voyage, now(), now());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO sealed_profiles
                            (voyage_code, station, depth_m, sample_id, sample_no,
                             salinity_psu, collected_at_utc, sealed_at_utc)
                        VALUES (?, 'S', 0, 2, 'NO-2', 36.000, ?, ?)
                        """,
                voyage, now(), now()))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
