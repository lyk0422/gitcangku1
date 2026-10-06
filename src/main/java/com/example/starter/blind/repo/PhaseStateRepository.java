package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 交叉实验阶段状态数据访问；阶段迁移以条件更新兜底，配合实验行锁串行化。
 * 表内不保存任何处理代码：第一阶段处理为席位原隐藏代码，第二阶段为另一代码，均由席位映射推导。
 */
@Repository
public class PhaseStateRepository {

    /** 阶段状态行；时间字段为 Unix 毫秒 UTC，NULL 表示尚未发生。 */
    public record PhaseStateRow(
            long allocationId,
            String experimentId,
            String participantId,
            String status,
            int version,
            Long firstStartedAt,
            Long firstEndedAt,
            Long secondStartedAt,
            Long secondEndedAt) {
    }

    private static final String COLUMNS =
            "allocation_id, experiment_id, participant_id, status, version, "
                    + "first_started_at, first_ended_at, second_started_at, second_ended_at";

    private static final RowMapper<PhaseStateRow> MAPPER = (rs, n) -> new PhaseStateRow(
            rs.getLong("allocation_id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("status"),
            rs.getInt("version"),
            (Long) rs.getObject("first_started_at"),
            (Long) rs.getObject("first_ended_at"),
            (Long) rs.getObject("second_started_at"),
            (Long) rs.getObject("second_ended_at"));

    private final JdbcTemplate jdbc;

    public PhaseStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记交叉实验参与者时创建初始阶段行（NOT_STARTED，版本 0）。 */
    public void insert(PhaseStateRow row) {
        jdbc.update("INSERT INTO phase_state ("
                        + "allocation_id, experiment_id, participant_id, status, version, "
                        + "first_started_at, first_ended_at, second_started_at, second_ended_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.allocationId(), row.experimentId(), row.participantId(), row.status(),
                row.version(), row.firstStartedAt(), row.firstEndedAt(),
                row.secondStartedAt(), row.secondEndedAt());
    }

    public PhaseStateRow findByAllocationId(long allocationId) {
        List<PhaseStateRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM phase_state WHERE allocation_id = ?",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定阶段状态；与实验行锁配合，串行化阶段操作、退组与关闭。 */
    public PhaseStateRow lockByAllocationId(long allocationId) {
        List<PhaseStateRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM phase_state WHERE allocation_id = ? FOR UPDATE",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 第一阶段开始：仅 NOT_STARTED 可迁移，返回受影响行数。 */
    public int markFirstStarted(long allocationId, long startedAt) {
        return jdbc.update("UPDATE phase_state SET status = 'FIRST_RUNNING', "
                        + "first_started_at = ?, version = version + 1 "
                        + "WHERE allocation_id = ? AND status = 'NOT_STARTED'",
                startedAt, allocationId);
    }

    /** 第一阶段结束并进入洗脱：仅 FIRST_RUNNING 可迁移，返回受影响行数。 */
    public int markFirstEnded(long allocationId, long endedAt) {
        return jdbc.update("UPDATE phase_state SET status = 'WASHOUT', "
                        + "first_ended_at = ?, version = version + 1 "
                        + "WHERE allocation_id = ? AND status = 'FIRST_RUNNING'",
                endedAt, allocationId);
    }

    /** 第二阶段开始：仅 WASHOUT 可迁移，返回受影响行数。 */
    public int markSecondStarted(long allocationId, long startedAt) {
        return jdbc.update("UPDATE phase_state SET status = 'SECOND_RUNNING', "
                        + "second_started_at = ?, version = version + 1 "
                        + "WHERE allocation_id = ? AND status = 'WASHOUT'",
                startedAt, allocationId);
    }

    /** 第二阶段结束并完成：仅 SECOND_RUNNING 可迁移，返回受影响行数。 */
    public int markSecondEnded(long allocationId, long endedAt) {
        return jdbc.update("UPDATE phase_state SET status = 'DONE', "
                        + "second_ended_at = ?, version = version + 1 "
                        + "WHERE allocation_id = ? AND status = 'SECOND_RUNNING'",
                endedAt, allocationId);
    }
}
