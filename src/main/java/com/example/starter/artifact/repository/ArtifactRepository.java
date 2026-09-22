package com.example.starter.artifact.repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import com.example.starter.artifact.dto.DependencyDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 制品版本及其依赖声明的数据访问。
 */
@Repository
public class ArtifactRepository {

    private static final RowMapper<ArtifactRow> ARTIFACT_MAPPER = (rs, rowNum) -> new ArtifactRow(
            rs.getLong("id"), rs.getString("name"), rs.getInt("version"), rs.getBoolean("retracted"));

    private static final RowMapper<DependencyRow> DEPENDENCY_MAPPER = (rs, rowNum) -> new DependencyRow(
            rs.getLong("artifact_id"), rs.getString("dep_name"),
            rs.getInt("min_version"), rs.getInt("max_version"));

    private final JdbcTemplate jdbc;

    public ArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按名称与精确版本查找制品。
     */
    public Optional<ArtifactRow> findByNameAndVersion(String name, int version) {
        List<ArtifactRow> rows = jdbc.query(
                "SELECT id, name, version, retracted FROM artifact WHERE name = ? AND version = ?",
                ARTIFACT_MAPPER, name, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部制品版本（含已撤回），按名称、版本升序。
     */
    public List<ArtifactRow> findAll() {
        return jdbc.query(
                "SELECT id, name, version, retracted FROM artifact ORDER BY name, version",
                ARTIFACT_MAPPER);
    }

    /**
     * 统计仓库中不同的制品名称数量。
     */
    public int countDistinctNames() {
        Integer count = jdbc.queryForObject("SELECT COUNT(DISTINCT name) FROM artifact", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 判断仓库中是否已存在指定名称的任意版本。
     */
    public boolean existsName(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifact WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    /**
     * 统计指定名称的版本数量（含已撤回）。
     */
    public int countVersions(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifact WHERE name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    /**
     * 插入制品版本，返回生成的 id。
     */
    public long insert(String name, int version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO artifact (name, version, retracted) VALUES (?, ?, FALSE)",
                    new String[]{"id"});
            ps.setString(1, name);
            ps.setInt(2, version);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未能获取制品记录自增 id");
        }
        return key.longValue();
    }

    /**
     * 批量插入依赖声明。
     */
    public void insertDependencies(long artifactId, List<DependencyDto> dependencies) {
        jdbc.batchUpdate(
                "INSERT INTO artifact_dependency (artifact_id, dep_name, min_version, max_version)"
                        + " VALUES (?, ?, ?, ?)",
                dependencies, dependencies.size(),
                (ps, dep) -> {
                    ps.setLong(1, artifactId);
                    ps.setString(2, dep.name());
                    ps.setInt(3, dep.minVersion());
                    ps.setInt(4, dep.maxVersion());
                });
    }

    /**
     * 查询全部依赖声明。
     */
    public List<DependencyRow> findAllDependencies() {
        return jdbc.query(
                "SELECT artifact_id, dep_name, min_version, max_version FROM artifact_dependency",
                DEPENDENCY_MAPPER);
    }

    /**
     * 查询指定制品版本的依赖声明。
     */
    public List<DependencyRow> findDependencies(long artifactId) {
        return jdbc.query(
                "SELECT artifact_id, dep_name, min_version, max_version FROM artifact_dependency"
                        + " WHERE artifact_id = ?",
                DEPENDENCY_MAPPER, artifactId);
    }

    /**
     * 标记制品版本为已撤回。
     */
    public void markRetracted(long artifactId) {
        jdbc.update("UPDATE artifact SET retracted = TRUE WHERE id = ?", artifactId);
    }
}
