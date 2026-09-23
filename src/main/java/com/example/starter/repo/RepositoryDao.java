package com.example.starter.repo;

import com.example.starter.domain.ArtifactSignature;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.RepositorySnapshot;
import com.example.starter.domain.SigningPolicy;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仓库数据访问：制品、依赖、签名信任策略、签名、锁文件、仓库版本与幂等记录。
 *
 * <p>所有多语句业务操作均在 Service 层事务内执行；写事务通过
 * {@code SELECT ... FOR UPDATE} 锁定单行仓库版本表实现串行化，
 * 保证策略发布/钥匙撤销/补签与解析并发时，锁文件只能引用一个一致状态。
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
                "SELECT id, name, version, withdrawn, content_digest FROM artifact "
                        + "ORDER BY name ASC, version DESC",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1,
                        rs.getString("content_digest")));

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
                    row.withdrawn(), row.contentDigest(), List.copyOf(deps));
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
    public long insertArtifact(String name, int version, String contentDigest, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact (name, version, withdrawn, content_digest, created_at) "
                            + "VALUES (?, ?, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setString(3, contentDigest);
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
                "SELECT id, name, version, withdrawn, content_digest FROM artifact "
                        + "WHERE name = ? AND version = ?",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1,
                        rs.getString("content_digest")),
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
                row.contentDigest(), List.copyOf(deps));
    }

    /** 按主键读取制品版本，不存在返回 null。 */
    public ArtifactVersion loadArtifactById(long artifactId) {
        List<ArtifactRow> rows = jdbcTemplate.query(
                "SELECT id, name, version, withdrawn, content_digest FROM artifact WHERE id = ?",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getInt("withdrawn") == 1,
                        rs.getString("content_digest")),
                artifactId);
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
                row.contentDigest(), List.copyOf(deps));
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

    // ------------------------------------------------------------------
    // 签名钥匙
    // ------------------------------------------------------------------

    /** 钥匙行。 */
    public record KeyRow(String keyId, boolean revoked, Instant revokedAt) {
    }

    /** 钥匙不存在时登记为有效钥匙；已存在则不改变撤销状态。 */
    public void insertSigningKeyIfAbsent(String keyId, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO signing_key (key_id, revoked, created_at) "
                        + "SELECT ?, 0, ? WHERE NOT EXISTS (SELECT 1 FROM signing_key WHERE key_id = ?)",
                keyId, Timestamp.from(createdAt), keyId);
    }

    /** 按 keyId 读取钥匙，不存在返回 null。 */
    public KeyRow getSigningKey(String keyId) {
        List<KeyRow> rows = jdbcTemplate.query(
                "SELECT key_id, revoked, revoked_at FROM signing_key WHERE key_id = ?",
                (rs, n) -> new KeyRow(rs.getString("key_id"), rs.getInt("revoked") == 1,
                        rs.getTimestamp("revoked_at") == null ? null
                                : rs.getTimestamp("revoked_at").toInstant()),
                keyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 读取全部钥匙，按 keyId 升序。 */
    public List<KeyRow> listSigningKeys() {
        return jdbcTemplate.query(
                "SELECT key_id, revoked, revoked_at FROM signing_key ORDER BY key_id ASC",
                (rs, n) -> new KeyRow(rs.getString("key_id"), rs.getInt("revoked") == 1,
                        rs.getTimestamp("revoked_at") == null ? null
                                : rs.getTimestamp("revoked_at").toInstant()));
    }

    /** 当前已撤销钥匙集合。 */
    public Set<String> listRevokedKeyIds() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT key_id FROM signing_key WHERE revoked = 1", String.class));
    }

    /** 撤销钥匙，仅当钥匙存在且当前未撤销时生效，返回受影响行数。 */
    public int markKeyRevoked(String keyId, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE signing_key SET revoked = 1, revoked_at = ? WHERE key_id = ? AND revoked = 0",
                Timestamp.from(revokedAt), keyId);
    }

    // ------------------------------------------------------------------
    // 签名信任策略
    // ------------------------------------------------------------------

    /** 策略主表行。 */
    public record PolicyRow(long policyVersion, int threshold,
                           Instant effectiveAt, Instant createdAt) {
    }

    /** 新增策略主记录，返回自增主键。 */
    public long insertPolicy(long policyVersion, int threshold, Instant effectiveAt,
                             String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO signing_policy (policy_version, threshold, effective_at, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, policyVersion);
            ps.setInt(2, threshold);
            ps.setTimestamp(3, Timestamp.from(effectiveAt));
            ps.setString(4, requestId);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入策略未获取自增主键");
        }
        return key.longValue();
    }

    /** 写入一条策略可信钥匙清单。 */
    public void insertPolicyKey(long policyId, long policyVersion, String keyId) {
        jdbcTemplate.update(
                "INSERT INTO signing_policy_key (policy_id, policy_version, key_id) VALUES (?, ?, ?)",
                policyId, policyVersion, keyId);
    }

    /** 当前最大策略版本号，无策略时返回 0。 */
    public long maxPolicyVersion() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT MAX(policy_version) FROM signing_policy", Long.class);
        return max == null ? 0L : max;
    }

    /** 读取某版本策略的可信 keyId 清单，按 keyId 升序。 */
    public List<String> listPolicyKeys(long policyVersion) {
        return jdbcTemplate.queryForList(
                "SELECT key_id FROM signing_policy_key WHERE policy_version = ? ORDER BY key_id ASC",
                String.class, policyVersion);
    }

    /** 按版本号读取策略主记录，不存在返回 null。 */
    public PolicyRow getPolicy(long policyVersion) {
        List<PolicyRow> rows = jdbcTemplate.query(
                "SELECT policy_version, threshold, effective_at, created_at "
                        + "FROM signing_policy WHERE policy_version = ?",
                (rs, n) -> new PolicyRow(rs.getLong("policy_version"), rs.getInt("threshold"),
                        rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()),
                policyVersion);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 读取全部策略（含尚未生效），按版本号升序。 */
    public List<PolicyRow> listPolicies() {
        return jdbcTemplate.query(
                "SELECT policy_version, threshold, effective_at, created_at "
                        + "FROM signing_policy ORDER BY policy_version ASC",
                (rs, n) -> new PolicyRow(rs.getLong("policy_version"), rs.getInt("threshold"),
                        rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /**
     * 选择指定时刻的当前生效策略：effective_at <= asOf 中版本号最高者；不存在返回 null。
     * 须在事务内调用，调用方通过 repository_state 行锁保证快照一致。
     */
    public SigningPolicy findEffectivePolicy(Instant asOf) {
        List<PolicyRow> rows = jdbcTemplate.query(
                "SELECT policy_version, threshold, effective_at, created_at FROM signing_policy "
                        + "WHERE effective_at <= ? ORDER BY policy_version DESC LIMIT 1",
                (rs, n) -> new PolicyRow(rs.getLong("policy_version"), rs.getInt("threshold"),
                        rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(asOf));
        if (rows.isEmpty()) {
            return null;
        }
        PolicyRow row = rows.get(0);
        return new SigningPolicy(row.policyVersion(), listPolicyKeys(row.policyVersion()),
                row.threshold(), row.effectiveAt());
    }

    // ------------------------------------------------------------------
    // 制品签名
    // ------------------------------------------------------------------

    /** 新增制品版本签名。 */
    public void insertSignature(long artifactId, String signatureKey, String keyId, String digest,
                                String requestId, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO artifact_signature (artifact_id, signature_key, key_id, digest, "
                        + "request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, artifactId);
                    ps.setString(2, signatureKey);
                    ps.setString(3, keyId);
                    ps.setString(4, digest);
                    ps.setString(5, requestId);
                    ps.setTimestamp(6, Timestamp.from(createdAt));
                });
    }

    /** 读取某制品版本的全部签名，按 keyId 升序。 */
    public List<ArtifactSignature> listSignaturesForArtifact(long artifactId) {
        return jdbcTemplate.query(
                "SELECT artifact_id, key_id, digest, created_at FROM artifact_signature "
                        + "WHERE artifact_id = ? ORDER BY key_id ASC",
                (rs, n) -> new ArtifactSignature(rs.getLong("artifact_id"), rs.getString("key_id"),
                        rs.getString("digest"), rs.getTimestamp("created_at").toInstant()),
                artifactId);
    }

    /**
     * 批量读取多个制品版本的签名，按 artifactId 分组。
     * 同一制品只解析一次时供调用方一次性加载闭包内全部节点的签名。
     */
    public Map<Long, List<ArtifactSignature>> listSignaturesForArtifacts(Collection<Long> artifactIds) {
        if (artifactIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = artifactIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<ArtifactSignature> rows = jdbcTemplate.query(
                "SELECT artifact_id, key_id, digest, created_at FROM artifact_signature "
                        + "WHERE artifact_id IN (" + placeholders + ") ORDER BY key_id ASC",
                ps -> {
                    int i = 1;
                    for (Long id : artifactIds) {
                        ps.setLong(i++, id);
                    }
                },
                (rs, n) -> new ArtifactSignature(rs.getLong("artifact_id"), rs.getString("key_id"),
                        rs.getString("digest"), rs.getTimestamp("created_at").toInstant()));
        return rows.stream().collect(Collectors.groupingBy(ArtifactSignature::artifactId));
    }

    /** 签名行（含制品坐标），供证据查询。 */
    public record SignatureRow(String name, int version, String keyId,
                               String digest, Instant createdAt) {
    }

    /** 按制品坐标查询签名证据，按 keyId 升序；制品不存在返回空列表。 */
    public List<SignatureRow> listSignaturesByCoordinates(String name, int version) {
        return jdbcTemplate.query(
                "SELECT a.name AS name, a.version AS version, s.key_id AS key_id, s.digest AS digest, "
                        + "s.created_at AS created_at FROM artifact_signature s "
                        + "JOIN artifact a ON a.id = s.artifact_id "
                        + "WHERE a.name = ? AND a.version = ? ORDER BY s.key_id ASC",
                (rs, n) -> new SignatureRow(rs.getString("name"), rs.getInt("version"),
                        rs.getString("key_id"), rs.getString("digest"),
                        rs.getTimestamp("created_at").toInstant()),
                name, version);
    }

    /** 查询某钥匙提供的全部签名证据，按制品坐标升序。 */
    public List<SignatureRow> listSignaturesByKey(String keyId) {
        return jdbcTemplate.query(
                "SELECT a.name AS name, a.version AS version, s.key_id AS key_id, s.digest AS digest, "
                        + "s.created_at AS created_at FROM artifact_signature s "
                        + "JOIN artifact a ON a.id = s.artifact_id "
                        + "WHERE s.key_id = ? ORDER BY a.name ASC, a.version ASC",
                (rs, n) -> new SignatureRow(rs.getString("name"), rs.getInt("version"),
                        rs.getString("key_id"), rs.getString("digest"),
                        rs.getTimestamp("created_at").toInstant()),
                keyId);
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
     * 新增锁文件精确版本条目，并冻结该节点的签名验证证据。
     *
     * @param digest        节点内容摘要；无生效策略时为 null
     * @param policyVersion 采用的策略版本号；无生效策略时为 null
     * @param signerKeyIds  实际计入阈值的 keyId 升序逗号分隔集合；无生效策略时为 null
     */
    public void insertLockEntry(long lockFileId, String name, int version,
                                String digest, Long policyVersion, String signerKeyIds) {
        jdbcTemplate.update(
                "INSERT INTO lock_file_entry (lock_file_id, name, version, digest, policy_version, "
                        + "signer_key_ids) VALUES (?, ?, ?, ?, ?, ?)",
                lockFileId, name, version, digest, policyVersion, signerKeyIds);
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

    /** 锁文件条目行（含冻结的签名验证证据）。 */
    public record LockEntryRow(long lockFileId, String name, int version,
                               String digest, Long policyVersion, String signerKeyIds) {
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

    /** 查询某锁文件的全部条目，按名称升序，含冻结的摘要/策略版本/计入钥匙集合。 */
    public List<LockEntryRow> listLockEntries(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT lock_file_id, name, version, digest, policy_version, signer_key_ids "
                        + "FROM lock_file_entry WHERE lock_file_id = ? ORDER BY name ASC",
                (rs, n) -> new LockEntryRow(rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version"),
                        rs.getString("digest"),
                        rs.getObject("policy_version", Long.class),
                        rs.getString("signer_key_ids")),
                lockFileId);
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn,
                               String contentDigest) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }
}
