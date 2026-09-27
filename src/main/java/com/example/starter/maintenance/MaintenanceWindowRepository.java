package com.example.starter.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 维护窗口持久化：窗口版本号、状态与区间均落库；所有 SQL 使用参数化查询。
 */
@Repository
public class MaintenanceWindowRepository {

    private static final RowMapper<MaintenanceWindow> WINDOW_MAPPER = (rs, rowNum) -> new MaintenanceWindow(
            rs.getString("window_id"),
            rs.getString("station_id"),
            rs.getInt("maintenance_version"),
            rs.getTimestamp("start_at_utc").toInstant(),
            rs.getTimestamp("end_at_utc").toInstant(),
            WindowType.valueOf(rs.getString("window_type")),
            rs.getString("corrects_window_id"),
            WindowStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public MaintenanceWindowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入维护窗口。
     */
    public void insert(MaintenanceWindow window) {
        jdbcTemplate.update(
                "INSERT INTO maintenance_window (window_id, station_id, maintenance_version, "
                        + "start_at_utc, end_at_utc, window_type, corrects_window_id, status, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                window.windowId(),
                window.stationId(),
                window.maintenanceVersion(),
                Timestamp.from(window.startAtUtc()),
                Timestamp.from(window.endAtUtc()),
                window.windowType().name(),
                window.correctsWindowId(),
                window.status().name(),
                Timestamp.from(window.createdAtUtc()));
    }

    /**
     * 按窗口标识查询（不加锁）。
     */
    public Optional<MaintenanceWindow> findById(String windowId) {
        return jdbcTemplate.query(selectAll() + " WHERE window_id = ?", WINDOW_MAPPER, windowId)
                .stream().findFirst();
    }

    /**
     * 按窗口标识查询并加行锁，用于删除等写事务。
     */
    public Optional<MaintenanceWindow> findByIdForUpdate(String windowId) {
        return jdbcTemplate.query(selectAll() + " WHERE window_id = ? FOR UPDATE", WINDOW_MAPPER, windowId)
                .stream().findFirst();
    }

    /**
     * 查询站点全部窗口并对站点窗口集合加锁（行锁锁定该站点现有窗口行），
     * 用于登记/更正时在事务内完成重叠判定与版本号分配。
     */
    public List<MaintenanceWindow> findByStationForUpdate(String stationId) {
        return jdbcTemplate.query(
                selectAll() + " WHERE station_id = ? ORDER BY maintenance_version ASC FOR UPDATE",
                WINDOW_MAPPER, stationId);
    }

    /**
     * 查询站点全部生效窗口（不加锁），用于只读诊断。
     */
    public List<MaintenanceWindow> findActiveByStation(String stationId) {
        return jdbcTemplate.query(
                selectAll() + " WHERE station_id = ? AND status = 'ACTIVE' "
                        + "ORDER BY start_at_utc ASC, window_id ASC",
                WINDOW_MAPPER, stationId);
    }

    /**
     * 将窗口标记为 DELETED；历史快照中固化的引用不受影响。
     */
    public void markDeleted(String windowId) {
        jdbcTemplate.update("UPDATE maintenance_window SET status = 'DELETED' WHERE window_id = ?", windowId);
    }

    /**
     * 判断窗口是否已被任一合并快照固化引用（封存）。
     */
    public boolean isSealedBySnapshot(String windowId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_snapshot_window WHERE window_id = ?",
                Integer.class, windowId);
        return count != null && count > 0;
    }

    /**
     * 查询某时刻（左闭右开）命中的生效窗口，按维护版本升序返回。
     * 正常数据下同站生效窗口不允许重叠，至多返回一条非更正窗口；更正窗口可与原窗口同时命中。
     */
    public List<MaintenanceWindow> findActiveAtTime(String stationId, java.time.Instant atUtc) {
        return jdbcTemplate.query(
                selectAll() + " WHERE station_id = ? AND status = 'ACTIVE' "
                        + "AND start_at_utc <= ? AND end_at_utc > ? "
                        + "ORDER BY maintenance_version ASC",
                WINDOW_MAPPER, stationId, Timestamp.from(atUtc), Timestamp.from(atUtc));
    }

    private String selectAll() {
        return "SELECT window_id, station_id, maintenance_version, start_at_utc, end_at_utc, "
                + "window_type, corrects_window_id, status, created_at_utc FROM maintenance_window";
    }
}
