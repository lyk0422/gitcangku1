package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ChunkReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分片接收证据数据访问。唯一约束 uk_chunk_task_attempt_index 保证同任务同代次同序号最多一条；
 * 记录只增不改，重新拉取的新代次另起记录。
 */
@Repository
public class ChunkReceiptRepository {

    private static final RowMapper<ChunkReceipt> MAPPER = (rs, rowNum) -> new ChunkReceipt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt"), rs.getLong("release_id"),
            rs.getString("firmware_version"), rs.getInt("chunk_index"), rs.getString("digest"),
            rs.getString("received_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt, release_id, firmware_version,"
            + " chunk_index, digest, received_at_utc";

    private final JdbcTemplate jdbc;

    public ChunkReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, int attempt, long releaseId, String firmwareVersion,
                       int chunkIndex, String digest, String receivedAtUtc) {
        jdbc.update("INSERT INTO task_chunk_receipt"
                        + " (task_id, attempt, release_id, firmware_version, chunk_index, digest, received_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                taskId, attempt, releaseId, firmwareVersion, chunkIndex, digest, receivedAtUtc);
    }

    /**
     * 该代次已接收分片，按分片序号升序。
     */
    public List<ChunkReceipt> findByTaskAndAttempt(long taskId, int attempt) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_chunk_receipt"
                + " WHERE task_id = ? AND attempt = ? ORDER BY chunk_index", MAPPER, taskId, attempt);
    }

    public long countByTaskAndAttemptAndIndex(long taskId, int attempt, int chunkIndex) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM task_chunk_receipt"
                        + " WHERE task_id = ? AND attempt = ? AND chunk_index = ?",
                Long.class, taskId, attempt, chunkIndex);
        return count == null ? 0 : count;
    }
}
