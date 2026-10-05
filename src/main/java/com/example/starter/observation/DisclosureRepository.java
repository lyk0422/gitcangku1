package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 披露快照与附页持久化：disclosure_snapshot 每簇至多一行（首次发布，不可变）；
 * disclosure_addendum 记录发布后的更正附页，只追加不改写。所有 SQL 使用参数化查询。
 */
@Repository
public class DisclosureRepository {

    private static final TypeReference<List<DisclosureResolutionItem>> RESOLUTIONS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> FLAGS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DisclosureRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入首次披露快照；observation_id 主键冲突时抛出重复键异常（并发发布由上层重读后返回同一快照）。
     */
    public void insertSnapshot(DisclosureSnapshotRecord record) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_snapshot (observation_id, winning_version, location, reading, note, deleted, "
                        + "conflict_resolutions, quality_flags, embargo_until_utc, operator, request_id, "
                        + "released_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                record.winningVersion(),
                record.location(),
                record.reading(),
                record.note(),
                record.deleted(),
                writeJson(record.conflictResolutions()),
                writeJson(record.qualityFlags()),
                Timestamp.from(record.embargoUntilUtc()),
                record.operator(),
                record.requestId(),
                Timestamp.from(record.releasedAtUtc()));
    }

    /**
     * 按观测记录查询首次披露快照；未发布时返回空。
     */
    public Optional<DisclosureSnapshotRecord> findSnapshot(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, winning_version, location, reading, note, deleted, "
                                + "conflict_resolutions, quality_flags, embargo_until_utc, operator, request_id, "
                                + "released_at_utc FROM disclosure_snapshot WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 判断是否已生成首次披露快照。
     */
    public boolean snapshotExists(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disclosure_snapshot WHERE observation_id = ?", Integer.class, observationId);
        return count != null && count > 0;
    }

    /**
     * 追加一条更正附页：序号取当前最大值加一。调用方需持有该观测记录的行锁（写事务内），
     * 保证同一观测记录的附页序号连续且无并发空洞。
     */
    public void insertAddendum(ObservationSnapshot version, String requestId,
                               String operator, Instant createdAtUtc) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM disclosure_addendum WHERE observation_id = ?",
                Integer.class, version.observationId());
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        jdbcTemplate.update(
                "INSERT INTO disclosure_addendum (observation_id, seq, version, location, reading, note, deleted, "
                        + "request_id, operator, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                version.observationId(),
                nextSeq,
                version.version(),
                version.location(),
                version.reading(),
                version.note(),
                version.deleted(),
                requestId,
                operator,
                Timestamp.from(createdAtUtc));
    }

    /**
     * 按观测记录查询全部更正附页，按附页序号升序稳定排序。
     */
    public List<AddendumRecord> findAddenda(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, seq, version, location, reading, note, deleted, request_id, operator, "
                        + "created_at_utc FROM disclosure_addendum WHERE observation_id = ? ORDER BY seq ASC",
                ADDENDUM_MAPPER, observationId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize disclosure fields", e);
        }
    }

    private List<DisclosureResolutionItem> readResolutions(String json) {
        try {
            return objectMapper.readValue(json, RESOLUTIONS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize conflict resolutions", e);
        }
    }

    private List<String> readFlags(String json) {
        try {
            return objectMapper.readValue(json, FLAGS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize quality flags", e);
        }
    }

    private final RowMapper<DisclosureSnapshotRecord> SNAPSHOT_MAPPER = (rs, rowNum) -> new DisclosureSnapshotRecord(
            rs.getString("observation_id"),
            rs.getInt("winning_version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"),
            readResolutions(rs.getString("conflict_resolutions")),
            readFlags(rs.getString("quality_flags")),
            rs.getTimestamp("embargo_until_utc").toInstant(),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getTimestamp("released_at_utc").toInstant());

    private static final RowMapper<AddendumRecord> ADDENDUM_MAPPER = (rs, rowNum) -> new AddendumRecord(
            rs.getString("observation_id"),
            rs.getInt("seq"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"),
            rs.getString("request_id"),
            rs.getString("operator"),
            rs.getTimestamp("created_at_utc").toInstant());
}
