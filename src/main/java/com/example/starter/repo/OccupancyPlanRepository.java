package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 航线版本穿越序列数据访问。仅当前版本的序列由容量占用统计 SQL 计入；
 * 旧版本序列保留，供转配证据与历史追溯。
 */
@Repository
public class OccupancyPlanRepository {

    private final JdbcTemplate jdbc;

    public OccupancyPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询某航线某版本的穿越序列（按 seq 排序）；无登记返回空列表。 */
    public List<PlanSlotPo> findPlan(String routeId, int routeVersion) {
        return jdbc.query(
                "SELECT seq, cell_x, cell_y, bucket_start FROM route_occupancy_plan "
                        + "WHERE route_id = ? AND route_version = ? ORDER BY seq",
                (rs, n) -> new PlanSlotPo(rs.getInt("seq"), rs.getInt("cell_x"),
                        rs.getInt("cell_y"), rs.getLong("bucket_start")),
                routeId, routeVersion);
    }

    /** 删除某航线某版本的全部序列项（调用方负责事务）。 */
    public void deletePlan(String routeId, int routeVersion) {
        jdbc.update("DELETE FROM route_occupancy_plan WHERE route_id = ? AND route_version = ?",
                routeId, routeVersion);
    }

    /** 写入某航线某版本的穿越序列（调用方负责事务，须先删除旧序列）。 */
    public void insertPlan(String routeId, int routeVersion, List<PlanSlotPo> items) {
        for (PlanSlotPo item : items) {
            jdbc.update("INSERT INTO route_occupancy_plan "
                            + "(route_id, route_version, seq, cell_x, cell_y, bucket_start) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    routeId, routeVersion, item.seq(), item.cellX(), item.cellY(),
                    item.bucketStart());
        }
    }

    /**
     * 按 (routeId, routeVersion) 批量读取当前序列，返回键到序列的映射。
     * 调用方保证键集合较小（2~20 条参与航线）。
     */
    public Map<String, List<PlanSlotPo>> findCurrentPlans(List<RoutePo> routes) {
        Map<String, List<PlanSlotPo>> result = new HashMap<>();
        for (RoutePo route : routes) {
            result.put(route.routeId(), findPlan(route.routeId(), route.version()));
        }
        return result;
    }
}
