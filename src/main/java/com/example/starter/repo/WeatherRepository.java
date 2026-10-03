package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 气象最低标准、预测气象与占用的数据访问。
 * 所有写方法都必须在业务事务内调用；版本推进先更新协调锁行，
 * 与审查/禁飞区变更事务互斥，保证按提交顺序裁决。
 */
@Repository
public class WeatherRepository {

    private final JdbcTemplate jdbc;

    public WeatherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WeatherMinimaPo> MINIMA_MAPPER = (rs, n) -> new WeatherMinimaPo(
            rs.getString("zone_id"),
            rs.getLong("window_start_utc"),
            rs.getLong("window_end_utc"),
            rs.getInt("min_visibility_m"),
            rs.getInt("min_cloud_base_m"),
            rs.getLong("weather_version"));

    private static final RowMapper<WeatherForecastPo> FORECAST_MAPPER = (rs, n) -> new WeatherForecastPo(
            rs.getLong("forecast_version"),
            rs.getString("zone_id"),
            rs.getLong("window_start_utc"),
            rs.getLong("window_end_utc"),
            rs.getInt("visibility_m"),
            rs.getInt("cloud_base_m"));

    private static final RowMapper<OccupancyPo> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("occupancy_id"),
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            (Long) rs.getObject("weather_version"),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    /** 读取当前全局气象（值班）版本（单行）。 */
    public long getWeatherVersion() {
        Long version = jdbc.queryForObject(
                "SELECT weather_version FROM weather_meta WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 原子地将全局气象版本加一并返回新版本。
     * 先更新协调锁行（与审查、禁飞区变更事务互斥），再推进版本；
     * 必须在业务事务内调用，保证标准/预测变更与版本推进原子提交。
     */
    public long incrementWeatherVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE weather_meta SET weather_version = weather_version + 1 WHERE id = 1");
        return getWeatherVersion();
    }

    /** 查询某区域当前全部标准时段（按起点升序，排序稳定）。 */
    public List<WeatherMinimaPo> findMinima(String zoneId) {
        return jdbc.query(
                "SELECT zone_id, window_start_utc, window_end_utc, min_visibility_m, "
                        + "min_cloud_base_m, weather_version FROM weather_minima "
                        + "WHERE zone_id = ? ORDER BY window_start_utc",
                MINIMA_MAPPER, zoneId);
    }

    /** 查询全部区域的当前标准时段（按 zoneId、起点升序），供审查逐区域比对。 */
    public List<WeatherMinimaPo> findAllMinima() {
        return jdbc.query(
                "SELECT zone_id, window_start_utc, window_end_utc, min_visibility_m, "
                        + "min_cloud_base_m, weather_version FROM weather_minima "
                        + "ORDER BY zone_id, window_start_utc",
                MINIMA_MAPPER);
    }

    /** 整体替换某区域的标准时段集合（先删后插，调用方负责事务与前置校验）。 */
    public void replaceMinima(String zoneId, List<WeatherMinimaPo> windows) {
        jdbc.update("DELETE FROM weather_minima WHERE zone_id = ?", zoneId);
        for (WeatherMinimaPo w : windows) {
            jdbc.update("INSERT INTO weather_minima "
                            + "(zone_id, window_start_utc, window_end_utc, min_visibility_m, "
                            + "min_cloud_base_m, weather_version) VALUES (?, ?, ?, ?, ?, ?)",
                    w.zoneId(), w.windowStartUtc(), w.windowEndUtc(),
                    w.minVisibilityM(), w.minCloudBaseM(), w.weatherVersion());
        }
    }

    /** 批量写入一个版本的预测条目（调用方负责事务与前置校验；createdAt 来自可控时钟）。 */
    public void insertForecasts(List<WeatherForecastPo> entries, long createdAt) {
        for (WeatherForecastPo e : entries) {
            jdbc.update("INSERT INTO weather_forecast "
                            + "(forecast_version, zone_id, window_start_utc, window_end_utc, "
                            + "visibility_m, cloud_base_m, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    e.forecastVersion(), e.zoneId(), e.windowStartUtc(), e.windowEndUtc(),
                    e.visibilityM(), e.cloudBaseM(), createdAt);
        }
    }

    /**
     * 查询截至指定版本、某区域某时段（起点与终点精确匹配）的最新预测，不存在返回 null。
     */
    public WeatherForecastPo findForecastAsOf(long version, String zoneId,
                                              long windowStartUtc, long windowEndUtc) {
        return jdbc.query(
                "SELECT forecast_version, zone_id, window_start_utc, window_end_utc, "
                        + "visibility_m, cloud_base_m FROM weather_forecast "
                        + "WHERE zone_id = ? AND window_start_utc = ? AND window_end_utc = ? "
                        + "AND forecast_version <= ? "
                        + "ORDER BY forecast_version DESC LIMIT 1",
                FORECAST_MAPPER, zoneId, windowStartUtc, windowEndUtc, version)
                .stream().findFirst().orElse(null);
    }

    /** 查询截至指定版本可见的全部预测（每区域每时段取最新版本，按 zoneId、起点升序）。 */
    public List<WeatherForecastPo> findForecastsAsOf(long version) {
        return jdbc.query(
                "SELECT f.forecast_version, f.zone_id, f.window_start_utc, f.window_end_utc, "
                        + "f.visibility_m, f.cloud_base_m FROM weather_forecast f "
                        + "JOIN (SELECT zone_id, window_start_utc, MAX(forecast_version) mv "
                        + "FROM weather_forecast WHERE forecast_version <= ? "
                        + "GROUP BY zone_id, window_start_utc) t "
                        + "ON f.zone_id = t.zone_id AND f.window_start_utc = t.window_start_utc "
                        + "AND f.forecast_version = t.mv "
                        + "ORDER BY f.zone_id, f.window_start_utc",
                FORECAST_MAPPER, version);
    }

    /** 插入占用记录（调用方负责事务）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO occupancy "
                        + "(occupancy_id, review_id, route_id, route_version, airspace_version, "
                        + "weather_version, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.weatherVersion(), po.requestId(), po.createdAt());
    }

    /** 按 occupancyId 查询占用记录，不存在返回 null。 */
    public OccupancyPo findOccupancy(String occupancyId) {
        return jdbc.query(
                "SELECT occupancy_id, review_id, route_id, route_version, airspace_version, "
                        + "weather_version, request_id, created_at FROM occupancy "
                        + "WHERE occupancy_id = ?",
                OCCUPANCY_MAPPER, occupancyId).stream().findFirst().orElse(null);
    }
}