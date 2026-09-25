package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 演练批次登记与清理墓碑仓储。
 * batchKey 全局唯一；批次行锁用于串行化“批次内新演练事件上报”与“批次清理”。
 */
@Repository
public class DrillBatchRepository {

    private final JdbcTemplate jdbc;

    public DrillBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DrillBatch> MAPPER = (rs, n) -> map(rs);

    private static DrillBatch map(ResultSet rs) throws SQLException {
        Timestamp cleaned = rs.getTimestamp("cleaned_at");
        return new DrillBatch(rs.getLong("id"), rs.getString("batch_key"),
                cleaned == null ? null : cleaned.toInstant(), rs.getString("cleanup_key"),
                rs.getInt("deleted_incident_count"), rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 按批次键查询（不加锁）。
     */
    public Optional<DrillBatch> findByKey(String batchKey) {
        List<DrillBatch> rows = jdbc.query("SELECT * FROM drill_batches WHERE batch_key = ?",
                MAPPER, batchKey);
        return rows.stream().findFirst();
    }

    /**
     * 按批次键锁定读（SELECT ... FOR UPDATE），用于上报与清理的串行化。
     */
    public Optional<DrillBatch> lockByKey(String batchKey) {
        List<DrillBatch> rows = jdbc.query("SELECT * FROM drill_batches WHERE batch_key = ? FOR UPDATE",
                MAPPER, batchKey);
        return rows.stream().findFirst();
    }

    /**
     * 首次使用批次时登记。
     */
    public void insert(String batchKey, Instant now) {
        jdbc.update("INSERT INTO drill_batches (batch_key, cleaned_at, cleanup_key,"
                + " deleted_incident_count, created_at) VALUES (?,NULL,NULL,0,?)", batchKey,
                Timestamp.from(now));
    }

    /**
     * 清理成功：写入墓碑信息（清理时间、cleanupKey、删除事件数）。
     */
    public void markCleaned(String batchKey, String cleanupKey, int deletedIncidentCount, Instant now) {
        jdbc.update("UPDATE drill_batches SET cleaned_at = ?, cleanup_key = ?,"
                + " deleted_incident_count = ? WHERE batch_key = ?", Timestamp.from(now), cleanupKey,
                deletedIncidentCount, batchKey);
    }

    /**
     * 查询全部批次（含未清理与已清理墓碑），按登记时间倒序，用于清理历史查询。
     */
    public List<DrillBatch> listAll() {
        return jdbc.query("SELECT * FROM drill_batches ORDER BY created_at DESC, id DESC", MAPPER);
    }
}
