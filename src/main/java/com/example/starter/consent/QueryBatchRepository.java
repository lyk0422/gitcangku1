package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import java.sql.PreparedStatement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 批次查询快照的持久化访问：快照创建后不可改写，仅支持整体读取。
 */
@Repository
public class QueryBatchRepository {

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getLong("batch_id"),
            rs.getString("recipient_id"),
            Purpose.valueOf(rs.getString("purpose")));

    private static final RowMapper<BatchItemRow> ITEM_MAPPER = (rs, rowNum) -> new BatchItemRow(
            rs.getLong("batch_id"),
            rs.getString("subject_key"),
            rs.getInt("epoch"),
            rs.getInt("attestation_version"),
            rs.getString("records_json"));

    private final JdbcTemplate jdbc;

    public QueryBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批次查询快照头行。
     *
     * @param batchId     批次查询标识
     * @param recipientId 发起查询的数据接收方标识
     * @param purpose     查询用途
     */
    public record BatchRow(long batchId, String recipientId, Purpose purpose) {
    }

    /**
     * 批次查询单主体快照行。
     *
     * @param batchId             所属批次查询标识
     * @param subjectKey          主体标识
     * @param epoch               查询时主体的当前授权代次
     * @param attestationVersion  门禁校验所用的证明版本
     * @param recordsJson         该主体当前代次的记录快照（JSON 数组）
     */
    public record BatchItemRow(long batchId, String subjectKey, int epoch,
                               int attestationVersion, String recordsJson) {
    }

    long insertBatch(String recipientId, Purpose purpose) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO query_batch (recipient_id, purpose) VALUES (?, ?)",
                    new String[]{"batch_id"});
            ps.setString(1, recipientId);
            ps.setString(2, purpose.name());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("批次查询主键生成失败");
        }
        return key.longValue();
    }

    void insertItem(long batchId, String subjectKey, int epoch, int attestationVersion, String recordsJson) {
        jdbc.update(
                "INSERT INTO query_batch_item (batch_id, subject_key, epoch, attestation_version, records_json)"
                        + " VALUES (?, ?, ?, ?, ?)",
                batchId, subjectKey, epoch, attestationVersion, recordsJson);
    }

    Optional<BatchRow> findBatch(long batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT batch_id, recipient_id, purpose FROM query_batch WHERE batch_id = ?",
                BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    List<BatchItemRow> findItems(long batchId) {
        return jdbc.query(
                "SELECT batch_id, subject_key, epoch, attestation_version, records_json"
                        + " FROM query_batch_item WHERE batch_id = ? ORDER BY subject_key",
                ITEM_MAPPER, batchId);
    }
}
