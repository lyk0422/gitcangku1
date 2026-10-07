package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 介质迁移表访问。记录只追加；每件证物至多一条待完成迁移，由证物行锁保证。
 * 状态流转均为带期望版本的条件更新，并发竞争按事务提交顺序生效。
 */
@Repository
public class MediaMigrationRepository {

    private static final MigrationRowMapper ROW_MAPPER = new MigrationRowMapper();

    private final JdbcTemplate jdbc;

    public MediaMigrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条待验证迁移记录，初始版本 1；migration_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String migrationKey, String evidenceKey, long sourceMediaId, String sourceMediaNo,
                       int sourceVersion, long targetMediaId, String targetMediaNo,
                       String createdBy, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO media_migration
                            (migration_key, evidence_key, source_media_id, source_media_no, source_version,
                             target_media_id, target_media_no, status, witness_id, version,
                             created_by, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?, ?, NULL)
                        """,
                migrationKey, evidenceKey, sourceMediaId, sourceMediaNo, sourceVersion,
                targetMediaId, targetMediaNo, MigrationStatus.PENDING_VERIFY.name(), createdBy, now);
    }

    /**
     * 按迁移业务键查询。
     */
    public Optional<MediaMigration> findByKey(String migrationKey) {
        List<MediaMigration> rows = jdbc.query(
                "SELECT * FROM media_migration WHERE migration_key = ?", ROW_MAPPER, migrationKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前待完成（PENDING_VERIFY/PENDING_SWITCH）迁移（调用前必须已锁定证物行）。
     */
    public Optional<MediaMigration> findActiveByEvidenceKey(String evidenceKey) {
        List<MediaMigration> rows = jdbc.query("""
                        SELECT * FROM media_migration
                        WHERE evidence_key = ? AND status IN (?, ?)
                        """,
                ROW_MAPPER, evidenceKey,
                MigrationStatus.PENDING_VERIFY.name(), MigrationStatus.PENDING_SWITCH.name());
        return rows.stream().findFirst();
    }

    /**
     * 按发生顺序查询证物全部迁移记录。
     */
    public List<MediaMigration> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM media_migration WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 条件验证通过：仅当仍为 PENDING_VERIFY 且版本匹配时，写入见证人并进入 PENDING_SWITCH。
     *
     * @return 是否成功（false 表示已被并发事务流转或期望版本不匹配）
     */
    public boolean markVerified(long id, int expectedVersion, String witnessId) {
        int updated = jdbc.update("""
                        UPDATE media_migration
                        SET status = ?, witness_id = ?, version = version + 1
                        WHERE id = ? AND status = ? AND version = ?
                        """,
                MigrationStatus.PENDING_SWITCH.name(), witnessId,
                id, MigrationStatus.PENDING_VERIFY.name(), expectedVersion);
        return updated == 1;
    }

    /**
     * 条件决定迁移为终态（SWITCHED/CANCELLED）：仅当当前状态与期望版本均匹配时生效。
     *
     * @return 是否成功决定（false 表示已被并发事务先行决定或期望版本不匹配）
     */
    public boolean decide(long id, int expectedVersion, MigrationStatus from,
                          MigrationStatus decided, LocalDateTime decidedAt) {
        int updated = jdbc.update("""
                        UPDATE media_migration
                        SET status = ?, version = version + 1, decided_at = ?
                        WHERE id = ? AND status = ? AND version = ?
                        """,
                decided.name(), decidedAt, id, from.name(), expectedVersion);
        return updated == 1;
    }

    /**
     * 条件取消迁移：仅当仍待完成（PENDING_VERIFY/PENDING_SWITCH）且期望版本匹配时生效。
     *
     * @return 是否成功取消（false 表示已完结或期望版本不匹配）
     */
    public boolean cancel(long id, int expectedVersion, LocalDateTime decidedAt) {
        int updated = jdbc.update("""
                        UPDATE media_migration
                        SET status = ?, version = version + 1, decided_at = ?
                        WHERE id = ? AND status IN (?, ?) AND version = ?
                        """,
                MigrationStatus.CANCELLED.name(), decidedAt, id,
                MigrationStatus.PENDING_VERIFY.name(), MigrationStatus.PENDING_SWITCH.name(),
                expectedVersion);
        return updated == 1;
    }

    private static final class MigrationRowMapper implements RowMapper<MediaMigration> {
        @Override
        public MediaMigration mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MediaMigration(
                    rs.getLong("id"),
                    rs.getString("migration_key"),
                    rs.getString("evidence_key"),
                    rs.getLong("source_media_id"),
                    rs.getString("source_media_no"),
                    rs.getInt("source_version"),
                    rs.getLong("target_media_id"),
                    rs.getString("target_media_no"),
                    MigrationStatus.valueOf(rs.getString("status")),
                    rs.getString("witness_id"),
                    rs.getInt("version"),
                    rs.getString("created_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
