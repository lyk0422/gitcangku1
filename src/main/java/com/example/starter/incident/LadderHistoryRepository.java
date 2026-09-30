package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 升级阶梯等级变更历史仓储。只追加、不可变，不提供更新与删除。
 * 排序固定按 id（落库顺序），自动升级与人工降级各落一条。
 */
@Repository
public class LadderHistoryRepository {

    private final JdbcTemplate jdbc;

    public LadderHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LadderHistory> MAPPER = (rs, n) -> map(rs);

    private static LadderHistory map(ResultSet rs) throws SQLException {
        Integer fromLevel = nullableInt(rs, "from_level");
        Integer score = nullableInt(rs, "score");
        Long versionId = nullableLong(rs, "threshold_version_id");
        Timestamp cooldownSince = rs.getTimestamp("cooldown_since");
        return new LadderHistory(
                rs.getLong("id"), rs.getLong("incident_id"),
                LadderChangeType.valueOf(rs.getString("change_type")),
                fromLevel, rs.getInt("to_level"), score, versionId,
                rs.getString("operator"),
                cooldownSince == null ? null : cooldownSince.toInstant(),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * 追加一条不可变等级变更历史，返回生成主键。
     */
    public long insert(LadderHistory history) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_ladder_history (incident_id, change_type, from_level,"
                            + " to_level, score, threshold_version_id, operator, cooldown_since,"
                            + " occurred_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, history.incidentId());
            ps.setString(2, history.changeType().name());
            if (history.fromLevel() == null) {
                ps.setObject(3, null);
            } else {
                ps.setInt(3, history.fromLevel());
            }
            ps.setInt(4, history.toLevel());
            if (history.score() == null) {
                ps.setObject(5, null);
            } else {
                ps.setInt(5, history.score());
            }
            if (history.thresholdVersionId() == null) {
                ps.setObject(6, null);
            } else {
                ps.setLong(6, history.thresholdVersionId());
            }
            ps.setString(7, history.operator());
            ps.setTimestamp(8, history.cooldownSince() == null
                    ? null : Timestamp.from(history.cooldownSince()));
            ps.setTimestamp(9, Timestamp.from(history.occurredAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件全部等级变更历史，按落库顺序返回（只读，排序稳定）。
     */
    public List<LadderHistory> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_ladder_history WHERE incident_id = ? ORDER BY id",
                MAPPER, incidentId);
    }
}
