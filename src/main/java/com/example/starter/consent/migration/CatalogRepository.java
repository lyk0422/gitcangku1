package com.example.starter.consent.migration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 用途目录与迁移证据、查询代次的持久化访问，所有 SQL 均参数化。
 */
@Repository
public class CatalogRepository {

    private static final RowMapper<GenerationRow> GENERATION_MAPPER = (rs, n) -> new GenerationRow(
            rs.getLong("catalog_generation"),
            toInstant(rs.getTimestamp("effective_start")),
            toInstant(rs.getTimestamp("effective_end")),
            rs.getString("migration_key"));

    private static final RowMapper<EntryRow> ENTRY_MAPPER = (rs, n) -> new EntryRow(
            rs.getLong("catalog_generation"),
            rs.getString("purpose"),
            new LongRange(rs.getLong("range_start"), rs.getLong("range_end")),
            rs.getString("supersedes"),
            rs.getString("status"));

    private static final RowMapper<MigrationRow> MIGRATION_MAPPER = (rs, n) -> new MigrationRow(
            rs.getString("migration_key"),
            rs.getLong("catalog_version"),
            rs.getLong("catalog_generation"),
            rs.getString("source_purpose"),
            new LongRange(rs.getLong("source_range_start"), rs.getLong("source_range_end")),
            toInstant(rs.getTimestamp("effective_start")),
            toInstant(rs.getTimestamp("effective_end")),
            rs.getString("status"),
            rs.getString("request_id"),
            toInstant(rs.getTimestamp("applied_at")));

    private static final RowMapper<TargetRow> TARGET_MAPPER = (rs, n) -> new TargetRow(
            rs.getString("migration_key"),
            rs.getString("purpose"),
            new LongRange(rs.getLong("range_start"), rs.getLong("range_end")),
            rs.getString("supersedes"),
            rs.getInt("ordinal"));

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 目录代次行。 */
    public record GenerationRow(long catalogGeneration, Instant effectiveStart, Instant effectiveEnd,
                                String migrationKey) {
    }

    /** 目录条目行。 */
    public record EntryRow(long catalogGeneration, String purpose, LongRange range, String supersedes,
                           String status) {
    }

    /** 迁移证据行。 */
    public record MigrationRow(String migrationKey, long catalogVersion, long catalogGeneration,
                               String sourcePurpose, LongRange sourceRange, Instant effectiveStart,
                               Instant effectiveEnd, String status, String requestId, Instant appliedAt) {
    }

    /** 迁移目标行。 */
    public record TargetRow(String migrationKey, String purpose, LongRange range, String supersedes,
                            int ordinal) {
    }

    public Optional<GenerationRow> findGeneration(long generation) {
        return jdbc.query(
                "SELECT catalog_generation, effective_start, effective_end, migration_key"
                        + " FROM purpose_catalog_generation WHERE catalog_generation = ?",
                GENERATION_MAPPER, generation).stream().findFirst();
    }

    public long latestGeneration() {
        Long value = jdbc.queryForObject(
                "SELECT MAX(catalog_generation) FROM purpose_catalog_generation", Long.class);
        return value == null ? 0L : value;
    }

    public List<EntryRow> findEntries(long generation) {
        return jdbc.query(
                "SELECT catalog_generation, purpose, range_start, range_end, supersedes, status"
                        + " FROM purpose_catalog_entry WHERE catalog_generation = ? ORDER BY purpose",
                ENTRY_MAPPER, generation);
    }

    public Optional<EntryRow> findEntry(long generation, String purpose) {
        return jdbc.query(
                "SELECT catalog_generation, purpose, range_start, range_end, supersedes, status"
                        + " FROM purpose_catalog_entry WHERE catalog_generation = ? AND purpose = ?",
                ENTRY_MAPPER, generation, purpose).stream().findFirst();
    }

    /**
     * 行级锁定指定目录代次行，串行化并发目录迁移。
     */
    public Optional<GenerationRow> lockGeneration(long generation) {
        return jdbc.query(
                "SELECT catalog_generation, effective_start, effective_end, migration_key"
                        + " FROM purpose_catalog_generation WHERE catalog_generation = ? FOR UPDATE",
                GENERATION_MAPPER, generation).stream().findFirst();
    }

    /**
     * 行级锁定某代次下的用途条目，串行化迁移与该用途的授权/写入。
     */
    public Optional<EntryRow> lockEntry(long generation, String purpose) {
        return jdbc.query(
                "SELECT catalog_generation, purpose, range_start, range_end, supersedes, status"
                        + " FROM purpose_catalog_entry WHERE catalog_generation = ? AND purpose = ? FOR UPDATE",
                ENTRY_MAPPER, generation, purpose).stream().findFirst();
    }

