package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 圆形障碍档案数据访问。障碍版本从 1 起，修订/撤销通过条件更新推进版本，
 * 旧版本不可改；撤销后记录保留、不参与包络审查。
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
            rs.getInt("cx"),
            rs.getInt("cy"),
            rs.getInt("radius"),
            rs.getString("status"));

    private static final String COLUMNS =
            "obstacle_id, version, cx, cy, radius, status";

    /** 按 obstacleId 查询障碍当前版本，不存在返回 null。 */
    public ObstaclePo findObstacle(String obstacleId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM obstacle WHERE obstacle_id = ?",
                    OBSTACLE_MAPPER, obstacleId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部有效（ACTIVE）障碍，按 obstacleId 字典序，保证命中明细顺序稳定。 */
    public List<ObstaclePo> findActiveObstacles() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM obstacle WHERE status = 'ACTIVE' ORDER BY obstacle_id",
                OBSTACLE_MAPPER);
    }

    /** 登记障碍（初始版本 1，调用方负责事务与空域版本递增）。 */
    public void insertObstacle(ObstaclePo obstacle) {
        jdbc.update("INSERT INTO obstacle "
                        + "(obstacle_id, version, cx, cy, radius, status, touch) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                obstacle.obstacleId(), obstacle.version(), obstacle.cx(), obstacle.cy(),
                obstacle.radius(), obstacle.status());
    }

    /**
     * 条件修订障碍几何：仅当当前版本等于 expectedVersion 且仍有效时，
     * 更新圆心/半径并版本加一（同时推进 touch 持有行写锁）。
     *
     * @return 更新行数；0 表示版本不匹配或已撤销
     */
    public int reviseObstacle(String obstacleId, int expectedVersion,
                              int cx, int cy, int radius) {
        return jdbc.update(
                "UPDATE obstacle SET version = version + 1, cx = ?, cy = ?, radius = ?, "
                        + "touch = touch + 1 "
                        + "WHERE obstacle_id = ? AND version = ? AND status = 'ACTIVE'",
                cx, cy, radius, obstacleId, expectedVersion);
    }

    /**
     * 条件撤销障碍：仅当当前版本等于 expectedVersion 且仍有效时，
     * 置为 REVOKED 并版本加一（同时推进 touch 持有行写锁）。
     *
     * @return 更新行数；0 表示版本不匹配或已撤销
     */
    public int revokeObstacle(String obstacleId, int expectedVersion) {
        return jdbc.update(
                "UPDATE obstacle SET version = version + 1, status = 'REVOKED', "
                        + "touch = touch + 1 "
                        + "WHERE obstacle_id = ? AND version = ? AND status = 'ACTIVE'",
                obstacleId, expectedVersion);
    }
}
