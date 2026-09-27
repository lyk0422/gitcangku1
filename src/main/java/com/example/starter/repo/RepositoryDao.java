package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.DeprecationStatus;
import com.example.starter.domain.DeprecationWindow;
import com.example.starter.domain.DeprecationEvent;
import com.example.starter.domain.RepositorySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 仓库数据访问：制品、依赖、锁文件、仓库版本与幂等记录。
 *
 * <p>所有多语句业务操作均在 Service 层事务内执行；写事务通过
 * {@code SELECT ... FOR UPDATE} 锁定单行仓库版本表实现串行化，
 * 保证锁定与撤回并发时看到的版本号和快照一致。
 */
@Repository
public class RepositoryDao {

    private final JdbcTemplate jdbcTemplate;

    public RepositoryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁定仓库版本单行并返回当前版本号；写事务入口调用，互斥其他写事务。
     */
    public long lockRepositoryState() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1 FOR UPDATE", Long.class);
        return version == null ? 0L : version;
    }

    /** 读取当前仓库版本号（不加锁，供查询使用）。 */
    public long getRepositoryVersion() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 读取仓库一致性快照：全部制品版本（含撤回，版本号降序）及其依赖。
     * 须在已持有 repository_state 行锁的事务内调用。
     */
    public RepositorySnapshot loadSnapshot() {
        List<ArtifactRow> rows = jdbcTemplate.query(
                "SELECT id, name, version, withdrawn FROM artifact ORDER BY name ASC, version DESC",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1));

        List<DepRow> depRows = jdbcTemplate.query(
                "SELECT artifact_id, name, minimum_version, maximum_version FROM artifact_dependency",
                (rs, n) -> new DepRow(rs.getLong("artifact_id"), rs.getString("name"),
                        rs.getInt("minimum_version"), rs.getInt("maximum_version")));
        Map<Long, List<DependencyRange>> depsByArtifact = depRows.stream()
                .collect(Collectors.groupingBy(DepRow::artifactId,
                        Collectors.mapping(d -> new DependencyRange(d.name(), d.minimumVersion(), d.maximumVersion()),
                                Collectors.toList())));

        Map<String, List<ArtifactVersion>> artifacts = new LinkedHashMap<>();
        for (ArtifactRow row : rows) {
            List<DependencyRange> deps = depsByArtifact.getOrDefault(row.id(), List.of());
            ArtifactVersion version = new ArtifactVersion(row.id(), row.name(), row.version(),
                    row.withdrawn(), List.copyOf(deps));
            artifacts.computeIfAbsent(row.name(), k -> new ArrayList<>()).add(version);
        }
        return new RepositorySnapshot(getRepositoryVersion(), Map.copyOf(artifacts));
    }

    /** name+version 的制品版本是否已存在（含撤回版本）。 */
    public boolean artifactExists(String name, int version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = ? AND version = ?",
                Integer.class, name, version);
        return count != null && count > 0;
    }

    /** 仓库中已登记的不同制品名称数量。 */
    public int countDistinctNames() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT name) FROM artifact", Integer.class);
        return count == null ? 0 : count;
    }

    /** 指定名称已登记的版本数量（含撤回）。 */
    public int countVersions(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    /** 新增制品版本，返回自增主键。 */
    public long insertArtifact(String name, int version, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact (name, version, withdrawn, created_at) VALUES (?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入制品未获取自增主键");
        }
        return key.longValue();
    }

    /** 为制品版本写入一条依赖声明。 */
    public void insertDependency(long artifactId, String name, int minimumVersion, int maximumVersion) {
        jdbcTemplate.update(
                "INSERT INTO artifact_dependency (artifact_id, name, minimum_version, maximum_version) "
                        + "VALUES (?, ?, ?, ?)",
                artifactId, name, minimumVersion, maximumVersion);
    }

    /** 读取单个制品版本（含依赖），不存在返回 null。 */
    public ArtifactVersion loadArtifact(String name, int version) {
        List<ArtifactRow> rows = jdbcTemplate.query(
                "SELECT id, name, version, withdrawn FROM artifact WHERE name = ? AND version = ?",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1),
                name, version);
        if (rows.isEmpty()) {
            return null;
        }
        ArtifactRow row = rows.get(0);
        List<DependencyRange> deps = jdbcTemplate.query(
                "SELECT name, minimum_version, maximum_version FROM artifact_dependency WHERE artifact_id = ?",
                (rs, n) -> new DependencyRange(rs.getString("name"),
                        rs.getInt("minimum_version"), rs.getInt("maximum_version")),
                row.id());
        return new ArtifactVersion(row.id(), row.name(), row.version(), row.withdrawn(),
                List.copyOf(deps));
    }

    /** 仓库版本号加一，返回加一后的版本号（须在持有行锁时调用）。 */
    public long incrementRepositoryVersion() {
        jdbcTemplate.update("UPDATE repository_state SET version = version + 1 WHERE id = 1");
        return getRepositoryVersion();
    }

    /**
     * 撤回指定制品版本，仅当当前未撤回时生效，返回受影响行数。
     * 条件更新保证重复撤回/并发撤回的原子判定。
     */
    public int markWithdrawn(long artifactId) {
        return jdbcTemplate.update(
                "UPDATE artifact SET withdrawn = 1 WHERE id = ? AND withdrawn = 0", artifactId);
    }

    /** 新增锁文件主记录，返回自增主键。resolvedAt 为该锁固化的 UTC 解析时刻。 */
    public long insertLockFile(String rootName, int rootVersion, long repositoryVersion,
                               String requestId, Instant resolvedAt, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lock_file (root_name, root_version, repository_version, request_id, "
                            + "resolved_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rootName);
            ps.setInt(2, rootVersion);
            ps.setLong(3, repositoryVersion);
            ps.setString(4, requestId);
            ps.setTimestamp(5, Timestamp.from(resolvedAt));
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入锁文件未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增锁文件精确版本条目。 */
    public void insertLockEntry(long lockFileId, String name, int version) {
        jdbcTemplate.update(
                "INSERT INTO lock_file_entry (lock_file_id, name, version) VALUES (?, ?, ?)",
                lockFileId, name, version);
    }

    /** 幂等记录视图。 */
    public record IdempotentRecord(String requestId, String operation, String requestHash,
                                   int httpStatus, String responseJson) {
    }

    /**
     * 插入进行中的幂等占位记录（同事务内随后更新为完成态）。
     * requestId 冲突由唯一索引抛出 DuplicateKeyException。
     */
    public void insertPendingIdempotentRequest(String requestId, String operation, String requestHash,
                                               Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_request (request_id, operation, request_hash, http_status, "
                        + "response_json, created_at) VALUES (?, ?, ?, ?, CAST('' AS CHARACTER LARGE OBJECT), ?)",
                requestId, operation, requestHash, 0, Timestamp.from(createdAt));
    }

    /** 将幂等记录更新为成功完成态，与业务变更在同一事务提交。 */
    public void completeIdempotentRequest(String requestId, int httpStatus, String responseJson) {
        jdbcTemplate.update(
                "UPDATE idempotent_request SET http_status = ?, response_json = ? WHERE request_id = ?",
                ps -> {
                    ps.setInt(1, httpStatus);
                    ps.setClob(2, new java.io.StringReader(responseJson));
                    ps.setString(3, requestId);
                });
    }

    /** 按 requestId 查找已完成的成功幂等记录，不存在返回 null。 */
    public IdempotentRecord findIdempotentRequest(String requestId) {
        List<IdempotentRecord> records = jdbcTemplate.query(
                "SELECT request_id, operation, request_hash, http_status, response_json "
                        + "FROM idempotent_request WHERE request_id = ? AND http_status > 0",
                (rs, n) -> new IdempotentRecord(rs.getString("request_id"),
                        rs.getString("operation"), rs.getString("request_hash"),
                        rs.getInt("http_status"), rs.getString("response_json")),
                requestId);
        return records.isEmpty() ? null : records.get(0);
    }

    /** 锁文件列表行：不含条目明细与弃用警告。 */
    public record LockFileRow(long id, String rootName, int rootVersion,
                              long repositoryVersion, Instant resolvedAt, Instant createdAt) {
    }

    /** 锁文件条目行。 */
    public record LockEntryRow(long lockFileId, String name, int version) {
    }

    /** 锁文件固化的弃用警告行。 */
    public record LockWarningRow(long lockFileId, String name, int version,
                                 String replacementName, int replacementVersion,
                                 Instant effectiveAt, String note, Instant resolvedAt) {
    }

    /** 查询全部历史锁文件，按 ID 升序。 */
    public List<LockFileRow> listLockFiles() {
        return jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, resolved_at, created_at "
                        + "FROM lock_file ORDER BY id ASC",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getTimestamp("resolved_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** 按主键查询锁文件，不存在返回 null。 */
    public LockFileRow getLockFile(long id) {
        List<LockFileRow> rows = jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, resolved_at, created_at "
                        + "FROM lock_file WHERE id = ?",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getTimestamp("resolved_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某锁文件的全部条目，按名称升序。 */
    public List<LockEntryRow> listLockEntries(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT lock_file_id, name, version FROM lock_file_entry "
                        + "WHERE lock_file_id = ? ORDER BY name ASC",
                (rs, n) -> new LockEntryRow(rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version")),
                lockFileId);
    }

    /** 查询某锁文件固化的全部弃用警告，按制品、版本升序。 */
    public List<LockWarningRow> listLockWarnings(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT lock_file_id, name, version, replacement_name, replacement_version, "
                        + "effective_at, note, resolved_at FROM lock_deprecation_warning "
                        + "WHERE lock_file_id = ? ORDER BY name ASC, version ASC",
                (rs, n) -> new LockWarningRow(rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version"),
                        rs.getString("replacement_name"), rs.getInt("replacement_version"),
                        rs.getTimestamp("effective_at").toInstant(),
                        rs.getString("note"),
                        rs.getTimestamp("resolved_at").toInstant()),
                lockFileId);
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }

    // ------------------------------------------------------------------
    // 弃用窗口
    // ------------------------------------------------------------------

    private static final String DEPRECATION_SELECT =
            "SELECT d.id, d.artifact_id, a.name AS artifact_name, a.version AS artifact_version, "
                    + "d.effective_at, d.replacement_artifact_id, "
                    + "ra.name AS replacement_name, ra.version AS replacement_version, "
                    + "d.note, d.status, d.revoked_at, d.created_at "
                    + "FROM artifact_deprecation d "
                    + "JOIN artifact a ON a.id = d.artifact_id "
                    + "JOIN artifact ra ON ra.id = d.replacement_artifact_id ";

    private static DeprecationWindow mapDeprecation(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DeprecationWindow(
                rs.getLong("id"),
                rs.getLong("artifact_id"),
                rs.getString("artifact_name"),
                rs.getInt("artifact_version"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getLong("replacement_artifact_id"),
                rs.getString("replacement_name"),
                rs.getInt("replacement_version"),
                rs.getString("note"),
                DeprecationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 新增弃用窗口，返回自增主键。 */
    public long insertDeprecation(long artifactId, Instant effectiveAt, long replacementArtifactId,
                                  String note, String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact_deprecation (artifact_id, effective_at, replacement_artifact_id, "
                            + "note, status, revoked_at, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, 'ACTIVE', NULL, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, artifactId);
            ps.setTimestamp(2, Timestamp.from(effectiveAt));
            ps.setLong(3, replacementArtifactId);
            ps.setString(4, note);
            ps.setString(5, requestId);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入弃用窗口未获取自增主键");
        }
        return key.longValue();
    }

    /** 调整既有 ACTIVE 窗口的生效时刻、替代版本与说明（批量设置使用）。 */
    public void updateDeprecation(long deprecationId, Instant effectiveAt, long replacementArtifactId,
                                  String note, String requestId) {
        jdbcTemplate.update(
                "UPDATE artifact_deprecation SET effective_at = ?, replacement_artifact_id = ?, "
                        + "note = ?, request_id = ? WHERE id = ? AND status = 'ACTIVE'",
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(effectiveAt));
                    ps.setLong(2, replacementArtifactId);
                    ps.setString(3, note);
                    ps.setString(4, requestId);
                    ps.setLong(5, deprecationId);
                });
    }

    /**
     * 撤销窗口，仅当 ACTIVE 且尚未撤销时生效，返回受影响行数。
     * 是否允许撤销（尚未到生效时刻）由服务层按当前时钟判定。
     */
    public int markDeprecationRevoked(long deprecationId, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE artifact_deprecation SET status = 'REVOKED', revoked_at = ? "
                        + "WHERE id = ? AND status = 'ACTIVE' AND revoked_at IS NULL",
                Timestamp.from(revokedAt), deprecationId);
    }

    /** 追加一条弃用事件（只追加历史）。 */
    public void insertDeprecationEvent(long deprecationId, long artifactId, long replacementArtifactId,
                                       String eventType, Instant effectiveAt, String note,
                                       String requestId, Instant eventAt) {
        jdbcTemplate.update(
                "INSERT INTO artifact_deprecation_event (deprecation_id, artifact_id, replacement_artifact_id, "
                        + "event_type, effective_at, note, request_id, event_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, deprecationId);
                    ps.setLong(2, artifactId);
                    ps.setLong(3, replacementArtifactId);
                    ps.setString(4, eventType);
                    ps.setTimestamp(5, Timestamp.from(effectiveAt));
                    ps.setString(6, note);
                    ps.setString(7, requestId);
                    ps.setTimestamp(8, Timestamp.from(eventAt));
                });
    }

    /** 按主键查询弃用窗口（含已撤销），不存在返回 null。 */
    public DeprecationWindow getDeprecation(long id) {
        List<DeprecationWindow> windows = jdbcTemplate.query(
                DEPRECATION_SELECT + "WHERE d.id = ?", RepositoryDao::mapDeprecation, id);
        return windows.isEmpty() ? null : windows.get(0);
    }

    /** 查询某制品版本当前 ACTIVE 窗口，不存在返回 null。 */
    public DeprecationWindow findActiveDeprecation(long artifactId) {
        List<DeprecationWindow> windows = jdbcTemplate.query(
                DEPRECATION_SELECT + "WHERE d.artifact_id = ? AND d.status = 'ACTIVE'",
                RepositoryDao::mapDeprecation, artifactId);
        return windows.isEmpty() ? null : windows.get(0);
    }

    /** 查询全部 ACTIVE 窗口，按制品名称、版本升序（完整替代图校验使用）。 */
    public List<DeprecationWindow> listActiveDeprecations() {
        return jdbcTemplate.query(
                DEPRECATION_SELECT + "WHERE d.status = 'ACTIVE' "
                        + "ORDER BY a.name ASC, a.version ASC, d.id ASC",
                RepositoryDao::mapDeprecation);
    }

    /** 查询弃用事件历史，按事件时刻、ID 升序；只读。 */
    public List<DeprecationEvent> listDeprecationEvents() {
        String sql = eventSelect() + " ORDER BY e.event_at ASC, e.id ASC";
        return jdbcTemplate.query(sql, RepositoryDao::mapEvent);
    }

    /** 查询单个窗口的事件历史，按事件时刻、ID 升序；只读。 */
    public List<DeprecationEvent> listDeprecationEvents(long deprecationId) {
        String sql = eventSelect() + " WHERE e.deprecation_id = ? ORDER BY e.event_at ASC, e.id ASC";
        return jdbcTemplate.query(sql, RepositoryDao::mapEvent, deprecationId);
    }

    private static String eventSelect() {
        return "SELECT e.id, e.deprecation_id, e.artifact_id, a.name AS artifact_name, "
                + "a.version AS artifact_version, e.replacement_artifact_id, "
                + "ra.name AS replacement_name, ra.version AS replacement_version, "
                + "e.event_type, e.effective_at, e.note, e.request_id, e.event_at "
                + "FROM artifact_deprecation_event e "
                + "JOIN artifact a ON a.id = e.artifact_id "
                + "JOIN artifact ra ON ra.id = e.replacement_artifact_id";
    }

    private static DeprecationEvent mapEvent(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DeprecationEvent(
                rs.getLong("id"),
                rs.getLong("deprecation_id"),
                rs.getLong("artifact_id"),
                rs.getString("artifact_name"),
                rs.getInt("artifact_version"),
                rs.getLong("replacement_artifact_id"),
                rs.getString("replacement_name"),
                rs.getInt("replacement_version"),
                rs.getString("event_type"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getString("note"),
                rs.getString("request_id"),
                rs.getTimestamp("event_at").toInstant());
    }

    /** 固化一条锁定时刻命中的待生效弃用警告。 */
    public void insertLockWarning(long lockFileId, String name, int version,
                                  String replacementName, int replacementVersion,
                                  Instant effectiveAt, String note, Instant resolvedAt) {
        jdbcTemplate.update(
                "INSERT INTO lock_deprecation_warning (lock_file_id, name, version, replacement_name, "
                        + "replacement_version, effective_at, note, resolved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, lockFileId);
                    ps.setString(2, name);
                    ps.setInt(3, version);
                    ps.setString(4, replacementName);
                    ps.setInt(5, replacementVersion);
                    ps.setTimestamp(6, Timestamp.from(effectiveAt));
                    ps.setString(7, note);
                    ps.setTimestamp(8, Timestamp.from(resolvedAt));
                });
    }
}
