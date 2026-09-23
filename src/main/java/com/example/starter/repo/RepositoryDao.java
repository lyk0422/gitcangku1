package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.CandidateRejection;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.CoordinatePattern;
import com.example.starter.domain.PlatformAvailability;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.SubstitutionCandidate;
import com.example.starter.domain.SubstitutionPolicy;
import com.example.starter.domain.SubstitutionRule;
import com.example.starter.domain.SubstitutionStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                               String platform, Long policyVersion,
                               String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lock_file (root_name, root_version, repository_version, "
                            + "platform, policy_version, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rootName);
            ps.setInt(2, rootVersion);
            ps.setLong(3, repositoryVersion);
            ps.setString(4, platform);
            if (policyVersion == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, policyVersion);
            }
            ps.setString(6, requestId);
            ps.setTimestamp(7, Timestamp.from(createdAt));
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

    /** 锁文件列表行：不含条目明细。 */
    public record LockFileRow(long id, String rootName, int rootVersion,
                              long repositoryVersion, String platform, Long policyVersion,
                              Instant createdAt) {
    }

    /** 锁文件条目行。 */
    public record LockEntryRow(long lockFileId, String name, int version) {
    }

    /** 查询全部历史锁文件，按 ID 升序。 */
    public List<LockFileRow> listLockFiles() {
        return jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, platform, "
                        + "policy_version, created_at FROM lock_file ORDER BY id ASC",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getString("platform"),
                        rs.getObject("policy_version", Long.class),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** 按主键查询锁文件，不存在返回 null。 */
    public LockFileRow getLockFile(long id) {
        List<LockFileRow> rows = jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, platform, "
                        + "policy_version, created_at FROM lock_file WHERE id = ?",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getString("platform"),
                        rs.getObject("policy_version", Long.class),
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

    // ------------------------------------------------------------------
    // 平台可用性、恢复
    // ------------------------------------------------------------------

    /** 为制品版本登记一个可用平台。 */
    public void insertArtifactPlatform(long artifactId, String platform) {
        jdbcTemplate.update(
                "INSERT INTO artifact_platform (artifact_id, platform) VALUES (?, ?)",
                artifactId, platform);
    }

    /**
     * 读取全部制品版本的平台白名单；无行的制品版本不在 Map 中（语义为全平台可用）。
     * 须在一致性事务内调用。
     */
    public PlatformAvailability loadAvailability() {
        List<PlatformRow> rows = jdbcTemplate.query(
                "SELECT artifact_id, platform FROM artifact_platform",
                (rs, n) -> new PlatformRow(rs.getLong("artifact_id"), rs.getString("platform")));
        Map<Long, Set<String>> map = new HashMap<>();
        for (PlatformRow row : rows) {
            map.computeIfAbsent(row.artifactId(), k -> new HashSet<>()).add(row.platform());
        }
        return new PlatformAvailability(map);
    }

    /**
     * 恢复（取消撤回）指定制品版本，仅当当前已撤回时生效，返回受影响行数。
     */
    public int restoreWithdrawn(long artifactId) {
        return jdbcTemplate.update(
                "UPDATE artifact SET withdrawn = 0 WHERE id = ? AND withdrawn = 1", artifactId);
    }

    // ------------------------------------------------------------------
    // 策略版本
    // ------------------------------------------------------------------

    /**
     * 锁定策略单行版本并返回当前 policyVersion（0 表示尚未发布过策略）。
     * 写事务入口调用，与 repository_state 行锁一起串行化策略激活与锁定。
     */
    public long lockPolicyState() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT current_version FROM policy_state WHERE id = 1 FOR UPDATE", Long.class);
        return version == null ? 0L : version;
    }

    /** 读取当前 policyVersion（不加锁，供查询使用），从未发布时为 null。 */
    public Long getCurrentPolicyVersion() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT current_version FROM policy_state WHERE id = 1", Long.class);
        return version == null || version == 0L ? null : version;
    }

    /** 策略激活：策略版本号加一，返回新版本号（须同时持有 repository_state 行锁）。 */
    public long advancePolicyVersion() {
        jdbcTemplate.update("UPDATE policy_state SET current_version = current_version + 1 WHERE id = 1");
        Long version = jdbcTemplate.queryForObject(
                "SELECT current_version FROM policy_state WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /** 插入一个新的不可变策略版本主记录。 */
    public void insertPolicy(long version, int ruleCount, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO substitution_policy (version, rule_count, created_at) VALUES (?, ?, ?)",
                version, ruleCount, Timestamp.from(createdAt));
    }

    /** 插入策略规则，返回自增主键。 */
    public long insertRule(long policyVersion, int ruleOrder, String sourcePattern,
                           String platform, Instant effectiveAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO substitution_rule (policy_version, rule_order, source_pattern, "
                            + "platform, effective_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, policyVersion);
            ps.setInt(2, ruleOrder);
            ps.setString(3, sourcePattern);
            ps.setString(4, platform);
            ps.setTimestamp(5, Timestamp.from(effectiveAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入策略规则未获取自增主键");
        }
        return key.longValue();
    }

    /** 插入规则候选坐标。 */
    public void insertCandidate(long ruleId, int priority, String coordinate) {
        jdbcTemplate.update(
                "INSERT INTO substitution_candidate (rule_id, priority, coordinate) VALUES (?, ?, ?)",
                ruleId, priority, coordinate);
    }

    /**
     * 在一致性事务内读取指定 policyVersion 的完整策略；version 为 null 或不存在时返回 null。
     */
    public SubstitutionPolicy loadPolicy(Long version) {
        if (version == null) {
            return null;
        }
        List<PolicyRow> policyRows = jdbcTemplate.query(
                "SELECT version, rule_count, created_at FROM substitution_policy WHERE version = ?",
                (rs, n) -> new PolicyRow(rs.getLong("version"), rs.getInt("rule_count"),
                        rs.getTimestamp("created_at").toInstant()),
                version);
        if (policyRows.isEmpty()) {
            return null;
        }
        PolicyRow policyRow = policyRows.get(0);

        List<RuleRow> ruleRows = jdbcTemplate.query(
                "SELECT id, rule_order, source_pattern, platform, effective_at "
                        + "FROM substitution_rule WHERE policy_version = ? ORDER BY rule_order ASC",
                (rs, n) -> new RuleRow(rs.getLong("id"), rs.getInt("rule_order"),
                        rs.getString("source_pattern"), rs.getString("platform"),
                        rs.getTimestamp("effective_at").toInstant()),
                version);

        List<CandidateRow> candidateRows = jdbcTemplate.query(
                "SELECT c.rule_id, c.priority, c.coordinate "
                        + "FROM substitution_candidate c "
                        + "JOIN substitution_rule r ON r.id = c.rule_id "
                        + "WHERE r.policy_version = ? ORDER BY c.rule_id ASC, c.priority ASC",
                (rs, n) -> new CandidateRow(rs.getLong("rule_id"), rs.getInt("priority"),
                        rs.getString("coordinate")),
                version);
        Map<Long, List<SubstitutionCandidate>> candidatesByRule = new LinkedHashMap<>();
        for (CandidateRow row : candidateRows) {
            candidatesByRule.computeIfAbsent(row.ruleId(), k -> new ArrayList<>())
                    .add(new SubstitutionCandidate(row.coordinate(), row.priority()));
        }

        List<SubstitutionRule> rules = new ArrayList<>();
        for (RuleRow row : ruleRows) {
            List<SubstitutionCandidate> candidates =
                    List.copyOf(candidatesByRule.getOrDefault(row.id(), List.of()));
            rules.add(new SubstitutionRule(row.id(), CoordinatePattern.of(row.sourcePattern()),
                    row.platform(), candidates, row.effectiveAt()));
        }
        return new SubstitutionPolicy(policyRow.version(), policyRow.createdAt(), rules);
    }

    /** 列出全部已发布策略版本（不含规则明细），按版本升序。 */
    public List<PolicySummaryRow> listPolicies() {
        return jdbcTemplate.query(
                "SELECT version, rule_count, created_at FROM substitution_policy ORDER BY version ASC",
                (rs, n) -> new PolicySummaryRow(rs.getLong("version"), rs.getInt("rule_count"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** 策略摘要行。 */
    public record PolicySummaryRow(long version, int ruleCount, Instant createdAt) {
    }

    // ------------------------------------------------------------------
    // 锁解释快照
    // ------------------------------------------------------------------

    /** 新增替代步骤，返回自增主键。 */
    public long insertSubstitutionStep(long lockFileId, SubstitutionStep step) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lock_substitution_step (lock_file_id, step_order, "
                            + "original_coordinate, source_pattern, platform, final_coordinate, "
                            + "policy_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lockFileId);
            ps.setInt(2, step.stepOrder());
            ps.setString(3, step.originalCoordinate());
            ps.setString(4, step.sourcePattern());
            ps.setString(5, step.platform());
            ps.setString(6, step.finalCoordinate());
            ps.setLong(7, step.policyVersion());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入替代步骤未获取自增主键");
        }
        long stepId = key.longValue();
        for (CandidateRejection rejection : step.rejections()) {
            insertStepRejection(stepId, rejection);
        }
        return stepId;
    }

    private void insertStepRejection(long stepId, CandidateRejection rejection) {
        jdbcTemplate.update(
                "INSERT INTO lock_step_rejection (step_id, coordinate, priority, reason) "
                        + "VALUES (?, ?, ?, ?)",
                stepId, rejection.coordinate(), rejection.priority(), rejection.reason());
    }

    /** 查询某锁文件的全部替代步骤（含拒绝候选），按步骤序号、候选优先级稳定排序。 */
    public List<SubstitutionStep> listSubstitutionSteps(long lockFileId) {
        List<StepRow> stepRows = jdbcTemplate.query(
                "SELECT id, step_order, original_coordinate, source_pattern, platform, "
                        + "final_coordinate, policy_version FROM lock_substitution_step "
                        + "WHERE lock_file_id = ? ORDER BY step_order ASC",
                (rs, n) -> new StepRow(rs.getLong("id"), rs.getInt("step_order"),
                        rs.getString("original_coordinate"), rs.getString("source_pattern"),
                        rs.getString("platform"), rs.getString("final_coordinate"),
                        rs.getLong("policy_version")),
                lockFileId);

        List<RejectionRow> rejectionRows = jdbcTemplate.query(
                "SELECT step_id, coordinate, priority, reason FROM lock_step_rejection "
                        + "WHERE step_id IN (SELECT id FROM lock_substitution_step WHERE lock_file_id = ?) "
                        + "ORDER BY step_id ASC, priority ASC",
                (rs, n) -> new RejectionRow(rs.getLong("step_id"), rs.getString("coordinate"),
                        rs.getInt("priority"), rs.getString("reason")),
                lockFileId);
        Map<Long, List<CandidateRejection>> rejectionsByStep = new LinkedHashMap<>();
        for (RejectionRow row : rejectionRows) {
            rejectionsByStep.computeIfAbsent(row.stepId(), k -> new ArrayList<>())
                    .add(new CandidateRejection(row.coordinate(), row.priority(), row.reason()));
        }

        List<SubstitutionStep> steps = new ArrayList<>();
        for (StepRow row : stepRows) {
            steps.add(new SubstitutionStep(row.stepOrder(), row.originalCoordinate(),
                    row.sourcePattern(), row.platform(), row.finalCoordinate(),
                    row.policyVersion(),
                    List.copyOf(rejectionsByStep.getOrDefault(row.id(), List.of()))));
        }
        return steps;
    }

    private record PlatformRow(long artifactId, String platform) {
    }

    private record PolicyRow(long version, int ruleCount, Instant createdAt) {
    }

    private record RuleRow(long id, int ruleOrder, String sourcePattern,
                           String platform, Instant effectiveAt) {
    }

    private record CandidateRow(long ruleId, int priority, String coordinate) {
    }

    private record StepRow(long id, int stepOrder, String originalCoordinate,
                           String sourcePattern, String platform, String finalCoordinate,
                           long policyVersion) {
    }

    private record RejectionRow(long stepId, String coordinate, int priority, String reason) {
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }
}
