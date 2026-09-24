package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.SuspectMarking;

/**
 * SUSPECT 标记持久化。标记只增不改；解除仅写入清除信息，保留可追溯历史。
 */
@Repository
public class SuspectMarkingRepository {

    private static final RowMapper<Long> ID_MAPPER = (rs, rowNum) -> rs.getLong("measurement_id");

    private static final RowMapper<SuspectMarking> MAPPER = (rs, rowNum) -> new SuspectMarking(
            rs.getLong("id"),
            rs.getLong("check_id"),
            rs.getLong("measurement_id"),
            JdbcTimes.fromDb(rs.getObject("marked_at", LocalDateTime.class)),
            (Long) rs.getObject("cleared_by_check_id", Long.class),
            JdbcTimes.fromDb(rs.getObject("cleared_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public SuspectMarkingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 将区间内全部已放行结果标记为该 FAIL 核查引入的 SUSPECT（须在事务内、已锁定相关测量行后调用）。
     * 只标记 status='RELEASED' 的结果；待放行结果由放行接口按区间直接拒绝。
     * 返回新写入的标记数。
     */
    public int markReleasedInRange(long checkId, String instrumentId,
                                   Instant from, Instant to, Instant markedAt) {
        return jdbc.update(
                "INSERT INTO suspect_marking (check_id, measurement_id, marked_at, cleared_by_check_id, cleared_at) "
                        + "SELECT ?, m.id, ?, NULL, NULL FROM measurement m "
                        + "WHERE m.instrument_id = ? AND m.measured_at >= ? AND m.measured_at < ? "
                        + "AND m.status = 'RELEASED' ORDER BY m.id",
                checkId, JdbcTimes.toDb(markedAt), instrumentId,
                JdbcTimes.toDb(from), JdbcTimes.toDb(to));
    }

    /** 该测量是否仍存在未解除的 SUSPECT 标记。 */
    public boolean hasActiveMarking(long measurementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suspect_marking WHERE measurement_id = ? AND cleared_by_check_id IS NULL",
                Integer.class, measurementId);
        return count != null && count > 0;
    }

    /**
     * 查询仍在隔离该测量的最早 FAIL 核查键（按 FAIL 核查时刻升序）；无未解除标记时为空。
     * 用于放行 409 响应指明触发的 checkKey。
     */
    public String findActiveBlockingCheckKey(long measurementId) {
        List<String> keys = jdbc.query(
                "SELECT c.check_key FROM suspect_marking s JOIN interim_check c ON c.id = s.check_id "
                        + "WHERE s.measurement_id = ? AND s.cleared_by_check_id IS NULL "
                        + "ORDER BY c.checked_at, c.id LIMIT 1",
                (rs, rowNum) -> rs.getString("check_key"), measurementId);
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** 某 FAIL 核查标记涉及的全部测量记录 ID（按测量 ID 升序）。 */
    public List<Long> findMeasurementIdsByCheckId(long checkId) {
        return jdbc.query("SELECT measurement_id FROM suspect_marking WHERE check_id = ? ORDER BY measurement_id",
                ID_MAPPER, checkId);
    }

    /** 查询某测量的全部 SUSPECT 标记历史（含已解除，按标记 ID 升序）。 */
    public List<SuspectMarking> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM suspect_marking WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }

    /**
     * 解除：把这批 FAIL 核查引入、且测量不再被其他未解除 FAIL 覆盖的 SUSPECT 标记置为已清除。
     * 仍被其他未解除 FAIL（不在本次解除集合内）覆盖的标记保留；证书撤销状态不参与计算。
     *
     * @param resolvedCheckIds 本次 PASS 覆盖解除的 FAIL 核查 ID
     * @param passCheckId      解除方 PASS 核查 ID
     * @param clearedAt        解除时间（UTC）
     * @return 实际清除的标记数
     */
    public int clearMarkings(List<Long> resolvedCheckIds, long passCheckId, Instant clearedAt) {
        if (resolvedCheckIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(resolvedCheckIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(passCheckId);
        args.add(JdbcTimes.toDb(clearedAt));
        args.addAll(resolvedCheckIds);
        args.addAll(resolvedCheckIds);
        return jdbc.update(
                "UPDATE suspect_marking SET cleared_by_check_id = ?, cleared_at = ? "
                        + "WHERE cleared_by_check_id IS NULL AND check_id IN (" + placeholders + ") "
                        + "AND NOT EXISTS ("
                        + "SELECT 1 FROM suspect_marking other WHERE other.measurement_id = suspect_marking.measurement_id "
                        + "AND other.cleared_by_check_id IS NULL AND other.check_id NOT IN (" + placeholders + "))",
                args.toArray());
    }
}
