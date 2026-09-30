package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 事件升级阶梯当前状态仓储。进入阶梯的事件至多一行（uk_ladder_state_incident 唯一约束）。
 * 所有写路径均先锁定 incidents 事件行，同事件并发分数更新与降级按事务提交顺序串行生效。
 */
@Repository
public class LadderStateRepository {

    private final JdbcTemplate jdbc;

    public LadderStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LadderState> MAPPER = (rs, n) -> map(rs);

    private static LadderState map(ResultSet rs) throws SQLException {
        Timestamp promoted = rs.getTimestamp("last_promoted_at");
        return new LadderState(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("current_level"),
                rs.getInt("last_score"), rs.getLong("threshold_version_id"),
                promoted == null ? null : promoted.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入事件首条阶梯状态（首次进入阶梯），返回生成主键。
     * incident_id 唯一约束兜底并发首次插入。
     */
    public long insert(LadderState state) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_ladder_states (incident_id, current_level, last_score,"
                            + " threshold_version_id, last_promoted_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, state.incidentId());
            ps.setInt(2, state.currentLevel());
            ps.setInt(3, state.lastScore());
            ps.setLong(4, state.thresholdVersionId());
            ps.setTimestamp(5, state.lastPromotedAt() == null
                    ? null : Timestamp.from(state.lastPromotedAt()));
            ps.setTimestamp(6, Timestamp.from(state.createdAt()));
            ps.setTimestamp(7, Timestamp.from(state.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件阶梯状态（不加锁），用于只读场景；未进入阶梯返回空。
     */
    public Optional<LadderState> findByIncident(long incidentId) {
        List<LadderState> rows = jdbc.query(
                "SELECT * FROM incident_ladder_states WHERE incident_id = ?", MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 分数更新后写回最近分数与最近使用的阈值版本（不改变等级与冷却起点）。
     */
    public void updateScore(long incidentId, int score, long thresholdVersionId, Instant updatedAt) {
        jdbc.update("UPDATE incident_ladder_states SET last_score = ?, threshold_version_id = ?,"
                        + " updated_at = ? WHERE incident_id = ?",
                score, thresholdVersionId, Timestamp.from(updatedAt), incidentId);
    }

    /**
     * 自动升级：提高当前等级并刷新冷却起点为最近升级时刻。
     */
    public void updateLevelAndPromotion(long incidentId, int level, int score, long thresholdVersionId,
                                        Instant promotedAt, Instant updatedAt) {
        jdbc.update("UPDATE incident_ladder_states SET current_level = ?, last_score = ?,"
                        + " threshold_version_id = ?, last_promoted_at = ?, updated_at = ?"
                        + " WHERE incident_id = ?",
                level, score, thresholdVersionId, Timestamp.from(promotedAt),
                Timestamp.from(updatedAt), incidentId);
    }

    /**
     * 人工降级的原子条件更新：仅当事件当前等级仍为 expectedLevel 且事件未关闭时降一级。
     * 并发降级或期间关闭会使更新行数为 0，保证同一事件并发降级最多一次生效。
     * 返回受影响行数（0 或 1）。
     */
    public int demoteIfSameLevelAndOpen(long incidentId, int expectedLevel, Instant updatedAt) {
        return jdbc.update("UPDATE incident_ladder_states s SET s.current_level = s.current_level - 1,"
                        + " s.updated_at = ? WHERE s.incident_id = ? AND s.current_level = ?"
                        + " AND EXISTS (SELECT 1 FROM incidents i WHERE i.id = s.incident_id"
                        + " AND i.status <> 'CLOSED')",
                Timestamp.from(updatedAt), incidentId, expectedLevel);
    }
}
