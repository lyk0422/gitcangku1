package com.example.starter.aging.repo;

import com.example.starter.aging.domain.AgingBatch;
import com.example.starter.aging.domain.BatchStatus;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 熟成批次存取。时间列以 UTC 本地时间写入 TIMESTAMP 字段。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<AgingBatch> MAPPER = (rs, n) -> new AgingBatch(
            rs.getLong("id"),
            rs.getString("batch_code"),
            rs.getObject("entry_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getInt("target_days"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入熟成中批次并返回自增主键。 */
    public long insert(String batchCode, Instant entryAt, int targetDays) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO aging_batch (batch_code, entry_at, target_days, status, created_at)"
                            + " VALUES (?, ?, ?, 'AGING', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchCode);
            ps.setTimestamp(2, toTs(entryAt));
            ps.setInt(3, targetDays);
            ps.setTimestamp(4, toTs(Instant.now()));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<AgingBatch> findById(long id) {
        return jdbc.query("SELECT * FROM aging_batch WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按主键加行锁读取，用于串行化放行与作废等竞争操作。 */
    public Optional<AgingBatch> lockById(long id) {
        return jdbc.query("SELECT * FROM aging_batch WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public void updateStatus(long id, BatchStatus status) {
        jdbc.update("UPDATE aging_batch SET status = ? WHERE id = ?", status.name(), id);
    }

    static Timestamp toTs(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}