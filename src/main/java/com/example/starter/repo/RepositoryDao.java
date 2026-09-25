package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.Attestation;
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

    /** 新增制品版本，返回自增主键；digest 可空（未登记摘要）。 */
    public long insertArtifact(String name, int version, String digest, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact (name, version, withdrawn, digest, created_at) VALUES (?, ?, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setString(3, digest);
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
                              long repositoryVersion, Instant createdAt) {
    }

    /** 锁文件条目行。 */
    public record LockEntryRow(long lockFileId, String name, int version) {
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
                "SELECT lock_file_id, name, version FROM lock_file_entry "
                        + "WHERE lock_file_id = ? ORDER BY name ASC",
                (rs, n) -> new LockEntryRow(rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version")),
                lockFileId);
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }

    // ------------------------------------------------------------------
    // 来源策略
    // ------------------------------------------------------------------

    /** 当前最大策略版本号；无策略返回 0。 */
    public int maxPolicyVersion() {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM provenance_policy", Integer.class);
        return version == null ? 0 : version;
    }

    /** 追加一个策略版本，返回自增主键。 */
    public long insertPolicy(int version, int minLevel, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO provenance_policy (version, min_level, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, version);
            ps.setInt(2, minLevel);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入策略版本未获取自增主键");
        }
        return key.longValue();
    }

    /** 为策略版本写入一个允许的来源仓标识。 */
    public void insertPolicyRepo(long policyId, String repoId) {
        jdbcTemplate.update(
                "INSERT INTO provenance_policy_repo (policy_id, repo_id) VALUES (?, ?)",
                policyId, repoId);
    }

    /** 策略版本行（不含允许仓集合）。 */
    public record PolicyRow(long id, int version, int minLevel, Instant createdAt) {
    }

    /** 查询全部策略版本，按版本号升序。 */
    public List<PolicyRow> listPolicies() {
        return jdbcTemplate.query(
                "SELECT id, version, min_level, created_at FROM provenance_policy ORDER BY version ASC",
                (rs, n) -> new PolicyRow(rs.getLong("id"), rs.getInt("version"),
                        rs.getInt("min_level"), rs.getTimestamp("created_at").toInstant()));
    }

    /** 查询某策略版本允许的来源仓标识，字典序升序。 */
    public List<String> listPolicyRepos(long policyId) {
        return jdbcTemplate.query(
                "SELECT repo_id FROM provenance_policy_repo WHERE policy_id = ? ORDER BY repo_id ASC",
                (rs, n) -> rs.getString("repo_id"), policyId);
    }

    // ------------------------------------------------------------------
    // 来源证明
    // ------------------------------------------------------------------

    /** 指定坐标当前最大证明版本号；无证明返回 0。 */
    public int maxAttestationVersion(String name, int version) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(attestation_version), 0) FROM attestation "
                        + "WHERE name = ? AND version = ?",
                Integer.class, name, version);
        return value == null ? 0 : value;
    }

    /** 追加一条证明，返回自增主键。 */
    public long insertAttestation(String name, int version, int attestationVersion,
                                  String repoId, String digest, int level, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO attestation (name, version, attestation_version, repo_id, digest, "
                            + "attestation_level, revoked, created_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setInt(3, attestationVersion);
            ps.setString(4, repoId);
            ps.setString(5, digest);
            ps.setInt(6, level);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入证明未获取自增主键");
        }
        return key.longValue();
    }

    /** 读取指定坐标的当前证明（证明版本最大的一条），不存在返回 null。 */
    public Attestation loadCurrentAttestation(String name, int version) {
        List<Attestation> rows = jdbcTemplate.query(
                "SELECT id, name, version, attestation_version, repo_id, digest, attestation_level, revoked "
                        + "FROM attestation WHERE name = ? AND version = ? "
                        + "ORDER BY attestation_version DESC",
                (rs, n) -> mapAttestation(rs), name, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取全部坐标的当前证明：坐标键(name:version) -> 证明版本最大的一条。
     */
    public Map<String, Attestation> loadCurrentAttestations() {
        List<Attestation> rows = jdbcTemplate.query(
                "SELECT id, name, version, attestation_version, repo_id, digest, attestation_level, revoked "
                        + "FROM attestation ORDER BY name ASC, version ASC, attestation_version ASC",
                (rs, n) -> mapAttestation(rs));
        Map<String, Attestation> current = new LinkedHashMap<>();
        for (Attestation row : rows) {
            // 按证明版本升序遍历，后写覆盖先写，最终保留最大版本。
            current.put(row.coordinate(), row);
        }
        return current;
    }

    /**
     * 撤销指定证明，仅当当前未撤销时生效，返回受影响行数。
     */
    public int markAttestationRevoked(long attestationId) {
        return jdbcTemplate.update(
                "UPDATE attestation SET revoked = 1 WHERE id = ? AND revoked = 0", attestationId);
    }

    private static Attestation mapAttestation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Attestation(rs.getLong("id"), rs.getString("name"), rs.getInt("version"),
                rs.getInt("attestation_version"), rs.getString("repo_id"), rs.getString("digest"),
                rs.getInt("attestation_level"), rs.getInt("revoked") == 1);
    }

    // ------------------------------------------------------------------
    // 来源校验辅助数据
    // ------------------------------------------------------------------

    /** 全部制品坐标的登记摘要：坐标键 -> 摘要；未登记摘要的坐标不出现在结果中。 */
    public Map<String, String> loadArtifactDigests() {
        Map<String, String> digests = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT name, version, digest FROM artifact WHERE digest IS NOT NULL",
                (rs, n) -> {
                    digests.put(rs.getString("name") + ":" + rs.getInt("version"),
                            rs.getString("digest"));
                    return null;
                });
        return digests;
    }

    /** 全部制品坐标声明的依赖：坐标键 -> 依赖区间列表。 */
    public Map<String, List<DependencyRange>> loadAllDependencies() {
        List<DepRowWithCoord> depRows = jdbcTemplate.query(
                "SELECT a.name AS artifact_name, a.version AS artifact_version, "
                        + "d.name, d.minimum_version, d.maximum_version "
                        + "FROM artifact_dependency d JOIN artifact a ON d.artifact_id = a.id",
                (rs, n) -> new DepRowWithCoord(rs.getString("artifact_name"),
                        rs.getInt("artifact_version"), rs.getString("name"),
                        rs.getInt("minimum_version"), rs.getInt("maximum_version")));
        Map<String, List<DependencyRange>> index = new LinkedHashMap<>();
        for (DepRowWithCoord row : depRows) {
            index.computeIfAbsent(row.artifactName() + ":" + row.artifactVersion(),
                            k -> new ArrayList<>())
                    .add(new DependencyRange(row.name(), row.minimumVersion(), row.maximumVersion()));
        }
        return index;
    }

    private record DepRowWithCoord(String artifactName, int artifactVersion, String name,
                                   int minimumVersion, int maximumVersion) {
    }

    // ------------------------------------------------------------------
    // 发布快照
    // ------------------------------------------------------------------

    /** 发布记录行。 */
    public record PublishRow(long id, long lockFileId, int policyVersion, String provenanceKey,
                             String operatorName, Instant createdAt) {
    }

    /** 发布条目行。 */
    public record PublishEntryRow(long publishId, String name, int version, long attestationId,
                                  int attestationVersion, String repoId, String digest, int level) {
    }

    /** 新增发布记录，返回自增主键；provenance_key 冲突由唯一索引抛出。 */
    public long insertPublishRecord(long lockFileId, int policyVersion, String provenanceKey,
                                    String operatorName, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO publish_record (lock_file_id, policy_version, provenance_key, "
                            + "operator_name, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lockFileId);
            ps.setInt(2, policyVersion);
            ps.setString(3, provenanceKey);
            ps.setString(4, operatorName);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入发布记录未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增发布快照条目。 */
    public void insertPublishEntry(long publishId, String name, int version, long attestationId,
                                   int attestationVersion, String repoId, String digest, int level) {
        jdbcTemplate.update(
                "INSERT INTO publish_entry (publish_id, name, version, attestation_id, "
                        + "attestation_version, repo_id, digest, attestation_level) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                publishId, name, version, attestationId, attestationVersion, repoId, digest, level);
    }

    /** 按 provenanceKey 查询发布记录，不存在返回 null。 */
    public PublishRow findPublishByKey(String provenanceKey) {
        List<PublishRow> rows = jdbcTemplate.query(
                "SELECT id, lock_file_id, policy_version, provenance_key, operator_name, created_at "
                        + "FROM publish_record WHERE provenance_key = ?",
                (rs, n) -> mapPublishRow(rs), provenanceKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某锁文件最近一次发布记录，未发布返回 null。 */
    public PublishRow findLatestPublishByLock(long lockFileId) {
        List<PublishRow> rows = jdbcTemplate.query(
                "SELECT id, lock_file_id, policy_version, provenance_key, operator_name, created_at "
                        + "FROM publish_record WHERE lock_file_id = ? ORDER BY id DESC",
                (rs, n) -> mapPublishRow(rs), lockFileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static PublishRow mapPublishRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PublishRow(rs.getLong("id"), rs.getLong("lock_file_id"),
                rs.getInt("policy_version"), rs.getString("provenance_key"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toInstant());
    }

    /** 查询某次发布的全部冻结条目，按名称升序。 */
    public List<PublishEntryRow> listPublishEntries(long publishId) {
        return jdbcTemplate.query(
                "SELECT publish_id, name, version, attestation_id, attestation_version, repo_id, "
                        + "digest, attestation_level FROM publish_entry "
                        + "WHERE publish_id = ? ORDER BY name ASC",
                (rs, n) -> new PublishEntryRow(rs.getLong("publish_id"), rs.getString("name"),
                        rs.getInt("version"), rs.getLong("attestation_id"),
                        rs.getInt("attestation_version"), rs.getString("repo_id"),
                        rs.getString("digest"), rs.getInt("attestation_level")),
                publishId);
    }
}
