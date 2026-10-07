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
 * 介质迁移记录表访问。记录只追加；每件证物至多一条 PENDING 迁移，
 * 由证物行锁与服务层状态校验共同保证；决定（切换/取消）仅允许一次条件更新。
 */
@Repository
public class MediaMigrationRepository {

    private static final MigrationRowMapper ROW_MAPPER = new MigrationRowMapper();

    private final JdbcTemplate jdbc;

    public MediaMigrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条待完成迁移，固定源介质版本与新介质。
     */
    public void insert(String evidenceKey, long sourceMediaId, long targetMediaId,
                       String createdBy, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO media_migration
                            (evidence_key, source_media_id, target_media_id, status, created_by,
                             witness_id, source_sha256, target_sha256, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?, NULL)
                        """,
                evidenceKey, sourceMediaId, targetMediaId, MigrationStatus.PENDING.name(),
                createdBy, now);
    }

    /**
     * 查询证物当前待完成的迁移（调用前必须已锁定证物行）。
     */
    public Optional<MediaMigration> findPendingByEvidenceKey(String evidenceKey) {
        List<MediaMigration> rows = jdbc.query(
                "SELECT * FROM media_migration WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, MigrationStatus.PENDING.name());
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
     * 记录验证见证者；仅当迁移仍为 PENDING 时生效。
     *
     * @return 是否成功记录（false 表示迁移已被并发决定）
     */
    public boolean recordWitness(long id, String witnessId) {
        int updated = jdbc.update(
                "UPDATE media_migration SET witness_id = ? WHERE id = ? AND status = ?",
                witnessId, id, MigrationStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 切换完成：冻结两介质摘要并写入决定时刻；仅当迁移仍为 PENDING 时生效。
     *
     * @return 是否成功切换（false 表示已被并发取消或切换）
     */
    public boolean complete(long id, String sourceSha256, String targetSha256,
                            LocalDateTime decidedAt) {
        int updated = jdbc.update("""
                        UPDATE media_migration
                        SET status = ?, source_sha256 = ?, target_sha256 = ?, decided_at = ?
                        WHERE id = ? AND status = ?
                        """,
                MigrationStatus.COMPLETED.name(), sourceSha256, targetSha256, decidedAt,
                id, MigrationStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 取消迁移；仅当迁移仍为 PENDING 时生效，源副本不变。
     *
     * @return 是否成功取消（false 表示已被并发切换或取消）
     */
    public boolean cancel(long id, LocalDateTime decidedAt) {
        int updated = jdbc.update(
                "UPDATE media_migration SET status = ?, decided_at = ? WHERE id = ? AND status = ?",
                MigrationStatus.CANCELLED.name(), decidedAt, id, MigrationStatus.PENDING.name());
        return updated == 1;
    }

    private static final class MigrationRowMapper implements RowMapper<MediaMigration> {
        @Override
        public MediaMigration mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MediaMigration(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getLong("source_media_id"),
                    rs.getLong("target_media_id"),
                    MigrationStatus.valueOf(rs.getString("status")),
                    rs.getString("created_by"),
                    rs.getString("witness_id"),
                    rs.getString("source_sha256"),
                    rs.getString("target_sha256"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
