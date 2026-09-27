package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleaseShard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 发布单分片摘要登记数据访问。分片按序号升序读取，登记后只在摘要未锁定时可整体替换。
 */
@Repository
public class ReleaseShardRepository {

    private static final RowMapper<ReleaseShard> MAPPER = (rs, rowNum) -> new ReleaseShard(
            rs.getLong("release_id"), rs.getInt("shard_index"), rs.getString("shard_digest"));

    private final JdbcTemplate jdbc;

    public ReleaseShardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int shardIndex, String shardDigest) {
        jdbc.update("INSERT INTO release_shard (release_id, shard_index, shard_digest) VALUES (?, ?, ?)",
                releaseId, shardIndex, shardDigest);
    }

    public List<ReleaseShard> findByReleaseOrdered(long releaseId) {
        return jdbc.query(
                "SELECT release_id, shard_index, shard_digest FROM release_shard"
                        + " WHERE release_id = ? ORDER BY shard_index",
                MAPPER, releaseId);
    }

    public void deleteByRelease(long releaseId) {
        jdbc.update("DELETE FROM release_shard WHERE release_id = ?", releaseId);
    }
}
