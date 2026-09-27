package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 合法保留持久化访问：保留窗口按 UTC 左闭右开解释。
 */
@Repository
public class LegalHoldRepository {

    private static final RowMapper<LegalHoldRow> MAPPER = (rs, rowNum) -> new LegalHoldRow(
            rs.getLong("id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("hold_reason"),
            rs.getTimestamp("valid_from").toInstant(),
            rs.getTimestamp("valid_to").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public LegalHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 合法保留行。
     *
     * @param id         自增主键
     * @param validFrom  窗口起点（UTC，含）
     * @param validTo    窗口终点（UTC，不含）
     */
    public record LegalHoldRow(long id, String subjectKey, Purpose purpose, int epoch, String recordKey,
                               String holdReason, Instant validFrom, Instant validTo, Instant createdAt) {

        /**
         * 指定时刻是否落在保留窗口内：validFrom &lt;= now &lt; validTo。
         */
        public boolean covers(Instant now) {
            return !now.isBefore(validFrom) && now.isBefore(validTo);
        }
    }

    List<LegalHoldRow> listForRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        return jdbc.query(
                "SELECT id, subject_key, purpose, epoch, record_key, hold_reason, valid_from, valid_to,"
                        + " created_at FROM legal_hold"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " ORDER BY valid_from ASC, id ASC",
                MAPPER, subjectKey, purpose.name(), epoch, recordKey);
    }

    void insert(String subjectKey, Purpose purpose, int epoch, String recordKey, String holdReason,
                Instant validFrom, Instant validTo) {
        jdbc.update(
                "INSERT INTO legal_hold (subject_key, purpose, epoch, record_key, hold_reason,"
                        + " valid_from, valid_to) VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, holdReason,
                Timestamp.from(validFrom), Timestamp.from(validTo));
    }
}
