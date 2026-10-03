package com.example.starter.aging.repo;

import com.example.starter.aging.domain.ReleaseRecord;
import com.example.starter.aging.domain.SnapshotItem;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 放行记录与放行快照存取。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseRecord> RELEASE_MAPPER = (rs, n) -> new ReleaseRecord(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("request_id"),
            rs.getObject("released_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private static final RowMapper<SnapshotItem> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotItem(
            rs.getLong("id"),
            rs.getLong("release_id"),
            rs.getObject("day_date", LocalDate.class),
            rs.getLong("turn_id"),
            rs.getLong("temperature_id"));

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入放行记录，唯一约束（批次、请求编号）冲突时由调用方捕获处理。 */
    public long insertRelease(long batchId, String requestId, Instant releasedAt) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO release_record (batch_id, request_id, released_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, requestId);
            ps.setTimestamp(3, BatchRepository.toTs(releasedAt));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void insertSnapshotItem(long releaseId, LocalDate dayDate, long turnId, long temperatureId) {
        jdbc.update("INSERT INTO release_snapshot_item (release_id, day_date, turn_id, temperature_id)"
                + " VALUES (?, ?, ?, ?)", releaseId, dayDate, turnId, temperatureId);
    }

    public Optional<ReleaseRecord> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM release_record WHERE batch_id = ?", RELEASE_MAPPER, batchId)
                .stream().findFirst();
    }

    public Optional<ReleaseRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM release_record WHERE request_id = ?", RELEASE_MAPPER, requestId)
                .stream().findFirst();
    }

    public List<SnapshotItem> findSnapshotByReleaseId(long releaseId) {
        return jdbc.query("SELECT * FROM release_snapshot_item WHERE release_id = ? ORDER BY day_date",
                SNAPSHOT_MAPPER, releaseId);
    }
}