package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 禁运与未解决冲突标记持久化。所有 SQL 使用参数化查询。
 */
@Repository
public class EmbargoRepository {

    private static final RowMapper<EmbargoRecord> EMBARGO_MAPPER = (rs, rowNum) -> new EmbargoRecord(
            rs.getString("observation_id"),
            rs.getTimestamp("embargo_until_utc").toInstant(),
            rs.getString("disclosure_scope"),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getTimestamp("set_at_utc").toInstant());

    private static final RowMapper<PendingConflictRecord> PENDING_MAPPER = (rs, rowNum) -> new PendingConflictRecord(
            rs.getString("observation_id"),
            rs.getString("conflict_fields"),
            rs.getInt("base_version"),
            rs.getInt("current_version"),
            rs.getTimestamp("detected_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EmbargoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按观测记录查询禁运设置；不存在时返回空。
     */
    public Optional<EmbargoRecord> findEmbargo(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, embargo_until_utc, disclosure_scope, operator, request_id, set_at_utc "
                                + "FROM observation_embargo WHERE observation_id = ?",
                        EMBARGO_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 插入禁运设置；同一簇重复设置由主键约束拒绝（DuplicateKeyException）。
     */
    public void insertEmbargo(EmbargoRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_embargo (observation_id, embargo_until_utc, disclosure_scope, "
                        + "operator, request_id, set_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                record.observationId(), Timestamp.from(record.embargoUntilUtc()), record.disclosureScope(),
                record.operator(), record.requestId(), Timestamp.from(record.setAtUtc()));
    }

    /**
     * 查询未解决冲突标记；不存在时返回空。
     */
    public Optional<PendingConflictRecord> findPendingConflict(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, conflict_fields, base_version, current_version, detected_at_utc "
                                + "FROM observation_pending_conflict WHERE observation_id = ?",
                        PENDING_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 写入未解决冲突标记（首次插入）。
     */
    public void insertPendingConflict(PendingConflictRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_pending_conflict (observation_id, conflict_fields, base_version, "
                        + "current_version, detected_at_utc) VALUES (?, ?, ?, ?, ?)",
                record.observationId(), record.conflictFieldsJson(), record.baseVersion(),
                record.currentVersion(), Timestamp.from(record.detectedAtUtc()));
    }

    /**
     * 刷新未解决冲突标记（同一簇再次冲突时更新为最近一次）。
     */
    public void updatePendingConflict(PendingConflictRecord record) {
        jdbcTemplate.update(
                "UPDATE observation_pending_conflict SET conflict_fields = ?, base_version = ?, "
                        + "current_version = ?, detected_at_utc = ? WHERE observation_id = ?",
                record.conflictFieldsJson(), record.baseVersion(), record.currentVersion(),
                Timestamp.from(record.detectedAtUtc()), record.observationId());
    }

    /**
     * 清除未解决冲突标记（显式解决或删除记录时，在调用方事务内执行）。
     */
    public void deletePendingConflict(String observationId) {
        jdbcTemplate.update("DELETE FROM observation_pending_conflict WHERE observation_id = ?", observationId);
    }
}