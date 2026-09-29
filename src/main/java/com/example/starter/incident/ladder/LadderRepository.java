package com.example.starter.incident.ladder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 事件升级阶梯当前态与等级历史的 JDBC 仓储。
 * 当前态每事件至多一行（主键即 incident_id）；历史表只追加、不更新不删除。
 * 所有写路径均先锁定相关事件/版本行，保证同事件并发写按事务提交顺序串行生效。
 */
@Repository
public class LadderRepository {

    private final JdbcTemplate jdbc;

    public LadderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentLadder> STATE_MAPPER = (rs, n) -> mapState(rs);

    private static final RowMapper<LadderHistoryEntry> HISTORY_MAPPER = (rs, n) -> mapHistory(rs);

    private static IncidentLadder mapState(ResultSet rs) throws SQLException {
        return new IncidentLadder(
                rs.getLong("incident_id"), rs.getLong("score"), rs.getInt("current_level"),
                rs.getLong("threshold_version_id"),
                rs.getTimestamp("last_escalated_at").toInstant(),
                rs.getString("last_escalated_by"),
                rs.getTimestamp("entered_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"));
    }

    private static LadderHistoryEntry mapHistory(ResultSet rs) throws SQLException {
        Integer fromLevel = rs.getObject("from_level", Integer.class);
        Long fromScore = rs.getObject("from_score", Long.class);
        return new LadderHistoryEntry(
                rs.getLong("id"), rs.getLong("incident_id"),
                LadderChangeType.valueOf(rs.getString("change_type")),
                fromLevel, rs.getInt("to_level"), fromScore, rs.getLong("to_score"),
                rs.getLong("threshold_version_id"), rs.getString("threshold_version_key"),
                rs.getString("actor"), rs.getTimestamp("cooldown_start_at").toInstant(),
                rs.getString("command_key"), rs.getTimestamp("occurred_at").toInstant());
    }

    /**
     * 按事件 id 加锁读取当前态（SELECT ... FOR UPDATE），无行表示分数缺失。
     */
    public Optional<IncidentLadder> lockByIncidentId(long incidentId) {
        List<IncidentLadder> rows = jdbc.query(
                "SELECT * FROM incident_ladder_state WHERE incident_id = ? FOR UPDATE",
                STATE_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 按事件 id 普通读取当前态，用于只读明细查询。
     */
    public Optional<IncidentLadder> findByIncidentId(long incidentId) {
        List<IncidentLadder> rows = jdbc.query(
                "SELECT * FROM incident_ladder_state WHERE incident_id = ?",
                STATE_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部已登记事件的当前态，按事件 id 升序稳定返回（诊断接口再做业务排序）。
     */
    public List<IncidentLadder> listAll() {
        return jdbc.query("SELECT * FROM incident_ladder_state ORDER BY incident_id", STATE_MAPPER);
    }

    /**
     * 首次登记插入当前态行；并发重复插入由主键约束拒绝。
     */
    public void insert(IncidentLadder state) {
        jdbc.update("INSERT INTO incident_ladder_state (incident_id, score, current_level,"
                        + " threshold_version_id, last_escalated_at, last_escalated_by, entered_at,"
                        + " updated_at, updated_by) VALUES (?,?,?,?,?,?,?,?,?)",
                state.incidentId(), state.score(), state.currentLevel(), state.thresholdVersionId(),
                Timestamp.from(state.lastEscalatedAt()), state.lastEscalatedBy(),
                Timestamp.from(state.enteredAt()), Timestamp.from(state.updatedAt()),
                state.updatedBy());
    }

    /**
     * 分数更新后整行改写当前态（调用方已持有该行锁）。
     */
    public void update(IncidentLadder state) {
        int updated = jdbc.update("UPDATE incident_ladder_state SET score = ?, current_level = ?,"
                        + " threshold_version_id = ?, last_escalated_at = ?, last_escalated_by = ?,"
                        + " updated_at = ?, updated_by = ? WHERE incident_id = ?",
                state.score(), state.currentLevel(), state.thresholdVersionId(),
                Timestamp.from(state.lastEscalatedAt()), state.lastEscalatedBy(),
                Timestamp.from(state.updatedAt()), state.updatedBy(), state.incidentId());
        if (updated == 0) {
            throw new DuplicateKeyException("incident_ladder_state 行不存在: " + state.incidentId());
        }
    }

    /**
     * 条件降级：仅当当前等级仍等于 expectedLevel 时降一级（newLevel = expectedLevel - 1），
     * 分数与阈值版本不变，只刷新 updated_at/by；冷却起点不刷新。
     * 返回更新行数：并发降级竞争下只有一个事务返回 1。
     */
    public int demoteIfLevel(long incidentId, int expectedLevel, int newLevel, String actor,
                             Instant at) {
        return jdbc.update("UPDATE incident_ladder_state SET current_level = ?, updated_at = ?,"
                        + " updated_by = ? WHERE incident_id = ? AND current_level = ?",
                newLevel, Timestamp.from(at), actor, incidentId, expectedLevel);
    }

    /**
     * 追加一条不可变等级历史行，返回生成主键。
     */
    public long insertHistory(LadderHistoryEntry entry) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_ladder_history (incident_id, change_type, from_level,"
                            + " to_level, from_score, to_score, threshold_version_id,"
                            + " threshold_version_key, actor, cooldown_start_at, command_key,"
                            + " occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, entry.incidentId());
            ps.setString(2, entry.changeType().name());
            if (entry.fromLevel() == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, entry.fromLevel());
            }
            ps.setInt(4, entry.toLevel());
            if (entry.fromScore() == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, entry.fromScore());
            }
            ps.setLong(6, entry.toScore());
            ps.setLong(7, entry.thresholdVersionId());
            ps.setString(8, entry.thresholdVersionKey());
            ps.setString(9, entry.actor());
            ps.setTimestamp(10, Timestamp.from(entry.cooldownStartAt()));
            ps.setString(11, entry.commandKey());
            ps.setTimestamp(12, Timestamp.from(entry.occurredAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件完整等级历史，按落库顺序（id 升序）稳定返回。
     */
    public List<LadderHistoryEntry> listHistory(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_ladder_history WHERE incident_id = ? ORDER BY id",
                HISTORY_MAPPER, incidentId);
    }
}
