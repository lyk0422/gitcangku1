package com.example.starter.repo;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.PollenBatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 花粉批号仓储。
 */
@Repository
public class PollenBatchRepository {

    private final JdbcTemplate jdbc;

    public PollenBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建批号，状态 ACTIVE；批号重复时抛出 DuplicateKeyException。 */
    public long insert(String batchNo, Instant createdAt) {
        jdbc.update("INSERT INTO pollen_batch (batch_no, status, created_at) VALUES (?, ?, ?)",
                batchNo, BatchStatus.ACTIVE.name(), UtcTimes.toDb(createdAt));
        Long id = jdbc.queryForObject("SELECT id FROM pollen_batch WHERE batch_no = ?", Long.class, batchNo);
        return id == null ? -1L : id;
    }

    /** 按批号查询。 */
    public Optional<PollenBatch> findByBatchNo(String batchNo) {
        return jdbc.query("SELECT * FROM pollen_batch WHERE batch_no = ?", this::map, batchNo)
                .stream().findFirst();
    }

    /** 停用批号；返回更新行数，0 表示不存在或已停用。 */
    public int disable(String batchNo) {
        return jdbc.update("UPDATE pollen_batch SET status = ? WHERE batch_no = ? AND status = ?",
                BatchStatus.DISABLED.name(), batchNo, BatchStatus.ACTIVE.name());
    }

    private PollenBatch map(ResultSet rs, int rowNum) throws SQLException {
        return new PollenBatch(rs.getLong("id"), rs.getString("batch_no"),
                BatchStatus.valueOf(rs.getString("status")),
                UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));
    }
}