package com.example.starter.repo;

import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.CoordinationGroup;
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
        return new RepositorySnapshot(getRepositoryVersion(), Map.copyOf(artifacts),
                loadCoordinationGroups());
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

    // ------------------------------------------------------------------
    // 协调组
    // ------------------------------------------------------------------

    /** 协调组完整记录：主键、元数据与规范化后的成员、允许元组。 */
    public record CoordinationGroupRecord(long id, String groupId, String operator,
                                          long repositoryVersion, Instant createdAt,
                                          List<String> members,
                                          List<Map<String, Integer>> tuples) {
    }

    /** 协调组数量（按 group_id 计）。 */
    public int countCoordinationGroups() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM coordination_group", Integer.class);
        return count == null ? 0 : count;
    }

    /** 按业务标识查协调组完整记录，不存在返回 null。 */
    public CoordinationGroupRecord findCoordinationGroup(String groupId) {
        List<CoordinationGroupRecord> records = jdbcTemplate.query(
                "SELECT id, group_id, operator, repository_version, created_at "
                        + "FROM coordination_group WHERE group_id = ?",
                (rs, n) -> new CoordinationGroupRecord(rs.getLong("id"), rs.getString("group_id"),
                        rs.getString("operator"), rs.getLong("repository_version"),
                        rs.getTimestamp("created_at").toInstant(), List.of(), List.of()),
                groupId);
        if (records.isEmpty()) {
            return null;
        }
        return withContent(records.get(0));
    }

    /** 全部协调组（含成员与元组），按 group_id 升序。 */
    public List<CoordinationGroupRecord> listCoordinationGroups() {
        List<CoordinationGroupRecord> records = jdbcTemplate.query(
                "SELECT id, group_id, operator, repository_version, created_at "
                        + "FROM coordination_group ORDER BY group_id ASC",
                (rs, n) -> new CoordinationGroupRecord(rs.getLong("id"), rs.getString("group_id"),
                        rs.getString("operator"), rs.getLong("repository_version"),
                        rs.getTimestamp("created_at").toInstant(), List.of(), List.of()));
        return records.stream().map(this::withContent).toList();
    }

    /** 快照视图：全部协调组规则（组 ID 升序）。 */
    public List<CoordinationGroup> loadCoordinationGroups() {
        return listCoordinationGroups().stream()
                .map(r -> new CoordinationGroup(r.groupId(), r.members(), r.tuples()))
                .toList();
    }

    /** 为组记录装配成员与允许元组（均按名称/键升序规范化）。 */
    private CoordinationGroupRecord withContent(CoordinationGroupRecord base) {
        List<String> members = jdbcTemplate.query(
                "SELECT name FROM coordination_group_member WHERE group_ref = ? ORDER BY name ASC",
                (rs, n) -> rs.getString("name"), base.id());
        List<TupleRow> tupleRows = jdbcTemplate.query(
                "SELECT id FROM coordination_tuple WHERE group_ref = ? ORDER BY tuple_key ASC",
                (rs, n) -> new TupleRow(rs.getLong("id")), base.id());
        List<Map<String, Integer>> tuples = new ArrayList<>();
        for (TupleRow tupleRow : tupleRows) {
            Map<String, Integer> entries = jdbcTemplate.query(
                    "SELECT name, version FROM coordination_tuple_entry WHERE tuple_ref = ? "
                            + "ORDER BY name ASC",
                    (rs, n) -> Map.entry(rs.getString("name"), rs.getInt("version")),
                    tupleRow.id()).stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> a, LinkedHashMap::new));
            tuples.add(Map.copyOf(entries));
        }
        return new CoordinationGroupRecord(base.id(), base.groupId(), base.operator(),
                base.repositoryVersion(), base.createdAt(),
                List.copyOf(members), List.copyOf(tuples));
    }

    /** 新增协调组主记录，返回自增主键。 */
    public long insertCoordinationGroup(String groupId, String operator,
                                        long repositoryVersion, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO coordination_group (group_id, operator, repository_version, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, groupId);
            ps.setString(2, operator);
            ps.setLong(3, repositoryVersion);
            ps.setTimestamp(4, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入协调组未获取自增主键");
        }
        return key.longValue();
    }

    /** 修订：更新组元数据并清空旧成员与元组（同事务内随后写入新内容）。 */
    public void replaceCoordinationGroupContent(long groupRef, String operator,
                                                long repositoryVersion, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE coordination_group SET operator = ?, repository_version = ?, created_at = ? "
                        + "WHERE id = ?",
                operator, repositoryVersion, Timestamp.from(createdAt), groupRef);
        jdbcTemplate.update(
                "DELETE FROM coordination_tuple_entry WHERE tuple_ref IN "
                        + "(SELECT id FROM coordination_tuple WHERE group_ref = ?)", groupRef);
        jdbcTemplate.update("DELETE FROM coordination_tuple WHERE group_ref = ?", groupRef);
        jdbcTemplate.update("DELETE FROM coordination_group_member WHERE group_ref = ?", groupRef);
    }

    /** 写入一个组成员。 */
    public void insertCoordinationMember(long groupRef, String name) {
        jdbcTemplate.update(
                "INSERT INTO coordination_group_member (group_ref, name) VALUES (?, ?)",
                groupRef, name);
    }

    /** 写入一条允许元组，返回自增主键。 */
    public long insertCoordinationTuple(long groupRef, String tupleKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO coordination_tuple (group_ref, tuple_key) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, groupRef);
            ps.setString(2, tupleKey);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入允许元组未获取自增主键");
        }
        return key.longValue();
    }

    /** 写入元组的一个成员版本条目。 */
    public void insertCoordinationTupleEntry(long tupleRef, String name, int version) {
        jdbcTemplate.update(
                "INSERT INTO coordination_tuple_entry (tuple_ref, name, version) VALUES (?, ?, ?)",
                tupleRef, name, version);
    }

    /** 记录锁文件的协调组命中依据。 */
    public void insertLockCoordinationHit(long lockFileId, String groupId, String tupleJson) {
        jdbcTemplate.update(
                "INSERT INTO lock_coordination_hit (lock_file_id, group_id, tuple_json) "
                        + "VALUES (?, ?, ?)",
                lockFileId, groupId, tupleJson);
    }

    /** 锁文件命中依据行。 */
    public record LockHitRow(String groupId, String tupleJson) {
    }

    /** 查询某锁文件的全部协调组命中依据，按组 ID 升序。 */
    public List<LockHitRow> listLockCoordinationHits(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT group_id, tuple_json FROM lock_coordination_hit "
                        + "WHERE lock_file_id = ? ORDER BY group_id ASC",
                (rs, n) -> new LockHitRow(rs.getString("group_id"), rs.getString("tuple_json")),
                lockFileId);
    }

    private record TupleRow(long id) {
    }

    private record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    private record DepRow(long artifactId, String name, int minimumVersion, int maximumVersion) {
    }
}
