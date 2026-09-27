package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.consent.dto.MigrationView;

/**
 * 驻留区域与迁移历史的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class ResidencyRepository {

    private static final RowMapper<RegionRow> REGION_MAPPER = (rs, rowNum) -> new RegionRow(
            rs.getString("region_code"),
            toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<MigrationView> MIGRATION_MAPPER = (rs, rowNum) -> new MigrationView(
            rs.getLong("migration_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("source_epoch"),
            rs.getInt("target_epoch"),
            rs.getString("source_region"),
            rs.getString("target_region"),
            rs.getInt("requested_count"),
            rs.getInt("copied_count"),
            toInstant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public ResidencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 驻留区域行。
     *
     * @param regionCode 区域代码
     * @param createdAt  登记时间（UTC）
     */
    public record RegionRow(String regionCode, Instant createdAt) {
    }

    Optional<RegionRow> findRegion(String regionCode) {
        List<RegionRow> rows = jdbc.query(
                "SELECT region_code, created_at FROM residency_region WHERE region_code = ?",
                REGION_MAPPER, regionCode);
        return rows.stream().findFirst();
    }

    boolean regionExists(String regionCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM residency_region WHERE region_code = ?", Integer.class, regionCode);
        return count != null && count > 0;
    }

    void insertRegion(String regionCode, String requestId, Instant createdAt) {
        jdbc.update(
                "INSERT INTO residency_region (region_code, request_id, created_at) VALUES (?, ?, ?)",
                regionCode, requestId, Timestamp.from(createdAt));
    }

    /**
     * 全部已登记区域，按区域代码字典序返回，保证稳定排序。
     */
    List<RegionRow> findAllRegions() {
        return jdbc.query(
                "SELECT region_code, created_at FROM residency_region ORDER BY region_code",
                REGION_MAPPER);
    }

    void insertMigration(String requestId, String subjectKey, Purpose purpose,
                         int sourceEpoch, int targetEpoch, String sourceRegion, String targetRegion,
                         int requestedCount, int copiedCount, Instant createdAt) {
        jdbc.update(
                "INSERT INTO residency_migration (request_id, subject_key, purpose, source_epoch, target_epoch,"
                        + " source_region, target_region, requested_count, copied_count, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, subjectKey, purpose.name(), sourceEpoch, targetEpoch,
                sourceRegion, targetRegion, requestedCount, copiedCount, Timestamp.from(createdAt));
    }

    /**
     * 迁移历史：按迁移序号升序返回，保证稳定排序。
     */
    List<MigrationView> findMigrations(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT migration_id, subject_key, purpose, source_epoch, target_epoch, source_region,"
                        + " target_region, requested_count, copied_count, created_at"
                        + " FROM residency_migration WHERE subject_key = ? AND purpose = ?"
                        + " ORDER BY migration_id",
                MIGRATION_MAPPER, subjectKey, purpose.name());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
