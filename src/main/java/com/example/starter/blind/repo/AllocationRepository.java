package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分配数据访问；seat_no 与 treatment 仅在库内与揭盲流程使用，不进入普通响应与日志。
 */
@Repository
public class AllocationRepository {

    /** 分配行（含盲底，仅供服务层内部使用）。 */
    public record AllocationRow(
            long id,
            String experimentId,
            String participantId,
            int blockNo,
            int seatNo,
            String blindCode,
            String status,
            String assignedActor,
            long assignedAt,
            Long withdrawnAt,
            String urgentReview,
            Long unblindedAt) {
    }

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("blind_code"),
            rs.getString("status"),
            rs.getString("assigned_actor"),
            rs.getLong("assigned_at"),
            (Long) rs.getObject("withdrawn_at"),
            rs.getString("urgent_review"),
            (Long) rs.getObject("unblinded_at"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, block_no, seat_no, blind_code, status, "
                    + "assigned_actor, assigned_at, withdrawn_at, urgent_review, unblinded_at";

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AllocationRow row) {
        jdbc.update("INSERT INTO allocation ("
                        + "experiment_id, participant_id, block_no, seat_no, blind_code, "
                        + "status, assigned_actor, assigned_at, withdrawn_at, urgent_review, unblinded_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.blockNo(), row.seatNo(), row.blindCode(),
                row.status(), row.assignedActor(), row.assignedAt(), row.withdrawnAt(),
                row.urgentReview(), row.unblindedAt());
    }

    public AllocationRow findByExperimentAndParticipant(String experimentId, String participantId) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                ALLOCATION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定分配，串行化紧急揭盲、常规批准与退组对同一分配的并发裁决。
     */
    public AllocationRow lockById(long allocationId) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation WHERE id = ? FOR UPDATE",
                ALLOCATION_MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 原子领取按区组、席位顺序排列的第一个空位：把尚未被占用的最小 block_no/seat_no
     * 关联给新参与者。依赖 allocation(experiment_id, block_no, seat_no) 唯一索引兜底并发。
     *
     * @return 领取到的席位；实验满额时返回 null
     */
    public VacantSeat takeFirstVacantSeat(String experimentId) {
        List<VacantSeat> seats = jdbc.query(
                "SELECT s.block_no, s.seat_no FROM seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no "
                        + "AND a.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? AND a.id IS NULL "
                        + "ORDER BY s.block_no, s.seat_no LIMIT 1",
                (rs, n) -> new VacantSeat(rs.getInt(1), rs.getInt(2)),
                experimentId);
        return seats.isEmpty() ? null : seats.get(0);
    }

    /** 原子领取结果。 */
    public record VacantSeat(int blockNo, int seatNo) {
    }

    public long countOccupied(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = ?", Long.class, experimentId);
        return count == null ? 0 : count;
    }

    /**
     * 退组：仅 ASSIGNED -> WITHDRAWN，不释放席位。
     *
     * @return 受影响行数；0 表示不存在或已退组
     */
    public int markWithdrawn(long allocationId, long withdrawnAt) {
        return jdbc.update(
                "UPDATE allocation SET status = 'WITHDRAWN', withdrawn_at = ? "
                        + "WHERE id = ? AND status = 'ASSIGNED'",
                withdrawnAt, allocationId);
    }

    /**
     * 插入时若参与者已在同实验占席，抛出由唯一约束触发的 DuplicateKeyException。
     */
    public boolean isDuplicateParticipant(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_allocation_participant");
    }

    public boolean isDuplicateSeat(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_allocation_seat");
    }

    public boolean isDuplicateBlindCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_allocation_blind_code");
    }

    /**
     * SEVERE 不良事件上报后标记分配为 URGENT_REVIEW；已标记时幂等。
     */
    public void markUrgentReview(long allocationId) {
        jdbc.update("UPDATE allocation SET urgent_review = 'Y' WHERE id = ?", allocationId);
    }

    /**
     * 原子完成揭盲：写入揭盲时间并复位 URGENT_REVIEW 标记；仅未揭盲时生效。
     *
     * @return 受影响行数；0 表示该分配已揭盲
     */
    public int completeUnblind(long allocationId, long unblindedAt) {
        return jdbc.update(
                "UPDATE allocation SET unblinded_at = ?, urgent_review = 'N' "
                        + "WHERE id = ? AND unblinded_at IS NULL",
                unblindedAt, allocationId);
    }

    /**
     * 查询实验内处于 URGENT_REVIEW 的分配清单（按分配时间升序）。
     */
    public List<AllocationRow> findUrgentReviewByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation "
                        + "WHERE experiment_id = ? AND urgent_review = 'Y' ORDER BY assigned_at, id",
                ALLOCATION_MAPPER, experimentId);
    }
}
