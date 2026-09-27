package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 观测版本附件引用固化持久化：记录只追加不修改，后续脱敏不追溯改写。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class FrozenReferenceRepository {

    private static final RowMapper<FrozenReference> REFERENCE_MAPPER = (rs, rowNum) -> new FrozenReference(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("attachment_id"),
            (Integer) rs.getObject("redaction_version"),
            rs.getTimestamp("frozen_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public FrozenReferenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 固化一条附件引用：记录观测版本生成时该附件引用的脱敏版本（null 表示原始摘要）。
     */
    public void insert(FrozenReference reference) {
        jdbcTemplate.update(
                "INSERT INTO observation_version_attachment "
                        + "(observation_id, version, attachment_id, redaction_version, frozen_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                reference.observationId(), reference.version(), reference.attachmentId(),
                reference.redactionVersion(), Timestamp.from(reference.frozenAtUtc()));
    }

    /**
     * 查询指定观测版本固化的全部附件引用，按附件标识排序，保证顺序稳定。
     */
    public List<FrozenReference> findByObservationVersion(String observationId, int version) {
        return jdbcTemplate.query(
                "SELECT observation_id, version, attachment_id, redaction_version, frozen_at_utc "
                        + "FROM observation_version_attachment "
                        + "WHERE observation_id = ? AND version = ? ORDER BY attachment_id ASC",
                REFERENCE_MAPPER, observationId, version);
    }
}
