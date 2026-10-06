package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 航线版本能量档案数据访问。
 * 耗能列表以逗号拼接文本保存，档案按 (routeId, routeVersion) 冻结、不可变。
 */
@Repository
public class EnergyProfileRepository {

    private final JdbcTemplate jdbc;

    public EnergyProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按航线与版本查询能量档案，不存在返回 null。 */
    public EnergyProfilePo findProfile(String routeId, int routeVersion) {
        return jdbc.query(
                "SELECT route_id, route_version, available_energy, reserve_energy, "
                        + "segment_costs, diversion_costs, operator, request_id, created_at "
                        + "FROM route_energy_profile WHERE route_id = ? AND route_version = ?",
                (rs, n) -> new EnergyProfilePo(
                        rs.getString("route_id"),
                        rs.getInt("route_version"),
                        rs.getLong("available_energy"),
                        rs.getLong("reserve_energy"),
                        decodeLongs(rs.getString("segment_costs")),
                        decodeLongs(rs.getString("diversion_costs")),
                        rs.getString("operator"),
                        rs.getString("request_id"),
                        rs.getLong("created_at")),
                routeId, routeVersion).stream().findFirst().orElse(null);
    }

    /** 插入能量档案（调用方负责事务；同航线同版本只允许插入一次）。 */
    public void insertProfile(EnergyProfilePo po) {
        jdbc.update("INSERT INTO route_energy_profile "
                        + "(route_id, route_version, available_energy, reserve_energy, "
                        + "segment_costs, diversion_costs, operator, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.routeId(), po.routeVersion(), po.availableEnergy(), po.reserveEnergy(),
                encodeLongs(po.segmentConsumptions()), encodeLongs(po.diversionEnergies()),
                po.operator(), po.requestId(), po.createdAt());
    }

    /** 长整型列表编码：逗号拼接；空列表编码为空串。 */
    public static String encodeLongs(List<Long> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    /** 长整型列表解码，空串返回空列表。 */
    public static List<Long> decodeLongs(String text) {
        List<Long> values = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String item : text.split(",")) {
                values.add(Long.parseLong(item));
            }
        }
        return values;
    }
}
