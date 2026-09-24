package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 导出快照的持久化访问：快照写入后不可变，只提供插入与查询，不提供更新或删除。
 */
@Repository
public class ExportRepository {

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("export_key"),
            rs.getString("subject_key"),
            rs.getString("generated_at"));

    private static final RowMapper<PurposeRow> PURPOSE_MAPPER = (rs, rowNum) -> new PurposeRow(
            rs.getString("export_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getInt("record_count"));

    private static final RowMapper<SnapshotRecordRow> RECORD_MAPPER = (rs, rowNum) -> new SnapshotRecordRow(
            rs.getString("export_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public ExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 快照头行。
     *
     * @param exportKey   导出标识，全局唯一
     * @param subjectKey  主体标识
     * @param generatedAt 生成时刻（ISO-8601，UTC 偏移）
     */
    public record SnapshotRow(String exportKey, String subjectKey, String generatedAt) {
    }

    /**
     * 快照用途行。
     *
     * @param exportKey   导出标识
     * @param purpose     用途
     * @param epoch       生成时刻该用途的有效授权代次
     * @param recordCount 生成时刻该用途该代次的记录数
     */
    public record PurposeRow(String exportKey, Purpose purpose, int epoch, int recordCount) {
    }

    /**
     * 快照记录行。
     *
     * @param exportKey 导出标识
     * @param purpose   用途
     * @param epoch     记录所属代次
     * @param recordKey 记录键
     * @param payload   记录内容
     */
    public record SnapshotRecordRow(String exportKey, Purpose purpose, int epoch, String recordKey, String payload) {
    }

    void insertSnapshot(String exportKey, String subjectKey, String requestId, String generatedAt) {
        jdbc.update(
                "INSERT INTO export_snapshot (export_key, subject_key, request_id, generated_at)"
                        + " VALUES (?, ?, ?, ?)",
                exportKey, subjectKey, requestId, generatedAt);
    }

    void insertPurpose(String exportKey, Purpose purpose, int epoch, int recordCount) {
        jdbc.update(
                "INSERT INTO export_snapshot_purpose (export_key, purpose, epoch, record_count)"
                        + " VALUES (?, ?, ?, ?)",
                exportKey, purpose.name(), epoch, recordCount);
    }

    void insertRecord(String exportKey, Purpose purpose, int epoch, String recordKey, String payload) {
        jdbc.update(
                "INSERT INTO export_snapshot_record (export_key, purpose, epoch, record_key, payload)"
                        + " VALUES (?, ?, ?, ?, ?)",
                exportKey, purpose.name(), epoch, recordKey, payload);
    }

    Optional<SnapshotRow> findSnapshot(String exportKey) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT export_key, subject_key, generated_at FROM export_snapshot WHERE export_key = ?",
                SNAPSHOT_MAPPER, exportKey);
        return rows.stream().findFirst();
    }

    List<SnapshotRow> findSnapshotsBySubject(String subjectKey) {
        return jdbc.query(
                "SELECT export_key, subject_key, generated_at FROM export_snapshot"
                        + " WHERE subject_key = ? ORDER BY generated_at ASC, export_key ASC",
                SNAPSHOT_MAPPER, subjectKey);
    }

    List<PurposeRow> findPurposes(String exportKey) {
        return jdbc.query(
                "SELECT export_key, purpose, epoch, record_count FROM export_snapshot_purpose"
                        + " WHERE export_key = ? ORDER BY purpose ASC",
                PURPOSE_MAPPER, exportKey);
    }

    List<SnapshotRecordRow> findRecords(String exportKey, Purpose purpose) {
        return jdbc.query(
                "SELECT export_key, purpose, epoch, record_key, payload FROM export_snapshot_record"
                        + " WHERE export_key = ? AND purpose = ? ORDER BY record_key ASC",
                RECORD_MAPPER, exportKey, purpose.name());
    }
}
