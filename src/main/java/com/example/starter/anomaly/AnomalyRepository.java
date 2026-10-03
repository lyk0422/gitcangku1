package com.example.starter.anomaly;

import java.sql.PreparedStatement;
import java.sql.Statement;
import com.example.starter.common.UtcTimes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 异常标记持久化。已标记异常的批次只能由质检员驳回或发起独立复烧批次。
 */
@Repository
public class AnomalyRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AnomalyRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 异常标记视图。
     */
    public record AnomalyMark(long id, long batchId, String markedBy, String reason, Instant markedAt) {
    }

    /**
     * 新增异常标记，返回自增ID。
     */
    public long insert(long batchId, String markedBy, String reason) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO anomaly_mark (batch_id, marked_by, reason, marked_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, markedBy);
            ps.setString(3, reason);
            ps.setObject(4, UtcTimes.toDb(clock.instant()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批次是否存在异常标记。
     */
    public boolean existsByBatch(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM anomaly_mark WHERE batch_id = ?", Integer.class, batchId);
        return count != null && count > 0;
    }

    /**
     * 查询批次全部异常标记。
     */
    public List<AnomalyMark> findByBatch(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, marked_by, reason, marked_at FROM anomaly_mark"
                        + " WHERE batch_id = ? ORDER BY id",
                (rs, rowNum) -> new AnomalyMark(rs.getLong("id"), rs.getLong("batch_id"),
                        rs.getString("marked_by"), rs.getString("reason"),
                        UtcTimes.fromDb(rs.getObject("marked_at", java.time.LocalDateTime.class))),
                batchId);
    }
}