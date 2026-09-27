package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 固化证据持久化：观测新版本（合并/人工冲突选择）生成时写入当时各附件引用版本，永不更新。
 */
@Repository
public class EvidenceRepository {

    private static final RowMapper<EvidenceRecord> MAPPER = (rs, rowNum) -> new EvidenceRecord(
            rs.getString("observation_id"),
            rs.getInt("observation_version"),
            rs.getString("attachment_id"),
            "ORIGINAL".equals(rs.getString("ref_type")),
            rs.getString("redaction_id"),
            (Integer) rs.getObject("redaction_version"),
            rs.getString("rule_version"),
            rs.getTimestamp("fixed_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条固化证据。
     */
    public void insert(EvidenceRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_evidence (observation_id, observation_version, attachment_id, "
                        + "ref_type, redaction_id, redaction_version, rule_version, fixed_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.observationId(), record.observationVersion(), record.attachmentId(),
                record.original() ? "ORIGINAL" : "REDACTION", record.redactionId(),
                record.redactionVersion(), record.ruleVersion(), Timestamp.from(record.fixedAtUtc()));
    }

    /**
     * 查询某观测版本固化的全部附件证据，按附件标识升序（历史排序稳定）。
     */
    public List<EvidenceRecord> findByVersion(String observationId, int observationVersion) {
        return jdbcTemplate.query(
                "SELECT observation_id, observation_version, attachment_id, ref_type, redaction_id, "
                        + "redaction_version, rule_version, fixed_at_utc FROM observation_evidence "
                        + "WHERE observation_id = ? AND observation_version = ? ORDER BY attachment_id ASC",
                MAPPER, observationId, observationVersion);
    }
}
