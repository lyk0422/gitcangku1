package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchRelease;

/**
 * 批次放行快照持久化。快照只增不改，同一批次同一修订版本最多一条（唯一约束兜底）。
 */
@Repository
public class BatchReleaseRepository {

    private static final RowMapper<BatchRelease> MAPPER = (rs, rowNum) -> new BatchRelease(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("batch_revision"),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            rs.getInt("included_count"),
            rs.getInt("excluded_count"),
            rs.getBigDecimal("mean_value"),
            rs.getBigDecimal("min_value"),
            rs.getBigDecimal("max_value"));

    private final JdbcTemplate jdbc;

    public BatchReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入放行快照并返回生成的 ID。
     */
    public long insert(BatchRelease release) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO batch_release "
                            + "(batch_id, batch_revision, released_by, released_at, included_count, "
                            + "excluded_count, mean_value, min_value, max_value) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, release.batchId());
            ps.setInt(2, release.batchRevision());
            ps.setString(3, release.releasedBy());
            ps.setObject(4, JdbcTimes.toDb(release.releasedAt()));
            ps.setInt(5, release.includedCount());
            ps.setInt(6, release.excludedCount());
            ps.setBigDecimal(7, release.meanValue());
            ps.setBigDecimal(8, release.minValue());
            ps.setBigDecimal(9, release.maxValue());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询批次的全部放行快照（按 ID 升序，即放行顺序）。
     */
    public List<BatchRelease> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM batch_release WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }
}
