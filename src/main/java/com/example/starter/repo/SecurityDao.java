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
 * 漏洞公告、豁免与发布快照的数据访问。
 *
 * <p>多语句业务操作均在 Service 层事务内执行；写事务通过
 * {@code repository_state} 行锁串行化，公告更新、豁免确认、撤销、
 * 重解析与发布并发按事务提交顺序裁决。
 */
@Repository
public class SecurityDao {

    private final JdbcTemplate jdbcTemplate;

    public SecurityDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 漏洞公告行。 */
    public record AdvisoryRow(long id, String vulnerabilityId, String artifactName,
                              int artifactVersion, String severity, Instant expiresAt,
                              Instant updatedAt) {
    }

    /** 锁定图漏洞命中行：公告坐标命中锁定图中的某个精确制品版本。 */
    public record HitRow(String vulnerabilityId, String artifactName, int artifactVersion,
                         String severity, Instant advisoryExpiresAt) {
    }

    /** 豁免行；status 为 PENDING/CONFIRMED/REVOKED。 */
    public record ExceptionRow(long id, String exceptionKey, long lockFileId,
                               String vulnerabilityId, Instant expiresAt, String reason,
                               String status, String reviewer1, String reviewer2,
                               Instant createdAt, Instant confirmedAt,
                               String revokedBy, Instant revokedAt) {
    }

    /** 发布快照主记录行。 */
    public record PublishRow(long id, long lockFileId, String rootName, int rootVersion,
                             long repositoryVersion, Instant publishedAt, String requestId) {
    }

    /** 发布快照条目行。 */
    public record PublishEntryRow(long publishId, String name, int version) {
    }

    // 公告 ----------------------------------------------------------------

