package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 气象最低标准与预测气象数据访问。
 * 版本行（weather_meta）的读取与推进必须在业务事务内配合协调锁使用，
 * 与审核事务互斥，保证审核固化的版本号与标准/预测内容来自同一已提交状态。
 */
@Repository
public class WeatherRepository {

    private final JdbcTemplate jdbc;

    public WeatherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WeatherMinimaPo> MINIMA_MAPPER = (rs, n) -> new WeatherMinimaPo(
            rs.getString("zone_id"),
            rs.getLong("effective_from"),
            rs.getLong("effective_to"),
            rs.getInt("min_visibility_m"),
            rs.getInt("min_cloud_base_m"),
            rs.getLong("standards_version"));

    private static final RowMapper<WeatherForecastPo> FORECAST_MAPPER = (rs, n) -> new WeatherForecastPo(
            rs.getLong("forecast_version"),
            rs.getString("zone_id"),
            rs.getLong("valid_from"),
            rs.getLong("valid_to"),
            rs.getInt("visibility_m"),
            rs.getInt("cloud_base_m"));

    /** 读取当前气象标准版本（单行）。 */
    public long getStandardsVersion() {
        Long version = jdbc.queryForObject(
                "SELECT standards_version FROM weather_meta WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /** 读取当前预测气象版本（单行）。 */
    public long getForecastVersion() {
        Long version = jdbc.queryForObject(
                "SELECT forecast_version FROM weather_meta WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /** 原子地将气象标准版本加一并返回新版本（调用方负责事务与协调锁）。 */
    public long incrementStandardsVersion() {
        jdbc.update("UPDATE weather_meta SET standards_version = standards_version + 1 WHERE id = 1");
        return getStandardsVersion();
    }

    /** 原子地将预测气象版本加一并返回新版本（调用方负责事务与协调锁）。 */
    public long incrementForecastVersion() {
        jdbc.update("UPDATE weather_meta SET forecast_version = forecast_version + 1 WHERE id = 1");
        return getForecastVersion();
    }

    /** 查询某区域当前生效的全部最低标准时段，按起点升序（稳定排序）。 */
    public List<WeatherMinimaPo> findMinima(String zoneId) {
        return jdbc.query(
                "SELECT zone_id, effective_from, effective_to, min_visibility_m, min_cloud_base_m, "
                        + "standards_version FROM weather_minima "
                        + "WHERE zone_id = ? ORDER BY effective_from, effective_to",
                MINIMA_MAPPER, zoneId);
    }

    /** 删除某区域全部最低标准时段（整体替换前调用，调用方负责事务）。 */
    public void deleteMinima(String zoneId) {
        jdbc.update("DELETE FROM weather_minima WHERE zone_id = ?", zoneId);
    }

    /** 写入一条最低标准时段（调用方负责事务）。 */
    public void insertMinima(WeatherMinimaPo po) {
        jdbc.update("INSERT INTO weather_minima "
                        + "(zone_id, effective_from, effective_to, min_visibility_m, min_cloud_base_m, "
                        + "standards_version) VALUES (?, ?, ?, ?, ?, ?)",
                po.zoneId(), po.effectiveFrom(), po.effectiveTo(),
                po.minVisibilityM(), po.minCloudBaseM(), po.standardsVersion());
    }

    /** 查询指定预测版本的全部记录，按 zoneId、起点升序（稳定排序）。 */
    public List<WeatherForecastPo> findForecast(long forecastVersion) {
        return jdbc.query(
                "SELECT forecast_version, zone_id, valid_from, valid_to, visibility_m, cloud_base_m "
                        + "FROM weather_forecast WHERE forecast_version = ? "
                        + "ORDER BY zone_id, valid_from, valid_to",
                FORECAST_MAPPER, forecastVersion);
    }

    /** 查询指定预测版本、指定区域的全部记录，按起点升序（稳定排序）。 */
    public List<WeatherForecastPo> findForecastForZone(long forecastVersion, String zoneId) {
        return jdbc.query(
                "SELECT forecast_version, zone_id, valid_from, valid_to, visibility_m, cloud_base_m "
                        + "FROM weather_forecast WHERE forecast_version = ? AND zone_id = ? "
                        + "ORDER BY valid_from, valid_to",
                FORECAST_MAPPER, forecastVersion, zoneId);
    }

    /** 写入一条预测记录（调用方负责事务）。 */
    public void insertForecast(WeatherForecastPo po) {
        jdbc.update("INSERT INTO weather_forecast "
                        + "(forecast_version, zone_id, valid_from, valid_to, visibility_m, cloud_base_m) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                po.forecastVersion(), po.zoneId(), po.validFrom(), po.validTo(),
                po.visibilityM(), po.cloudBaseM());
    }
}
