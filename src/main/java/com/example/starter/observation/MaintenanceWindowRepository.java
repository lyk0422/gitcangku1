package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 维护窗口持久化：maintenance_window 只追加（登记/更正/撤回），不提供更新与删除接口。
 * 所有 SQL 使用参数化查询。窗口版本号在站点行锁内由 MAX(version)+1 分配。
 */
@Repository
public class MaintenanceWindowRepository {

    private static final RowMapper<MaintenanceWindow> WINDOW_MAPPER = (rs, rowNum) -> {
        Timestamp start = rs.getTimestamp("start_at_utc");
        Timestamp end = rs.getTimestamp("end_at_utc");
        Timestamp created = rs.getTimestamp("created_at_utc");
        return new MaintenanceWindow(
                rs.getString("window_id"),
                rs.getString("station_id"),
                rs.getInt("version"),
                MaintenanceWindowKind.valueOf(rs.getString("kind")),
                start == null ? null : start.toInstant(),
                end == null ? null : end.toInstant(),
                rs.getString("supersedes_window_id"),
                rs.getBoolean("retracted"),
                created.toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public MaintenanceWindowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条窗口版本行（登记或更正）。
     */
    public void insert(MaintenanceWindow window) {
        jdbcTemplate.update(
                "INSERT INTO maintenance_window (window_id, station_id, version, kind, start_at_utc, end_at_utc, "
                        + "duration_seconds, supersedes_window_id, retracted, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                window.windowId(), window.stationId(), window.version(), window.kind().name(),
                window.startAtUtc() == null ? null : Timestamp.from(window.startAtUtc()),
                window.endAtUtc() == null ? null : Timestamp.from(window.endAtUtc()),
                window.durationSeconds(),
                window.supersedesWindowId(), window.retracted(),
                Timestamp.from(window.createdAtUtc()));
    }

    /**
     * 读取站点全部窗口版本行（含已被替换的登记窗口与撤回行），按维护版本升序，供历史查询与折叠有效窗口。
     */
    public List<MaintenanceWindow> findAllByStation(String stationId) {
        return jdbcTemplate.query(
                "SELECT window_id, station_id, version, kind, start_at_utc, end_at_utc, supersedes_window_id, "
                        + "retracted, created_at_utc FROM maintenance_window WHERE station_id = ? ORDER BY version ASC",
                WINDOW_MAPPER, stationId);
    }

    /**
     * 读取站点全部窗口版本行并加站点关联行锁（站点行锁之外的兜底串行化，调用方须先持有站点行锁）。
     */
    public List<MaintenanceWindow> findAllByStationForUpdate(String stationId) {
        return jdbcTemplate.query(
                "SELECT window_id, station_id, version, kind, start_at_utc, end_at_utc, supersedes_window_id, "
                        + "retracted, created_at_utc FROM maintenance_window WHERE station_id = ? "
                        + "ORDER BY version ASC FOR UPDATE",
                WINDOW_MAPPER, stationId);
    }

    /**
     * 按窗口标识查询单个版本行。
     */
    public Optional<MaintenanceWindow> findByWindowId(String windowId) {
        return jdbcTemplate.query(
                        "SELECT window_id, station_id, version, kind, start_at_utc, end_at_utc, supersedes_window_id, "
                                + "retracted, created_at_utc FROM maintenance_window WHERE window_id = ?",
                        WINDOW_MAPPER, windowId)
                .stream().findFirst();
    }

    /**
     * 站点当前最大维护版本号；尚无窗口时返回 0。
     */
    public int findMaxVersion(String stationId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM maintenance_window WHERE station_id = ?",
                Integer.class, stationId);
        return max == null ? 0 : max;
    }
}
