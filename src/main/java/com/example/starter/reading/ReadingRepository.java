package com.example.starter.reading;

import java.sql.PreparedStatement;
import java.sql.Statement;
import com.example.starter.common.UtcTimes;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 测温点持久化：只提供追加与查询，不提供更新和删除。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReadingRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 追加一个测温点，返回自增ID。同一批次内测温时刻唯一，重复时由唯一约束拒绝。
     */
    public long insert(TemperatureReading reading) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO temperature_reading (batch_id, measured_at, temperature_c, created_at)"
                            + " VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, reading.batchId());
            ps.setObject(2, UtcTimes.toDb(reading.measuredAt()));
            ps.setBigDecimal(3, reading.temperatureC());
            ps.setObject(4, UtcTimes.toDb(clock.instant()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按测温时刻升序查询批次全部测温点。
     */
    public List<TemperatureReading> findByBatchOrderByTime(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, measured_at, temperature_c FROM temperature_reading"
                        + " WHERE batch_id = ? ORDER BY measured_at, id",
                (rs, rowNum) -> new TemperatureReading(rs.getLong("id"), rs.getLong("batch_id"),
                        UtcTimes.fromDb(rs.getObject("measured_at", java.time.LocalDateTime.class)),
                        rs.getBigDecimal("temperature_c")),
                batchId);
    }

    /**
     * 批次内是否已存在指定测温时刻的点。
     */
    public boolean existsByBatchAndTime(long batchId, java.time.Instant measuredAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE batch_id = ? AND measured_at = ?",
                Integer.class, batchId, UtcTimes.toDb(measuredAt));
        return count != null && count > 0;
    }

    /**
     * 批次内最大测温点ID，无点时返回 null。
     */
    public Long maxIdByBatch(long batchId) {
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM temperature_reading WHERE batch_id = ?",
                Long.class, batchId);
    }
}