package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 拓扑波次发布图数据访问。节点与边在创建发布单时写入并冻结，发布后不可改。
 * 边按字典序规范化存储（device_a < device_b），无自环与重复边。
 */
@Repository
public class TopologyRepository {

    /**
     * 无向边，deviceA 为字典序较小端点。
     */
    public record TopologyEdge(String deviceA, String deviceB) {
    }

    private final JdbcTemplate jdbc;

    public TopologyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertNode(long releaseId, String deviceId) {
        jdbc.update("INSERT INTO release_topology_node (release_id, device_id) VALUES (?, ?)",
                releaseId, deviceId);
    }

    public void insertEdge(long releaseId, String deviceA, String deviceB) {
        jdbc.update("INSERT INTO release_topology_edge (release_id, device_a, device_b) VALUES (?, ?, ?)",
                releaseId, deviceA, deviceB);
    }

    public List<String> findNodes(long releaseId) {
        return jdbc.query("SELECT device_id FROM release_topology_node WHERE release_id = ?"
                        + " ORDER BY device_id",
                (rs, rowNum) -> rs.getString("device_id"), releaseId);
    }

    public List<TopologyEdge> findEdges(long releaseId) {
        return jdbc.query("SELECT device_a, device_b FROM release_topology_edge WHERE release_id = ?"
                        + " ORDER BY device_a, device_b",
                (rs, rowNum) -> new TopologyEdge(rs.getString("device_a"), rs.getString("device_b")),
                releaseId);
    }
}
