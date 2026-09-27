package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.consent.dto.GrantResidencyView;

/**
 * 授权代次与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getString("region_code"),
            rs.getInt("consent_version"));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"),
            rs.getString("region_code"),
            toInstant(rs.getTimestamp("erased_at")));

    private static final RowMapper<GrantResidencyView> GRANT_VIEW_MAPPER = (rs, rowNum) -> new GrantResidencyView(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getString("region_code"),
            rs.getInt("consent_version"),
            rs.getInt("record_count"),
            rs.getInt("erased_count"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("revoked_at")));

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权代次行。
     *
     * @param subjectKey     主体标识
     * @param purpose        用途
     * @param epoch          代次，从 1 开始
     * @param status         状态：ACTIVE 有效 / REVOKED 已撤回
     * @param regionCode     本代登记的驻留区域代码；NULL 表示未登记
     * @param consentVersion 授权版本号，创建为 1，状态变更时递增
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status,
                           String regionCode, int consentVersion) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param recordKey  记录键
     * @param payload    记录内容；已清除为 NULL
     * @param regionCode 写入时的驻留区域路由；NULL 表示无区域
     * @param erasedAt   清除时间（UTC）；未清除为 NULL
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String recordKey,
                            String payload, String regionCode, Instant erasedAt) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, region_code, consent_version FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<GrantRow> findLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, region_code, consent_version FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    void insertGrant(String subjectKey, Purpose purpose, int epoch, String regionCode, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, region_code, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                subjectKey, purpose.name(), epoch, regionCode, requestId);
    }

    /**
     * 仅当代次当前为 ACTIVE 时撤回；撤回同时递增授权版本号。返回是否实际发生状态变更。
     */
    boolean revokeGrant(String subjectKey, Purpose purpose, int epoch, Instant revokedAt) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = ?,"
                        + " consent_version = consent_version + 1"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    Optional<RecordRow> findRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload, region_code, erased_at"
                        + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 跨全部代次查找记录键，按代次升序返回；用于迁移时识别“不同区域对象”。
     */
    List<RecordRow> findRecordsAnyEpoch(String subjectKey, Purpose purpose, String recordKey) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload, region_code, erased_at"
                        + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND record_key = ? ORDER BY epoch",
                RECORD_MAPPER, subjectKey, purpose.name(), recordKey);
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey,
                      String payload, String regionCode, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, region_code, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload, regionCode, requestId);
    }

    /**
     * 清除记录：内容置 NULL 并记录清除时间；仅当记录尚未清除时生效，返回是否实际清除。
     */
    boolean eraseRecord(String subjectKey, Purpose purpose, int epoch, String recordKey, Instant erasedAt) {
        int updated = jdbc.update(
                "UPDATE consent_record SET payload = NULL, erased_at = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " AND erased_at IS NULL",
                Timestamp.from(erasedAt), subjectKey, purpose.name(), epoch, recordKey);
        return updated > 0;
    }

    /**
     * 授权代次驻留诊断视图：按代次升序返回每个代次的区域、授权版本与记录统计。
     */
    List<GrantResidencyView> findGrantViews(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT g.subject_key, g.purpose, g.epoch, g.status, g.region_code, g.consent_version,"
                        + " g.created_at, g.revoked_at,"
                        + " COUNT(r.record_key) AS record_count,"
                        + " COALESCE(SUM(CASE WHEN r.erased_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS erased_count"
                        + " FROM consent_grant g"
                        + " LEFT JOIN consent_record r ON r.subject_key = g.subject_key"
                        + " AND r.purpose = g.purpose AND r.epoch = g.epoch"
                        + " WHERE g.subject_key = ? AND g.purpose = ?"
                        + " GROUP BY g.subject_key, g.purpose, g.epoch, g.status, g.region_code,"
                        + " g.consent_version, g.created_at, g.revoked_at"
                        + " ORDER BY g.epoch",
                GRANT_VIEW_MAPPER, subjectKey, purpose.name());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
