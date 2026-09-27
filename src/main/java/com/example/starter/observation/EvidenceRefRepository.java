package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 观测版本证据固化持久化：observation_evidence_ref 行在观测新版本生成时写入，永不更新或删除。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class EvidenceRefRepository {

    private static final RowMapper<EvidenceRefRecord> REF_MAPPER = (rs, rowNum) -> new EvidenceRefRecord(
            rs.getString("observation_id"),
            rs.getInt("observation_version"),
            rs.getString("attachment_id"),
            (Integer) rs.getObject("redaction_version"),
            rs.getTimestamp("frozen_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EvidenceRefRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 固化一条证据引用；主键冲突（同观测同版本同附件重复固化）抛出重复键异常。
     */
    public void insert(EvidenceRefRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_evidence_ref (observation_id, observation_version, attachment_id, "
                        + "redaction_version, frozen_at_utc) VALUES (?, ?, ?, ?, ?)",
                record.observationId(), record.observationVersion(), record.attachmentId(),
                record.redactionVersion(), Timestamp.from(record.frozenAtUtc()));
    }

    /**
     * 查询某观测版本固化的全部证据引用，按附件标识排序，保证证据列表顺序稳定。
     */
    public List<EvidenceRefRecord> findByObservationVersion(String observationId, int observationVersion) {
        return jdbcTemplate.query(
                "SELECT observation_id, observation_version, attachment_id, redaction_version, frozen_at_utc "
                        + "FROM observation_evidence_ref WHERE observation_id = ? AND observation_version = ? "
                        + "ORDER BY attachment_id ASC",
                REF_MAPPER, observationId, observationVersion);
    }
}
