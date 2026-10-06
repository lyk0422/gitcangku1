package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 交叉实验阶段数据访问；只保存阶段时间戳与版本号，阶段状态由时间戳派生。
 * 处理代码不落本表：第一阶段沿用席位隐藏代码，第二阶段为其对侧代码，均不出现在普通响应。
 */
@Repository
public class CrossoverPhaseRepository {

    /**
     * 交叉阶段行。各时间戳为 Unix 毫秒 UTC，NULL 表示该事件尚未发生；
     * version 为已成功阶段迁移次数（0~4），用于期望版本校验。
     */
    public record CrossoverPhaseRow(
            long allocationId,
            String experimentId,
            String participantId,
            Long firstStartedAt,
            Long firstEndedAt,
            Long secondStartedAt,
            Long secondEndedAt,
            int version) {
    }

    private static final RowMapper<CrossoverPhaseRow> MAPPER = (rs, n) -> new CrossoverPhaseRow(
            rs.getLong("allocation_id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            (Long) rs.getObject("first_started_at"),
            (Long) rs.getObject("first_ended_at"),
            (Long) rs.getObject("second_started_at"),
            (Long) rs.getObject("second_ended_at"),
            rs.getInt("version"));

    private static final String COLUMNS =
            "allocation_id, experiment_id, participant_id, first_started_at, first_ended_at, "
                    + "second_started_at, second_ended_at, version";

    private final JdbcTemplate jdbc;

    public CrossoverPhaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记交叉实验参与者时同步创建阶段行，初始无任何时间戳、版本为 0。
     */
    public void insert(long allocationId, String experimentId, String participantId) {
        jdbc.update("INSERT INTO crossover_phase ("
                        + "allocation_id, experiment_id, participant_id, first_started_at, first_ended_at, "
                        + "second_started_at, second_ended_at, version"
                        + ") VALUES (?, ?, ?, NULL, NULL, NULL, NULL, 0)",
                allocationId, experimentId, participantId);
    }

    public CrossoverPhaseRow findByAllocationId(long allocationId) {
        List<CrossoverPhaseRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM crossover_phase WHERE allocation_id = ?",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 开始第一阶段；仅当尚未开始时生效。
     *
     * @return 受影响行数；0 表示已开始过（每阶段至多开始一次）
     */
    public int startFirst(long allocationId, long startedAt) {
        return jdbc.update("UPDATE crossover_phase SET first_started_at = ?, version = version + 1 "
                + "WHERE allocation_id = ? AND first_started_at IS NULL", startedAt, allocationId);
    }

    /**
     * 结束第一阶段；仅当已开始且未结束时生效。
     *
     * @return 受影响行数；0 表示未开始或已结束
     */
    public int endFirst(long allocationId, long endedAt) {
        return jdbc.update("UPDATE crossover_phase SET first_ended_at = ?, version = version + 1 "
                        + "WHERE allocation_id = ? AND first_started_at IS NOT NULL AND first_ended_at IS NULL",
                endedAt, allocationId);
    }

    /**
     * 开始第二阶段；仅当第一阶段已结束且第二阶段未开始时生效。
     *
     * @return 受影响行数；0 表示前置条件不满足（每阶段至多开始一次）
     */
    public int startSecond(long allocationId, long startedAt) {
        return jdbc.update("UPDATE crossover_phase SET second_started_at = ?, version = version + 1 "
                        + "WHERE allocation_id = ? AND first_ended_at IS NOT NULL AND second_started_at IS NULL",
                startedAt, allocationId);
    }

    /**
     * 结束第二阶段；仅当已开始且未结束时生效。
     *
     * @return 受影响行数；0 表示未开始或已结束
     */
    public int endSecond(long allocationId, long endedAt) {
        return jdbc.update("UPDATE crossover_phase SET second_ended_at = ?, version = version + 1 "
                        + "WHERE allocation_id = ? AND second_started_at IS NOT NULL AND second_ended_at IS NULL",
                endedAt, allocationId);
    }
}
