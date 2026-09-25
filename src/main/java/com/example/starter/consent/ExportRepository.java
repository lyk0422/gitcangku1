package com.example.starter.consent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 导出快照持久化访问：快照生成后不可变，只新增不更新，基于 JdbcTemplate 参数化 SQL。
 */
@Repository
public class ExportRepository {

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("export_key"),
            rs.getString("subject_key"),
            rs.getString("purposes"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<PurposeRow> PURPOSE_MAPPER = (rs, rowNum) -> new PurposeRow(
            rs.getString("export_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getInt("record_count"));

    private static final RowMapper<SnapshotRecordRow> RECORD_MAPPER = (rs, rowNum) -> new SnapshotRecordRow(
            rs.getString("export_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getString("record_key"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public ExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 导出快照主行。
     *
     * @param exportKey  导出快照标识，全局唯一
     * @param subjectKey 主体标识
     * @param purposes   覆盖的用途，按字典序逗号连接
     * @param createdAt  快照生成时刻（服务器时区 Asia/Shanghai）
     */
    public record SnapshotRow(String exportKey, String subjectKey, String purposes, LocalDateTime createdAt) {
    }

    /**
     * 快照用途行：固化生成时刻的有效代次与记录数。
     *
     * @param exportKey   导出快照标识
     * @param purpose     用途
     * @param epoch       生成时刻该用途的有效授权代次
     * @param recordCount 生成时刻该代次的记录总数
     */
    public record PurposeRow(String exportKey, Purpose purpose, int epoch, int recordCount) {
    }

    /**
     * 快照记录行：固化生成时刻的单条记录。
     *
     * @param exportKey 导出快照标识
     * @param purpose   用途
     * @param recordKey 记录键
     * @param payload   记录内容（生成时刻的快照值）
     */
    public record SnapshotRecordRow(String exportKey, Purpose purpose, String recordKey, String payload) {
    }

    Optional<SnapshotRow> findSnapshot(String exportKey) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT export_key, subject_key, purposes, created_at FROM export_snapshot"
                        + " WHERE export_key = ?",
                SNAPSHOT_MAPPER, exportKey);
        return rows.stream().findFirst();
    }

    List<SnapshotRow> findSnapshotsBySubject(String subjectKey) {
        return jdbc.query(
                "SELECT export_key, subject_key, purposes, created_at FROM export_snapshot"
                        + " WHERE subject_key = ? ORDER BY created_at ASC, export_key ASC",
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
                "SELECT export_key, purpose, record_key, payload FROM export_snapshot_record"
                        + " WHERE export_key = ? AND purpose = ? ORDER BY record_key ASC",
                RECORD_MAPPER, exportKey, purpose.name());
    }

    void insertSnapshot(String exportKey, String subjectKey, String requestId,
                        String purposes, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO export_snapshot (export_key, subject_key, request_id, purposes, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                exportKey, subjectKey, requestId, purposes, createdAt);
    }

    void insertPurpose(String exportKey, Purpose purpose, int epoch, int recordCount) {
        jdbc.update(
                "INSERT INTO export_snapshot_purpose (export_key, purpose, epoch, record_count)"
                        + " VALUES (?, ?, ?, ?)",
                exportKey, purpose.name(), epoch, recordCount);
    }

    void insertRecord(String exportKey, Purpose purpose, String recordKey, String payload) {
        jdbc.update(
                "INSERT INTO export_snapshot_record (export_key, purpose, record_key, payload)"
                        + " VALUES (?, ?, ?, ?)",
                exportKey, purpose.name(), recordKey, payload);
    }
}
