package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceProfile;
import com.example.starter.firmware.domain.ProfileCompatibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 设备硬件画像数据访问。画像版本每次更新加一；投放筛选时按 device_id 升序加行锁，
 * 与画像更新形成一致提交顺序，保证每个任务只引用一个完整画像版本。
 */
@Repository
public class DeviceProfileRepository {

    private static final RowMapper<DeviceProfile> MAPPER = (rs, rowNum) -> {
        String tags = rs.getString("tags");
        return new DeviceProfile(rs.getString("device_id"), rs.getString("model"),
                (Integer) rs.getObject("memory_mb"),
                tags == null ? null : ProfileCompatibility.split(tags),
                rs.getInt("profile_version"));
    };

    private static final String COLUMNS = "device_id, model, memory_mb, tags, profile_version";

    private final JdbcTemplate jdbc;

    public DeviceProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String deviceId, String model, Integer memoryMb, Set<String> tags) {
        jdbc.update("INSERT INTO device_profile (device_id, model, memory_mb, tags, profile_version)"
                        + " VALUES (?, ?, ?, ?, 1)",
                deviceId, model, memoryMb, tags == null ? null : ProfileCompatibility.join(tags));
    }

    /**
     * 更新画像字段并将画像版本加一，返回影响行数（0 表示画像不存在）。
     */
    public int update(String deviceId, String model, Integer memoryMb, Set<String> tags) {
        return jdbc.update("UPDATE device_profile SET model = ?, memory_mb = ?, tags = ?,"
                        + " profile_version = profile_version + 1, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE device_id = ?",
                model, memoryMb, tags == null ? null : ProfileCompatibility.join(tags), deviceId);
    }

    public Optional<DeviceProfile> findById(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_profile WHERE device_id = ?",
                MAPPER, deviceId).stream().findFirst();
    }

    /**
     * 按 device_id 升序加行锁读取全部画像，供投放筛选在同一事务内冻结画像版本。
     */
    public List<DeviceProfile> findAllForUpdate() {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_profile ORDER BY device_id FOR UPDATE",
                MAPPER);
    }
}
