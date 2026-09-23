package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 职责轮换单数据访问；仅保存成功激活的单，失败整单回滚不写表、不占 rotationKey。
 */
@Repository
public class RotationOrderRepository {

    /** 轮换单行；名册与冲突依据以规范化 JSON 串保存。 */
    public record RotationOrderRow(
            String rotationKey,
            String experimentId,
            int expectedExperimentVersion,
            int newExperimentVersion,
            long generationId,
            Long beforeGenerationId,
            String requestId,
            String beforeRoster,
            String targetRoster,
            String conflictEvidence,
            String status,
            String createdBy,
            long createdAt,
            Long activatedAt) {
    }

    private static final RowMapper<RotationOrderRow> MAPPER = (rs, n) -> new RotationOrderRow(
            rs.getString("rotation_key"),
            rs.getString("experiment_id"),
            rs.getInt("expected_experiment_version"),
            rs.getInt("new_experiment_version"),
            rs.getLong("generation_id"),
            (Long) rs.getObject("before_generation_id"),
            rs.getString("request_id"),
            rs.getString("before_roster"),
            rs.getString("target_roster"),
            rs.getString("conflict_evidence"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getLong("created_at"),
            (Long) rs.getObject("activated_at"));

    private static final String COLUMNS =
            "rotation_key, experiment_id, expected_experiment_version, new_experiment_version, "
                    + "generation_id, before_generation_id, request_id, before_roster, target_roster, "
                    + "conflict_evidence, status, created_by, created_at, activated_at";

    private final JdbcTemplate jdbc;

    public RotationOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RotationOrderRow row) {
        jdbc.update("INSERT INTO rotation_order (rotation_key, experiment_id, "
                        + "expected_experiment_version, new_experiment_version, generation_id, "
                        + "before_generation_id, request_id, before_roster, target_roster, "
                        + "conflict_evidence, status, created_by, created_at, activated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVATED', ?, ?, ?)",
                row.rotationKey(), row.experimentId(), row.expectedExperimentVersion(),
                row.newExperimentVersion(), row.generationId(), row.beforeGenerationId(),
                row.requestId(), row.beforeRoster(), row.targetRoster(), row.conflictEvidence(),
                row.createdBy(), row.createdAt(), row.activatedAt());
    }

    public RotationOrderRow findByKey(String rotationKey) {
        List<RotationOrderRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM rotation_order WHERE rotation_key = ?",
                MAPPER, rotationKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RotationOrderRow findByGenerationId(long generationId) {
        List<RotationOrderRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM rotation_order WHERE generation_id = ?",
                MAPPER, generationId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
