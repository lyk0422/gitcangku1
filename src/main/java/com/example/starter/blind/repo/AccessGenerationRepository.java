package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 授权代次、代次角色名册与采集者范围数据访问。
 * 代次切换的原子性（旧代 SUPERSEDED、新代 ACTIVE、实验指针更新）由 RotationService 单事务保证。
 */
@Repository
public class AccessGenerationRepository {

    /** 授权代次行。 */
    public record AccessGenerationRow(
            long id,
            String experimentId,
            int generationNo,
            String status,
            long effectiveAt,
            Long supersededAt,
            String rotationKey,
            long createdAt) {
    }

    /** 代次角色名册行：roleName 为三类职责角色，grantedFields 为最小字段逗号串。 */
    public record RoleAssignmentRow(
            long generationId,
            String experimentId,
            String roleName,
            String actorId,
            String grantedFields) {
    }

    private static final RowMapper<AccessGenerationRow> GENERATION_MAPPER = (rs, n) ->
            new AccessGenerationRow(
                    rs.getLong("id"),
                    rs.getString("experiment_id"),
                    rs.getInt("generation_no"),
                    rs.getString("status"),
                    rs.getLong("effective_at"),
                    (Long) rs.getObject("superseded_at"),
                    rs.getString("rotation_key"),
                    rs.getLong("created_at"));

    private static final RowMapper<RoleAssignmentRow> ROLE_MAPPER = (rs, n) ->
            new RoleAssignmentRow(
                    rs.getLong("generation_id"),
                    rs.getString("experiment_id"),
                    rs.getString("role_name"),
                    rs.getString("actor_id"),
                    rs.getString("granted_fields"));

    private static final String GENERATION_COLUMNS =
            "id, experiment_id, generation_no, status, effective_at, superseded_at, "
                    + "rotation_key, created_at";

    private final JdbcTemplate jdbc;

    public AccessGenerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新代次并回填自增主键；新代次初始为 ACTIVE。 */
    public long insertGeneration(AccessGenerationRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO access_generation (experiment_id, generation_no, status, "
                            + "effective_at, superseded_at, rotation_key, created_at) "
                            + "VALUES (?, ?, 'ACTIVE', ?, NULL, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.experimentId());
            ps.setInt(2, row.generationNo());
            ps.setLong(3, row.effectiveAt());
            ps.setString(4, row.rotationKey());
            ps.setLong(5, row.createdAt());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 access_generation 未返回主键");
        }
        return key.longValue();
    }

    public AccessGenerationRow findById(long generationId) {
        List<AccessGenerationRow> rows = jdbc.query(
                "SELECT " + GENERATION_COLUMNS + " FROM access_generation WHERE id = ?",
                GENERATION_MAPPER, generationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定代次，串行化令牌校验与代次切换。 */
    public AccessGenerationRow lockById(long generationId) {
        List<AccessGenerationRow> rows = jdbc.query(
                "SELECT " + GENERATION_COLUMNS + " FROM access_generation WHERE id = ? FOR UPDATE",
                GENERATION_MAPPER, generationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AccessGenerationRow findActiveByExperiment(String experimentId) {
        List<AccessGenerationRow> rows = jdbc.query(
                "SELECT " + GENERATION_COLUMNS + " FROM access_generation "
                        + "WHERE experiment_id = ? AND status = 'ACTIVE'",
                GENERATION_MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 实验内下一代序号（无代次时为 1）。 */
    public int nextGenerationNo(String experimentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(generation_no), 0) FROM access_generation "
                        + "WHERE experiment_id = ?", Integer.class, experimentId);
        return (max == null ? 0 : max) + 1;
    }

    /** 原子结束旧代次：ACTIVE -> SUPERSEDED。返回受影响行数。 */
    public int supersede(long generationId, long supersededAt) {
        return jdbc.update(
                "UPDATE access_generation SET status = 'SUPERSEDED', superseded_at = ? "
                        + "WHERE id = ? AND status = 'ACTIVE'",
                supersededAt, generationId);
    }

    public void insertRoleAssignment(RoleAssignmentRow row) {
        jdbc.update("INSERT INTO role_assignment (generation_id, experiment_id, role_name, "
                        + "actor_id, granted_fields) VALUES (?, ?, ?, ?, ?)",
                row.generationId(), row.experimentId(), row.roleName(),
                row.actorId(), row.grantedFields());
    }

    public List<RoleAssignmentRow> findRoleAssignments(long generationId) {
        return jdbc.query(
                "SELECT generation_id, experiment_id, role_name, actor_id, granted_fields "
                        + "FROM role_assignment WHERE generation_id = ? "
                        + "ORDER BY role_name, actor_id",
                ROLE_MAPPER, generationId);
    }

    public void insertCollectorScope(long generationId, String experimentId,
                                     String actorId, String participantId) {
        jdbc.update("INSERT INTO collector_scope (generation_id, experiment_id, actor_id, "
                        + "participant_id) VALUES (?, ?, ?, ?)",
                generationId, experimentId, actorId, participantId);
    }

    public List<String> findCollectorScope(long generationId, String actorId) {
        return jdbc.queryForList(
                "SELECT participant_id FROM collector_scope "
                        + "WHERE generation_id = ? AND actor_id = ? ORDER BY participant_id",
                String.class, generationId, actorId);
    }

    /** 该代次内某采集者是否被授予某受试者范围。 */
    public boolean scopeContains(long generationId, String actorId, String participantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collector_scope WHERE generation_id = ? AND actor_id = ? "
                        + "AND participant_id = ?",
                Integer.class, generationId, actorId, participantId);
        return count != null && count > 0;
    }
}
