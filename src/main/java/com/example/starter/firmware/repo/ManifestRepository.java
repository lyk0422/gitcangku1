package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ManifestChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 发布版本分片清单数据访问。一个发布单至多一份清单；一经有任务拉取即不可修改（由服务层在行锁内裁决）。
 */
@Repository
public class ManifestRepository {

    /**
     * 清单概要。
     *
     * @param releaseId       所属发布单ID
     * @param chunkCount      分片总数
     * @param aggregateDigest 完整包聚合摘要
     * @param registeredAtUtc 登记时刻（UTC ISO-8601）
     */
    public record ManifestRow(long releaseId, int chunkCount, String aggregateDigest,
                              String registeredAtUtc) {
    }

    private final JdbcTemplate jdbc;

    public ManifestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertManifest(long releaseId, int chunkCount, String aggregateDigest, String registeredAtUtc) {
        jdbc.update("INSERT INTO release_manifest (release_id, chunk_count, aggregate_digest, registered_at_utc)"
                + " VALUES (?, ?, ?, ?)", releaseId, chunkCount, aggregateDigest, registeredAtUtc);
    }

    public void deleteManifest(long releaseId) {
        jdbc.update("DELETE FROM release_manifest_chunk WHERE release_id = ?", releaseId);
        jdbc.update("DELETE FROM release_manifest WHERE release_id = ?", releaseId);
    }

    public void insertChunk(long releaseId, int chunkIndex, String digest) {
        jdbc.update("INSERT INTO release_manifest_chunk (release_id, chunk_index, digest) VALUES (?, ?, ?)",
                releaseId, chunkIndex, digest);
    }

    public Optional<ManifestRow> findManifest(long releaseId) {
        return jdbc.query("SELECT release_id, chunk_count, aggregate_digest, registered_at_utc"
                        + " FROM release_manifest WHERE release_id = ?",
                (rs, rowNum) -> new ManifestRow(rs.getLong("release_id"), rs.getInt("chunk_count"),
                        rs.getString("aggregate_digest"), rs.getString("registered_at_utc")),
                releaseId).stream().findFirst();
    }

    /**
     * 按序号升序（规范排序）读取全部分片摘要。
     */
    public List<ManifestChunk> findChunks(long releaseId) {
        return jdbc.query("SELECT chunk_index, digest FROM release_manifest_chunk"
                        + " WHERE release_id = ? ORDER BY chunk_index",
                (rs, rowNum) -> new ManifestChunk(rs.getInt("chunk_index"), rs.getString("digest")),
                releaseId);
    }
}
