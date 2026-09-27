package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ChunkReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分片接收证据数据访问。唯一约束 uk_receipt_task_attempt_chunk 保证同任务同代次同序号最多一笔。
 */
@Repository
public class ChunkReceiptRepository {

    private static final RowMapper<ChunkReceipt> MAPPER = (rs, rowNum) -> new ChunkReceipt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"), rs.getInt("chunk_index"),
            rs.getString("digest"), rs.getString("expected_digest"), rs.getBoolean("matched"),
            rs.getString("received_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt_no, chunk_index, digest, expected_digest,"
            + " matched, received_at_utc";

    private final JdbcTemplate jdbc;

    public ChunkReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, int attemptNo, int chunkIndex, String digest,
                       String expectedDigest, boolean matched, String receivedAtUtc) {
        jdbc.update("INSERT INTO task_chunk_receipt (task_id, attempt_no, chunk_index, digest,"
                        + " expected_digest, matched, received_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                taskId, attemptNo, chunkIndex, digest, expectedDigest, matched, receivedAtUtc);
    }

    public List<ChunkReceipt> findByAttempt(long taskId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_chunk_receipt"
                        + " WHERE task_id = ? AND attempt_no = ? ORDER BY chunk_index",
                MAPPER, taskId, attemptNo);
    }

    public List<ChunkReceipt> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_chunk_receipt"
                        + " WHERE task_id = ? ORDER BY attempt_no, chunk_index", MAPPER, taskId);
    }
}