    /** 新增或按（漏洞编号+坐标）更新公告，返回落库后的行。 */
    public AdvisoryRow upsertAdvisory(String vulnerabilityId, String artifactName,
                                      int artifactVersion, String severity,
                                      Instant expiresAt, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE vulnerability_advisory SET severity = ?, expires_at = ?, updated_at = ? "
                        + "WHERE vulnerability_id = ? AND artifact_name = ? AND artifact_version = ?",
                ps -> {
                    ps.setString(1, severity);
                    ps.setTimestamp(2, Timestamp.from(expiresAt));
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setString(4, vulnerabilityId);
                    ps.setString(5, artifactName);
                    ps.setInt(6, artifactVersion);
                });
        if (updated == 0) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO vulnerability_advisory (vulnerability_id, artifact_name, "
                                + "artifact_version, severity, expires_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, vulnerabilityId);
                ps.setString(2, artifactName);
                ps.setInt(3, artifactVersion);
                ps.setString(4, severity);
                ps.setTimestamp(5, Timestamp.from(expiresAt));
                ps.setTimestamp(6, Timestamp.from(now));
                return ps;
            }, keyHolder);
        }
        List<AdvisoryRow> rows = jdbcTemplate.query(
                "SELECT id, vulnerability_id, artifact_name, artifact_version, severity, "
                        + "expires_at, updated_at FROM vulnerability_advisory "
                        + "WHERE vulnerability_id = ? AND artifact_name = ? AND artifact_version = ?",
                (rs, n) -> new AdvisoryRow(rs.getLong("id"), rs.getString("vulnerability_id"),
                        rs.getString("artifact_name"), rs.getInt("artifact_version"),
                        rs.getString("severity"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                vulnerabilityId, artifactName, artifactVersion);
        if (rows.isEmpty()) {
            throw new IllegalStateException("公告写入后读取失败");
        }
        return rows.get(0);
    }

    /** 列出全部公告，按漏洞编号、制品名、版本升序。 */
    public List<AdvisoryRow> listAdvisories() {
        return jdbcTemplate.query(
                "SELECT id, vulnerability_id, artifact_name, artifact_version, severity, "
                        + "expires_at, updated_at FROM vulnerability_advisory "
                        + "ORDER BY vulnerability_id ASC, artifact_name ASC, artifact_version ASC",
                (rs, n) -> new AdvisoryRow(rs.getLong("id"), rs.getString("vulnerability_id"),
                        rs.getString("artifact_name"), rs.getInt("artifact_version"),
                        rs.getString("severity"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    /** 查询某锁定图当前未过期（advisory expires_at > now）的全部命中，按坐标排序。 */
    public List<HitRow> listActiveHits(long lockFileId, Instant now) {
        return jdbcTemplate.query(
                "SELECT a.vulnerability_id, a.artifact_name, a.artifact_version, a.severity, "
                        + "a.expires_at AS advisory_expires_at "
                        + "FROM lock_file_entry e "
                        + "JOIN vulnerability_advisory a "
                        + "  ON a.artifact_name = e.name AND a.artifact_version = e.version "
                        + "WHERE e.lock_file_id = ? AND a.expires_at > ? "
                        + "ORDER BY a.vulnerability_id ASC, a.artifact_name ASC, a.artifact_version ASC",
                (rs, n) -> new HitRow(rs.getString("vulnerability_id"),
                        rs.getString("artifact_name"), rs.getInt("artifact_version"),
                        rs.getString("severity"),
                        rs.getTimestamp("advisory_expires_at").toInstant()),
                lockFileId, Timestamp.from(now));
    }

    /** 按主键读取公告，不存在返回 null。 */
    public AdvisoryRow getAdvisory(long id) {
        List<AdvisoryRow> rows = jdbcTemplate.query(
                "SELECT id, vulnerability_id, artifact_name, artifact_version, severity, "
                        + "expires_at, updated_at FROM vulnerability_advisory WHERE id = ?",
                (rs, n) -> new AdvisoryRow(rs.getLong("id"), rs.getString("vulnerability_id"),
                        rs.getString("artifact_name"), rs.getInt("artifact_version"),
                        rs.getString("severity"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // 豁免 ----------------------------------------------------------------

    /** 按 exceptionKey 指纹读取豁免，不存在返回 null。 */
    public ExceptionRow findExceptionByKey(String exceptionKey) {
        List<ExceptionRow> rows = queryExceptions(
                "SELECT * FROM vulnerability_exception WHERE exception_key = ?", exceptionKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键读取豁免，不存在返回 null。 */
    public ExceptionRow getException(long id) {
        List<ExceptionRow> rows = queryExceptions(
                "SELECT * FROM vulnerability_exception WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 列出某锁定图的全部豁免（含已撤销），按 ID 升序。 */
    public List<ExceptionRow> listExceptions(long lockFileId) {
        return queryExceptions(
                "SELECT * FROM vulnerability_exception WHERE lock_file_id = ? ORDER BY id ASC",
                lockFileId);
    }

    /** 插入 PENDING 豁免（仅第一审核人），返回自增主键。 */
    public long insertPendingException(String exceptionKey, long lockFileId,
                                       String vulnerabilityId, Instant expiresAt,
                                       String reason, String reviewer1, Instant now,
                                       String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO vulnerability_exception (exception_key, lock_file_id, "
                            + "vulnerability_id, expires_at, reason, status, reviewer1, "
                            + "created_at, request_id) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, exceptionKey);
            ps.setLong(2, lockFileId);
            ps.setString(3, vulnerabilityId);
            ps.setTimestamp(4, Timestamp.from(expiresAt));
            ps.setString(5, reason);
            ps.setString(6, reviewer1);
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setString(8, requestId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入豁免未获取自增主键");
        }
        return key.longValue();
    }

    /** 第二审核人确认：升级为 CONFIRMED 不可变双人快照。 */
    public int completeException(long exceptionId, String reviewer2, Instant confirmedAt) {
        return jdbcTemplate.update(
                "UPDATE vulnerability_exception SET status = 'CONFIRMED', reviewer2 = ?, "
                        + "confirmed_at = ? WHERE id = ? AND status = 'PENDING'",
                ps -> {
                    ps.setString(1, reviewer2);
                    ps.setTimestamp(2, Timestamp.from(confirmedAt));
                    ps.setLong(3, exceptionId);
                });
    }

    /** 撤销未撤销的豁免，返回受影响行数（已撤销时为 0）。 */
    public int revokeException(long exceptionId, String revokedBy, Instant now) {
        return jdbcTemplate.update(
                "UPDATE vulnerability_exception SET status = 'REVOKED', revoked_by = ?, "
                        + "revoked_at = ? WHERE id = ? AND status <> 'REVOKED'",
                ps -> {
                    ps.setString(1, revokedBy);
                    ps.setTimestamp(2, Timestamp.from(now));
                    ps.setLong(3, exceptionId);
                });
    }

    private List<ExceptionRow> queryExceptions(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, n) -> new ExceptionRow(
                rs.getLong("id"), rs.getString("exception_key"),
                rs.getLong("lock_file_id"), rs.getString("vulnerability_id"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("reason"),
                rs.getString("status"), rs.getString("reviewer1"), rs.getString("reviewer2"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("confirmed_at") == null ? null
                        : rs.getTimestamp("confirmed_at").toInstant(),
                rs.getString("revoked_by"),
                rs.getTimestamp("revoked_at") == null ? null
                        : rs.getTimestamp("revoked_at").toInstant()),
                args);
    }

    // 发布快照 ------------------------------------------------------------

    /** 按 requestId 读取已完成的发布快照主记录，不存在返回 null。 */
    public PublishRow findPublishByRequestId(String requestId) {
        List<PublishRow> rows = queryPublishes(
                "SELECT * FROM publish_snapshot WHERE request_id = ?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键读取发布快照主记录，不存在返回 null。 */
    public PublishRow getPublish(long id) {
        List<PublishRow> rows = queryPublishes(
                "SELECT * FROM publish_snapshot WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新增发布快照主记录，返回自增主键。 */
    public long insertPublish(long lockFileId, String rootName, int rootVersion,
                              long repositoryVersion, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO publish_snapshot (lock_file_id, root_name, root_version, "
                            + "repository_version, request_id, published_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lockFileId);
            ps.setString(2, rootName);
            ps.setInt(3, rootVersion);
            ps.setLong(4, repositoryVersion);
            ps.setString(5, requestId);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入发布快照未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增发布快照条目。 */
    public void insertPublishEntry(long publishId, String name, int version) {
        jdbcTemplate.update(
                "INSERT INTO publish_snapshot_entry (publish_id, name, version) VALUES (?, ?, ?)",
                publishId, name, version);
    }

    /** 查询某锁定图的全部发布快照主记录，按 ID 升序。 */
    public List<PublishRow> listPublishes(long lockFileId) {
        return queryPublishes(
                "SELECT * FROM publish_snapshot WHERE lock_file_id = ? ORDER BY id ASC", lockFileId);
    }

    /** 查询某发布快照的全部条目，按名称升序。 */
    public List<PublishEntryRow> listPublishEntries(long publishId) {
        return jdbcTemplate.query(
                "SELECT publish_id, name, version FROM publish_snapshot_entry "
                        + "WHERE publish_id = ? ORDER BY name ASC",
                (rs, n) -> new PublishEntryRow(rs.getLong("publish_id"),
                        rs.getString("name"), rs.getInt("version")),
                publishId);
    }

    private List<PublishRow> queryPublishes(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, n) -> new PublishRow(
                rs.getLong("id"), rs.getLong("lock_file_id"),
                rs.getString("root_name"), rs.getInt("root_version"),
                rs.getLong("repository_version"),
                rs.getTimestamp("published_at").toInstant(),
                rs.getString("request_id")),
                args);
    }
}
