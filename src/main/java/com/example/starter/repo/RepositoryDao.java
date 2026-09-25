package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.NamespacePolicy;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仓库数据访问：制品、依赖、许可证、命名空间策略、锁文件、仓库版本与幂等记录。
 *
 * <p>所有多语句业务操作均在 Service 层事务内执行；写事务通过
 * {@code SELECT ... FOR UPDATE} 锁定单行仓库版本表实现串行化，
 * 保证锁定与撤回/许可证/策略并发时看到的版本号和快照一致。
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
     * 读取仓库一致性快照：全部制品版本（含撤回，版本号降序）、依赖及命名空间策略。
     * 须在已持有 repository_state 行锁的事务内调用。
     */
    public RepositorySnapshot loadSnapshot() {
        List<ArtifactRow> rows = jdbcTemplate.query(
                "SELECT id, name, version, withdrawn, license FROM artifact ORDER BY name ASC, version DESC",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1, rs.getString("license")));

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
                    row.withdrawn(), row.license(), List.copyOf(deps));
            artifacts.computeIfAbsent(row.name(), k -> new ArrayList<>()).add(version);
        }
        return new RepositorySnapshot(getRepositoryVersion(), Map.copyOf(artifacts), loadPolicies());
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

    /** 新增制品版本，许可证初始为 NULL（UNKNOWN），返回自增主键。 */
    public long insertArtifact(String name, int version, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact (name, version, withdrawn, license, created_at) "
                            + "VALUES (?, ?, 0, NULL, ?)",
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
                "SELECT id, name, version, withdrawn, license FROM artifact WHERE name = ? AND version = ?",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1, rs.getString("license")),
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
                row.license(), List.copyOf(deps));
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

    /**
     * 登记/修改制品版本的许可证；仅当版本未撤回时生效，返回受影响行数。
     * license 传 null 表示清除登记恢复为 UNKNOWN。
     */
    public int updateLicense(String name, int version, String license) {
        return jdbcTemplate.update(
                "UPDATE artifact SET license = ? WHERE name = ? AND version = ? AND withdrawn = 0",
                license, name, version);
    }

    // ------------------------------------------------------------------
    // 命名空间策略
    // ------------------------------------------------------------------

    /** 策略行。 */
    public record PolicyRow(String namespace, long version, boolean rejectUnknown) {
    }

    /** 读取全部命名空间策略（含各自允许许可证集合）。 */
    public Map<String, NamespacePolicy> loadPolicies() {
        List<PolicyRow> rows = jdbcTemplate.query(
                "SELECT namespace, version, reject_unknown FROM namespace_policy",
                (rs, n) -> new PolicyRow(rs.getString("namespace"), rs.getLong("version"),
                        rs.getInt("reject_unknown") == 1));
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> licensesByNamespace = jdbcTemplate.query(
                "SELECT namespace, license FROM namespace_policy_license",
                (rs) -> {
                    Map<String, List<String>> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        map.computeIfAbsent(rs.getString("namespace"), k -> new ArrayList<>())
                                .add(rs.getString("license"));
                    }
                    return map;
                });
        Map<String, NamespacePolicy> policies = new LinkedHashMap<>();
        for (PolicyRow row : rows) {
            Set<String> licenses = new HashSet<>(
                    licensesByNamespace.getOrDefault(row.namespace(), List.of()));
            policies.put(row.namespace(), new NamespacePolicy(
                    row.namespace(), row.version(), row.rejectUnknown(), licenses));
        }
        return Map.copyOf(policies);
    }

    /** 读取单个命名空间策略，不存在返回 null。 */
    public NamespacePolicy loadPolicy(String namespace) {
        List<PolicyRow> rows = jdbcTemplate.query(
                "SELECT namespace, version, reject_unknown FROM namespace_policy WHERE namespace = ?",
                (rs, n) -> new PolicyRow(rs.getString("namespace"), rs.getLong("version"),
                        rs.getInt("reject_unknown") == 1),
                namespace);
        if (rows.isEmpty()) {
            return null;
        }
        PolicyRow row = rows.get(0);
        List<String> licenses = jdbcTemplate.query(
                "SELECT license FROM namespace_policy_license WHERE namespace = ?",
                (rs, n) -> rs.getString("license"), namespace);
        return new NamespacePolicy(row.namespace(), row.version(), row.rejectUnknown(),
                new HashSet<>(licenses));
    }

    /** 策略详情行：含最后修改时间。 */
    public record PolicyDetailRow(String namespace, long version, boolean rejectUnknown,
                                  Instant updatedAt) {
    }

    /** 读取单个命名空间策略详情（含修改时间），不存在返回 null。 */
    public PolicyDetailRow loadPolicyDetail(String namespace) {
        List<PolicyDetailRow> rows = jdbcTemplate.query(
                "SELECT namespace, version, reject_unknown, updated_at FROM namespace_policy "
                        + "WHERE namespace = ?",
                (rs, n) -> new PolicyDetailRow(rs.getString("namespace"), rs.getLong("version"),
                        rs.getInt("reject_unknown") == 1,
                        rs.getTimestamp("updated_at").toInstant()),
                namespace);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新建命名空间策略（version=1）。 */
    public void insertPolicy(String namespace, boolean rejectUnknown, Instant updatedAt) {
        jdbcTemplate.update(
                "INSERT INTO namespace_policy (namespace, version, reject_unknown, updated_at) "
                        + "VALUES (?, 1, ?, ?)",
                namespace, rejectUnknown ? 1 : 0, Timestamp.from(updatedAt));
    }

    /** 策略版本号加一并更新是否拒绝 UNKNOWN，返回受影响行数（乐观版本条件）。 */
    public int updatePolicy(String namespace, long expectedVersion, boolean rejectUnknown,
                            Instant updatedAt) {
        return jdbcTemplate.update(
                "UPDATE namespace_policy SET version = version + 1, reject_unknown = ?, updated_at = ? "
                        + "WHERE namespace = ? AND version = ?",
                rejectUnknown ? 1 : 0, Timestamp.from(updatedAt), namespace, expectedVersion);
    }

    /** 删除命名空间策略下的全部允许许可证（随修改整体替换）。 */
    public void deletePolicyLicenses(String namespace) {
        jdbcTemplate.update("DELETE FROM namespace_policy_license WHERE namespace = ?", namespace);
    }

    /** 写入一条允许许可证。 */
    public void insertPolicyLicense(String namespace, String license) {
        jdbcTemplate.update(
                "INSERT INTO namespace_policy_license (namespace, license) VALUES (?, ?)",
                namespace, license);
    }

    // ------------------------------------------------------------------
    // 锁文件
    // ------------------------------------------------------------------

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

    /**
     * 新增锁文件精确版本条目，同时固化锁定时该版本的许可证与命名空间策略版本。
     *
     * @param license       锁定时登记的许可证；null 表示当时为 UNKNOWN
     * @param policyVersion 锁定时命名空间策略版本；当时无策略为 0
     */
    public void insertLockEntry(long lockFileId, String name, int version,
                                String license, long policyVersion) {
        jdbcTemplate.update(
                "INSERT INTO lock_file_entry (lock_file_id, name, version, license, policy_version) "
                        + "VALUES (?, ?, ?, ?, ?)",
                lockFileId, name, version, license, policyVersion);
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

    /** 锁文件条目行，含锁定时固化的许可证与策略版本。 */
    public record LockEntryRow(long lockFileId, String name, int version,
                               String license, long policyVersion) {
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
                "SELECT lock_file_id, name, version, license, policy_version FROM lock_file_entry "
                        + "WHERE lock_file_id = ? ORDER BY name ASC",
                (rs, n) -> new LockEntryRow(rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version"),
                        rs.getString("license"), rs.getLong("policy_version")),
                lockFileId);
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn, String license) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }
}
