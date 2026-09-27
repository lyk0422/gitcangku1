package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 跨对象查询快照持久化访问：快照主单与对象明细只在查询成功时写入，之后任何业务操作都不改写。
 */
@Repository
public class QuerySnapshotRepository {

    private static final RowMapper<SnapshotRow> MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("snapshot_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getString("requested_regions"),
            rs.getString("params_fingerprint"),
            rs.getInt("object_count"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SnapshotItemRow> ITEM_MAPPER = (rs, rowNum) -> new SnapshotItemRow(
            rs.getString("snapshot_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getString("record_key"),
            rs.getInt("epoch"),
            rs.getString("region_code"),
            rs.getInt("consent_version"),
            rs.getString("payload"),
            rs.getInt("ordinal"));

    private final JdbcTemplate jdbc;

    public QuerySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 快照主单行。
     */
    public record SnapshotRow(String snapshotId, String subjectKey, Purpose purpose,
                              String requestedRegions, String paramsFingerprint,
                              int objectCount, String responseBody, Instant createdAt) {
    }

    /**
     * 快照对象明细行。
     */
    public record SnapshotItemRow(String snapshotId, String subjectKey, Purpose purpose, String recordKey,
                                  int epoch, String regionCode, int consentVersion, String payload, int ordinal) {
    }

    Optional<SnapshotRow> find(String snapshotId) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT snapshot_id, subject_key, purpose, requested_regions, params_fingerprint,"
                        + " object_count, response_body, created_at FROM query_snapshot WHERE snapshot_id = ?",
                MAPPER, snapshotId);
        return rows.stream().findFirst();
    }

    List<SnapshotItemRow> listItems(String snapshotId) {
        return jdbc.query(
                "SELECT snapshot_id, subject_key, purpose, record_key, epoch, region_code, consent_version,"
                        + " payload, ordinal FROM query_snapshot_item WHERE snapshot_id = ? ORDER BY ordinal ASC",
                ITEM_MAPPER, snapshotId);
    }

    void insert(SnapshotRow row) {
        jdbc.update(
                "INSERT INTO query_snapshot (snapshot_id, subject_key, purpose, requested_regions,"
                        + " params_fingerprint, object_count, response_body) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.snapshotId(), row.subjectKey(), row.purpose().name(), row.requestedRegions(),
                row.paramsFingerprint(), row.objectCount(), row.responseBody());
    }

    void insertItem(SnapshotItemRow row) {
        jdbc.update(
                "INSERT INTO query_snapshot_item (snapshot_id, subject_key, purpose, record_key, epoch,"
                        + " region_code, consent_version, payload, ordinal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.snapshotId(), row.subjectKey(), row.purpose().name(), row.recordKey(), row.epoch(),
                row.regionCode(), row.consentVersion(), row.payload(), row.ordinal());
    }
}
