package com.example.starter.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测站与维护窗口持久化。所有 SQL 使用参数化查询；业务时间一律为 UTC。
 */
@Repository
public class StationRepository {

    private static final RowMapper<StationRecord> STATION_MAPPER = (rs, rowNum) -> new StationRecord(
            rs.getString("station_id"),
            rs.getTimestamp("activated_at_utc").toInstant());

    private static final RowMapper<MaintenanceWindowRecord> WINDOW_MAPPER = (rs, rowNum) -> new MaintenanceWindowRecord(
            rs.getString("window_id"),
            rs.getString("station_id"),
            rs.getTimestamp("start_utc").toInstant(),
            rs.getTimestamp("end_utc").toInstant(),
            rs.getInt("window_version"),
            rs.getString("status"),
            rs.getString("corrects_window_id"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final String WINDOW_COLUMNS =
            "window_id, station_id, start_utc, end_utc, window_version, status, corrects_window_id, "
                    + "request_id, created_at_utc";

    private final JdbcTemplate jdbcTemplate;

    public StationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按标识查询观测站（不加锁）。
     */
    public Optional<StationRecord> findStation(String stationId) {
        return jdbcTemplate.query(
                        "SELECT station_id, activated_at_utc FROM observation_station WHERE station_id = ?",
                        STATION_MAPPER, stationId)
                .stream().findFirst();
    }

    /**
     * 按标识查询观测站并加行锁（SELECT ... FOR UPDATE），
     * 用于串行化同一站点的窗口变更与观测提交（按提交顺序裁决）。
     */
    public Optional<StationRecord> findStationForUpdate(String stationId) {
        return jdbcTemplate.query(
                        "SELECT station_id, activated_at_utc FROM observation_station WHERE station_id = ? FOR UPDATE",
                        STATION_MAPPER, stationId)
                .stream().findFirst();
    }

    /**
     * 登记新观测站。
     */
    public void insertStation(StationRecord station) {
        jdbcTemplate.update(
                "INSERT INTO observation_station (station_id, activated_at_utc, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                station.stationId(), Timestamp.from(station.activatedAtUtc()));
    }

    /**
     * 按标识查询维护窗口（不加锁）。
     */
    public Optional<MaintenanceWindowRecord> findWindow(String windowId) {
        return jdbcTemplate.query(
                        "SELECT " + WINDOW_COLUMNS + " FROM maintenance_window WHERE window_id = ?",
                        WINDOW_MAPPER, windowId)
                .stream().findFirst();
    }

    /**
     * 查询站点全部窗口（含各状态），按开始时刻、窗口标识稳定排序，用于历史与诊断查询。
     */
    public List<MaintenanceWindowRecord> findWindowsByStation(String stationId) {
        return jdbcTemplate.query(
                "SELECT " + WINDOW_COLUMNS + " FROM maintenance_window WHERE station_id = ? "
                        + "ORDER BY start_utc ASC, window_id ASC",
                WINDOW_MAPPER, stationId);
    }

    /**
     * 查询站点生效中（ACTIVE）窗口，按开始时刻、窗口标识稳定排序。
     */
    public List<MaintenanceWindowRecord> findActiveWindows(String stationId) {
        return jdbcTemplate.query(
                "SELECT " + WINDOW_COLUMNS + " FROM maintenance_window "
                        + "WHERE station_id = ? AND status = 'ACTIVE' ORDER BY start_utc ASC, window_id ASC",
                WINDOW_MAPPER, stationId);
    }

    /**
     * 查询站点生效中且与指定左闭右开区间重叠的窗口（重叠判定：existing.start < newEnd 且 existing.end > newStart）。
     */
    public List<MaintenanceWindowRecord> findOverlappingActiveWindows(String stationId, Instant startUtc,
                                                                      Instant endUtc) {
        return jdbcTemplate.query(
                "SELECT " + WINDOW_COLUMNS + " FROM maintenance_window "
                        + "WHERE station_id = ? AND status = 'ACTIVE' AND start_utc < ? AND end_utc > ? "
                        + "ORDER BY start_utc ASC, window_id ASC",
                WINDOW_MAPPER, stationId, Timestamp.from(endUtc), Timestamp.from(startUtc));
    }

    /**
     * 查询站点生效中且覆盖指定时刻的窗口（左闭右开：start <= at < end）；至多一条，由重叠约束保证。
     */
    public Optional<MaintenanceWindowRecord> findActiveWindowCovering(String stationId, Instant atUtc) {
        return jdbcTemplate.query(
                        "SELECT " + WINDOW_COLUMNS + " FROM maintenance_window "
                                + "WHERE station_id = ? AND status = 'ACTIVE' AND start_utc <= ? AND end_utc > ? "
                                + "ORDER BY start_utc ASC, window_id ASC",
                        WINDOW_MAPPER, stationId, Timestamp.from(atUtc), Timestamp.from(atUtc))
                .stream().findFirst();
    }

    /**
     * 插入维护窗口（登记或更正）。
     */
    public void insertWindow(MaintenanceWindowRecord window) {
        jdbcTemplate.update(
                "INSERT INTO maintenance_window (window_id, station_id, start_utc, end_utc, window_version, status, "
                        + "corrects_window_id, request_id, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                window.windowId(), window.stationId(), Timestamp.from(window.startUtc()),
                Timestamp.from(window.endUtc()), window.windowVersion(), window.status(),
                window.correctsWindowId(), window.requestId(), Timestamp.from(window.createdAtUtc()));
    }

    /**
     * 更新窗口状态（SUPERSEDED / WITHDRAWN）；窗口区间与版本永不修改。
     */
    public void updateWindowStatus(String windowId, String status) {
        jdbcTemplate.update("UPDATE maintenance_window SET status = ? WHERE window_id = ?", status, windowId);
    }

    /**
     * 判断窗口是否已被合并快照覆盖：存在命中该窗口且所在冲突簇已合并的观测提交。
     * 被覆盖的窗口不可修改/撤回，只能追加更正窗口。
     */
    public boolean isWindowSealed(String windowId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_observation o JOIN cluster_merge_snapshot s "
                        + "ON o.cluster_id = s.cluster_id WHERE o.window_id = ?",
                Integer.class, windowId);
        return count != null && count > 0;
    }
}
