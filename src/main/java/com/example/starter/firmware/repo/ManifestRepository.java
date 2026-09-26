package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Manifest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 发布版本分片清单数据访问。清单与分片明细在同一事务内整体替换；
 * 任一任务拉取后由业务层拒绝修改，表数据只增不改的约定由 release_manifest 单行承载。
 */
@Repository
public class ManifestRepository {

    private final JdbcTemplate jdbc;

    public ManifestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 整体替换清单：先删后插，调用方需已完成全部校验并持有发布单行锁。
     */
    public void replace(Manifest manifest) {
        jdbc.update("DELETE FROM release_manifest_chunk WHERE release_id = ?", manifest.releaseId());
        jdbc.update("DELETE FROM release_manifest WHERE release_id = ?", manifest.releaseId());
        jdbc.update("INSERT INTO release_manifest"
                        + " (release_id, firmware_version, chunk_count, package_digest, registered_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?)",
                manifest.releaseId(), manifest.firmwareVersion(), manifest.chunkCount(),
                manifest.packageDigest(), manifest.registeredAtUtc());
        for (int i = 0; i < manifest.chunkDigests().size(); i++) {
            jdbc.update("INSERT INTO release_manifest_chunk (release_id, chunk_index, digest)"
                    + " VALUES (?, ?, ?)", manifest.releaseId(), i, manifest.chunkDigests().get(i));
        }
    }

    /**
     * 读取清单及按序号升序排列的分片摘要；chunkDigests 的下标即分片序号。
     */
    public Optional<Manifest> findByReleaseId(long releaseId) {
        var rows = jdbc.query("SELECT firmware_version, chunk_count, package_digest, registered_at_utc"
                        + " FROM release_manifest WHERE release_id = ?",
                (rs, rowNum) -> new String[]{rs.getString("firmware_version"),
                        String.valueOf(rs.getInt("chunk_count")), rs.getString("package_digest"),
                        rs.getString("registered_at_utc")},
                releaseId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String[] head = rows.get(0);
        List<String> digests = jdbc.query("SELECT digest FROM release_manifest_chunk"
                + " WHERE release_id = ? ORDER BY chunk_index", (rs, rowNum) -> rs.getString("digest"), releaseId);
        return Optional.of(new Manifest(releaseId, head[0], Integer.parseInt(head[1]), head[2], head[3], digests));
    }

    public boolean existsByReleaseId(long releaseId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM release_manifest WHERE release_id = ?",
                Long.class, releaseId);
        return count != null && count > 0;
    }
}
