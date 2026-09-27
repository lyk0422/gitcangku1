package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 占用数据访问。占用记录不可变，只增不改。
 */
@Repository
public class OccupationRepository {

    private final JdbcTemplate jdbc;

    public OccupationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<OccupationPo> MAPPER = (rs, n) -> new OccupationPo(
            rs.getString("occupation_id"),
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            (Long) rs.getObject("forecast_version"),
            (Long) rs.getObject("standards_version"),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    /** 按 occupationId 查询占用记录，不存在返回 null。 */
    public OccupationPo findOccupation(String occupationId) {
        return jdbc.query(
                "SELECT occupation_id, review_id, route_id, route_version, airspace_version, "
                        + "forecast_version, standards_version, request_id, created_at "
                        + "FROM occupation WHERE occupation_id = ?",
                MAPPER, occupationId).stream().findFirst().orElse(null);
    }

    /** 查询某航线的全部占用记录，按创建时间、occupationId 升序（稳定排序）。 */
    public List<OccupationPo> findByRoute(String routeId) {
        return jdbc.query(
                "SELECT occupation_id, review_id, route_id, route_version, airspace_version, "
                        + "forecast_version, standards_version, request_id, created_at "
                        + "FROM occupation WHERE route_id = ? "
                        + "ORDER BY created_at, occupation_id",
                MAPPER, routeId);
    }

    /** 插入不可变占用记录（调用方负责事务）。 */
    public void insertOccupation(OccupationPo po) {
        jdbc.update("INSERT INTO occupation "
                        + "(occupation_id, review_id, route_id, route_version, airspace_version, "
                        + "forecast_version, standards_version, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupationId(), po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.forecastVersion(), po.standardsVersion(),
                po.requestId(), po.createdAt());
    }
}
