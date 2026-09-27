package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 气象最低标准、预测气象、值班版本与空域占用的数据访问。
 *
 * <p>三个版本（气象标准/预测/值班）的变更都先对 coord_lock 行做真实更新，
 * 与审核事务互斥（同全局空域版本机制），保证审核读到的全部版本与数据
 * 来自同一个已提交状态。</p>
 */
@Repository
public class WeatherRepository {

    private final JdbcTemplate jdbc;

    public WeatherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WeatherMinimaPo> MINIMA_MAPPER = (rs, n) -> new WeatherMinimaPo(
            rs.getString("zone_id"),
            rs.getInt("window_start"),
            rs.getInt("window_end"),
            rs.getInt("min_visibility_m"),
            rs.getInt("min_cloud_base_m"),
            rs.getLong("minima_version"));

    private static final RowMapper<WeatherForecastPo> FORECAST_MAPPER = (rs, n) -> new WeatherForecastPo(
            rs.getLong("forecast_version"),
            rs.getString("zone_id"),
            rs.getInt("window_start"),
            rs.getInt("window_end"),
            rs.getInt("visibility_m"),
            rs.getInt("cloud_base_m"),
            rs.getLong("created_at"));

    private static final RowMapper<OccupationPo> OCCUPATION_MAPPER = (rs, n) -> new OccupationPo(
            rs.getString("occupation_id"),
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getLong("weather_version"),
            (Long) rs.getObject("forecast_version"),
            rs.getLong("duty_version"),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    // ============================ 版本 ============================

    /** 读取当前气象标准/预测/值班版本（单行）。 */
    public WeatherVersions getVersions() {
        return jdbc.queryForObject(
                "SELECT weather_version, forecast_version, duty_version FROM weather_meta WHERE id = 1",
                (rs, n) -> new WeatherVersions(rs.getLong("weather_version"),
                        rs.getLong("forecast_version"), rs.getLong("duty_version")));
    }

    /** 气象标准版本加一并返回新版本（先取协调锁，须在业务事务内调用）。 */
    public long incrementWeatherVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET weather_version = weather_version + 1 WHERE id = 1");
        return getVersions().weatherVersion();
    }

    /** 预测版本加一并返回新版本（先取协调锁，须在业务事务内调用）。 */
    public long incrementForecastVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET forecast_version = forecast_version + 1 WHERE id = 1");
        return getVersions().forecastVersion();
    }

    /** 值班版本加一并返回新版本（先取协调锁，须在业务事务内调用）。 */
    public long incrementDutyVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET duty_version = duty_version + 1 WHERE id = 1");
        return getVersions().dutyVersion();
    }

    // ============================ 最低标准 ============================

    /** 整组替换某区域的气象最低标准（先删后插，调用方负责事务与版本递增）。 */
    public void replaceMinima(String zoneId, List<WeatherMinimaPo> windows) {
        jdbc.update("DELETE FROM weather_minima WHERE zone_id = ?", zoneId);
        for (WeatherMinimaPo window : windows) {
            jdbc.update("INSERT INTO weather_minima "
                            + "(zone_id, window_start, window_end, min_visibility_m, min_cloud_base_m, minima_version) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    window.zoneId(), window.windowStart(), window.windowEnd(),
                    window.minVisibilityM(), window.minCloudBaseM(), window.minimaVersion());
        }
    }

    /** 查询某区域当前生效的最低标准时段（按时段起始升序，稳定排序）。 */
    public List<WeatherMinimaPo> findMinima(String zoneId) {
        return jdbc.query(
                "SELECT zone_id, window_start, window_end, min_visibility_m, min_cloud_base_m, minima_version "
                        + "FROM weather_minima WHERE zone_id = ? ORDER BY window_start, window_end",
                MINIMA_MAPPER, zoneId);
    }

    // ============================ 预测 ============================

    /** 批量写入预测条目（调用方负责事务与版本分配）。 */
    public void insertForecasts(List<WeatherForecastPo> entries) {
        for (WeatherForecastPo entry : entries) {
            jdbc.update("INSERT INTO weather_forecast "
                            + "(forecast_version, zone_id, window_start, window_end, visibility_m, cloud_base_m, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    entry.forecastVersion(), entry.zoneId(), entry.windowStart(), entry.windowEnd(),
                    entry.visibilityM(), entry.cloudBaseM(), entry.createdAt());
        }
    }

    /** 查询指定预测版本下某区域的全部预测条目（按时段起始升序）。 */
    public List<WeatherForecastPo> findForecasts(long forecastVersion, String zoneId) {
        return jdbc.query(
                "SELECT forecast_version, zone_id, window_start, window_end, visibility_m, cloud_base_m, created_at "
                        + "FROM weather_forecast WHERE forecast_version = ? AND zone_id = ? "
                        + "ORDER BY window_start, window_end",
                FORECAST_MAPPER, forecastVersion, zoneId);
    }

    /** 查询指定预测版本的全部条目（按区域、时段起始升序，稳定排序）。 */
    public List<WeatherForecastPo> findForecastVersion(long forecastVersion) {
        return jdbc.query(
                "SELECT forecast_version, zone_id, window_start, window_end, visibility_m, cloud_base_m, created_at "
                        + "FROM weather_forecast WHERE forecast_version = ? "
                        + "ORDER BY zone_id, window_start, window_end",
                FORECAST_MAPPER, forecastVersion);
    }

    /** 指定预测版本是否存在（至少一条预测条目）。 */
    public boolean forecastVersionExists(long forecastVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM weather_forecast WHERE forecast_version = ?",
                Integer.class, forecastVersion);
        return count != null && count > 0;
    }

    // ============================ 占用 ============================

    /** 创建占用（调用方负责事务）。 */
    public void insertOccupation(OccupationPo po) {
        jdbc.update("INSERT INTO occupation "
                        + "(occupation_id, review_id, route_id, route_version, airspace_version, "
                        + "weather_version, forecast_version, duty_version, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupationId(), po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.weatherVersion(), po.forecastVersion(), po.dutyVersion(),
                po.requestId(), po.createdAt());
    }

    /** 按 occupationId 查询占用，不存在返回 null。 */
    public OccupationPo findOccupation(String occupationId) {
        return jdbc.query(
                "SELECT occupation_id, review_id, route_id, route_version, airspace_version, "
                        + "weather_version, forecast_version, duty_version, request_id, created_at "
                        + "FROM occupation WHERE occupation_id = ?",
                OCCUPATION_MAPPER, occupationId).stream().findFirst().orElse(null);
    }

    /** 按 reviewId 查询占用，不存在返回 null。 */
    public OccupationPo findOccupationByReview(String reviewId) {
        return jdbc.query(
                "SELECT occupation_id, review_id, route_id, route_version, airspace_version, "
                        + "weather_version, forecast_version, duty_version, request_id, created_at "
                        + "FROM occupation WHERE review_id = ?",
                OCCUPATION_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某航线的全部占用历史（按创建时间、occupationId 升序，稳定排序）。 */
    public List<OccupationPo> findOccupationsByRoute(String routeId) {
        return jdbc.query(
                "SELECT occupation_id, review_id, route_id, route_version, airspace_version, "
                        + "weather_version, forecast_version, duty_version, request_id, created_at "
                        + "FROM occupation WHERE route_id = ? ORDER BY created_at, occupation_id",
                OCCUPATION_MAPPER, routeId);
    }
}
