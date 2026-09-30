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
 * 风险分数不可变证据仓储。每次分数更新提交一条，只追加，不提供更新与删除。
 * 阈值版本修订、自动升级、人工降级均不回写旧证据。
 */
@Repository
public class LadderScoreRepository {

    private final JdbcTemplate jdbc;

    public LadderScoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LadderScore> MAPPER = (rs, n) -> map(rs);

    private static LadderScore map(ResultSet rs) throws SQLException {
        int evaluatedLevel = rs.getInt("evaluated_level");
        return new LadderScore(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("score"),
                rs.getLong("threshold_version_id"),
                rs.wasNull() ? null : evaluatedLevel,
                rs.getString("submitted_by"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 追加一条不可变分数证据，返回生成主键。
     */
    public long insert(LadderScore score) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_ladder_scores (incident_id, score, threshold_version_id,"
                            + " evaluated_level, submitted_by, occurred_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, score.incidentId());
            ps.setInt(2, score.score());
            ps.setLong(3, score.thresholdVersionId());
            if (score.evaluatedLevel() == null) {
                ps.setObject(4, null);
            } else {
                ps.setInt(4, score.evaluatedLevel());
            }
            ps.setString(5, score.submittedBy());
            ps.setTimestamp(6, Timestamp.from(score.occurredAt()));
            ps.setTimestamp(7, Timestamp.from(score.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件全部分数证据，按提交顺序返回（只读诊断）。
     */
    public List<LadderScore> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_ladder_scores WHERE incident_id = ? ORDER BY id",
                MAPPER, incidentId);
    }

    /**
     * 统计事件分数证据条数，供不可变性测试核对。
     */
    public int countByIncident(long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_ladder_scores WHERE incident_id = ?",
                Integer.class, incidentId);
        return count == null ? 0 : count;
    }
}
