package com.example.starter.consent;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 读取审计持久化访问：只记录依据承诺成功的读取；撤回不删除历史记录，只追加不修改。
 */
@Repository
public class ReadAuditRepository {

    private static final RowMapper<ReadAuditRow> MAPPER = (rs, rowNum) -> new ReadAuditRow(
            rs.getLong("id"),
            rs.getString("subject_key"),
            rs.getString("recipient_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getTimestamp("read_at").toInstant());

    private final JdbcTemplate jdbc;

    public ReadAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取审计行。
     *
     * @param id           审计记录编号
     * @param subjectKey   授权主体标识
     * @param recipientKey 执行读取的接收方标识
     * @param purpose      读取用途
     * @param epoch        读取所依据的授权代次
     * @param recordKey    被读取的记录键
     * @param readAt       UTC 读取时刻
     */
    public record ReadAuditRow(long id, String subjectKey, String recipientKey, Purpose purpose, int epoch,
                               String recordKey, Instant readAt) {
    }

    long insert(String subjectKey, String recipientKey, Purpose purpose, int epoch,
                String recordKey, Instant readAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO read_audit (subject_key, recipient_key, purpose, epoch, record_key, read_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, subjectKey);
            ps.setString(2, recipientKey);
            ps.setString(3, purpose.name());
            ps.setInt(4, epoch);
            ps.setString(5, recordKey);
            ps.setTimestamp(6, Timestamp.from(readAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    List<ReadAuditRow> findBySubject(String subjectKey) {
        return jdbc.query(
                "SELECT id, subject_key, recipient_key, purpose, epoch, record_key, read_at"
                        + " FROM read_audit WHERE subject_key = ? ORDER BY id",
                MAPPER, subjectKey);
    }

    int countByRecipientAndRecord(String recipientKey, String subjectKey, Purpose purpose,
                                  int epoch, String recordKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM read_audit"
                        + " WHERE recipient_key = ? AND subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                Integer.class, recipientKey, subjectKey, purpose.name(), epoch, recordKey);
        return count == null ? 0 : count;
    }
}
