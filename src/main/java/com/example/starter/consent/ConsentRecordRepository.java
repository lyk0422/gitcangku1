package com.example.starter.consent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 授权数据记录仓库，基于 JdbcTemplate 的参数化 SQL 实现。
 */
@Repository
public class ConsentRecordRepository {

    private static final RecordRowMapper MAPPER = new RecordRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public ConsentRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按主体+用途+代次+记录键查询记录。
     */
    public Optional<ConsentRecord> find(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<ConsentRecord> rows = jdbcTemplate.query(
                "SELECT id, subject_key, purpose, epoch, record_key, payload, request_id, created_at"
                        + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 插入一条记录，返回插入后的实体。
     */
    public ConsentRecord insert(String subjectKey, Purpose purpose, int epoch,
                                String recordKey, String payload, String requestId) {
        jdbcTemplate.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload, requestId);
        return find(subjectKey, purpose, epoch, recordKey).orElseThrow();
    }

    private static final class RecordRowMapper implements RowMapper<ConsentRecord> {

        @Override
        public ConsentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ConsentRecord(
                    rs.getLong("id"),
                    rs.getString("subject_key"),
                    Purpose.valueOf(rs.getString("purpose")),
                    rs.getInt("epoch"),
                    rs.getString("record_key"),
                    rs.getString("payload"),
                    rs.getString("request_id"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    }
}
