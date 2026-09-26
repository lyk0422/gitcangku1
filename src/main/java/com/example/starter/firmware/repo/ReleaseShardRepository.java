package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleaseShard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 发布版本分片摘要清单数据访问。按发布单整体登记；清单只增不改（不允许删除或更新），
 * 一经任务拉取即不可修改由服务层在发布单行锁内判定。
 */
@Repository
public class ReleaseShardRepository {

    private static final RowMapper<ReleaseShard> MAPPER = (rs, rowNum) -> new ReleaseShard(
            rs.getLong("id"), rs.getLong("release_id"), rs.getInt("shard_no"),
            rs.getString("shard_digest"));

    private static final String COLUMNS = "id, release_id, shard_no, shard_digest";

    private final JdbcTemplate jdbc;

    public ReleaseShardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int shardNo, String shardDigest) {
        jdbc.update("INSERT INTO release_shard (release_id, shard_no, shard_digest) VALUES (?, ?, ?)",
                releaseId, shardNo, shardDigest);
    }

    /**
     * 整体替换重登时清除旧清单；仅允许在尚无任务拉取时调用（服务层在发布单行锁内判定）。
     */
    public void deleteByRelease(long releaseId) {
        jdbc.update("DELETE FROM release_shard WHERE release_id = ?", releaseId);
    }

    /**
     * 按分片序号升序返回清单，保证规范排序。
     */
    public List<ReleaseShard> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_shard WHERE release_id = ? ORDER BY shard_no",
                MAPPER, releaseId);
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM release_shard WHERE release_id = ?",
                Long.class, releaseId);
        return count == null ? 0 : count;
    }
}
