package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 气象标准（区域与最低标准窗口）、预测气象与空域占用的数据访问。
 * 所有写方法都必须在业务事务内调用；版本递增前先对协调锁行做真实更新，
 * 与审核/占用事务按提交顺序串行化。
 */
@Repository
public class WeatherRepository {

    private final JdbcTemplate jdbc;

    public WeatherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WeatherRegionPo> REGION_MAPPER = (rs, n) -> new WeatherRegionPo(
            rs.getString("region_id"),
            rs.getInt("x_min"),
            rs.getInt("y_min"),
            rs.getInt("x_max"),
            rs.getInt("y_max"),
            rs.getLong("duty_version"),
            rs.getLong("created_at"));

    private static final RowMapper<MinimaWindowPo> WINDOW_MAPPER = (rs, n) -> new MinimaWindowPo(
            rs.getString("region_id"),
            rs.getLong("window_start"),
            rs.getLong("window_end"),
            rs.getInt("visibility_min_m"),
            rs.getInt("cloud_base_min_m"),
            rs.getLong("duty_version"));

    private static final RowMapper<ForecastEntryPo> FORECAST_MAPPER = (rs, n) -> new ForecastEntryPo(
            rs.getLong("forecast_version"),
            rs.getString("region_id"),
            rs.getLong("window_start"),
            rs.getLong("window_end"),
            rs.getInt("visibility_m"),
            rs.getInt("cloud_base_m"));

    private static final RowMapper<OccupancyPo> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("occupancy_id"),
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getLong("duty_version"),
            (Long) rs.getObject("forecast_version"),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    // ============================ 气象版本 ============================

