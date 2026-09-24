package com.example.starter.calibration.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 测量结果 SUSPECT 隔离标记持久化。每条标记表示“某 FAIL 核查把某已放行结果隔离”；
 * 仅可随更晚 PASS 解除而清除（cleared=TRUE），历史保留不删除。
 * 一个结果可被多个 FAIL 同时覆盖，只有不存在未清除标记时才恢复当前可用资格。
 */
@Repository
public class SuspectMarkerRepository {

    private final JdbcTemplate jdbc;

    public SuspectMarkerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 为指定 FAIL 核查写入一条逐结果隔离标记。调用方已持仪器锁，同一 (结果,核查) 仅插入一次。
     */
    public void insert(long measurementId, long checkId, Instant createdAt) {
        jdbc.update("INSERT INTO measurement_suspect "
                        + "(measurement_id, check_id, cleared, created_at) VALUES (?, ?, FALSE, ?)",
                measurementId, checkId, JdbcTimes.toDb(createdAt));
    }

    /**
     * 查询某 FAIL 核查对应的全部被标记测量记录 ID（按 id 升序，去重）。
     */
    public List<Long> findMeasurementIdsByCheck(long checkId) {
        return jdbc.queryForList(
                "SELECT DISTINCT measurement_id FROM measurement_suspect WHERE check_id = ? ORDER BY measurement_id",
                Long.class, checkId);
    }

    /**
     * 随 PASS 解除：清除指定 FAIL 核查引入的未清除标记（须在事务内调用）。
     */
    public void clearByCheck(long checkId, long passCheckId, Instant clearedAt) {
        jdbc.update("UPDATE measurement_suspect SET cleared = TRUE, cleared_by_check_id = ?, cleared_at = ? "
                        + "WHERE check_id = ? AND cleared = FALSE",
                passCheckId, JdbcTimes.toDb(clearedAt), checkId);
    }

    /**
     * 统计某测量仍存在的未清除隔离标记数。
     */
    public int countOpenMarkers(long measurementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_suspect WHERE measurement_id = ? AND cleared = FALSE",
                Integer.class, measurementId);
        return count == null ? 0 : count;
    }
}
