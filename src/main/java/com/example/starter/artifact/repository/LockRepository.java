package com.example.starter.artifact.repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import com.example.starter.artifact.dto.LockEntryDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 锁文件及其条目的数据访问。锁文件创建后不被改写，条目为精确版本快照。
 */
@Repository
public class LockRepository {

    private static final RowMapper<LockFileRow> LOCK_MAPPER = (rs, rowNum) -> new LockFileRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("root_name"),
            rs.getInt("root_version"), rs.getLong("repo_version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<LockEntryDto> ENTRY_MAPPER = (rs, rowNum) -> new LockEntryDto(
            rs.getString("name"), rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public LockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入锁文件头，返回生成的锁文件 id。
     */
    public long insertLockFile(String requestId, String rootName, int rootVersion, long repoVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lock_file (request_id, root_name, root_version, repo_version)"
                            + " VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, rootName);
            ps.setInt(3, rootVersion);
            ps.setLong(4, repoVersion);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未能获取锁文件自增 id");
        }
        return key.longValue();
    }

    /**
     * 批量插入锁文件条目。
     */
    public void insertEntries(long lockFileId, List<LockEntryDto> entries) {
        jdbc.batchUpdate(
                "INSERT INTO lock_entry (lock_file_id, name, version) VALUES (?, ?, ?)",
                entries, entries.size(),
                (ps, entry) -> {
                    ps.setLong(1, lockFileId);
                    ps.setString(2, entry.name());
                    ps.setInt(3, entry.version());
                });
    }

    /**
     * 查询全部锁文件，按 id 升序。
     */
    public List<LockFileRow> findAllLocks() {
        return jdbc.query(
                "SELECT id, request_id, root_name, root_version, repo_version, created_at"
                        + " FROM lock_file ORDER BY id",
                LOCK_MAPPER);
    }

    /**
     * 按 id 查询锁文件。
     */
    public Optional<LockFileRow> findLock(long lockFileId) {
        List<LockFileRow> rows = jdbc.query(
                "SELECT id, request_id, root_name, root_version, repo_version, created_at"
                        + " FROM lock_file WHERE id = ?",
                LOCK_MAPPER, lockFileId);
        return rows.stream().findFirst();
    }

    /**
     * 查询锁文件条目，按名称升序。
     */
    public List<LockEntryDto> findEntries(long lockFileId) {
        return jdbc.query(
                "SELECT name, version FROM lock_entry WHERE lock_file_id = ? ORDER BY name",
                ENTRY_MAPPER, lockFileId);
    }
}
