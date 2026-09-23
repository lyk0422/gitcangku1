package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 授权代次、最小知情授权与职责轮换单数据访问。
 * 同一实验至多一个 ACTIVE 代次由 uq_generation_active 唯一索引兜底。
 */
@Repository
public class RotationRepository {

    /** 授权代次行。status 取值 ACTIVE/ENDED；时间均为 Unix 毫秒 UTC。 */
    public record GenerationRow(
            long id,
            String experimentId,
            int generationNo,
            String rotationKey,
            String status,
            long issuedAt,
            long effectiveAt,
            Long endedAt) {
    }

    /** 最小知情授权行。visibleFields 为该角色所需最小字段清单（逗号分隔）。 */
    public record GrantRow(
            long id,
            long generationId,
            String experimentId,
            String actorId,
            String roleType,
            String visibleFields,
            long createdAt) {
    }

    /** 职责轮换单行；仅激活成功的轮换落库。 */
    public record RotationRow(
            String rotationKey,
            String experimentId,
            String requestId,
            String actorId,
            int expectedExperimentVersion,
            int beforeGenerationNo,
            int afterGenerationNo,
            String beforeRosterJson,
            String afterRosterJson,
            String knowledgeBasisJson,
            long effectiveAt,
            long activatedAt) {
    }

    private static final RowMapper<GenerationRow> GENERATION_MAPPER = (rs, n) -> new GenerationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getInt("generation_no"),
            rs.getString("rotation_key"),
            rs.getString("status"),
            rs.getLong("issued_at"),
            rs.getLong("effective_at"),
            (Long) rs.getObject("ended_at"));

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, n) -> new GrantRow(
            rs.getLong("id"),
            rs.getLong("generation_id"),
            rs.getString("experiment_id"),
            rs.getString("actor_id"),
            rs.getString("role_type"),
            rs.getString("visible_fields"),
            rs.getLong("created_at"));

    private static final RowMapper<RotationRow> ROTATION_MAPPER = (rs, n) -> new RotationRow(
            rs.getString("rotation_key"),
            rs.getString("experiment_id"),
            rs.getString("request_id"),
            rs.getString("actor_id"),
            rs.getInt("expected_experiment_version"),
            rs.getInt("before_generation_no"),
            rs.getInt("after_generation_no"),
            rs.getString("before_roster_json"),
            rs.getString("after_roster_json"),
            rs.getString("knowledge_basis_json"),
            rs.getLong("effective_at"),
            rs.getLong("activated_at"));

    private static final String GENERATION_COLUMNS =
            "id, experiment_id, generation_no, rotation_key, status, issued_at, effective_at, ended_at";

    private static final String ROTATION_COLUMNS =
            "rotation_key, experiment_id, request_id, actor_id, expected_experiment_version, "
                    + "before_generation_no, after_generation_no, before_roster_json, "
                    + "after_roster_json, knowledge_basis_json, effective_at, activated_at";

    private final JdbcTemplate jdbc;

    public RotationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 授权代次 ----------------

    /**
     * 插入新代次；ACTIVE 代次须携带 active_experiment_key 占位。
     *
     * @return 自增主键
     */
    public long insertGeneration(String experimentId, int generationNo, String rotationKey,
                                 String status, long issuedAt, long effectiveAt) {
        jdbc.update("INSERT INTO access_generation ("
                        + "experiment_id, generation_no, rotation_key, status, issued_at, "
                        + "effective_at, ended_at, active_experiment_key"
                        + ") VALUES (?, ?, ?, ?, ?, ?, NULL, ?)",
                experimentId, generationNo, rotationKey, status, issuedAt, effectiveAt,
                "ACTIVE".equals(status) ? experimentId : null);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM access_generation "
                + "WHERE experiment_id = ? AND generation_no = ?", Long.class, experimentId, generationNo);
        if (id == null) {
            throw new IllegalStateException("代次插入后未找到主键");
        }
        return id;
    }

    /** 查询实验当前 ACTIVE 代次；不存在返回 null。 */
    public GenerationRow findActiveGeneration(String experimentId) {
        List<GenerationRow> rows = jdbc.query(
                "SELECT " + GENERATION_COLUMNS + " FROM access_generation "
                        + "WHERE experiment_id = ? AND status = 'ACTIVE'",
                GENERATION_MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 原子结束实验当前 ACTIVE 代次并释放活动占位。
     *
     * @return 受影响行数；0 表示无活动代次（并发下由调用方判冲突）
     */
    public int endActiveGeneration(String experimentId, long endedAt) {
        return jdbc.update("UPDATE access_generation SET status = 'ENDED', ended_at = ?, "
                        + "active_experiment_key = NULL "
                        + "WHERE experiment_id = ? AND status = 'ACTIVE'",
                endedAt, experimentId);
    }

    // ---------------- 最小知情授权 ----------------

    public void insertGrant(long generationId, String experimentId, String actorId,
                            String roleType, String visibleFields, long createdAt) {
        jdbc.update("INSERT INTO access_grant ("
                        + "generation_id, experiment_id, actor_id, role_type, visible_fields, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                generationId, experimentId, actorId, roleType, visibleFields, createdAt);
    }

    /** 查询某代次下某人的某类角色授权；不存在返回 null。 */
    public GrantRow findGrant(long generationId, String actorId, String roleType) {
        List<GrantRow> rows = jdbc.query(
                "SELECT id, generation_id, experiment_id, actor_id, role_type, visible_fields, created_at "
                        + "FROM access_grant "
                        + "WHERE generation_id = ? AND actor_id = ? AND role_type = ?",
                GRANT_MAPPER, generationId, actorId, roleType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某代次全部授权（查询轮换结果时还原名册）。 */
    public List<GrantRow> findGrantsByGeneration(long generationId) {
        return jdbc.query(
                "SELECT id, generation_id, experiment_id, actor_id, role_type, visible_fields, created_at "
                        + "FROM access_grant WHERE generation_id = ? ORDER BY role_type, actor_id",
                GRANT_MAPPER, generationId);
    }

    // ---------------- 职责轮换单 ----------------

    /**
     * 插入轮换单；rotation_key 主键唯一，重复时抛出 DuplicateKeyException。
     */
    public void insertRotation(RotationRow row) {
        jdbc.update("INSERT INTO role_rotation (" + ROTATION_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.rotationKey(), row.experimentId(), row.requestId(), row.actorId(),
                row.expectedExperimentVersion(), row.beforeGenerationNo(), row.afterGenerationNo(),
                row.beforeRosterJson(), row.afterRosterJson(), row.knowledgeBasisJson(),
                row.effectiveAt(), row.activatedAt());
    }

    public RotationRow findRotation(String experimentId, String rotationKey) {
        List<RotationRow> rows = jdbc.query(
                "SELECT " + ROTATION_COLUMNS + " FROM role_rotation "
                        + "WHERE experiment_id = ? AND rotation_key = ?",
                ROTATION_MAPPER, experimentId, rotationKey);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
