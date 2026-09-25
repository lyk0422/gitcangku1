package com.example.starter.repo;

import com.example.starter.domain.PolicyCoordinate;
import com.example.starter.domain.ProvenanceAttestation;
import com.example.starter.domain.ProvenancePolicy;
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
 * 来源策略、证明与发布快照数据访问。
 *
 * <p>所有多语句业务操作均在 Service 层事务内执行；写事务通过
 * {@link RepositoryDao#lockRepositoryState()} 串行化，保证策略、证明、
 * 撤销与发布按提交顺序裁决。
 */
@Repository
public class ProvenanceDao {

    private final JdbcTemplate jdbcTemplate;

    public ProvenanceDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------
    // 策略
    // ------------------------------------------------------------------

    /** 查询锁定图当前最新策略版本号；从未定义返回 0。 */
    public int currentPolicyVersion(String lockName) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM provenance_policy WHERE lock_name = ?",
                Integer.class, lockName);
        return version == null ? 0 : version;
    }

    /** 新增策略版本主记录，返回自增主键。 */
    public long insertPolicy(String lockName, int version, String operator, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO provenance_policy (lock_name, version, operator, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lockName);
            ps.setInt(2, version);
            ps.setString(3, operator);
            ps.setTimestamp(4, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入策略版本未获取自增主键");
        }
        return key.longValue();
    }

    /** 写入一条策略坐标要求。 */
    public void insertPolicyCoordinate(long policyId, PolicyCoordinate coordinate) {
        jdbcTemplate.update(
                "INSERT INTO provenance_policy_coordinate "
                        + "(policy_id, name, required_level, required_digest) VALUES (?, ?, ?, ?)",
                policyId, coordinate.name(), coordinate.requiredLevel(),
                coordinate.requiredDigest() == null ? "" : coordinate.requiredDigest());
    }

    /** 读取指定策略版本（含坐标，按名称升序）；不存在返回 null。 */
    public ProvenancePolicy loadPolicy(String lockName, int version) {
        List<PolicyHeaderRow> headers = jdbcTemplate.query(
                "SELECT id, lock_name, version, created_at FROM provenance_policy "
                        + "WHERE lock_name = ? AND version = ?",
                (rs, n) -> new PolicyHeaderRow(rs.getLong("id"),
                        rs.getString("lock_name"), rs.getInt("version"),
                        rs.getTimestamp("created_at").toInstant()),
                lockName, version);
        if (headers.isEmpty()) {
            return null;
        }
        PolicyHeaderRow header = headers.get(0);
        List<PolicyCoordinate> coordinates = jdbcTemplate.query(
                "SELECT name, required_level, required_digest FROM provenance_policy_coordinate "
                        + "WHERE policy_id = ? ORDER BY name ASC",
                (rs, n) -> new PolicyCoordinate(rs.getString("name"),
                        rs.getInt("required_level"), rs.getString("required_digest")),
                header.id());
        return new ProvenancePolicy(header.lockName(), header.version(), header.createdAt(),
                List.copyOf(coordinates));
    }

    private record PolicyHeaderRow(long id, String lockName, int version, Instant createdAt) {
    }

    // ------------------------------------------------------------------
    // 证明
    // ------------------------------------------------------------------

    /** 新增来源证明，返回自增主键（即证明版本顺序）。 */
    public long insertAttestation(String name, int version, String sourceRepository,
                                  String buildDigest, int attestationLevel,
                                  String operator, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO provenance_attestation "
                            + "(name, version, source_repository, build_digest, attestation_level, "
                            + "operator, revoked, created_at) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, version);
            ps.setString(3, sourceRepository);
            ps.setString(4, buildDigest);
            ps.setInt(5, attestationLevel);
            ps.setString(6, operator);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入来源证明未获取自增主键");
        }
        return key.longValue();
    }

    /** 按主键读取证明（含已撤销）；不存在返回 null。 */
    public ProvenanceAttestation loadAttestation(long id) {
        List<ProvenanceAttestation> records = jdbcTemplate.query(
                "SELECT id, name, version, source_repository, build_digest, attestation_level, "
                        + "operator, revoked, created_at FROM provenance_attestation WHERE id = ?",
                (rs, n) -> mapAttestation(rs),
                id);
        return records.isEmpty() ? null : records.get(0);
    }

    /**
     * 读取坐标最新一条证明（不区分是否撤销）；无任何证明返回 null。
     *
     * <p>证明按提交顺序（自增 ID）裁决：最新一条是该坐标的当前生效证明；
     * 最新证明已撤销时坐标即视为无有效证明（未发布锁定图不可用），
     * 须重新提交证明恢复，已发布快照不受影响。
     */
    public ProvenanceAttestation loadLatestAttestation(String name, int version) {
        List<ProvenanceAttestation> records = jdbcTemplate.query(
                "SELECT id, name, version, source_repository, build_digest, attestation_level, "
                        + "operator, revoked, created_at FROM provenance_attestation "
                        + "WHERE name = ? AND version = ? ORDER BY id DESC FETCH FIRST 1 ROWS ONLY",
                (rs, n) -> mapAttestation(rs),
                name, version);
        return records.isEmpty() ? null : records.get(0);
    }

    /** 撤销证明，仅当当前未撤销时生效，返回受影响行数。 */
    public int markRevoked(long attestationId) {
        return jdbcTemplate.update(
                "UPDATE provenance_attestation SET revoked = 1 WHERE id = ? AND revoked = 0",
                attestationId);
    }

    private ProvenanceAttestation mapAttestation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProvenanceAttestation(rs.getLong("id"), rs.getString("name"),
                rs.getInt("version"), rs.getString("source_repository"),
                rs.getString("build_digest"), rs.getInt("attestation_level"),
                rs.getString("operator"), rs.getInt("revoked") == 1,
                rs.getTimestamp("created_at").toInstant());
    }

    // ------------------------------------------------------------------
    // 锁定图策略绑定
    // ------------------------------------------------------------------

    /** 绑定（或收紧）锁定图的策略版本。 */
    public void bindPolicyVersion(long lockFileId, int policyVersion) {
        jdbcTemplate.update(
                "UPDATE lock_file SET policy_version = ? WHERE id = ?", policyVersion, lockFileId);
    }

    // ------------------------------------------------------------------
    // 发布快照
    // ------------------------------------------------------------------

    /** 发布快照行。 */
    public record ReleaseRow(long id, long lockFileId, String rootName, int rootVersion,
                             int policyVersion, String provenanceKey, String operator,
                             Instant createdAt) {
    }

    /** 发布快照条目行。 */
    public record ReleaseEntryRow(long snapshotId, String name, int version, long attestationId,
                                  String sourceRepository, String buildDigest, int attestationLevel) {
    }

    /** 新增发布快照主记录，返回自增主键。 */
    public long insertReleaseSnapshot(long lockFileId, String rootName, int rootVersion,
                                      int policyVersion, String provenanceKey,
                                      String operator, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_snapshot "
                            + "(lock_file_id, root_name, root_version, policy_version, "
                            + "provenance_key, operator, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lockFileId);
            ps.setString(2, rootName);
            ps.setInt(3, rootVersion);
            ps.setInt(4, policyVersion);
            ps.setString(5, provenanceKey);
            ps.setString(6, operator);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入发布快照未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增一条固化快照条目。 */
    public void insertReleaseEntry(long snapshotId, ReleaseEntryRow entry) {
        jdbcTemplate.update(
                "INSERT INTO release_snapshot_entry "
                        + "(snapshot_id, name, version, attestation_id, source_repository, "
                        + "build_digest, attestation_level) VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshotId, entry.name(), entry.version(), entry.attestationId(),
                entry.sourceRepository(), entry.buildDigest(), entry.attestationLevel());
    }

    /** 按锁定图查询发布快照；未发布返回 null。 */
    public ReleaseRow getReleaseByLockFile(long lockFileId) {
        List<ReleaseRow> rows = jdbcTemplate.query(
                "SELECT id, lock_file_id, root_name, root_version, policy_version, provenance_key, "
                        + "operator, created_at FROM release_snapshot WHERE lock_file_id = ?",
                (rs, n) -> new ReleaseRow(rs.getLong("id"), rs.getLong("lock_file_id"),
                        rs.getString("root_name"), rs.getInt("root_version"),
                        rs.getInt("policy_version"), rs.getString("provenance_key"),
                        rs.getString("operator"), rs.getTimestamp("created_at").toInstant()),
                lockFileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询发布快照的全部固化条目，按名称升序。 */
    public List<ReleaseEntryRow> listReleaseEntries(long snapshotId) {
        return jdbcTemplate.query(
                "SELECT snapshot_id, name, version, attestation_id, source_repository, "
                        + "build_digest, attestation_level FROM release_snapshot_entry "
                        + "WHERE snapshot_id = ? ORDER BY name ASC",
                (rs, n) -> new ReleaseEntryRow(rs.getLong("snapshot_id"), rs.getString("name"),
                        rs.getInt("version"), rs.getLong("attestation_id"),
                        rs.getString("source_repository"), rs.getString("build_digest"),
                        rs.getInt("attestation_level")),
                snapshotId);
    }
}
