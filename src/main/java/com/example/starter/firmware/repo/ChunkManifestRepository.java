package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ChunkEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 分片摘要清单数据访问。清单登记后不可修改，uk_chunk_release_index 保证同发布单序号唯一。
 */
@Repository
public class ChunkManifestRepository {

    private static final RowMapper<ChunkEntry> MAPPER = (rs, rowNum) ->
            new ChunkEntry(rs.getInt("chunk_index"), rs.getString("digest"));

    private final JdbcTemplate jdbc;

    public ChunkManifestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, int chunkIndex, String digest) {
        jdbc.update("INSERT INTO firmware_chunk (release_id, chunk_index, digest) VALUES (?, ?, ?)",
                releaseId, chunkIndex, digest);
    }

    /**
     * 按序号升序返回发布单的规范排序分片清单。
     */
    public List<ChunkEntry> findByRelease(long releaseId) {
        return jdbc.query("SELECT chunk_index, digest FROM firmware_chunk WHERE release_id = ?"
                + " ORDER BY chunk_index", MAPPER, releaseId);
    }

    public Optional<String> findDigest(long releaseId, int chunkIndex) {
        return jdbc.query("SELECT digest FROM firmware_chunk WHERE release_id = ? AND chunk_index = ?",
                (rs, rowNum) -> rs.getString("digest"), releaseId, chunkIndex).stream().findFirst();
    }
}
