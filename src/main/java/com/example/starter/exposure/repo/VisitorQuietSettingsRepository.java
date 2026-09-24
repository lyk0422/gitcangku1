package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.VisitorQuietSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客静默设置数据访问。加锁/版本 CAS 方法必须在事务内调用。
 */
@Repository
public class VisitorQuietSettingsRepository {

    private static final RowMapper<VisitorQuietSettings> MAPPER = (rs, rowNum) -> new VisitorQuietSettings(
            rs.getString("visitor_id"),
            rs.getInt("utc_offset_minutes"),
            rs.getInt("quiet_start_minute"),
            rs.getInt("quiet_end_minute"),
            rs.getBoolean("allow_critical"),
            rs.getInt("version"),
            rs.getLong("updated_at_utc"));

    private static final String COLUMNS =
            "visitor_id, utc_offset_minutes, quiet_start_minute, quiet_end_minute, "
                    + "allow_critical, version, updated_at_utc";

    private final JdbcTemplate jdbc;

    public VisitorQuietSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按访客查询设置（无锁）；未登记返回 empty（视为无静默）。 */
    public Optional<VisitorQuietSettings> findById(String visitorId) {
        return query(visitorId, false);
    }

    /** 行锁读取访客设置；未登记返回 empty。 */
    public Optional<VisitorQuietSettings> lockById(String visitorId) {
        return query(visitorId, true);
    }

    /** 首次登记设置，版本固定为 1；编号冲突由调用方按唯一约束处理。 */
    public void insert(VisitorQuietSettings settings) {
        jdbc.update("INSERT INTO visitor_quiet_settings "
                        + "(visitor_id, utc_offset_minutes, quiet_start_minute, quiet_end_minute, "
                        + "allow_critical, version, updated_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                settings.visitorId(),
                settings.utcOffsetMinutes(),
                settings.quietStartMinute(),
                settings.quietEndMinute(),
                settings.allowCritical(),
                settings.version(),
                settings.updatedAtUtc());
    }

    /**
     * 版本 CAS 更新：仅当当前版本为 expectedVersion 时写入新值并 version+1。
     *
     * @return 是否更新成功；false 表示版本冲突（409）
     */
    public boolean compareAndSetUpdate(VisitorQuietSettings settings, int expectedVersion) {
        int rows = jdbc.update("UPDATE visitor_quiet_settings "
                        + "SET utc_offset_minutes = ?, quiet_start_minute = ?, quiet_end_minute = ?, "
                        + "allow_critical = ?, version = version + 1, updated_at_utc = ? "
                        + "WHERE visitor_id = ? AND version = ?",
                settings.utcOffsetMinutes(),
                settings.quietStartMinute(),
                settings.quietEndMinute(),
                settings.allowCritical(),
                settings.updatedAtUtc(),
                settings.visitorId(),
                expectedVersion);
        return rows == 1;
    }

    private Optional<VisitorQuietSettings> query(String visitorId, boolean forUpdate) {
        String sql = "SELECT " + COLUMNS + " FROM visitor_quiet_settings WHERE visitor_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        List<VisitorQuietSettings> list = jdbc.query(sql, MAPPER, visitorId);
        return list.stream().findFirst();
    }
}
