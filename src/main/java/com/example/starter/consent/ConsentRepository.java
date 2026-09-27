package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代次与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 *
 * <p>时间统一以 UTC 写入；记录的驻留区域与授权版本在写入/复制时固化，后续撤回或清除不改写历史列。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getString("region_code"),
            toInstant(rs.getTimestamp("revoked_at")),
            rs.getString("revoke_reason"));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"),
            rs.getString("region_code"),
            rs.getInt("consent_version"),
            toInstant(rs.getTimestamp("erased_at")),
            rs.getString("erase_request_id"));

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 授权代次行。
     *
     * @param subjectKey   主体标识
     * @param purpose      用途
     * @param epoch        代次，从 1 开始
     * @param status       状态：ACTIVE 有效 / REVOKED 已撤回
     * @param regionCode   本 epoch 绑定的驻留区域代码
     * @param revokedAt    撤回时间（UTC），未撤回为 null
     * @param revokeReason 撤回原因：USER_REVOKE / MIGRATION，未撤回为 null
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status,
                           String regionCode, Instant revokedAt, String revokeReason) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey      主体标识
     * @param purpose         用途
     * @param epoch           所属代次
     * @param recordKey       记录键
     * @param payload         记录内容；清除后为 null（null 语义稳定）
     * @param regionCode      驻留区域代码，清除后仍保留原值作为历史证据
     * @param consentVersion  授权版本：写入固化为 1，迁移复制的副本为源版本 +1
     * @param erasedAt        清除时间（UTC），未清除为 null
     * @param eraseRequestId  清除请求标识，未清除为 null
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload,
                            String regionCode, int consentVersion, Instant erasedAt, String eraseRequestId) {

        /**
         * 是否已清除：payload 已置空。
         */
        public boolean erased() {
            return payload == null;
        }
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, region_code, revoked_at, revoke_reason"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<GrantRow> findLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, region_code, revoked_at, revoke_reason"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 锁定该“主体＋用途”的最新代次行，串行化并发授权与迁移；无代次时返回空。
     */
    Optional<GrantRow> lockLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, region_code, revoked_at, revoke_reason"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1 FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 列出该“主体＋用途”的全部代次，按代次升序，供历史诊断查询。
     */
    List<GrantRow> listGrants(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, status, region_code, revoked_at, revoke_reason"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch ASC",
                GRANT_MAPPER, subjectKey, purpose.name());
    }

    void insertGrant(String subjectKey, Purpose purpose, int epoch, String regionCode, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, region_code, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                subjectKey, purpose.name(), epoch, regionCode, requestId);
    }

    /**
     * 仅当代次当前为 ACTIVE 时撤回；返回是否实际发生状态变更。撤回原因与时间由调用方给定（UTC）。
     */
    boolean revokeGrant(String subjectKey, Purpose purpose, int epoch, RevokeReason reason, Instant revokedAt) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = ?, revoke_reason = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), reason.name(), subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    Optional<RecordRow> findRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload, region_code, consent_version,"
                        + " erased_at, erase_request_id FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 跨代次查找同记录键（用于迁移预检区分“对象不存在”与“对象属于其他 epoch”）。
     */
    Optional<RecordRow> findRecordAnyEpoch(String subjectKey, Purpose purpose, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload, region_code, consent_version,"
                        + " erased_at, erase_request_id FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND record_key = ? ORDER BY epoch DESC LIMIT 1",
                RECORD_MAPPER, subjectKey, purpose.name(), recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 批量取出指定代次内的对象（参数化 IN），按 record_key 升序，保持快照排序稳定。
     */
    List<RecordRow> findRecords(String subjectKey, Purpose purpose, int epoch, Collection<String> recordKeys) {
        if (recordKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", recordKeys.stream().map(key -> "?").toList());
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload, region_code, consent_version,"
                        + " erased_at, erase_request_id FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND record_key IN (" + placeholders + ") ORDER BY record_key ASC",
                RECORD_MAPPER, mergeArgs(subjectKey, purpose.name(), epoch, recordKeys));
    }

    private Object[] mergeArgs(String subjectKey, String purpose, int epoch, Collection<String> recordKeys) {
        Object[] args = new Object[3 + recordKeys.size()];
        args[0] = subjectKey;
        args[1] = purpose;
        args[2] = epoch;
        int i = 3;
        for (String key : recordKeys) {
            args[i++] = key;
        }
        return args;
    }

    /**
     * 列出该“主体＋用途”的全部记录（含已撤回代次与已清除行），按代次与记录键升序，供历史诊断。
     */
    List<RecordRow> listRecords(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload, region_code, consent_version,"
                        + " erased_at, erase_request_id FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch ASC, record_key ASC",
                RECORD_MAPPER, subjectKey, purpose.name());
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload,
                      String regionCode, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, region_code,"
                        + " request_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload, regionCode, requestId);
    }

    /**
     * 迁移复制：副本驻留目标区域，授权版本 = 源版本 + 1。
     */
    void copyRecord(RecordRow source, int targetEpoch, String targetRegion, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, region_code,"
                        + " request_id, consent_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                source.subjectKey(), source.purpose().name(), targetEpoch, source.recordKey(),
                source.payload(), targetRegion, requestId, source.consentVersion() + 1);
    }

    /**
     * 清除单对象内容：仅置空 payload 与写入清除审计列，驻留区域、版本、创建时间等历史列保持不变。
     * 返回是否实际发生清除（未清除过才为 true）。
     */
    boolean eraseRecord(String subjectKey, Purpose purpose, int epoch, String recordKey,
                        String requestId, Instant erasedAt) {
        int updated = jdbc.update(
                "UPDATE consent_record SET payload = NULL, erased_at = ?, erase_request_id = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " AND erased_at IS NULL",
                Timestamp.from(erasedAt), requestId, subjectKey, purpose.name(), epoch, recordKey);
        return updated > 0;
    }
}
