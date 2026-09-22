package com.example.starter.dao;

import com.example.starter.domain.Point;
import com.example.starter.domain.RouteRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 航线表访问：点列以 JSON 存储；替换点列时版本加一。
 */
@Repository
public class RouteDao {

    private static final TypeReference<List<Point>> POINT_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RouteDao(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 按主键查询航线（不加锁，仅用于只读查询）。
     */
    public Optional<RouteRecord> findById(String routeId) {
        return jdbc.query("SELECT * FROM routes WHERE route_id = ?", this::map, routeId)
                .stream().findFirst();
    }

    /**
     * 排他锁定航线行并读取；须在事务内调用，用于串行化替换与审核。
     */
    public Optional<RouteRecord> lockById(String routeId) {
        return jdbc.query("SELECT * FROM routes WHERE route_id = ? FOR UPDATE", this::map, routeId)
                .stream().findFirst();
    }

    /**
     * 插入新航线，初始版本为 1；routeId 重复时由主键约束抛出异常。
     */
    public void insert(String routeId, List<Point> points, Instant now) {
        jdbc.update("INSERT INTO routes (route_id, version, points, created_at, updated_at)"
                        + " VALUES (?, 1, ?, ?, ?)",
                routeId, writePoints(points), Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 替换点列并将版本加一；须在持有航线行排他锁的事务内调用。
     */
    public void replacePoints(String routeId, List<Point> points, Instant now) {
        jdbc.update("UPDATE routes SET version = version + 1, points = ?, updated_at = ? WHERE route_id = ?",
                writePoints(points), Timestamp.from(now), routeId);
    }

    private RouteRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RouteRecord(
                rs.getString("route_id"),
                rs.getInt("version"),
                readPoints(rs.getString("points")));
    }

    private String writePoints(List<Point> points) {
        try {
            return objectMapper.writeValueAsString(points);
        } catch (Exception e) {
            throw new IllegalStateException("序列化航线点列失败", e);
        }
    }

    private List<Point> readPoints(String json) {
        try {
            return objectMapper.readValue(json, POINT_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化航线点列失败", e);
        }
    }
}
