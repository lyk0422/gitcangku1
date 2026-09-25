package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 跑道与跑道关闭窗口数据访问。
 */
@Repository
public class RunwayRepository {

    private final JdbcTemplate jdbc;

    public RunwayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RunwayPo> RUNWAY_MAPPER = (rs, n) -> new RunwayPo(
            rs.getString("runway_id"),
            rs.getInt("version"),
            rs.getInt("hourly_capacity"));

    private static final RowMapper<RunwayClosurePo> CLOSURE_MAPPER = (rs, n) -> new RunwayClosurePo(
            rs.getString("closure_id"),
            rs.getString("runway_id"),
            rs.getLong("start_utc"),
            rs.getLong("end_utc"),
            rs.getBoolean("allow_emergency"),
            rs.getString("operator"),
            rs.getString("closure_key"),
            rs.getInt("runway_version"),
            rs.getLong("created_at"));

    /** 按 runwayId 查询跑道，不存在返回 null。 */
    public RunwayPo findRunway(String runwayId) {
        try {
            return jdbc.queryForObject(
                    "SELECT runway_id, version, hourly_capacity FROM runway WHERE runway_id = ?",
                    RUNWAY_MAPPER, runwayId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对跑道行做真实更新（touch 加一）取得行级排他锁并读取跑道。
     * 与关闭窗口登记的版本推进互斥；跑道不存在返回 null。
     */
    public RunwayPo findRunwayForUpdate(String runwayId) {
        int locked = jdbc.update("UPDATE runway SET touch = touch + 1 WHERE runway_id = ?", runwayId);
        if (locked == 0) {
            return null;
        }
        return findRunway(runwayId);
    }

    /** 登记跑道（初始版本 0，调用方负责事务）。 */
    public void insertRunway(String runwayId, int hourlyCapacity) {
        jdbc.update("INSERT INTO runway (runway_id, version, hourly_capacity, touch) "
                + "VALUES (?, 0, ?, 0)", runwayId, hourlyCapacity);
    }

    /**
     * 条件推进跑道版本：仅当当前版本等于 expectedVersion 时加一（同时推进 touch 持行锁）。
     *
     * @return 更新行数；0 表示版本不匹配
     */
    public int compareAndIncrementVersion(String runwayId, int expectedVersion) {
        return jdbc.update("UPDATE runway SET version = version + 1, touch = touch + 1 "
                + "WHERE runway_id = ? AND version = ?", runwayId, expectedVersion);
    }

    /** 查询跑道全部关闭窗口（按开始时刻、closureId 升序）。 */
    public List<RunwayClosurePo> findClosures(String runwayId) {
        return jdbc.query("SELECT closure_id, runway_id, start_utc, end_utc, allow_emergency, "
                        + "operator, closure_key, runway_version, created_at "
                        + "FROM runway_closure WHERE runway_id = ? "
                        + "ORDER BY start_utc, closure_id",
                CLOSURE_MAPPER, runwayId);
    }

    /** 插入关闭窗口（调用方负责事务与重叠校验）。 */
    public void insertClosure(RunwayClosurePo po) {
        jdbc.update("INSERT INTO runway_closure "
                        + "(closure_id, runway_id, start_utc, end_utc, allow_emergency, "
                        + "operator, closure_key, runway_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.closureId(), po.runwayId(), po.startUtc(), po.endUtc(), po.allowEmergency(),
                po.operator(), po.closureKey(), po.runwayVersion(), po.createdAt());
    }
}
