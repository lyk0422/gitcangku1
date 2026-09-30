package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.RiskLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
                "SELECT id, name, version, withdrawn, risk_level FROM artifact "
                        + "ORDER BY name ASC, version DESC",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1,
                        RiskLevel.valueOf(rs.getString("risk_level"))));

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
                    row.riskLevel(), row.withdrawn(), List.copyOf(deps));
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
    public long insertArtifact(String name, int version, RiskLevel riskLevel, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact (name, version, withdrawn, risk_level, created_at) "
                            + "VALUES (?, ?, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setString(3, riskLevel.name());
            ps.setTimestamp(4, Timestamp.from(createdAt));
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
                "SELECT id, name, version, withdrawn, risk_level FROM artifact "
                        + "WHERE name = ? AND version = ?",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1,
                        RiskLevel.valueOf(rs.getString("risk_level"))),
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
        return new ArtifactVersion(row.id(), row.name(), row.version(),
                row.riskLevel(), row.withdrawn(), List.copyOf(deps));
    }

    /** 按主键读取制品版本，不存在返回 null（锁定事务内按解决方案精确取风险级别）。 */
    public ArtifactVersion loadArtifactById(long id) {
        List<ArtifactRow> rows = jdbcTemplate.query(
                "SELECT id, name, version, withdrawn, risk_level FROM artifact WHERE id = ?",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1,
                        RiskLevel.valueOf(rs.getString("risk_level"))),
                id);
        if (rows.isEmpty()) {
            return null;
        }
        ArtifactRow row = rows.get(0);
        return new ArtifactVersion(row.id(), row.name(), row.version(),
                row.riskLevel(), row.withdrawn(), List.of());
    }

    /** 标记风险级别，返回受影响行数（制品版本不存在时为 0）。 */
    public int updateRiskLevel(String name, int version, RiskLevel riskLevel) {
        return jdbcTemplate.update(
                "UPDATE artifact SET risk_level = ? WHERE name = ? AND version = ?",
                riskLevel.name(), name, version);
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

    /** 新增锁文件主记录，返回自增主键。 */
    public long insertLockFile(String rootName, int rootVersion, long repositoryVersion,
                               String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lock_file (root_name, root_version, repository_version, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rootName);
            ps.setInt(2, rootVersion);
            ps.setLong(3, repositoryVersion);
            ps.setString(4, requestId);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入锁文件未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增锁文件精确版本条目，固化锁定时风险级别与所用豁免 ID。 */
    public void insertLockEntry(long lockFileId, String name, int version,
                                RiskLevel riskLevel, Long exceptionId) {
        jdbcTemplate.update(
                "INSERT INTO lock_file_entry (lock_file_id, name, version, risk_level, exception_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                lockFileId, name, version, riskLevel.name(), exceptionId);
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

    /** 锁文件列表行：不含条目明细。 */
    public record LockFileRow(long id, String rootName, int rootVersion,
                              long repositoryVersion, Instant createdAt) {
    }

    /** 锁文件条目行：含锁定时固化的风险级别与所用豁免 ID（可能为空）。 */
    public record LockEntryRow(long lockFileId, String name, int version,
                               RiskLevel riskLevel, Long exceptionId) {
    }

    /** 查询全部历史锁文件，按 ID 升序。 */
    public List<LockFileRow> listLockFiles() {
        return jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, created_at "
                        + "FROM lock_file ORDER BY id ASC",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** 按主键查询锁文件，不存在返回 null。 */
    public LockFileRow getLockFile(long id) {
        List<LockFileRow> rows = jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, created_at "
                        + "FROM lock_file WHERE id = ?",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getTimestamp("created_at").toInstant()),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某锁文件的全部条目，按名称升序。 */
    public List<LockEntryRow> listLockEntries(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT lock_file_id, name, version, risk_level, exception_id FROM lock_file_entry "
                        + "WHERE lock_file_id = ? ORDER BY name ASC",
                (rs, n) -> new LockEntryRow(rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version"),
                        RiskLevel.valueOf(rs.getString("risk_level")),
                        (Long) rs.getObject("exception_id")),
                lockFileId);
    }

    /** 豁免申请行。 */
    public record ExceptionRow(
            long id,
            String dependencyName,
            int dependencyVersion,
            Instant expiresAt,
            String reason,
            String status,
            int contentVersion,
            Instant createdAt,
            Instant approvedAt,
            Instant revokedAt) {
    }

    /** 新增豁免申请（PENDING），返回自增主键。 */
    public long insertException(String dependencyName, int dependencyVersion,
                                Instant expiresAt, String reason, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO risk_exception (dependency_name, dependency_version, expires_at, "
                            + "reason, status, content_version, created_at) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dependencyName);
            ps.setInt(2, dependencyVersion);
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.setString(4, reason);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入豁免申请未获取自增主键");
        }
        return key.longValue();
    }

    /** 按主键读取豁免申请（加行锁，审批/撤销/锁定事务内使用），不存在返回 null。 */
    public ExceptionRow lockException(long id) {
        List<ExceptionRow> rows = jdbcTemplate.query(
                "SELECT id, dependency_name, dependency_version, expires_at, reason, status, "
                        + "content_version, created_at, approved_at, revoked_at "
                        + "FROM risk_exception WHERE id = ? FOR UPDATE",
                (rs, n) -> mapExceptionRow(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键读取豁免申请（不加锁，查询使用），不存在返回 null。 */
    public ExceptionRow getException(long id) {
        List<ExceptionRow> rows = jdbcTemplate.query(
                "SELECT id, dependency_name, dependency_version, expires_at, reason, status, "
                        + "content_version, created_at, approved_at, revoked_at "
                        + "FROM risk_exception WHERE id = ?",
                (rs, n) -> mapExceptionRow(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按 ID 批量读取豁免申请（不加锁）。 */
    public Map<Long, ExceptionRow> getExceptions(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        List<ExceptionRow> rows = jdbcTemplate.query(
                "SELECT id, dependency_name, dependency_version, expires_at, reason, status, "
                        + "content_version, created_at, approved_at, revoked_at "
                        + "FROM risk_exception WHERE id IN (" + placeholders + ")",
                (rs, n) -> mapExceptionRow(rs), ids.toArray());
        Map<Long, ExceptionRow> map = new LinkedHashMap<>();
        for (ExceptionRow row : rows) {
            map.put(row.id(), row);
        }
        return map;
    }

    /** 查询全部豁免申请，按 ID 升序。 */
    public List<ExceptionRow> listExceptions() {
        return jdbcTemplate.query(
                "SELECT id, dependency_name, dependency_version, expires_at, reason, status, "
                        + "content_version, created_at, approved_at, revoked_at "
                        + "FROM risk_exception ORDER BY id ASC",
                (rs, n) -> mapExceptionRow(rs));
    }

    private ExceptionRow mapExceptionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ExceptionRow(rs.getLong("id"),
                rs.getString("dependency_name"),
                rs.getInt("dependency_version"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getInt("content_version"),
                rs.getTimestamp("created_at").toInstant(),
                approvedAt == null ? null : approvedAt.toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    /** 读取豁免的审批人列表（按确认先后），须在已锁定申请行的事务内调用。 */
    public List<String> listApprovers(long exceptionId) {
        return jdbcTemplate.query(
                "SELECT approver FROM risk_exception_approval WHERE exception_id = ? ORDER BY id ASC",
                (rs, n) -> rs.getString("approver"), exceptionId);
    }

    /**
     * 写入一条审批确认；同一申请同一审批人重复确认由唯一索引拦截。
     */
    public void insertApproval(long exceptionId, String approver, int contentVersion, Instant approvedAt) {
        jdbcTemplate.update(
                "INSERT INTO risk_exception_approval (exception_id, approver, content_version, approved_at) "
                        + "VALUES (?, ?, ?, ?)",
                exceptionId, approver, contentVersion, Timestamp.from(approvedAt));
    }

    /** 将豁免申请置为生效（第二名审批人确认时调用）。 */
    public int markExceptionApproved(long id, int contentVersion, Instant approvedAt) {
        return jdbcTemplate.update(
                "UPDATE risk_exception SET status = 'APPROVED', approved_at = ? "
                        + "WHERE id = ? AND status = 'PENDING' AND content_version = ?",
                Timestamp.from(approvedAt), id, contentVersion);
    }

    /** 撤销已生效豁免，返回受影响行数（非 APPROVED 状态为 0）。 */
    public int markExceptionRevoked(long id, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE risk_exception SET status = 'REVOKED', revoked_at = ? "
                        + "WHERE id = ? AND status = 'APPROVED'",
                Timestamp.from(revokedAt), id);
    }

    /** 新增锁文件的豁免依据固化快照。 */
    public void insertLockException(long lockFileId, long exceptionId,
                                    String dependencyName, int dependencyVersion,
                                    Instant expiresAt, String reason, String status,
                                    String approversCsv, Instant lockedAt) {
        jdbcTemplate.update(
                "INSERT INTO lock_file_exception (lock_file_id, exception_id, dependency_name, "
                        + "dependency_version, expires_at, reason, status, approvers, locked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                lockFileId, exceptionId, dependencyName, dependencyVersion,
                Timestamp.from(expiresAt), reason, status, approversCsv, Timestamp.from(lockedAt));
    }

    /** 锁文件豁免依据快照行。 */
    public record LockExceptionRow(
            long lockFileId,
            long exceptionId,
            String dependencyName,
            int dependencyVersion,
            Instant expiresAt,
            String reason,
            String status,
            List<String> approvers,
            Instant lockedAt) {
    }

    /** 查询某锁文件固化的豁免依据，按依赖名称升序。 */
    public List<LockExceptionRow> listLockExceptions(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT lock_file_id, exception_id, dependency_name, dependency_version, "
                        + "expires_at, reason, status, approvers, locked_at "
                        + "FROM lock_file_exception WHERE lock_file_id = ? "
                        + "ORDER BY dependency_name ASC",
                (rs, n) -> {
                    String approversCsv = rs.getString("approvers");
                    List<String> approvers = approversCsv.isBlank()
                            ? List.of()
                            : Arrays.stream(approversCsv.split(",")).toList();
                    return new LockExceptionRow(rs.getLong("lock_file_id"),
                            rs.getLong("exception_id"),
                            rs.getString("dependency_name"),
                            rs.getInt("dependency_version"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getString("reason"),
                            rs.getString("status"),
                            approvers,
                            rs.getTimestamp("locked_at").toInstant());
                }, lockFileId);
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn,
                               RiskLevel riskLevel) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }
}
