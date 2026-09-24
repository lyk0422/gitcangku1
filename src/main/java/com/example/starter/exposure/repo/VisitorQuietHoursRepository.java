package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.VisitorQuietHours;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客静默时段设置数据访问。加锁/CAS 方法必须在事务内调用：
 * 申请曝光持行锁读取设置，保证“读取设置 -> 裁决”期间修改设置的事务按提交顺序串行裁决。
 */
@Repository
public class VisitorQuietHoursRepository {

    private final JdbcTemplate jdbc;

    public VisitorQuietHoursRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<VisitorQuietHours> MAPPER = (rs, rowNum) -> new VisitorQuietHours(
            rs.getString("visitor_id"),
            rs.getInt("utc_offset_minutes"),
            rs.getInt("quiet_start_minute"),
            rs.getInt("quiet_end_minute"),
            rs.getBoolean("allow_critical"),
            rs.getInt("version"),
            rs.getLong("created_at_utc"),
            rs.getLong("updated_at_utc"));

    private static final String COLUMNS =
            "visitor_id, utc_offset_minutes, quiet_start_minute, quiet_end_minute, "
                    + "allow_critical, version, created_at_utc, updated_at_utc";

    /** 普通读取访客设置；未登记返回 empty（视为无静默）。 */
    public Optional<VisitorQuietHours> findById(String visitorId) {
        List<VisitorQuietHours> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM visitor_quiet_hours WHERE visitor_id = ?",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /** 行锁读取访客设置；未登记返回 empty。 */
    public Optional<VisitorQuietHours> lockById(String visitorId) {
        List<VisitorQuietHours> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM visitor_quiet_hours WHERE visitor_id = ? FOR UPDATE",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /** 首次登记设置（version 固定为 1）；访客已存在时抛唯一键冲突。 */
    public void insert(VisitorQuietHours settings) {
        jdbc.update("INSERT INTO visitor_quiet_hours "
                        + "(visitor_id, utc_offset_minutes, quiet_start_minute, quiet_end_minute, "
                        + "allow_critical, version, created_at_utc, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                settings.visitorId(),
                settings.utcOffsetMinutes(),
                settings.quietStartMinute(),
                settings.quietEndMinute(),
                settings.allowCritical(),
                settings.version(),
                settings.createdAtUtc(),
                settings.updatedAtUtc());
    }

    /**
     * 乐观锁修改设置：仅当 version 与 expectedVersion 一致时整体覆盖并 version+1。
     *
     * @return 是否更新成功；false 表示版本冲突（409）或访客不存在
     */
    public boolean compareAndSetUpdate(VisitorQuietHours settings, int expectedVersion) {
        int rows = jdbc.update("UPDATE visitor_quiet_hours SET utc_offset_minutes = ?, "
                        + "quiet_start_minute = ?, quiet_end_minute = ?, allow_critical = ?, "
                        + "version = version + 1, updated_at_utc = ? "
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

    /** 乐观锁 CAS 失败时区分“版本不匹配”与“访客不存在”。 */
    public boolean exists(String visitorId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM visitor_quiet_hours WHERE visitor_id = ?",
                Integer.class, visitorId);
        return count != null && count > 0;
    }
}
