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

        Map<Long, String> checksumByArtifact = jdbcTemplate.query(
                "SELECT artifact_id, checksum_value FROM artifact_checksum",
                rs -> {
                    Map<Long, String> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getLong("artifact_id"), rs.getString("checksum_value"));
                    }
                    return map;
                });
        Map<Long, List<AttestationRow>> attestationsByArtifact = jdbcTemplate.query(
                "SELECT id, artifact_id, role, checksum_value, state, attested_at, revoked_at, revoked_by "
                        + "FROM artifact_attestation ORDER BY artifact_id ASC, role ASC",
                rs -> {
                    Map<Long, List<AttestationRow>> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        AttestationRow row = mapAttestation(rs);
                        map.computeIfAbsent(row.artifactId(), k -> new ArrayList<>()).add(row);
                    }
                    return map;
                });

        Map<String, List<ArtifactVersion>> artifacts = new LinkedHashMap<>();
        for (ArtifactRow row : rows) {
            List<DependencyRange> deps = depsByArtifact.getOrDefault(row.id(), List.of());
            ArtifactVersion version = new ArtifactVersion(row.id(), row.name(), row.version(),
                    row.withdrawn(), List.copyOf(deps),
                    checksumByArtifact.get(row.id()),
                    toDomainAttestations(attestationsByArtifact.get(row.id())));
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

    /** 读取单个制品版本（含依赖、摘要与见证），不存在返回 null。 */
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
        ChecksumRow checksumRow = findChecksum(row.id());
        List<Attestation> attestations = toDomainAttestations(listAttestations(row.id()));
        return new ArtifactVersion(row.id(), row.name(), row.version(), row.withdrawn(),
                List.copyOf(deps), checksumRow == null ? null : checksumRow.checksumValue(),
                attestations);
    }

    private List<Attestation> toDomainAttestations(List<AttestationRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(r -> new Attestation(r.id(), r.artifactId(), r.role(), r.checksumValue(),
                        r.active(), r.attestedAt(), r.revokedAt(), r.revokedBy()))
                .toList();
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

    /** 新增锁文件精确版本条目，同时固化该版本登记的摘要，返回自增主键。 */
    public long insertLockEntry(long lockFileId, String name, int version, String checksum) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lock_file_entry (lock_file_id, name, version, checksum_value) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lockFileId);
            ps.setString(2, name);
            ps.setInt(3, version);
            ps.setString(4, checksum);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入锁文件条目未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增锁条目固化的单条见证快照，返回自增主键。 */
    public long insertLockEntryAttestation(long lockFileEntryId, String role,
                                           String checksum, Instant attestedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO lock_entry_attestation "
                            + "(lock_file_entry_id, role, checksum_value, attested_at) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lockFileEntryId);
            ps.setString(2, role);
            ps.setString(3, checksum);
            ps.setTimestamp(4, Timestamp.from(attestedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入固化见证未获取自增主键");
        }
        return key.longValue();
    }

    /** 查询某锁文件条目固化的全部见证，按角色升序。 */
    public List<FrozenAttestationRow> listLockEntryAttestations(long lockFileEntryId) {
        return jdbcTemplate.query(
                "SELECT lock_file_entry_id, role, checksum_value, attested_at "
                        + "FROM lock_entry_attestation WHERE lock_file_entry_id = ? ORDER BY role ASC",
                (rs, n) -> new FrozenAttestationRow(rs.getLong("lock_file_entry_id"),
                        rs.getString("role"), rs.getString("checksum_value"),
                        rs.getTimestamp("attested_at").toInstant()),
                lockFileEntryId);
    }

    /** 新增制品版本的内容校验摘要（artifact_id 唯一，重复由唯一索引报错）。 */
    public void insertChecksum(long artifactId, String checksum, String registeredBy, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO artifact_checksum (artifact_id, checksum_value, registered_by, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                artifactId, checksum, registeredBy, Timestamp.from(createdAt));
    }

    /** 读取制品版本的登记摘要，不存在返回 null。 */
    public ChecksumRow findChecksum(long artifactId) {
        List<ChecksumRow> rows = jdbcTemplate.query(
                "SELECT artifact_id, checksum_value, registered_by, created_at "
                        + "FROM artifact_checksum WHERE artifact_id = ?",
                (rs, n) -> new ChecksumRow(rs.getLong("artifact_id"),
                        rs.getString("checksum_value"), rs.getString("registered_by"),
                        rs.getTimestamp("created_at").toInstant()),
                artifactId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新增一条见证（artifact_id+role 唯一，同角色重复由唯一索引报错），返回自增主键。 */
    public long insertAttestation(long artifactId, String role, String checksum,
                                  Instant attestedAt, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact_attestation "
                            + "(artifact_id, role, checksum_value, state, attested_at, request_id) "
                            + "VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, artifactId);
            ps.setString(2, role);
            ps.setString(3, checksum);
            ps.setTimestamp(4, Timestamp.from(attestedAt));
            ps.setString(5, requestId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入见证未获取自增主键");
        }
        return key.longValue();
    }

    /** 读取某制品版本某角色的见证（含已撤销），不存在返回 null。 */
    public AttestationRow findAttestation(long artifactId, String role) {
        List<AttestationRow> rows = jdbcTemplate.query(
                "SELECT id, artifact_id, role, checksum_value, state, attested_at, revoked_at, revoked_by "
                        + "FROM artifact_attestation WHERE artifact_id = ? AND role = ?",
                (rs, n) -> mapAttestation(rs),
                artifactId, role);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 读取某制品版本的全部见证（含已撤销），按角色升序。 */
    public List<AttestationRow> listAttestations(long artifactId) {
        return jdbcTemplate.query(
                "SELECT id, artifact_id, role, checksum_value, state, attested_at, revoked_at, revoked_by "
                        + "FROM artifact_attestation WHERE artifact_id = ? ORDER BY role ASC",
                (rs, n) -> mapAttestation(rs),
                artifactId);
    }

    /**
     * 撤销指定见证，仅当当前为 ACTIVE 时生效，返回受影响行数。
     * 条件更新保证重复撤销/并发撤销的原子判定。
     */
    public int revokeAttestation(long attestationId, String revokedBy, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE artifact_attestation SET state = 'REVOKED', revoked_at = ?, revoked_by = ? "
                        + "WHERE id = ? AND state = 'ACTIVE'",
                Timestamp.from(revokedAt), revokedBy, attestationId);
    }

    private AttestationRow mapAttestation(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new AttestationRow(rs.getLong("id"), rs.getLong("artifact_id"),
                rs.getString("role"), rs.getString("checksum_value"),
                "ACTIVE".equals(rs.getString("state")),
                rs.getTimestamp("attested_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("revoked_by"));
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

    /** 锁文件条目行：含锁定时固化的摘要。 */
    public record LockEntryRow(long id, long lockFileId, String name, int version,
                               String checksum) {
    }

    /** 锁条目固化的见证快照行。 */
    public record FrozenAttestationRow(long lockFileEntryId, String role,
                                       String checksum, Instant attestedAt) {
    }

    /** 制品版本登记摘要行。 */
    public record ChecksumRow(long artifactId, String checksumValue,
                              String registeredBy, Instant createdAt) {
    }

    /** 见证记录行（含已撤销）。 */
    public record AttestationRow(long id, long artifactId, String role, String checksumValue,
                                 boolean active, Instant attestedAt,
                                 Instant revokedAt, String revokedBy) {
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

    /** 查询某锁文件的全部条目（含固化摘要），按名称升序。 */
    public List<LockEntryRow> listLockEntries(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT id, lock_file_id, name, version, checksum_value FROM lock_file_entry "
                        + "WHERE lock_file_id = ? ORDER BY name ASC",
                (rs, n) -> new LockEntryRow(rs.getLong("id"), rs.getLong("lock_file_id"),
                        rs.getString("name"), rs.getInt("version"),
                        rs.getString("checksum_value")),
                lockFileId);
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }
}
