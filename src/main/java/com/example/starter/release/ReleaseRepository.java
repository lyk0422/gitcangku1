package com.example.starter.release;

import java.sql.PreparedStatement;
import java.sql.Statement;
import com.example.starter.common.UtcTimes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 放行记录持久化。batch_id 唯一约束保证并发放行只产生一条记录。
 */
@Repository
public class ReleaseRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReleaseRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 放行记录视图。
     */
    public record ReleaseRecord(long id, long batchId, long programVersionId, long readingMaxId,
                                int readingCount, String releasedBy, Instant releasedAt,
                                String basisJson) {
    }

    /**
     * 插入放行记录，返回自增ID。重复放行由 uk_release_batch 唯一约束拒绝。
     */
    public long insert(long batchId, long programVersionId, long readingMaxId, int readingCount,
                       String releasedBy, String basisJson) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_record (batch_id, program_version_id, reading_max_id,"
                            + " reading_count, released_by, released_at, basis_json)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, programVersionId);
            ps.setLong(3, readingMaxId);
            ps.setInt(4, readingCount);
            ps.setString(5, releasedBy);
            ps.setObject(6, UtcTimes.toDb(clock.instant()));
            ps.setString(7, basisJson);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询批次的放行记录。
     */
    public Optional<ReleaseRecord> findByBatch(long batchId) {
        List<ReleaseRecord> list = jdbc.query(
                "SELECT id, batch_id, program_version_id, reading_max_id, reading_count,"
                        + " released_by, released_at, basis_json FROM release_record WHERE batch_id = ?",
                (rs, rowNum) -> new ReleaseRecord(rs.getLong("id"), rs.getLong("batch_id"),
                        rs.getLong("program_version_id"), rs.getLong("reading_max_id"),
                        rs.getInt("reading_count"), rs.getString("released_by"),
                        UtcTimes.fromDb(rs.getObject("released_at", java.time.LocalDateTime.class)), rs.getString("basis_json")),
                batchId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 批次的放行记录条数，用于并发场景核对只产生一条记录。
     */
    public int countByBatch(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = ?", Integer.class, batchId);
        return count == null ? 0 : count;
    }
}