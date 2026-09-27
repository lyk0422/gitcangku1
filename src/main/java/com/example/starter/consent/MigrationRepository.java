package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 跨区域迁移持久化访问：迁移主单与对象明细仅在整批成功提交时写入，失败不留任何行。
 */
@Repository
public class MigrationRepository {

    private static final RowMapper<MigrationRow> MAPPER = (rs, rowNum) -> new MigrationRow(
            rs.getString("migration_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("source_epoch"),
            rs.getString("source_region"),
            rs.getInt("target_epoch"),
            rs.getString("target_region"),
            rs.getInt("object_count"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<MigrationItemRow> ITEM_MAPPER = (rs, rowNum) -> new MigrationItemRow(
            rs.getString("migration_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("source_epoch"),
            rs.getInt("target_epoch"),
            rs.getString("record_key"),
            rs.getString("source_region"),
            rs.getString("target_region"));

    private final JdbcTemplate jdbc;

    public MigrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 迁移主单行。
     */
    public record MigrationRow(String migrationId, String subjectKey, Purpose purpose,
                               int sourceEpoch, String sourceRegion,
                               int targetEpoch, String targetRegion,
                               int objectCount, String status, Instant createdAt) {
    }

    /**
     * 迁移对象明细行。
     */
    public record MigrationItemRow(String migrationId, String subjectKey, Purpose purpose,
                                   int sourceEpoch, int targetEpoch, String recordKey,
                                   String sourceRegion, String targetRegion) {
    }

    Optional<MigrationRow> find(String migrationId) {
        List<MigrationRow> rows = jdbc.query(
                "SELECT migration_id, subject_key, purpose, source_epoch, source_region, target_epoch,"
                        + " target_region, object_count, status, created_at FROM residency_migration"
                        + " WHERE migration_id = ?",
                MAPPER, migrationId);
        return rows.stream().findFirst();
    }

    List<MigrationRow> listBySubject(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT migration_id, subject_key, purpose, source_epoch, source_region, target_epoch,"
                        + " target_region, object_count, status, created_at FROM residency_migration"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY created_at ASC, migration_id ASC",
                MAPPER, subjectKey, purpose.name());
    }

    List<MigrationItemRow> listItems(String migrationId) {
        return jdbc.query(
                "SELECT migration_id, subject_key, purpose, source_epoch, target_epoch, record_key,"
                        + " source_region, target_region FROM residency_migration_item"
                        + " WHERE migration_id = ? ORDER BY record_key ASC",
                ITEM_MAPPER, migrationId);
    }

    void insert(MigrationRow row) {
        jdbc.update(
                "INSERT INTO residency_migration (migration_id, subject_key, purpose, source_epoch,"
                        + " source_region, target_epoch, target_region, object_count, status, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.migrationId(), row.subjectKey(), row.purpose().name(), row.sourceEpoch(),
                row.sourceRegion(), row.targetEpoch(), row.targetRegion(), row.objectCount(),
                row.status(), row.migrationId());
    }

    void insertItem(MigrationItemRow row) {
        jdbc.update(
                "INSERT INTO residency_migration_item (migration_id, subject_key, purpose, source_epoch,"
                        + " target_epoch, record_key, source_region, target_region)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.migrationId(), row.subjectKey(), row.purpose().name(), row.sourceEpoch(),
                row.targetEpoch(), row.recordKey(), row.sourceRegion(), row.targetRegion());
    }
}
