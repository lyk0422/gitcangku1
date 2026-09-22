package com.example.starter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 锁文件及明细数据访问。
 */
@Repository
public class LockDao {

    private final JdbcTemplate jdbcTemplate;

    public LockDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record LockRow(long id, String rootName, int rootVersion,
                          long repositoryVersion, Instant createdAt) {
    }

    public record ItemRow(String artifactName, int artifactVersion) {
    }

    public long insertLock(String rootName, int rootVersion, long repositoryVersion, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lock_file (root_name, root_version, repository_version, request_id) "
                            + "VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, rootName);
            ps.setInt(2, rootVersion);
            ps.setLong(3, repositoryVersion);
            ps.setString(4, requestId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("锁文件写入后无法获取ID");
        }
        return key.longValue();
    }

    public void insertItem(long lockFileId, String artifactName, int artifactVersion) {
        jdbcTemplate.update(
                "INSERT INTO lock_file_item (lock_file_id, artifact_name, artifact_version) "
                        + "VALUES (?, ?, ?)",
                lockFileId, artifactName, artifactVersion);
    }

    public Optional<LockRow> findById(long id) {
        List<LockRow> rows = jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, created_at "
                        + "FROM lock_file WHERE id = ?",
                (rs, n) -> mapLockRow(rs), id);
        return rows.stream().findFirst();
    }

    public List<ItemRow> findItems(long lockFileId) {
        return jdbcTemplate.query(
                "SELECT artifact_name, artifact_version FROM lock_file_item "
                        + "WHERE lock_file_id = ? ORDER BY artifact_name ASC",
                (rs, n) -> new ItemRow(rs.getString("artifact_name"), rs.getInt("artifact_version")),
                lockFileId);
    }

    public List<LockRow> findAll(String rootName) {
        if (rootName == null || rootName.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT id, root_name, root_version, repository_version, created_at "
                            + "FROM lock_file ORDER BY id ASC",
                    (rs, n) -> mapLockRow(rs));
        }
        return jdbcTemplate.query(
                "SELECT id, root_name, root_version, repository_version, created_at "
                        + "FROM lock_file WHERE root_name = ? ORDER BY id ASC",
                (rs, n) -> mapLockRow(rs), rootName);
    }

    private static LockRow mapLockRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new LockRow(rs.getLong("id"), rs.getString("root_name"),
                rs.getInt("root_version"), rs.getLong("repository_version"),
                createdAt == null ? null : createdAt.toInstant());
    }
}
