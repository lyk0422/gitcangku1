package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * TOPOLOGY 发布单绑定的设备集合与无向邻接边数据访问。发布成功后图冻结不可改，只写一次。
 */
@Repository
public class TopologyRepository {

    /**
     * 无向边，规范化存储（deviceA 字典序小于 deviceB）。
     */
    public record Edge(String deviceA, String deviceB) {

        public static Edge of(String a, String b) {
            return a.compareTo(b) < 0 ? new Edge(a, b) : new Edge(b, a);
        }
    }

    private final JdbcTemplate jdbc;

    public TopologyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDevice(long releaseId, String deviceId) {
        jdbc.update("INSERT INTO release_topology_device (release_id, device_id) VALUES (?, ?)",
                releaseId, deviceId);
    }

    public void insertEdge(long releaseId, Edge edge) {
        jdbc.update("INSERT INTO release_topology_edge (release_id, device_a, device_b) VALUES (?, ?, ?)",
                releaseId, edge.deviceA(), edge.deviceB());
    }

    /**
     * 发布单绑定的设备ID，按设备ID升序。
     */
    public List<String> findDeviceIds(long releaseId) {
        return jdbc.query("SELECT device_id FROM release_topology_device WHERE release_id = ?"
                        + " ORDER BY device_id",
                (rs, rowNum) -> rs.getString("device_id"), releaseId);
    }

    /**
     * 发布单的邻接边，按端点升序。
     */
    public List<Edge> findEdges(long releaseId) {
        return jdbc.query("SELECT device_a, device_b FROM release_topology_edge WHERE release_id = ?"
                        + " ORDER BY device_a, device_b",
                (rs, rowNum) -> new Edge(rs.getString("device_a"), rs.getString("device_b")), releaseId);
    }
}
