package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代次与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"));

    private static final RowMapper<RecordVersionRow> VERSION_MAPPER = (rs, rowNum) -> new RecordVersionRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getInt("record_version"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权代次行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      代次，从 1 开始
     * @param status     状态：ACTIVE 有效 / REVOKED 已撤回
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param recordKey  记录键
     * @param payload    记录初始版本（v1）内容
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload) {
    }

    /**
     * 记录版本行：每次写入或更正追加一个完整版本，旧版本保留。
     *
     * @param subjectKey    主体标识
     * @param purpose       用途
     * @param epoch         所属代次
     * @param recordKey     记录键
     * @param recordVersion 记录版本号，从 1 开始递增
     * @param payload       该版本的完整记录内容
     */
    public record RecordVersionRow(String subjectKey, Purpose purpose, int epoch, String recordKey,
                                   int recordVersion, String payload) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 读取授权代次并加行锁（FOR UPDATE），用于更正与撤回的原子判定。
     */
    Optional<GrantRow> findGrantForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<GrantRow> findLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    void insertGrant(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, requestId);
    }

    /**
     * 仅当代次当前为 ACTIVE 时撤回；返回是否实际发生状态变更。
     */
    boolean revokeGrant(String subjectKey, Purpose purpose, int epoch) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    Optional<RecordRow> findRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload, requestId);
    }

    Optional<RecordVersionRow> findVersion(String subjectKey, Purpose purpose, int epoch,
                                           String recordKey, int recordVersion) {
        List<RecordVersionRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, record_version, payload"
                        + " FROM consent_record_version"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " AND record_version = ?",
                VERSION_MAPPER, subjectKey, purpose.name(), epoch, recordKey, recordVersion);
        return rows.stream().findFirst();
    }

    Optional<RecordVersionRow> findLatestVersion(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<RecordVersionRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, record_version, payload"
                        + " FROM consent_record_version"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " ORDER BY record_version DESC LIMIT 1",
                VERSION_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insertVersion(String subjectKey, Purpose purpose, int epoch, String recordKey,
                       int recordVersion, String payload, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record_version"
                        + " (subject_key, purpose, epoch, record_key, record_version, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, recordVersion, payload, requestId);
    }
}
