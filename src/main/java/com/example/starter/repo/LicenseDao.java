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
 * 许可证告知数据访问：策略、告知文本、告知绑定与发布快照。
 *
 * <p>所有多语句业务操作均在 Service 层写事务内执行，与制品写操作共用
 * {@code repository_state} 行锁串行化，按提交顺序裁决。
 */
@Repository
public class LicenseDao {

    private final JdbcTemplate jdbcTemplate;

    public LicenseDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 许可证策略行。 */
    public record PolicyRow(long id, String scopeType, Long lockFileId,
                            String artifactName, Integer artifactVersion,
                            String licenseId, String action, Instant createdAt) {
    }

    /** 告知文本行。 */
    public record NoticeTextRow(long id, String noticeKey, int version, String licenseId,
                                String body, String regions, String status,
                                Instant createdAt, Instant updatedAt) {
    }

    /** 告知绑定行。 */
    public record BindingRow(long id, String scopeType, Long lockFileId,
                             String artifactName, Integer artifactVersion,
                             String licenseId, String noticeKey, int noticeVersion,
                             Instant createdAt) {
    }

    /** 发布快照批次行。 */
    public record ReleaseRow(long id, String requestId, Instant createdAt) {
    }

    /** 发布快照单图条目行。 */
    public record ReleaseItemRow(long id, long releaseId, long lockFileId,
                                 String rootName, int rootVersion, String regions,
                                 Instant createdAt) {
    }

    /** 发布快照制品条目行。 */
    public record ReleaseEntryRow(long id, long itemId, String name, int version,
                                  boolean direct, String licenseId, String noticeKey,
                                  Integer noticeVersion, String noticeRegions) {
    }

    // ------------------------------------------------------------------
    // 策略
    // ------------------------------------------------------------------

    /** 新增许可证策略，返回自增主键。 */
    public long insertPolicy(String scopeType, Long lockFileId, String artifactName,
                             Integer artifactVersion, String licenseId, String action,
                             String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO license_policy (scope_type, lock_file_id, artifact_name, "
                            + "artifact_version, license_id, action_taken, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scopeType);
            ps.setObject(2, lockFileId);
            ps.setString(3, artifactName);
            ps.setObject(4, artifactVersion);
            ps.setString(5, licenseId);
            ps.setString(6, action);
            ps.setString(7, requestId);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入许可证策略未获取自增主键");
        }
        return key.longValue();
    }

