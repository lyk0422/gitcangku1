package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 数据清除请求持久化访问：清除审计行只增不改。
 */
@Repository
public class ErasureRepository {

    private static final RowMapper<ErasureRow> MAPPER = (rs, rowNum) -> new ErasureRow(
            rs.getString("request_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ErasureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 清除请求审计行。
     */
    public record ErasureRow(String requestId, String subjectKey, Purpose purpose, int epoch,
                             String recordKey, String status, Instant createdAt) {
    }

    Optional<ErasureRow> findByRequestId(String requestId) {
        List<ErasureRow> rows = jdbc.query(
                "SELECT request_id, subject_key, purpose, epoch, record_key, status, created_at"
                        + " FROM erasure_request WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    Optional<ErasureRow> findByObject(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<ErasureRow> rows = jdbc.query(
                "SELECT request_id, subject_key, purpose, epoch, record_key, status, created_at"
                        + " FROM erasure_request"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    List<ErasureRow> listBySubject(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT request_id, subject_key, purpose, epoch, record_key, status, created_at"
                        + " FROM erasure_request"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY created_at ASC, request_id ASC",
                MAPPER, subjectKey, purpose.name());
    }

    void insert(ErasureRow row) {
        jdbc.update(
                "INSERT INTO erasure_request (request_id, subject_key, purpose, epoch, record_key, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.requestId(), row.subjectKey(), row.purpose().name(), row.epoch(),
                row.recordKey(), row.status());
    }
}
