package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 许可证告知数据访问：告知文本版本、许可证策略与发布快照。
 *
 * <p>状态迁移均使用条件更新保证原子判定；所有多语句业务操作在 Service 层
 * 已持有 repository_state 行锁的事务内执行，与制品/锁定写操作按提交顺序串行。
 */
@Repository
public class LicenseDao {

    private final JdbcTemplate jdbcTemplate;

    public LicenseDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------
    // 告知文本
    // ------------------------------------------------------------------

    /** 告知文本版本行。 */
    public record NoticeTextRow(long id, String textKey, int version, String content,
                                String regions, String status, Instant createdAt, Instant updatedAt) {
    }

    /** 新增告知文本版本（初始状态 DRAFT），返回自增主键。 */
    public long insertNoticeText(String textKey, int version, String content, String regions,
                                 String status, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO notice_text (text_key, version, content, regions, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, textKey);
            ps.setInt(2, version);
            ps.setString(3, content);
            ps.setString(4, regions);
            ps.setString(5, status);
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入告知文本未获取自增主键");
        }
        return key.longValue();
    }

    /** 按族键与版本查询告知文本，不存在返回 null。 */
    public NoticeTextRow findNoticeText(String textKey, int version) {
        List<NoticeTextRow> rows = jdbcTemplate.query(
                "SELECT id, text_key, version, content, regions, status, created_at, updated_at "
                        + "FROM notice_text WHERE text_key = ? AND version = ?",
                (rs, n) -> new NoticeTextRow(rs.getLong("id"), rs.getString("text_key"),
                        rs.getInt("version"), rs.getString("content"), rs.getString("regions"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                textKey, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 仅当当前状态等于期望状态时迁移，返回受影响行数（0 表示状态冲突）。 */
    public int transitionNoticeTextStatus(long id, String expectedStatus, String newStatus, Instant now) {
        return jdbcTemplate.update(
                "UPDATE notice_text SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                newStatus, Timestamp.from(now), id, expectedStatus);
    }

    /** 仅当当前未撤销时置为 WITHDRAWN，返回受影响行数（0 表示已撤销）。 */
    public int withdrawNoticeText(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE notice_text SET status = 'WITHDRAWN', updated_at = ? "
                        + "WHERE id = ? AND status <> 'WITHDRAWN'",
                Timestamp.from(now), id);
    }

    /** 仅当当前未被撤销时缩窄地区覆盖，返回受影响行数。 */
    public int narrowNoticeTextRegions(long id, String regions, Instant now) {
        return jdbcTemplate.update(
                "UPDATE notice_text SET regions = ?, updated_at = ? WHERE id = ? AND status <> 'WITHDRAWN'",
                regions, Timestamp.from(now), id);
    }

    // ------------------------------------------------------------------
    // 许可证策略
    // ------------------------------------------------------------------

    /** 许可证策略行。 */
    public record PolicyRow(long id, String scopeType, Long lockFileId, String artifactName,
                            Integer artifactVersion, String noticeType, String textKey,
                            Integer textVersion, Instant createdAt) {
    }

    /** 新增许可证策略，返回自增主键。 */
    public long insertPolicy(String scopeType, Long lockFileId, String artifactName,
                             Integer artifactVersion, String noticeType, String textKey,
                             Integer textVersion, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO license_policy (scope_type, lock_file_id, artifact_name, artifact_version, "
                            + "notice_type, text_key, text_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scopeType);
            if (lockFileId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, lockFileId);
            }
            ps.setString(3, artifactName);
            if (artifactVersion == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, artifactVersion);
            }
            ps.setString(5, noticeType);
            ps.setString(6, textKey);
            if (textVersion == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, textVersion);
            }
            ps.setTimestamp(8, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入许可证策略未获取自增主键");
        }
        return key.longValue();
    }

    /** 查询全部许可证策略，按 ID 升序。 */
    public List<PolicyRow> listPolicies() {
        return jdbcTemplate.query(
                "SELECT id, scope_type, lock_file_id, artifact_name, artifact_version, notice_type, "
                        + "text_key, text_version, created_at FROM license_policy ORDER BY id ASC",
                (rs, n) -> new PolicyRow(rs.getLong("id"), rs.getString("scope_type"),
                        (Long) rs.getObject("lock_file_id"), rs.getString("artifact_name"),
                        (Integer) rs.getObject("artifact_version"), rs.getString("notice_type"),
                        rs.getString("text_key"), (Integer) rs.getObject("text_version"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    // ------------------------------------------------------------------
    // 发布快照
    // ------------------------------------------------------------------

    /** 发布快照主记录行。 */
    public record SnapshotRow(long id, String noticeKey, String targetRegions, Instant createdAt) {
    }

    /** 发布快照锁定图行。 */
    public record SnapshotLockRow(long id, long snapshotId, long lockFileId, String rootName,
                                  int rootVersion, long repositoryVersion) {
    }

    /** 发布快照条目行。 */
    public record SnapshotEntryRow(long id, long snapshotLockId, String artifactName,
                                   int artifactVersion, Long policyId, String textKey,
                                   Integer textVersion, String noticeRegions, String hitPath) {
    }

    /** 新增发布快照主记录，返回自增主键。 */
    public long insertSnapshot(String noticeKey, String targetRegions, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO publish_snapshot (notice_key, target_regions, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, noticeKey);
            ps.setString(2, targetRegions);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入发布快照未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增快照锁定图记录，返回自增主键。 */
    public long insertSnapshotLock(long snapshotId, long lockFileId, String rootName,
                                   int rootVersion, long repositoryVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO publish_snapshot_lock (snapshot_id, lock_file_id, root_name, root_version, "
                            + "repository_version) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, snapshotId);
            ps.setLong(2, lockFileId);
            ps.setString(3, rootName);
            ps.setInt(4, rootVersion);
            ps.setLong(5, repositoryVersion);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入快照锁定图未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增快照条目（每个制品每条命中策略一行）。 */
    public void insertSnapshotEntry(long snapshotLockId, String artifactName, int artifactVersion,
                                    Long policyId, String textKey, Integer textVersion,
                                    String noticeRegions, String hitPath) {
        jdbcTemplate.update(
                "INSERT INTO publish_snapshot_entry (snapshot_lock_id, artifact_name, artifact_version, "
                        + "policy_id, text_key, text_version, notice_regions, hit_path) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshotLockId, artifactName, artifactVersion, policyId, textKey, textVersion,
                noticeRegions, hitPath);
    }

    /** 查询全部发布快照主记录，按 ID 升序。 */
    public List<SnapshotRow> listSnapshots() {
        return jdbcTemplate.query(
                "SELECT id, notice_key, target_regions, created_at FROM publish_snapshot ORDER BY id ASC",
                (rs, n) -> new SnapshotRow(rs.getLong("id"), rs.getString("notice_key"),
                        rs.getString("target_regions"), rs.getTimestamp("created_at").toInstant()));
    }

    /** 按主键查询发布快照，不存在返回 null。 */
    public SnapshotRow findSnapshot(long id) {
        List<SnapshotRow> rows = jdbcTemplate.query(
                "SELECT id, notice_key, target_regions, created_at FROM publish_snapshot WHERE id = ?",
                (rs, n) -> new SnapshotRow(rs.getLong("id"), rs.getString("notice_key"),
                        rs.getString("target_regions"), rs.getTimestamp("created_at").toInstant()),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询快照的全部锁定图，按锁文件 ID 升序。 */
    public List<SnapshotLockRow> listSnapshotLocks(long snapshotId) {
        return jdbcTemplate.query(
                "SELECT id, snapshot_id, lock_file_id, root_name, root_version, repository_version "
                        + "FROM publish_snapshot_lock WHERE snapshot_id = ? ORDER BY lock_file_id ASC",
                (rs, n) -> new SnapshotLockRow(rs.getLong("id"), rs.getLong("snapshot_id"),
                        rs.getLong("lock_file_id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version")),
                snapshotId);
    }

    /** 查询快照锁定图的全部条目，按制品名称、策略 ID 升序。 */
    public List<SnapshotEntryRow> listSnapshotEntries(long snapshotLockId) {
        return jdbcTemplate.query(
                "SELECT id, snapshot_lock_id, artifact_name, artifact_version, policy_id, text_key, "
                        + "text_version, notice_regions, hit_path FROM publish_snapshot_entry "
                        + "WHERE snapshot_lock_id = ? ORDER BY artifact_name ASC, policy_id ASC",
                (rs, n) -> new SnapshotEntryRow(rs.getLong("id"), rs.getLong("snapshot_lock_id"),
                        rs.getString("artifact_name"), rs.getInt("artifact_version"),
                        (Long) rs.getObject("policy_id"), rs.getString("text_key"),
                        (Integer) rs.getObject("text_version"), rs.getString("notice_regions"),
                        rs.getString("hit_path")),
                snapshotLockId);
    }
}
