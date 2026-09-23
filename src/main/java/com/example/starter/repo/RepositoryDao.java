package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.Coordinate;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.SubstitutionPolicy;
import com.example.starter.domain.SubstitutionRule;
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

        Map<Long, Set<String>> platformsByArtifact = loadAllPlatforms();

        Map<String, List<ArtifactVersion>> artifacts = new LinkedHashMap<>();
        for (ArtifactRow row : rows) {
            List<DependencyRange> deps = depsByArtifact.getOrDefault(row.id(), List.of());
            Set<String> platforms = platformsByArtifact.getOrDefault(row.id(), Set.of());
            ArtifactVersion version = new ArtifactVersion(row.id(), row.name(), row.version(),
                    row.withdrawn(), List.copyOf(deps), Set.copyOf(platforms));
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
        Set<String> platforms = loadPlatforms(row.id());
        return new ArtifactVersion(row.id(), row.name(), row.version(), row.withdrawn(),
                List.copyOf(deps), Set.copyOf(platforms));
    }

    /** 为制品版本写入一个可用平台。 */
    public void insertPlatform(long artifactId, String platform) {
        jdbcTemplate.update(
                "INSERT INTO artifact_platform (artifact_id, platform) VALUES (?, ?)",
                artifactId, platform);
    }

    /** 读取某制品版本的可用平台清单，按平台名升序。 */
    public Set<String> loadPlatforms(long artifactId) {
        return new java.util.TreeSet<>(jdbcTemplate.queryForList(
                "SELECT platform FROM artifact_platform WHERE artifact_id = ? ORDER BY platform ASC",
                String.class, artifactId));
    }

    /** 批量读取全部制品版本的可用平台，供快照一次性加载。 */
    public Map<Long, Set<String>> loadAllPlatforms() {
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT artifact_id, platform FROM artifact_platform ORDER BY artifact_id ASC, platform ASC",
                rs -> {
                    long artifactId = rs.getLong("artifact_id");
                    result.computeIfAbsent(artifactId, k -> new java.util.TreeSet<>())
                            .add(rs.getString("platform"));
                });
        return result;
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
     * 恢复（取消撤回）指定制品版本，仅当当前已撤回时生效，返回受影响行数。
     * 历史锁文件解释不受恢复影响。
     */
    public int restoreWithdrawn(long artifactId) {
        return jdbcTemplate.update(
                "UPDATE artifact SET withdrawn = 0 WHERE id = ? AND withdrawn = 1", artifactId);
    }

    /** 新增锁文件主记录，返回自增主键。 */
    public long insertLockFile(String rootName, int rootVersion, long repositoryVersion,
                               String requestId, Instant createdAt) {
        return insertLockFile(rootName, rootVersion, repositoryVersion, requestId, null, null, createdAt);
    }

    /** 新增锁文件主记录（含平台与冻结策略版本），返回自增主键。 */
    public long insertLockFile(String rootName, int rootVersion, long repositoryVersion,
                               String requestId, String platform, Long policyVersion, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lock_file (root_name, root_version, repository_version, request_id, "
                            + "platform, policy_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rootName);
            ps.setInt(2, rootVersion);
            ps.setLong(3, repositoryVersion);
            ps.setString(4, requestId);
            ps.setString(5, platform);
            if (policyVersion == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, policyVersion);
            }
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
                              long repositoryVersion, Instant createdAt,
                              String platform, Long policyVersion) {
    }

    /** 锁文件条目行。 */
    public record LockEntryRow(long lockFileId, String name, int version) {
    }

    /** 查询全部历史锁文件，按 ID 升序。 */
    public List<LockFileRow> listLockFiles() {
        return jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, created_at, platform, policy_version "
                        + "FROM lock_file ORDER BY id ASC",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("platform"),
                        (Long) rs.getObject("policy_version")));
    }

    /** 按主键查询锁文件，不存在返回 null。 */
    public LockFileRow getLockFile(long id) {
        List<LockFileRow> rows = jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, created_at, platform, policy_version "
                        + "FROM lock_file WHERE id = ?",
                (rs, n) -> new LockFileRow(rs.getLong("id"), rs.getString("root_name"),
                        rs.getInt("root_version"), rs.getLong("repository_version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("platform"),
                        (Long) rs.getObject("policy_version")),
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
    // 替代策略
    // ------------------------------------------------------------------

    /** 是否存在相同 policyKey 的策略版本。 */
    public boolean policyKeyExists(String policyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM substitution_policy WHERE policy_key = ?",
                Integer.class, policyKey);
        return count != null && count > 0;
    }

    /** 新增策略版本主记录，返回自增策略版本号。 */
    public long insertPolicy(String policyKey, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO substitution_policy (policy_key, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, policyKey);
            ps.setTimestamp(2, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入策略未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增策略规则，返回规则主键。 */
    public long insertRule(long policyVersion, int ruleIndex, String sourcePattern,
                           String targetPlatform, Instant effectiveAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO substitution_rule (policy_version, rule_index, source_pattern, "
                            + "target_platform, effective_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, policyVersion);
            ps.setInt(2, ruleIndex);
            ps.setString(3, sourcePattern);
            ps.setString(4, targetPlatform);
            ps.setTimestamp(5, Timestamp.from(effectiveAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入规则未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增规则的替代坐标。 */
    public void insertAlternative(long ruleId, int position, String name, int version) {
        jdbcTemplate.update(
                "INSERT INTO substitution_alternative (rule_id, position, target_name, target_version) "
                        + "VALUES (?, ?, ?, ?)",
                ruleId, position, name, version);
    }

    /**
     * 读取当前最新策略版本（版本号最大），不含规则，不存在返回 null。
     */
    public SubstitutionPolicy loadLatestPolicyHeader() {
        List<SubstitutionPolicy> headers = jdbcTemplate.query(
                "SELECT policy_version, policy_key, created_at FROM substitution_policy "
                        + "ORDER BY policy_version DESC LIMIT 1",
                (rs, n) -> new SubstitutionPolicy(rs.getLong("policy_version"),
                        rs.getString("policy_key"),
                        rs.getTimestamp("created_at").toInstant(), List.of()));
        return headers.isEmpty() ? null : headers.get(0);
    }

    /** 读取指定策略版本的完整规则集合，规则按序号升序，替代坐标按优先级升序。 */
    public SubstitutionPolicy loadPolicy(long policyVersion) {
        List<SubstitutionPolicy> headers = jdbcTemplate.query(
                "SELECT policy_version, policy_key, created_at FROM substitution_policy "
                        + "WHERE policy_version = ?",
                (rs, n) -> new SubstitutionPolicy(rs.getLong("policy_version"),
                        rs.getString("policy_key"),
                        rs.getTimestamp("created_at").toInstant(), List.of()),
                policyVersion);
        if (headers.isEmpty()) {
            return null;
        }
        SubstitutionPolicy header = headers.get(0);

        List<RuleRow> ruleRows = jdbcTemplate.query(
                "SELECT id, rule_index, source_pattern, target_platform, effective_at "
                        + "FROM substitution_rule WHERE policy_version = ? ORDER BY rule_index ASC",
                (rs, n) -> new RuleRow(rs.getLong("id"), rs.getInt("rule_index"),
                        rs.getString("source_pattern"), rs.getString("target_platform"),
                        rs.getTimestamp("effective_at").toInstant()),
                policyVersion);

        List<SubstitutionRule> rules = new ArrayList<>();
        for (RuleRow row : ruleRows) {
            List<Coordinate> alternatives = jdbcTemplate.query(
                    "SELECT target_name, target_version FROM substitution_alternative "
                            + "WHERE rule_id = ? ORDER BY position ASC",
                    (rs, n) -> new Coordinate(rs.getString("target_name"),
                            rs.getInt("target_version")),
                    row.id());
            rules.add(new SubstitutionRule(row.id(), row.ruleIndex(), row.sourcePattern(),
                    row.targetPlatform(), row.effectiveAt(), List.copyOf(alternatives)));
        }
        return new SubstitutionPolicy(header.policyVersion(), header.policyKey(),
                header.createdAt(), List.copyOf(rules));
    }

    /** 读取当前最新策略版本的完整内容，不存在返回 null。 */
    public SubstitutionPolicy loadLatestPolicy() {
        SubstitutionPolicy header = loadLatestPolicyHeader();
        return header == null ? null : loadPolicy(header.policyVersion());
    }

    /** 列出全部策略版本头（不含规则），按版本号升序，稳定排序。 */
    public List<SubstitutionPolicy> listPolicyHeaders() {
        return jdbcTemplate.query(
                "SELECT policy_version, policy_key, created_at FROM substitution_policy "
                        + "ORDER BY policy_version ASC",
                (rs, n) -> new SubstitutionPolicy(rs.getLong("policy_version"),
                        rs.getString("policy_key"),
                        rs.getTimestamp("created_at").toInstant(), List.of()));
    }

    // ------------------------------------------------------------------
    // 锁图替代解释快照
    // ------------------------------------------------------------------

    /** 锁图替代步骤行：候选拒绝原因以 JSON 字符串存储。 */
    public record StepRow(long id, long lockFileId, int stepIndex,
                          String originalName, int originalVersion,
                          long ruleId, int ruleIndex, String sourcePattern,
                          String finalName, int finalVersion,
                          String rejectedCandidatesJson, long policyVersion) {
    }

    /** 新增一条锁图替代步骤快照。 */
    public void insertSubstitutionStep(long lockFileId, int stepIndex,
                                       String originalName, int originalVersion,
                                       long ruleId, int ruleIndex, String sourcePattern,
                                       String finalName, int finalVersion,
                                       String rejectedCandidatesJson, long policyVersion) {
        jdbcTemplate.update(
                "INSERT INTO lock_substitution_step (lock_file_id, step_index, original_name, "
                        + "original_version, rule_id, rule_index, source_pattern, final_name, "
                        + "final_version, rejected_candidates_json, policy_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                lockFileId, stepIndex, originalName, originalVersion, ruleId, ruleIndex,
                sourcePattern, finalName, finalVersion, rejectedCandidatesJson, policyVersion);
    }

    /** 查询某锁文件的全部替代步骤，按步骤序号升序。 */
    public List<StepRow> listSubstitutionSteps(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT id, lock_file_id, step_index, original_name, original_version, rule_id, "
                        + "rule_index, source_pattern, final_name, final_version, "
                        + "rejected_candidates_json, policy_version "
                        + "FROM lock_substitution_step WHERE lock_file_id = ? ORDER BY step_index ASC",
                (rs, n) -> new StepRow(rs.getLong("id"), rs.getLong("lock_file_id"),
                        rs.getInt("step_index"), rs.getString("original_name"),
                        rs.getInt("original_version"), rs.getLong("rule_id"),
                        rs.getInt("rule_index"), rs.getString("source_pattern"),
                        rs.getString("final_name"), rs.getInt("final_version"),
                        rs.getString("rejected_candidates_json"),
                        rs.getLong("policy_version")),
                lockFileId);
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }

    private record RuleRow(long id, int ruleIndex, String sourcePattern,
                           String targetPlatform, Instant effectiveAt) {
    }
}
