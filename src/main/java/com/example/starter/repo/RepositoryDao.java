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
    // 锁文件重解析报告（只增不改不删，天然不可变）
    // ------------------------------------------------------------------

    /** 重解析报告主记录行。 */
    public record ReresolveReportRow(long id, String reresolveKey, long lockFileId,
                                     String rootName, int rootVersion, long repositoryVersion,
                                     String conclusion, String requestId, Instant createdAt) {
    }

    /** 重解析报告新解析集合条目行。 */
    public record ReresolveEntryRow(long reportId, String name, int version) {
    }

    /** 重解析报告差异/不可行原因明细行，original/new_version 可空。 */
    public record ReresolveDiffRow(long reportId, String name, String changeType,
                                   Integer originalVersion, Integer newVersion,
                                   String reason, String detail) {
    }

    /** 新增重解析报告主记录，返回自增主键；reresolve_key 冲突抛 DuplicateKeyException。 */
    public long insertReresolveReport(String reresolveKey, long lockFileId, String rootName,
                                      int rootVersion, long repositoryVersion, String conclusion,
                                      String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reresolve_report (reresolve_key, lock_file_id, root_name, root_version, "
                            + "repository_version, conclusion, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reresolveKey);
            ps.setLong(2, lockFileId);
            ps.setString(3, rootName);
            ps.setInt(4, rootVersion);
            ps.setLong(5, repositoryVersion);
            ps.setString(6, conclusion);
            ps.setString(7, requestId);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入重解析报告未获取自增主键");
        }
        return key.longValue();
    }

    /** 新增报告新解析集合中的一条精确版本。 */
    public void insertReresolveEntry(long reportId, String name, int version) {
        jdbcTemplate.update(
                "INSERT INTO reresolve_report_entry (report_id, name, version) VALUES (?, ?, ?)",
                reportId, name, version);
    }

    /** 新增一条报告差异/不可行原因明细，版本列允许为空。 */
    public void insertReresolveDiff(long reportId, String name, String changeType,
                                    Integer originalVersion, Integer newVersion,
                                    String reason, String detail) {
        jdbcTemplate.update(
                "INSERT INTO reresolve_report_diff (report_id, name, change_type, original_version, "
                        + "new_version, reason, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, reportId);
                    ps.setString(2, name);
                    ps.setString(3, changeType);
                    if (originalVersion == null) {
                        ps.setNull(4, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(4, originalVersion);
                    }
                    if (newVersion == null) {
                        ps.setNull(5, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(5, newVersion);
                    }
                    ps.setString(6, reason);
                    ps.setString(7, detail);
                });
    }

    /** 按主键查询报告主记录，不存在返回 null。 */
    public ReresolveReportRow getReresolveReport(long id) {
        List<ReresolveReportRow> rows = jdbcTemplate.query(
                "SELECT id, reresolve_key, lock_file_id, root_name, root_version, repository_version, "
                        + "conclusion, request_id, created_at FROM reresolve_report WHERE id = ?",
                (rs, n) -> mapReportRow(rs),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按全局唯一 reresolveKey 查询报告主记录，不存在返回 null。 */
    public ReresolveReportRow findReresolveReportByKey(String reresolveKey) {
        List<ReresolveReportRow> rows = jdbcTemplate.query(
                "SELECT id, reresolve_key, lock_file_id, root_name, root_version, repository_version, "
                        + "conclusion, request_id, created_at FROM reresolve_report WHERE reresolve_key = ?",
                (rs, n) -> mapReportRow(rs),
                reresolveKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某锁文件的全部重解析报告，按报告 ID 升序（提交顺序）。 */
    public List<ReresolveReportRow> listReresolveReportsByLockFile(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT id, reresolve_key, lock_file_id, root_name, root_version, repository_version, "
                        + "conclusion, request_id, created_at FROM reresolve_report "
                        + "WHERE lock_file_id = ? ORDER BY id ASC",
                (rs, n) -> mapReportRow(rs),
                lockFileId);
    }

    /** 查询报告的新解析集合条目，按名称升序。 */
    public List<ReresolveEntryRow> listReresolveEntries(long reportId) {
        return jdbcTemplate.query(
                "SELECT report_id, name, version FROM reresolve_report_entry "
                        + "WHERE report_id = ? ORDER BY name ASC",
                (rs, n) -> new ReresolveEntryRow(rs.getLong("report_id"),
                        rs.getString("name"), rs.getInt("version")),
                reportId);
    }

    /** 查询报告的差异/不可行明细，按名称升序（同名称按变化类型稳定排序）。 */
    public List<ReresolveDiffRow> listReresolveDiffs(long reportId) {
        return jdbcTemplate.query(
                "SELECT report_id, name, change_type, original_version, new_version, reason, detail "
                        + "FROM reresolve_report_diff WHERE report_id = ? "
                        + "ORDER BY name ASC, change_type ASC",
                (rs, n) -> new ReresolveDiffRow(rs.getLong("report_id"),
                        rs.getString("name"), rs.getString("change_type"),
                        rs.getObject("original_version", Integer.class),
                        rs.getObject("new_version", Integer.class),
                        rs.getString("reason"), rs.getString("detail")),
                reportId);
    }

    private static ReresolveReportRow mapReportRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReresolveReportRow(rs.getLong("id"), rs.getString("reresolve_key"),
                rs.getLong("lock_file_id"), rs.getString("root_name"), rs.getInt("root_version"),
                rs.getLong("repository_version"), rs.getString("conclusion"),
                rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());
    }
}
