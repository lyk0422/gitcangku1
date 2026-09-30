package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ProfileSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 设备硬件画像数据访问。画像只追加版本：head 表指向当前版本，版本表与标签表历史不可变。
 * 版本追加的调用方须先持有 device 行锁，避免画像更新之间、画像更新与任务创建之间的竞态。
 */
@Repository
public class DeviceProfileRepository {

    private final JdbcTemplate jdbc;

    public DeviceProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入设备首个画像版本（版本号1），调用方须持有 device 行锁。
     */
    public long insertFirstVersion(String deviceId, String model, Integer memoryMb, List<String> tags) {
        long profileId = insertProfileRow(deviceId, 1, model, memoryMb);
        insertTags(profileId, tags);
        jdbc.update("INSERT INTO device_profile_head (device_id, current_version) VALUES (?, 1)", deviceId);
        return profileId;
    }

    /**
     * 追加新版本：仅当当前版本仍为 expectedVersion 时生效，返回影响行数（0 表示版本冲突）。
     * 调用方须持有 device 行锁。
     */
    public int appendVersion(String deviceId, int expectedVersion, String model, Integer memoryMb,
                             List<String> tags) {
        int updated = jdbc.update(
                "UPDATE device_profile_head SET current_version = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE device_id = ? AND current_version = ?",
                expectedVersion + 1, deviceId, expectedVersion);
        if (updated == 0) {
            return 0;
        }
        long profileId = insertProfileRow(deviceId, expectedVersion + 1, model, memoryMb);
        insertTags(profileId, tags);
        return 1;
    }

    private long insertProfileRow(String deviceId, int version, String model, Integer memoryMb) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_hardware_profile (device_id, profile_version, model, memory_mb)"
                            + " VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, deviceId);
            ps.setInt(2, version);
            ps.setString(3, model);
            if (memoryMb == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, memoryMb);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void insertTags(long profileId, List<String> tags) {
        for (String tag : tags) {
            jdbc.update("INSERT INTO device_profile_tag (profile_id, capability_tag) VALUES (?, ?)",
                    profileId, tag);
        }
    }

    public Optional<Integer> currentVersionNumber(String deviceId) {
        return jdbc.query(
                        "SELECT current_version FROM device_profile_head WHERE device_id = ?",
                        (rs, rowNum) -> rs.getInt("current_version"), deviceId).stream()
                .findFirst();
    }

    public Optional<ProfileSnapshot> findCurrent(String deviceId) {
        return currentVersionNumber(deviceId).flatMap(v -> findVersion(deviceId, v));
    }

    public Optional<ProfileSnapshot> findVersion(String deviceId, int version) {
        List<ProfileSnapshot> rows = jdbc.query(
                "SELECT id, device_id, profile_version, model, memory_mb FROM device_hardware_profile"
                        + " WHERE device_id = ? AND profile_version = ?",
                (rs, rowNum) -> new ProfileSnapshot(rs.getLong("id"), rs.getString("device_id"),
                        rs.getInt("profile_version"), rs.getString("model"),
                        (Integer) rs.getObject("memory_mb"), List.of()),
                deviceId, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ProfileSnapshot head = rows.get(0);
        List<String> tags = jdbc.queryForList(
                "SELECT capability_tag FROM device_profile_tag WHERE profile_id = ? ORDER BY capability_tag",
                String.class, head.id());
        return Optional.of(new ProfileSnapshot(head.id(), head.deviceId(), head.version(),
                head.model(), head.memoryMb(), List.copyOf(tags)));
    }
}
