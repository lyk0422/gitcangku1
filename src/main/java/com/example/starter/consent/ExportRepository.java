package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 导出快照持久化访问：快照生成后不可变，只插入不更新不删除；
 * 所有查询使用参数化 SQL。
 */
@Repository
public class ExportRepository {

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("export_key"),
            rs.getString("subject_key"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<SnapshotPurposeRow> PURPOSE_MAPPER = (rs, rowNum) -> new SnapshotPurposeRow(
            rs.getString("export_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getInt("record_count"));

    private static final RowMapper<SnapshotRecordRow> RECORD_MAPPER = (rs, rowNum) -> new SnapshotRecordRow(
            rs.getString("record_key"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public ExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 快照主表行。
     *
     * @param exportKey  导出快照标识，全局唯一
     * @param subjectKey 主体标识
     * @param createdAt  快照生成时刻（服务器时区 Asia/Shanghai）
     */
    public record SnapshotRow(String exportKey, String subjectKey, LocalDateTime createdAt) {
    }

    /**
     * 快照用途行。
     *
     * @param exportKey   所属导出快照标识
     * @param purpose     用途
     * @param epoch       生成时刻该用途的有效授权代次
     * @param recordCount 快照内该用途记录数
     */
    public record SnapshotPurposeRow(String exportKey, Purpose purpose, int epoch, int recordCount) {
    }

    /**
     * 快照记录行。
     *
     * @param recordKey 记录键
     * @param payload   记录内容
     */
    public record SnapshotRecordRow(String recordKey, String payload) {
    }

    Optional<SnapshotRow> findSnapshot(String exportKey) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT export_key, subject_key, created_at FROM export_snapshot WHERE export_key = ?",
                SNAPSHOT_MAPPER, exportKey);
        return rows.stream().findFirst();
    }

    List<SnapshotRow> findSnapshotsBySubject(String subjectKey) {
        return jdbc.query(
                "SELECT export_key, subject_key, created_at FROM export_snapshot"
                        + " WHERE subject_key = ? ORDER BY created_at ASC, export_key ASC",
                SNAPSHOT_MAPPER, subjectKey);
    }

    List<SnapshotPurposeRow> findPurposes(String exportKey) {
        return jdbc.query(
                "SELECT export_key, purpose, epoch, record_count FROM export_snapshot_purpose"
                        + " WHERE export_key = ? ORDER BY purpose ASC",
                PURPOSE_MAPPER, exportKey);
    }

    List<SnapshotRecordRow> findRecords(String exportKey, Purpose purpose) {
        return jdbc.query(
                "SELECT record_key, payload FROM export_snapshot_record"
                        + " WHERE export_key = ? AND purpose = ? ORDER BY seq ASC",
                RECORD_MAPPER, exportKey, purpose.name());
    }

    void insertSnapshot(String exportKey, String subjectKey, String requestId, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO export_snapshot (export_key, subject_key, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                exportKey, subjectKey, requestId, Timestamp.valueOf(createdAt));
    }

    void insertPurpose(String exportKey, Purpose purpose, int epoch, int recordCount) {
        jdbc.update(
                "INSERT INTO export_snapshot_purpose (export_key, purpose, epoch, record_count)"
                        + " VALUES (?, ?, ?, ?)",
                exportKey, purpose.name(), epoch, recordCount);
    }

    void insertRecord(String exportKey, Purpose purpose, int seq, String recordKey, String payload) {
        jdbc.update(
                "INSERT INTO export_snapshot_record (export_key, purpose, seq, record_key, payload)"
                        + " VALUES (?, ?, ?, ?, ?)",
                exportKey, purpose.name(), seq, recordKey, payload);
    }
}