    /** 读取当前气象值班版本（单行）。 */
    public long getDutyVersion() {
        Long v = jdbc.queryForObject("SELECT duty_version FROM weather_meta WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    /** 读取当前最新预测气象版本（单行；0 表示尚无任何预测上传）。 */
    public long getLatestForecastVersion() {
        Long v = jdbc.queryForObject("SELECT forecast_version FROM weather_meta WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    /** 在当前事务内加协调锁后将气象值班版本加一并返回新版本。 */
    public long incrementDutyVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET duty_version = duty_version + 1 WHERE id = 1");
        return getDutyVersion();
    }

    /** 在当前事务内加协调锁后将预测气象版本加一并返回新版本。 */
    public long incrementForecastVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET forecast_version = forecast_version + 1 WHERE id = 1");
        return getLatestForecastVersion();
    }

    // ============================ 气象区域与标准 ============================

    /** 按 regionId 查询气象区域，不存在返回 null。 */
    public WeatherRegionPo findRegion(String regionId) {
        try {
            return jdbc.queryForObject(
                    "SELECT region_id, x_min, y_min, x_max, y_max, duty_version, created_at "
                            + "FROM weather_region WHERE region_id = ?", REGION_MAPPER, regionId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部气象区域（按 regionId 字典序，排序稳定）。 */
    public List<WeatherRegionPo> findAllRegions() {
        return jdbc.query(
                "SELECT region_id, x_min, y_min, x_max, y_max, duty_version, created_at "
                        + "FROM weather_region ORDER BY region_id", REGION_MAPPER);
    }

    /** 查询某区域当前全部最低标准窗口（按时段起点升序，排序稳定）。 */
    public List<MinimaWindowPo> findWindows(String regionId) {
        return jdbc.query(
                "SELECT region_id, window_start, window_end, visibility_min_m, cloud_base_min_m, duty_version "
                        + "FROM weather_minima_window WHERE region_id = ? ORDER BY window_start",
                WINDOW_MAPPER, regionId);
    }

    /** 新建或整体替换气象区域几何（调用方负责事务与版本递增）。 */
    public void upsertRegion(WeatherRegionPo region) {
        int updated = jdbc.update(
                "UPDATE weather_region SET x_min = ?, y_min = ?, x_max = ?, y_max = ?, "
                        + "duty_version = ?, created_at = ? WHERE region_id = ?",
                region.xMin(), region.yMin(), region.xMax(), region.yMax(),
                region.dutyVersion(), region.createdAt(), region.regionId());
        if (updated == 0) {
            jdbc.update("INSERT INTO weather_region "
                            + "(region_id, x_min, y_min, x_max, y_max, duty_version, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    region.regionId(), region.xMin(), region.yMin(), region.xMax(), region.yMax(),
                    region.dutyVersion(), region.createdAt());
        }
    }

    /** 删除某区域全部旧标准窗口（修订时整体替换，调用方负责事务）。 */
    public void deleteWindows(String regionId) {
        jdbc.update("DELETE FROM weather_minima_window WHERE region_id = ?", regionId);
    }

    /** 写入一个最低标准窗口（调用方负责事务）。 */
    public void insertWindow(MinimaWindowPo window) {
        jdbc.update("INSERT INTO weather_minima_window "
                        + "(region_id, window_start, window_end, visibility_min_m, cloud_base_min_m, duty_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                window.regionId(), window.windowStart(), window.windowEnd(),
                window.visibilityMinM(), window.cloudBaseMinM(), window.dutyVersion());
    }

    // ============================ 预测气象 ============================

    /** 查询某预测版本的全部条目（按 regionId、时段起点升序，排序稳定）。 */
    public List<ForecastEntryPo> findForecastEntries(long forecastVersion) {
        return jdbc.query(
                "SELECT forecast_version, region_id, window_start, window_end, visibility_m, cloud_base_m "
                        + "FROM weather_forecast WHERE forecast_version = ? "
                        + "ORDER BY region_id, window_start",
                FORECAST_MAPPER, forecastVersion);
    }

    /** 查询某预测版本中指定区域与时段起点的条目，不存在返回 null。 */
    public ForecastEntryPo findForecastEntry(long forecastVersion, String regionId, long windowStart) {
        return jdbc.query(
                "SELECT forecast_version, region_id, window_start, window_end, visibility_m, cloud_base_m "
                        + "FROM weather_forecast "
                        + "WHERE forecast_version = ? AND region_id = ? AND window_start = ?",
                FORECAST_MAPPER, forecastVersion, regionId, windowStart)
                .stream().findFirst().orElse(null);
    }

    /** 写入一条预测气象条目（调用方负责事务与版本分配）。 */
    public void insertForecastEntry(ForecastEntryPo entry) {
        jdbc.update("INSERT INTO weather_forecast "
                        + "(forecast_version, region_id, window_start, window_end, visibility_m, cloud_base_m) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                entry.forecastVersion(), entry.regionId(), entry.windowStart(), entry.windowEnd(),
                entry.visibilityM(), entry.cloudBaseM());
    }

    // ============================ 空域占用 ============================

    /** 按 occupancyId 查询占用记录，不存在返回 null。 */
    public OccupancyPo findOccupancy(String occupancyId) {
        return jdbc.query(
                "SELECT occupancy_id, review_id, route_id, route_version, airspace_version, "
                        + "duty_version, forecast_version, operator, request_id, created_at "
                        + "FROM occupancy WHERE occupancy_id = ?",
                OCCUPANCY_MAPPER, occupancyId).stream().findFirst().orElse(null);
    }

    /** 按来源审核记录查询占用，不存在返回 null。 */
    public OccupancyPo findOccupancyByReview(String reviewId) {
        return jdbc.query(
                "SELECT occupancy_id, review_id, route_id, route_version, airspace_version, "
                        + "duty_version, forecast_version, operator, request_id, created_at "
                        + "FROM occupancy WHERE review_id = ?",
                OCCUPANCY_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 创建占用记录（调用方负责事务；版本快照来自审核记录，不可变）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO occupancy "
                        + "(occupancy_id, review_id, route_id, route_version, airspace_version, "
                        + "duty_version, forecast_version, operator, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.reviewId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.dutyVersion(), po.forecastVersion(), po.operator(), po.requestId(), po.createdAt());
    }
}