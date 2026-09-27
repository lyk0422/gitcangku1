package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ChunkReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 分片接收证据数据访问。uk_receipt_task_attempt_index 保证同任务同代次同序号最多一笔；
 * 证据只增不改，重新拉取的新代次不影响旧证据。
 */
@Repository
public class ChunkReceiptRepository {

    private static final RowMapper<ChunkReceipt> MAPPER = (rs, rowNum) -> new ChunkReceipt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt_no"),
            rs.getInt("chunk_index"), rs.getString("digest"), rs.getString("received_at_utc"));

    private static final String COLUMNS = "id, task_id, attempt_no, chunk_index, digest, received_at_utc";

    private final JdbcTemplate jdbc;

    public ChunkReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, int attemptNo, int chunkIndex, String digest, String receivedAtUtc) {
        jdbc.update("INSERT INTO chunk_receipt (task_id, attempt_no, chunk_index, digest, received_at_utc)"
                + " VALUES (?, ?, ?, ?, ?)", taskId, attemptNo, chunkIndex, digest, receivedAtUtc);
    }

    public Optional<ChunkReceipt> find(long taskId, int attemptNo, int chunkIndex) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chunk_receipt"
                        + " WHERE task_id = ? AND attempt_no = ? AND chunk_index = ?",
                MAPPER, taskId, attemptNo, chunkIndex).stream().findFirst();
    }

    /**
     * 指定代次已接收分片，按序号升序。
     */
    public List<ChunkReceipt> findByTaskAndAttempt(long taskId, int attemptNo) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chunk_receipt"
                        + " WHERE task_id = ? AND attempt_no = ? ORDER BY chunk_index",
                MAPPER, taskId, attemptNo);
    }

    /**
     * 任务全部代次的接收历史，按代次、序号升序。
     */
    public List<ChunkReceipt> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chunk_receipt"
                + " WHERE task_id = ? ORDER BY attempt_no, chunk_index", MAPPER, taskId);
    }

    public int countByTaskAndAttempt(long taskId, int attemptNo) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chunk_receipt"
                + " WHERE task_id = ? AND attempt_no = ?", Integer.class, taskId, attemptNo);
        return count == null ? 0 : count;
    }
}
