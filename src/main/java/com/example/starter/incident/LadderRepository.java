package com.example.starter.incident;

import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 升级阶梯阈值配置、事件阶梯状态与等级变更历史的 JDBC 仓储。
 * 配置按版本追加（旧版本保留，不回写）；状态行每事件至多一条（uk_ladder_state_incident）；
 * 历史行只插入不更新，字段均为变更时快照。所有调用均处于先锁定事件行的写事务内。
 */
@Repository
public class LadderRepository {

    private final JdbcTemplate jdbc;

    public LadderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LadderConfig> CONFIG_MAPPER = (rs, n) -> new LadderConfig(
            rs.getLong("id"), rs.getInt("config_version"),
            List.of(rs.getInt("level1_threshold"), rs.getInt("level2_threshold"),
                    rs.getInt("level3_threshold"), rs.getInt("level4_threshold"),
                    rs.getInt("level5_threshold")),
            rs.getInt("cooldown_minutes"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<LadderState> STATE_MAPPER = (rs, n) -> new LadderState(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("risk_score"),
            rs.getInt("current_level"), rs.getInt("config_version"),
            rs.getTimestamp("cooldown_start_at").toInstant(), rs.getString("last_operator"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<LadderHistoryEntry> HISTORY_MAPPER = (rs, n) -> new LadderHistoryEntry(
            rs.getLong("id"), rs.getLong("incident_id"),
            LadderChangeType.valueOf(rs.getString("change_type")),
            rs.getInt("from_level"), rs.getInt("to_level"), rs.getInt("risk_score"),
            rs.getInt("config_version"), rs.getString("actor"),
            rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("created_at").toInstant());

    /**
     * 追加新配置版本，返回生成主键；config_version 唯一约束兜底并发重复版本。
     */
    public long insertConfig(LadderConfig config) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_ladder_configs (config_version, level1_threshold,"
                            + " level2_threshold, level3_threshold, level4_threshold, level5_threshold,"
                            + " cooldown_minutes, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, config.configVersion());
            ps.setInt(2, config.thresholds().get(0));
            ps.setInt(3, config.thresholds().get(1));
            ps.setInt(4, config.thresholds().get(2));
            ps.setInt(5, config.thresholds().get(3));
            ps.setInt(6, config.thresholds().get(4));
            ps.setInt(7, config.cooldownMinutes());
            ps.setString(8, config.createdBy());
            ps.setTimestamp(9, Timestamp.from(config.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询最新（版本号最大）配置；尚无配置返回空。
     */
    public Optional<LadderConfig> findLatestConfig() {
        List<LadderConfig> rows = jdbc.query(
                "SELECT * FROM incident_ladder_configs ORDER BY config_version DESC LIMIT 1",
                CONFIG_MAPPER);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件的阶梯状态行（不存在返回空）。
     */
    public Optional<LadderState> findState(long incidentId) {
        List<LadderState> rows = jdbc.query(
                "SELECT * FROM incident_ladder_states WHERE incident_id = ?", STATE_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 插入事件阶梯状态行（每事件至多一条，唯一约束兜底并发重复插入）。
     */
    public void insertState(LadderState state) {
        jdbc.update("INSERT INTO incident_ladder_states (incident_id, risk_score, current_level,"
                        + " config_version, cooldown_start_at, last_operator, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                state.incidentId(), state.riskScore(), state.currentLevel(), state.configVersion(),
                Timestamp.from(state.cooldownStartAt()), state.lastOperator(),
                Timestamp.from(state.createdAt()), Timestamp.from(state.updatedAt()));
    }

    /**
     * 按 incident_id 更新事件阶梯状态行（分数、等级、配置版本、冷却起点、操作人）。
     */
    public void updateState(LadderState state) {
        jdbc.update("UPDATE incident_ladder_states SET risk_score = ?, current_level = ?,"
                        + " config_version = ?, cooldown_start_at = ?, last_operator = ?, updated_at = ?"
                        + " WHERE incident_id = ?",
                state.riskScore(), state.currentLevel(), state.configVersion(),
                Timestamp.from(state.cooldownStartAt()), state.lastOperator(),
                Timestamp.from(state.updatedAt()), state.incidentId());
    }

    /**
     * 追加等级变更历史（只插入，不回写）。
     */
    public void insertHistory(LadderHistoryEntry entry) {
        jdbc.update("INSERT INTO incident_ladder_history (incident_id, change_type, from_level, to_level,"
                        + " risk_score, config_version, actor, occurred_at, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                entry.incidentId(), entry.changeType().name(), entry.fromLevel(), entry.toLevel(),
                entry.riskScore(), entry.configVersion(), entry.actor(),
                Timestamp.from(entry.occurredAt()), Timestamp.from(entry.createdAt()));
    }

    /**
     * 查询事件全部等级变更历史，按发生顺序（落库顺序）返回，排序稳定。
     */
    public List<LadderHistoryEntry> listHistory(long incidentId) {
        return jdbc.query("SELECT * FROM incident_ladder_history WHERE incident_id = ? ORDER BY id",
                HISTORY_MAPPER, incidentId);
    }
}
