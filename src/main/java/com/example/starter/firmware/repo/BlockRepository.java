package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ModuleBlockItem;
import com.example.starter.firmware.domain.ModuleBlockRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 模块兼容阻断记录数据访问：记录与明细只增不改，明细按模块名升序读取。
 */
@Repository
public class BlockRepository {

    private static final RowMapper<ModuleBlockRecord> RECORD_MAPPER = (rs, rowNum) ->
            new ModuleBlockRecord(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                    rs.getInt("matrix_version"), rs.getInt("exemption_version"),
                    rs.getString("module_digest"), rs.getString("blocked_at_utc"));

    private static final RowMapper<ModuleBlockItem> ITEM_MAPPER = (rs, rowNum) ->
            new ModuleBlockItem(rs.getString("module"), rs.getString("device_version"),
                    rs.getString("min_version"));

    private final JdbcTemplate jdbc;

    public BlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertRecord(long releaseId, String deviceId, int matrixVersion, int exemptionVersion,
                             String moduleDigest, String blockedAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO module_block_record"
                            + " (release_id, device_id, matrix_version, exemption_version,"
                            + " module_digest, blocked_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setInt(3, matrixVersion);
            ps.setInt(4, exemptionVersion);
            ps.setString(5, moduleDigest);
            ps.setString(6, blockedAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertItem(long blockId, ModuleBlockItem item) {
        jdbc.update("INSERT INTO module_block_item (block_id, module, device_version, min_version)"
                        + " VALUES (?, ?, ?, ?)",
                blockId, item.module(), item.deviceVersion(), item.minVersion());
    }

    /**
     * 发布单阻断记录，按记录ID升序。
     */
    public List<ModuleBlockRecord> findRecordsByRelease(long releaseId) {
        return jdbc.query("SELECT id, release_id, device_id, matrix_version, exemption_version,"
                        + " module_digest, blocked_at_utc FROM module_block_record"
                        + " WHERE release_id = ? ORDER BY id", RECORD_MAPPER, releaseId);
    }

    /**
     * 阻断明细，按模块名升序，排序稳定。
     */
    public List<ModuleBlockItem> findItems(long blockId) {
        return jdbc.query("SELECT module, device_version, min_version FROM module_block_item"
                + " WHERE block_id = ? ORDER BY module", ITEM_MAPPER, blockId);
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM module_block_record WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }
}
