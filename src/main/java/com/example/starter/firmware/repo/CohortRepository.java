package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Cohort;
import com.example.starter.firmware.domain.CohortRegion;
import com.example.starter.firmware.domain.ReceiptResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 投放队列与区域配额数据访问。
 * 迁移校验与提交、回执入账、自动暂停共用 cohort 行锁（SELECT ... FOR UPDATE）形成一致提交顺序；
 * 队列级统计（success_count/failed_count）与暂停位只在持有行锁的事务内变更。
 */
@Repository
public class CohortRepository {

    private static final RowMapper<CohortRegion> REGION_MAPPER = (rs, rowNum) -> new CohortRegion(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("region_code"), rs.getInt("quota"));

    private static final RowMapper<Cohort> COHORT_MAPPER = (rs, rowNum) -> new Cohort(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("cohort_code"),
            rs.getString("firmware_version"), rs.getString("region_code"),
            rs.getInt("device_cap"), rs.getInt("canary_percent"),
            rs.getInt("sample_floor"), rs.getInt("failure_threshold_percent"),
            rs.getInt("success_count"), rs.getInt("failed_count"), rs.getBoolean("paused"));

    private static final String COHORT_COLUMNS = "id, release_id, cohort_code, firmware_version, region_code,"
            + " device_cap, canary_percent, sample_floor, failure_threshold_percent,"
            + " success_count, failed_count, paused";

    private final JdbcTemplate jdbc;

    public CohortRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertRegion(long releaseId, String regionCode, int quota) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cohort_region (release_id, region_code, quota) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, regionCode);
            ps.setInt(3, quota);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertCohort(long releaseId, String cohortCode, String firmwareVersion, String regionCode,
                             int deviceCap, int canaryPercent, int sampleFloor, int failureThresholdPercent) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cohort (release_id, cohort_code, firmware_version, region_code, device_cap,"
                            + " canary_percent, sample_floor, failure_threshold_percent)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, cohortCode);
            ps.setString(3, firmwareVersion);
            ps.setString(4, regionCode);
            ps.setInt(5, deviceCap);
            ps.setInt(6, canaryPercent);
            ps.setInt(7, sampleFloor);
            ps.setInt(8, failureThresholdPercent);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Cohort> findCohortById(long id) {
        return jdbc.query("SELECT " + COHORT_COLUMNS + " FROM cohort WHERE id = ?", COHORT_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Cohort> findCohortByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COHORT_COLUMNS + " FROM cohort WHERE id = ? FOR UPDATE",
                COHORT_MAPPER, id).stream().findFirst();
    }

    /**
     * 锁定并读取一个活动内的全部队列；迁移完整后态校验必须在持有这些行锁时进行。
     */
    public List<Cohort> findCohortsByReleaseForUpdate(long releaseId) {
        return jdbc.query("SELECT " + COHORT_COLUMNS + " FROM cohort WHERE release_id = ? ORDER BY id FOR UPDATE",
                COHORT_MAPPER, releaseId);
    }

    /**
     * 只读读取一个活动内的全部队列（预览用，不加行锁）。
     */
    public List<Cohort> findCohortsByRelease(long releaseId) {
        return jdbc.query("SELECT " + COHORT_COLUMNS + " FROM cohort WHERE release_id = ? ORDER BY id",
                COHORT_MAPPER, releaseId);
    }

    public List<CohortRegion> findRegionsByReleaseForUpdate(long releaseId) {
        return jdbc.query(
                "SELECT id, release_id, region_code, quota FROM cohort_region WHERE release_id = ? ORDER BY id"
                        + " FOR UPDATE", REGION_MAPPER, releaseId);
    }

    /**
     * 只读读取一个活动内的全部区域配额（预览/建档校验用，不加行锁）。
     */
    public List<CohortRegion> findRegionsByRelease(long releaseId) {
        return jdbc.query(
                "SELECT id, release_id, region_code, quota FROM cohort_region WHERE release_id = ? ORDER BY id",
                REGION_MAPPER, releaseId);
    }

    /**
     * 某队列当前已分配设备数。
     */
    public int countAssignmentsByCohort(long cohortId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cohort_assignment WHERE cohort_id = ?", Integer.class, cohortId);
        return count == null ? 0 : count;
    }

    /**
     * 活动内各队列当前已分配设备数（cohortId -&gt; 设备数），迁移完整后态在持有队列行锁时据此计算。
     */
    public java.util.Map<Long, Integer> countAssignmentsByReleaseGrouped(long releaseId) {
        var map = new java.util.HashMap<Long, Integer>();
        jdbc.query("SELECT cohort_id, COUNT(*) AS cnt FROM cohort_assignment WHERE release_id = ?"
                + " GROUP BY cohort_id", (rs, rowNum) -> {
            map.put(rs.getLong("cohort_id"), rs.getInt("cnt"));
            return null;
        }, releaseId);
        return map;
    }

    /**
     * 队列当前轮次统计加一（成功或失败），调用方必须已持有该队列行锁。
     */
    public void incrementCohortStats(long cohortId, ReceiptResult result) {
        if (result == ReceiptResult.SUCCESS) {
            jdbc.update("UPDATE cohort SET success_count = success_count + 1, updated_at = CURRENT_TIMESTAMP"
                    + " WHERE id = ?", cohortId);
        } else {
            jdbc.update("UPDATE cohort SET failed_count = failed_count + 1, updated_at = CURRENT_TIMESTAMP"
                    + " WHERE id = ?", cohortId);
        }
    }

    /**
     * 仅当队列尚未暂停时置暂停位，返回影响行数；调用方已持有行锁，并发回执至多一行生效。
     */
    public int pauseIfNotPaused(long cohortId) {
        return jdbc.update("UPDATE cohort SET paused = TRUE, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND paused = FALSE", cohortId);
    }

    /**
     * 人工恢复：清除暂停位并清零本轮统计（开启新监控轮次）。
     */
    public int resumeCohort(long cohortId) {
        return jdbc.update("UPDATE cohort SET paused = FALSE, success_count = 0, failed_count = 0,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND paused = TRUE", cohortId);
    }
}
