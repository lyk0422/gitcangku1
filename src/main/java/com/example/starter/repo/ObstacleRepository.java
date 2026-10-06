package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 圆形障碍档案数据访问。只保存当前版本行，旧版本不可改；
 * 版本推进与全局空域版本推进由调用方在同一事务内完成。
 */
@Repository
public class ObstacleRepository {

    private final JdbcTemplate jdbc;

    public ObstacleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ObstaclePo> OBSTACLE_MAPPER = (rs, n) -> new ObstaclePo(
            rs.getString("obstacle_id"),
            rs.getInt("version"),
            rs.getInt("center_x"),
            rs.getInt("center_y"),
            rs.getInt("radius"),
            rs.getString("status"));

    /** 按 obstacleId 查询障碍当前版本，不存在返回 null。 */
    public ObstaclePo findObstacle(String obstacleId) {
        try {
            return jdbc.queryForObject(
                    "SELECT obstacle_id, version, center_x, center_y, radius, status "
                            + "FROM obstacle WHERE obstacle_id = ?", OBSTACLE_MAPPER, obstacleId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部有效（ACTIVE）障碍，按 obstacleId 字典序，保证审查结果确定。 */
    public List<ObstaclePo> findActiveObstacles() {
        return jdbc.query(
                "SELECT obstacle_id, version, center_x, center_y, radius, status "
                        + "FROM obstacle WHERE status = 'ACTIVE' ORDER BY obstacle_id",
                OBSTACLE_MAPPER);
    }

    /** 登记障碍（初始版本 1，调用方负责事务与全局版本递增）。 */
    public void insertObstacle(ObstaclePo obstacle) {
        jdbc.update("INSERT INTO obstacle "
                        + "(obstacle_id, version, center_x, center_y, radius, status, touch) "
                        + "VALUES (?, 1, ?, ?, ?, ?, 0)",
                obstacle.obstacleId(), obstacle.centerX(), obstacle.centerY(),
                obstacle.radius(), obstacle.status());
    }

    /**
     * 条件修订障碍圆心与半径：仅当当前版本等于 expectedVersion 且仍有效时，
     * 版本加一并更新几何（同时推进 touch 持有行写锁）。
     *
     * @return 更新行数；0 表示版本不匹配或已撤销
     */
    public int compareAndRevise(String obstacleId, int expectedVersion,
                                int centerX, int centerY, int radius) {
        return jdbc.update(
                "UPDATE obstacle SET version = version + 1, center_x = ?, center_y = ?, "
                        + "radius = ?, touch = touch + 1 "
                        + "WHERE obstacle_id = ? AND version = ? AND status = 'ACTIVE'",
                centerX, centerY, radius, obstacleId, expectedVersion);
    }

    /**
     * 条件撤销障碍：仅当当前版本等于 expectedVersion 且仍有效时，
     * 版本加一并置为 REVOKED（同时推进 touch 持有行写锁）。
     *
     * @return 更新行数；0 表示版本不匹配或已撤销
     */
    public int compareAndRevoke(String obstacleId, int expectedVersion) {
        return jdbc.update(
                "UPDATE obstacle SET version = version + 1, status = 'REVOKED', "
                        + "touch = touch + 1 "
                        + "WHERE obstacle_id = ? AND version = ? AND status = 'ACTIVE'",
                obstacleId, expectedVersion);
    }
}
