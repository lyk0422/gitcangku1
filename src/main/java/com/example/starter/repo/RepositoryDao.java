package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
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
import java.util.Collection;
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

    /** 新增锁文件精确版本条目，固化锁定时风险级别与豁免依据。 */
    public void insertLockEntry(long lockFileId, String name, int version, String riskLevel,
                                Long exceptionId, Integer exceptionVersion, String statusAtLock) {
        jdbcTemplate.update(
                "INSERT INTO lock_file_entry (lock_file_id, name, version, risk_level, "
                        + "exception_id, exception_version, status_at_lock) VALUES (?, ?, ?, ?, ?, ?, ?)",
                lockFileId, name, version, riskLevel, exceptionId, exceptionVersion, statusAtLock);
    }

    /** 固化历史锁文件条目的豁免审批人快照（seq 从 1 开始）。 */
    public void insertLockEntryApprover(long lockFileId, String entryName, String approver, int seq) {
        jdbcTemplate.update(
                "INSERT INTO lock_entry_exception_approver (lock_file_id, entry_name, approver, seq) "
                        + "VALUES (?, ?, ?, ?)",
                lockFileId, entryName, approver, seq);
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

    /** 锁文件条目行：含锁定时固化的风险级别与豁免依据。 */
    public record LockEntryRow(long lockFileId, String name, int version, String riskLevel,
                               Long exceptionId, Integer exceptionVersion, String statusAtLock) {
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
                "SELECT lock_file_id, name, version, risk_level, exception_id, exception_version, status_at_lock "
                        + "FROM lock_file_entry WHERE lock_file_id = ? ORDER BY name ASC",
                (rs, n) -> new LockEntryRow(rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version"), rs.getString("risk_level"),
                        rs.getObject("exception_id", Long.class),
                        rs.getObject("exception_version", Integer.class),
                        rs.getString("status_at_lock")),
                lockFileId);
    }

    /** 查询历史锁条目中固化的豁免审批人，按审批顺序号升序。 */
    public List<String> listLockEntryApprovers(long lockFileId, String entryName) {
        return jdbcTemplate.queryForList(
                "SELECT approver FROM lock_entry_exception_approver "
                        + "WHERE lock_file_id = ? AND entry_name = ? ORDER BY seq ASC",
                String.class, lockFileId, entryName);
    }

    // ------------------------------------------------------------------
    // 风险标记
    // ------------------------------------------------------------------

    /** 风险标记行。 */
    public record RiskRow(String name, int version, String riskLevel, String reason,
                          Instant createdAt, Instant updatedAt) {
    }

    /** 新增制品版本风险标记，返回自增主键。 */
    public long insertRisk(String name, int version, String riskLevel, String reason, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact_risk (name, version, risk_level, reason, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setString(3, riskLevel);
            ps.setString(4, reason);
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入风险标记未获取自增主键");
        }
        return key.longValue();
    }

    /** 更新风险标记（等级、说明、更新时间），返回受影响行数。 */
    public int updateRisk(String name, int version, String riskLevel, String reason, Instant now) {
        return jdbcTemplate.update(
                "UPDATE artifact_risk SET risk_level = ?, reason = ?, updated_at = ? "
                        + "WHERE name = ? AND version = ?",
                ps -> {
                    ps.setString(1, riskLevel);
                    ps.setString(2, reason);
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setString(4, name);
                    ps.setInt(5, version);
                });
    }

    /** 按 name+version 查询风险标记，不存在返回 null。 */
    public RiskRow findRisk(String name, int version) {
        List<RiskRow> rows = jdbcTemplate.query(
                "SELECT name, version, risk_level, reason, created_at, updated_at "
                        + "FROM artifact_risk WHERE name = ? AND version = ?",
                (rs, n) -> new RiskRow(rs.getString("name"), rs.getInt("version"),
                        rs.getString("risk_level"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                name, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 批量查询多个 name+version 的风险标记，键为 name + '\u0000' + version。 */
    public Map<String, RiskRow> findRisks(Collection<Map.Entry<String, Integer>> targets) {
        Map<String, RiskRow> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> target : targets) {
            RiskRow row = findRisk(target.getKey(), target.getValue());
            if (row != null) {
                result.put(riskKey(target.getKey(), target.getValue()), row);
            }
        }
        return result;
    }

    /** 组装 name+version 的内存映射键。 */
    public static String riskKey(String name, int version) {
        return name + '\u0000' + version;
    }

    /** 查询全部风险标记，按名称、版本升序。 */
    public List<RiskRow> listRisks() {
        return jdbcTemplate.query(
                "SELECT name, version, risk_level, reason, created_at, updated_at "
                        + "FROM artifact_risk ORDER BY name ASC, version ASC",
                (rs, n) -> new RiskRow(rs.getString("name"), rs.getInt("version"),
                        rs.getString("risk_level"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    // ------------------------------------------------------------------
    // 高风险豁免
    // ------------------------------------------------------------------

    /** 豁免申请行。 */
    public record RiskExceptionRow(long id, String dependencyName, int dependencyVersion,
                                   int exceptionVersion, String status, String reason,
                                   Instant expiresAt, Instant createdAt, Instant decidedAt) {
    }

    /** 豁免审批记录行。 */
    public record ApprovalRow(long exceptionId, String approver, int exceptionVersion,
                              Instant approvedAt) {
    }

    /** 创建豁免申请（PENDING），返回自增主键。 */
    public long insertRiskException(String dependencyName, int dependencyVersion, String reason,
                                    Instant expiresAt, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO risk_exception (dependency_name, dependency_version, exception_version, "
                            + "status, reason, expires_at, created_at) VALUES (?, ?, 1, 'PENDING', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dependencyName);
            ps.setInt(2, dependencyVersion);
            ps.setString(3, reason);
            ps.setTimestamp(4, Timestamp.from(expiresAt));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入豁免申请未获取自增主键");
        }
        return key.longValue();
    }

    /** 按 ID 查询豁免申请（加行锁），不存在返回 null；须在事务内调用。 */
    public RiskExceptionRow lockRiskException(long id) {
        List<RiskExceptionRow> rows = jdbcTemplate.query(
                "SELECT id, dependency_name, dependency_version, exception_version, status, reason, "
                        + "expires_at, created_at, decided_at FROM risk_exception WHERE id = ? FOR UPDATE",
                (rs, n) -> mapExceptionRow(rs),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按 ID 查询豁免申请（不加锁），不存在返回 null。 */
    public RiskExceptionRow findRiskException(long id) {
        List<RiskExceptionRow> rows = jdbcTemplate.query(
                "SELECT id, dependency_name, dependency_version, exception_version, status, reason, "
                        + "expires_at, created_at, decided_at FROM risk_exception WHERE id = ?",
                (rs, n) -> mapExceptionRow(rs),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RiskExceptionRow mapExceptionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp decided = rs.getTimestamp("decided_at");
        return new RiskExceptionRow(rs.getLong("id"), rs.getString("dependency_name"),
                rs.getInt("dependency_version"), rs.getInt("exception_version"),
                rs.getString("status"), rs.getString("reason"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                decided == null ? null : decided.toInstant());
    }

    /** 修订 PENDING 申请：原因、到期时刻更新，申请版本加一。 */
    public int reviseRiskException(long id, String reason, Instant expiresAt) {
        return jdbcTemplate.update(
                "UPDATE risk_exception SET reason = ?, expires_at = ?, exception_version = exception_version + 1 "
                        + "WHERE id = ? AND status = 'PENDING'",
                reason, Timestamp.from(expiresAt), id);
    }

    /** 删除指定申请版本之外的历史审批（修订后旧版本审批作废）。 */
    public int deleteApprovalsExceptVersion(long exceptionId, int exceptionVersion) {
        return jdbcTemplate.update(
                "DELETE FROM risk_exception_approval WHERE exception_id = ? AND exception_version <> ?",
                exceptionId, exceptionVersion);
    }

    /**
     * 插入审批记录；同一豁免同一审批人重复确认由唯一索引拒绝。
     * 返回受影响行数（0 表示该审批人已审批过）。
     */
    public int insertApproval(long exceptionId, String approver, int exceptionVersion, Instant now) {
        try {
            return jdbcTemplate.update(
                    "INSERT INTO risk_exception_approval (exception_id, approver, exception_version, approved_at) "
                            + "VALUES (?, ?, ?, ?)",
                    exceptionId, approver, exceptionVersion, Timestamp.from(now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return 0;
        }
    }

    /** 统计针对指定申请版本的不同审批人数。 */
    public int countApprovalsForVersion(long exceptionId, int exceptionVersion) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM risk_exception_approval "
                        + "WHERE exception_id = ? AND exception_version = ?",
                Integer.class, exceptionId, exceptionVersion);
        return count == null ? 0 : count;
    }

    /** 将豁免置为 APPROVED 并记录生效时间，仅 PENDING 可转换。 */
    public int markExceptionApproved(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE risk_exception SET status = 'APPROVED', decided_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                Timestamp.from(now), id);
    }

    /** 撤销豁免（APPROVED -> REVOKED），返回受影响行数。 */
    public int revokeRiskException(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE risk_exception SET status = 'REVOKED', decided_at = ? "
                        + "WHERE id = ? AND status = 'APPROVED'",
                Timestamp.from(now), id);
    }

    /** 查询豁免的全部审批记录，按审批时间升序。 */
    public List<ApprovalRow> listApprovals(long exceptionId) {
        return jdbcTemplate.query(
                "SELECT exception_id, approver, exception_version, approved_at "
                        + "FROM risk_exception_approval WHERE exception_id = ? ORDER BY approved_at ASC, id ASC",
                (rs, n) -> new ApprovalRow(rs.getLong("exception_id"), rs.getString("approver"),
                        rs.getInt("exception_version"), rs.getTimestamp("approved_at").toInstant()),
                exceptionId);
    }

    /** 查询全部豁免申请，按 ID 升序。 */
    public List<RiskExceptionRow> listRiskExceptions() {
        return jdbcTemplate.query(
                "SELECT id, dependency_name, dependency_version, exception_version, status, reason, "
                        + "expires_at, created_at, decided_at FROM risk_exception ORDER BY id ASC",
                (rs, n) -> mapExceptionRow(rs));
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }
}