    /**
     * 汇总全部历史条目的替代边：替代者代码 -&gt; 被替代代码。
     */
    public java.util.Map<String, String> findAllSupersedes() {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        jdbc.query(
                "SELECT purpose, supersedes FROM purpose_catalog_entry"
                        + " WHERE supersedes IS NOT NULL",
                rs -> {
                    result.put(rs.getString("purpose"), rs.getString("supersedes"));
                });
        return result;
    }

    /**
     * 返回某用途在各代次中的最新条目（用于定位其当前所属目录代次）。
     */
    Optional<EntryRow> findLatestEntryForPurpose(String purpose) {
        return jdbc.query(
                "SELECT catalog_generation, purpose, range_start, range_end, supersedes, status"
                        + " FROM purpose_catalog_entry WHERE purpose = ?"
                        + " ORDER BY catalog_generation DESC LIMIT 1",
                ENTRY_MAPPER, purpose).stream().findFirst();
    }

    public void insertGeneration(GenerationRow row) {
        jdbc.update(
                "INSERT INTO purpose_catalog_generation"
                        + " (catalog_generation, effective_start, effective_end, migration_key)"
                        + " VALUES (?, ?, ?, ?)",
                row.catalogGeneration(),
                row.effectiveStart() == null ? null : Timestamp.from(row.effectiveStart()),
                row.effectiveEnd() == null ? null : Timestamp.from(row.effectiveEnd()),
                row.migrationKey());
    }

    public void insertEntry(EntryRow row) {
        jdbc.update(
                "INSERT INTO purpose_catalog_entry"
                        + " (catalog_generation, purpose, range_start, range_end, supersedes, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.catalogGeneration(), row.purpose(), row.range().start(), row.range().end(),
                row.supersedes(), row.status());
    }

    /**
     * 迁移激活时把旧代次中被拆分的用途条目置为 SUPERSEDED；
     * 使在该条目行锁上等待的授权/写入事务在锁释放后读到最新状态并被拒绝。
     */
    public int updateEntryStatus(long generation, String purpose, String status) {
        return jdbc.update(
                "UPDATE purpose_catalog_entry SET status = ?"
                        + " WHERE catalog_generation = ? AND purpose = ?",
                status, generation, purpose);
    }

    public void insertMigration(MigrationRow row) {
        jdbc.update(
                "INSERT INTO purpose_migration (migration_key, catalog_version, catalog_generation,"
                        + " source_purpose, source_range_start, source_range_end, effective_start,"
                        + " effective_end, status, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.migrationKey(), row.catalogVersion(), row.catalogGeneration(), row.sourcePurpose(),
                row.sourceRange().start(), row.sourceRange().end(),
                Timestamp.from(row.effectiveStart()), Timestamp.from(row.effectiveEnd()),
                row.status(), row.requestId());
    }

    public void insertTarget(TargetRow row) {
        jdbc.update(
                "INSERT INTO purpose_migration_target"
                        + " (migration_key, purpose, range_start, range_end, supersedes, ordinal)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.migrationKey(), row.purpose(), row.range().start(), row.range().end(),
                row.supersedes(), row.ordinal());
    }

    public Optional<MigrationRow> findMigrationByKey(String migrationKey) {
        return jdbc.query(
                "SELECT migration_key, catalog_version, catalog_generation, source_purpose,"
                        + " source_range_start, source_range_end, effective_start, effective_end,"
                        + " status, request_id, applied_at FROM purpose_migration WHERE migration_key = ?",
                MIGRATION_MAPPER, migrationKey).stream().findFirst();
    }

    public List<TargetRow> findTargets(String migrationKey) {
        return jdbc.query(
                "SELECT migration_key, purpose, range_start, range_end, supersedes, ordinal"
                        + " FROM purpose_migration_target WHERE migration_key = ? ORDER BY ordinal",
                TARGET_MAPPER, migrationKey);
    }

    public List<MigrationRow> findAllMigrations() {
        return jdbc.query(
                "SELECT migration_key, catalog_version, catalog_generation, source_purpose,"
                        + " source_range_start, source_range_end, effective_start, effective_end,"
                        + " status, request_id, applied_at FROM purpose_migration ORDER BY catalog_generation",
                MIGRATION_MAPPER);
    }

    public long insertQueryGeneration(long catalogGeneration) {
        jdbc.update(
                "INSERT INTO query_generation (catalog_generation, status) VALUES (?, 'ACTIVE')",
                catalogGeneration);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    public Long findQueryGenerationCatalog(long queryGeneration) {
        List<Long> rows = jdbc.query(
                "SELECT catalog_generation FROM query_generation WHERE query_generation = ?",
                (rs, n) -> rs.getLong(1), queryGeneration);
        return rows.stream().findFirst().orElse(null);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
