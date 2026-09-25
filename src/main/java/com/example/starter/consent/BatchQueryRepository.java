package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 批量查询批次与快照持久化访问。
 *
 * <p>每次批次查询都登记一条批次记录：门禁通过生成不可改写快照；门禁拒绝登记阻断明细。
 * 快照固化每个主体的授权代次与证明版本，后续撤销/续签/迁移不影响快照。
 */
@Repository
public class BatchQueryRepository {

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getString("batch_id"),
            rs.getString("recipient_id"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getString("record_key"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("batch_id"),
            rs.getString("subject_key"),
            rs.getInt("epoch"),
            rs.getString("attestation_id"),
            rs.getInt("attestation_version"),
            rs.getString("record_key"),
            rs.getString("payload"));

    private static final RowMapper<BlockRow> BLOCK_MAPPER = (rs, rowNum) -> new BlockRow(
            rs.getString("batch_id"),
            rs.getString("subject_key"),
            (Integer) rs.getObject("epoch"),
            rs.getString("reason"));

    private final JdbcTemplate jdbc;

    public BatchQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批次行。
     *
     * @param batchId     批次标识
     * @param recipientId 接收方标识
     * @param purpose     查询用途
     * @param recordKey   记录键
     * @param status      状态：SNAPSHOTTED 已生成快照 / BLOCKED 门禁拒绝
     * @param createdAt   创建时刻（UTC）
     */
    public record BatchRow(String batchId, String recipientId, Purpose purpose, String recordKey,
                           String status, Instant createdAt) {
    }

    /**
     * 快照条目行：固化授权代次与证明版本。
     */
    public record SnapshotRow(String batchId, String subjectKey, int epoch, String attestationId,
                              int attestationVersion, String recordKey, String payload) {
    }

    /**
     * 阻断明细行。
     *
     * @param batchId    批次标识
     * @param subjectKey 被阻断主体
     * @param epoch      当前授权代次，无授权为 null
     * @param reason     稳定原因码
     */
    public record BlockRow(String batchId, String subjectKey, Integer epoch, String reason) {
    }

    Optional<BatchRow> findBatch(String batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT batch_id, recipient_id, purpose, record_key, status, created_at"
                        + " FROM batch_query WHERE batch_id = ?",
                BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    void insertBatch(BatchRow row, String requestId) {
        jdbc.update(
                "INSERT INTO batch_query (batch_id, recipient_id, purpose, record_key, status, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchId(), row.recipientId(), row.purpose().name(), row.recordKey(),
                row.status(), requestId, Timestamp.from(row.createdAt()));
    }

    void insertSnapshotItem(SnapshotRow row) {
        jdbc.update(
                "INSERT INTO batch_snapshot_item (batch_id, subject_key, epoch, attestation_id,"
                        + " attestation_version, record_key, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchId(), row.subjectKey(), row.epoch(), row.attestationId(),
                row.attestationVersion(), row.recordKey(), row.payload());
    }

    void insertBlock(BlockRow row, int lineNo) {
        jdbc.update(
                "INSERT INTO batch_block (batch_id, subject_key, epoch, reason, line_no)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchId(), row.subjectKey(), row.epoch(), row.reason(), lineNo);
    }

    List<SnapshotRow> findSnapshotItems(String batchId) {
        return jdbc.query(
                "SELECT batch_id, subject_key, epoch, attestation_id, attestation_version, record_key, payload"
                        + " FROM batch_snapshot_item WHERE batch_id = ? ORDER BY subject_key ASC",
                SNAPSHOT_MAPPER, batchId);
    }

    List<BlockRow> findBlocks(String batchId) {
        return jdbc.query(
                "SELECT batch_id, subject_key, epoch, reason FROM batch_block"
                        + " WHERE batch_id = ? ORDER BY line_no ASC",
                BLOCK_MAPPER, batchId);
    }
}
