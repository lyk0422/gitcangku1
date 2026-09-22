package com.example.starter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 制品及依赖声明数据访问。
 */
@Repository
public class ArtifactDao {

    private final JdbcTemplate jdbcTemplate;

    public ArtifactDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsArtifact(String name, int version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = ? AND version = ?",
                Integer.class, name, version);
        return count != null && count > 0;
    }

    public long insertArtifact(String name, int version) {
        jdbcTemplate.update(
                "INSERT INTO artifact (name, version, withdrawn) VALUES (?, ?, FALSE)",
                name, version);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = ? AND version = ?",
                Long.class, name, version);
        if (id == null) {
            throw new IllegalStateException("登记后无法读取制品ID");
        }
        return id;
    }

    public void insertDependency(long artifactId, String depName, int minVersion, int maxVersion) {
        jdbcTemplate.update(
                "INSERT INTO artifact_dependency (artifact_id, dep_name, min_version, max_version) "
                        + "VALUES (?, ?, ?, ?)",
                artifactId, depName, minVersion, maxVersion);
    }

    /**
     * 统计仓库内不同制品名称总数（含已撤回版本占用的名称槽位）。
     */
    public int countDistinctNames() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT name) FROM artifact", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 统计某名称下版本数（含撤回）。
     */
    public int countVersions(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    /**
     * 将指定制品版本置为撤回状态；仅当存在且未撤回时返回 true。
     */
    public boolean markWithdrawn(String name, int version) {
        int updated = jdbcTemplate.update(
                "UPDATE artifact SET withdrawn = TRUE WHERE name = ? AND version = ? AND withdrawn = FALSE",
                name, version);
        return updated > 0;
    }

    /**
     * 查询某名称下所有未撤回版本号，从高到低排序。
     */
    public List<Integer> findAvailableVersions(String name) {
        return jdbcTemplate.queryForList(
                "SELECT version FROM artifact WHERE name = ? AND withdrawn = FALSE ORDER BY version DESC",
                Integer.class, name);
    }

    public record ArtifactRow(long id, String name, int version, boolean withdrawn) {
    }

    public record DependencyRow(long artifactId, String depName, int minVersion, int maxVersion) {
    }

    /**
     * 查询单个制品版本；不存在返回空。
     */
    public Optional<ArtifactRow> findOne(String name, int version) {
        List<ArtifactRow> rows = jdbcTemplate.query(
                "SELECT id, name, version, withdrawn FROM artifact WHERE name = ? AND version = ?",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("version"), rs.getBoolean("withdrawn")),
                name, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询某制品的全部依赖声明，按名称排序。
     */
    public List<DependencyRow> findDependencies(long artifactId) {
        return jdbcTemplate.query(
                "SELECT artifact_id, dep_name, min_version, max_version FROM artifact_dependency "
                        + "WHERE artifact_id = ? ORDER BY dep_name",
                (rs, n) -> new DependencyRow(rs.getLong("artifact_id"), rs.getString("dep_name"),
                        rs.getInt("min_version"), rs.getInt("max_version")),
                artifactId);
    }

    /**
     * 快照行：制品版本与其一条依赖（无依赖制品不出现）。
     */
    public record SnapshotRow(String name, int version, String depName,
                              Integer minVersion, Integer maxVersion) {
    }

    /**
     * 在已持有仓库版本行锁的前提下，读取全部未撤回制品及其依赖声明。
     * 返回行以制品名称、版本（降序）、依赖名称排序，供锁定回溯构造内存快照。
     */
    public List<SnapshotRow> loadActiveSnapshot() {
        return jdbcTemplate.query(
                "SELECT a.name AS name, a.version AS version, d.dep_name AS dep_name, "
                        + "d.min_version AS min_version, d.max_version AS max_version "
                        + "FROM artifact a LEFT JOIN artifact_dependency d ON d.artifact_id = a.id "
                        + "WHERE a.withdrawn = FALSE "
                        + "ORDER BY a.name ASC, a.version DESC, d.dep_name ASC",
                (rs, n) -> new SnapshotRow(rs.getString("name"), rs.getInt("version"),
                        rs.getString("dep_name"),
                        (Integer) rs.getObject("min_version"),
                        (Integer) rs.getObject("max_version")));
    }
}