    /** 查询全部策略，按登记 ID 升序（即提交顺序）。 */
    public List<PolicyRow> listPolicies() {
        return jdbcTemplate.query(
                "SELECT id, scope_type, lock_file_id, artifact_name, artifact_version, "
                        + "license_id, action_taken, created_at FROM license_policy ORDER BY id ASC",
                (rs, n) -> new PolicyRow(rs.getLong("id"), rs.getString("scope_type"),
                        (Long) rs.getObject("lock_file_id"), rs.getString("artifact_name"),
                        (Integer) rs.getObject("artifact_version"), rs.getString("license_id"),
                        rs.getString("action_taken"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    // ------------------------------------------------------------------
    // 告知文本
    // ------------------------------------------------------------------

    /** 新增告知文本版本（初始 DRAFT），返回自增主键。 */
    public long insertNoticeText(String noticeKey, int version, String licenseId, String body,
                                 String regions, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO license_notice_text (notice_key, version, license_id, body_text, "
                            + "regions, text_status, request_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, noticeKey);
            ps.setInt(2, version);
            ps.setString(3, licenseId);
            ps.setClob(4, new java.io.StringReader(body));
            ps.setString(5, regions);
            ps.setString(6, requestId);
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入告知文本未获取自增主键");
        }
        return key.longValue();
    }

    /** 按主键查询告知文本，不存在返回 null。 */
    public NoticeTextRow getNoticeTextById(long id) {
        List<NoticeTextRow> rows = jdbcTemplate.query(
                "SELECT id, notice_key, version, license_id, body_text, regions, text_status, "
                        + "created_at, updated_at FROM license_notice_text WHERE id = ?",
                (rs, n) -> mapNoticeText(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按业务键和版本查询告知文本，不存在返回 null。 */
    public NoticeTextRow findNoticeText(String noticeKey, int version) {
        List<NoticeTextRow> rows = jdbcTemplate.query(
                "SELECT id, notice_key, version, license_id, body_text, regions, text_status, "
                        + "created_at, updated_at FROM license_notice_text "
                        + "WHERE notice_key = ? AND version = ?",
                (rs, n) -> mapNoticeText(rs), noticeKey, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某标识全部版本，按版本号升序。 */
    public List<NoticeTextRow> listNoticeTexts(String noticeKey) {
        return jdbcTemplate.query(
                "SELECT id, notice_key, version, license_id, body_text, regions, text_status, "
                        + "created_at, updated_at FROM license_notice_text "
                        + "WHERE notice_key = ? ORDER BY version ASC",
                (rs, n) -> mapNoticeText(rs), noticeKey);
    }

    /** 查询全部告知文本版本，按标识、版本号升序。 */
    public List<NoticeTextRow> listAllNoticeTexts() {
        return jdbcTemplate.query(
                "SELECT id, notice_key, version, license_id, body_text, regions, text_status, "
                        + "created_at, updated_at FROM license_notice_text "
                        + "ORDER BY notice_key ASC, version ASC",
                (rs, n) -> mapNoticeText(rs));
    }

    /** 条件更新文本状态，返回受影响行数（仅当前状态匹配时生效）。 */
    public int updateNoticeStatus(long id, String expectedStatus, String newStatus, Instant now) {
        return jdbcTemplate.update(
                "UPDATE license_notice_text SET text_status = ?, updated_at = ? "
                        + "WHERE id = ? AND text_status = ?",
                newStatus, Timestamp.from(now), id, expectedStatus);
    }

    /** 缩窄地区集合（调用方已校验子集与状态），返回受影响行数。 */
    public int updateNoticeRegions(long id, String regions, Instant now) {
        return jdbcTemplate.update(
                "UPDATE license_notice_text SET regions = ?, updated_at = ? WHERE id = ?",
                regions, Timestamp.from(now), id);
    }

    private NoticeTextRow mapNoticeText(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NoticeTextRow(rs.getLong("id"), rs.getString("notice_key"),
                rs.getInt("version"), rs.getString("license_id"),
                rs.getString("body_text"), rs.getString("regions"),
                rs.getString("text_status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    // ------------------------------------------------------------------
    // 告知绑定
    // ------------------------------------------------------------------

    /** 新增告知绑定，返回自增主键。 */
    public long insertBinding(String scopeType, Long lockFileId, String artifactName,
                              Integer artifactVersion, String licenseId,
                              String noticeKey, int noticeVersion,
                              String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO license_notice_binding (scope_type, lock_file_id, artifact_name, "
                            + "artifact_version, license_id, notice_key, notice_version, "
                            + "request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scopeType);
            ps.setObject(2, lockFileId);
            ps.setString(3, artifactName);
            ps.setObject(4, artifactVersion);
            ps.setString(5, licenseId);
            ps.setString(6, noticeKey);
            ps.setInt(7, noticeVersion);
            ps.setString(8, requestId);
            ps.setTimestamp(9, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入告知绑定未获取自增主键");
        }
        return key.longValue();
    }

    /** 查询全部绑定，按登记 ID 升序（即提交顺序）。 */
    public List<BindingRow> listBindings() {
        return jdbcTemplate.query(
                "SELECT id, scope_type, lock_file_id, artifact_name, artifact_version, "
                        + "license_id, notice_key, notice_version, created_at "
                        + "FROM license_notice_binding ORDER BY id ASC",
                (rs, n) -> new BindingRow(rs.getLong("id"), rs.getString("scope_type"),
                        (Long) rs.getObject("lock_file_id"), rs.getString("artifact_name"),
                        (Integer) rs.getObject("artifact_version"), rs.getString("license_id"),
                        rs.getString("notice_key"), rs.getInt("notice_version"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    // ------------------------------------------------------------------
    // 发布快照
    // ------------------------------------------------------------------

    /** 新增发布批次，返回自增主键。 */
    public long insertRelease(String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_snapshot (request_id, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setTimestamp(2, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入发布快照未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增批次内单图条目，返回自增主键。 */
    public long insertReleaseItem(long releaseId, long lockFileId, String rootName,
                                  int rootVersion, String regions, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_snapshot_item (release_id, lock_file_id, root_name, "
                            + "root_version, regions, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, releaseId);
            ps.setLong(2, lockFileId);
            ps.setString(3, rootName);
            ps.setInt(4, rootVersion);
            ps.setString(5, regions);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入发布快照条目未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增快照制品条目。 */
    public void insertReleaseEntry(long itemId, String name, int version, boolean direct,
                                   String licenseId, String noticeKey, Integer noticeVersion,
                                   String noticeRegions) {
        jdbcTemplate.update(
                "INSERT INTO release_snapshot_entry (item_id, name, version, direct_dep, "
                        + "license_id, notice_key, notice_version, notice_regions) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, itemId);
                    ps.setString(2, name);
                    ps.setInt(3, version);
                    ps.setInt(4, direct ? 1 : 0);
                    ps.setString(5, licenseId);
                    ps.setString(6, noticeKey);
                    ps.setObject(7, noticeVersion);
                    ps.setString(8, noticeRegions);
                });
    }

    /** 查询全部发布批次，按 ID 升序。 */
    public List<ReleaseRow> listReleases() {
        return jdbcTemplate.query(
                "SELECT id, request_id, created_at FROM release_snapshot ORDER BY id ASC",
                (rs, n) -> new ReleaseRow(rs.getLong("id"), rs.getString("request_id"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** 按主键查询发布批次，不存在返回 null。 */
    public ReleaseRow getRelease(long id) {
        List<ReleaseRow> rows = jdbcTemplate.query(
                "SELECT id, request_id, created_at FROM release_snapshot WHERE id = ?",
                (rs, n) -> new ReleaseRow(rs.getLong("id"), rs.getString("request_id"),
                        rs.getTimestamp("created_at").toInstant()), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询批次内全部单图条目，按条目 ID 升序。 */
    public List<ReleaseItemRow> listReleaseItems(long releaseId) {
        return jdbcTemplate.query(
                "SELECT id, release_id, lock_file_id, root_name, root_version, regions, created_at "
                        + "FROM release_snapshot_item WHERE release_id = ? ORDER BY id ASC",
                (rs, n) -> new ReleaseItemRow(rs.getLong("id"), rs.getLong("release_id"),
                        rs.getLong("lock_file_id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getString("regions"),
                        rs.getTimestamp("created_at").toInstant()),
                releaseId);
    }

    /** 查询单图条目内全部制品条目，按名称升序。 */
    public List<ReleaseEntryRow> listReleaseEntries(long itemId) {
        return jdbcTemplate.query(
                "SELECT id, item_id, name, version, direct_dep, license_id, notice_key, "
                        + "notice_version, notice_regions FROM release_snapshot_entry "
                        + "WHERE item_id = ? ORDER BY name ASC",
                (rs, n) -> new ReleaseEntryRow(rs.getLong("id"), rs.getLong("item_id"),
                        rs.getString("name"), rs.getInt("version"),
                        rs.getInt("direct_dep") == 1, rs.getString("license_id"),
                        rs.getString("notice_key"),
                        (Integer) rs.getObject("notice_version"),
                        rs.getString("notice_regions")),
                itemId);
    }
}
