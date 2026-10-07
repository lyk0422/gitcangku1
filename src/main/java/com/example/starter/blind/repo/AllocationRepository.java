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

    /**
     * 分配行（含盲底，仅供服务层内部使用）。
     * feature1/feature2 为最小化分配的枚举特征（0/1）；BLOCK 模式为 null。
     */
    public record AllocationRow(
            long id,
            String experimentId,
            String participantId,
            int blockNo,
            int seatNo,
            String blindCode,
            Integer feature1,
            Integer feature2,
            String status,
            String assignedActor,
            long assignedAt,
            Long withdrawnAt) {
    }

    /** 边际格计数：某处理在某特征组合（格）上的历史分配人数，含已退组。 */
    public record CellCount(String treatment, int feature1, int feature2, long count) {
    }

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("blind_code"),
            (Integer) rs.getObject("feature1"),
            (Integer) rs.getObject("feature2"),
            rs.getString("status"),
            rs.getString("assigned_actor"),
            rs.getLong("assigned_at"),
            (Long) rs.getObject("withdrawn_at"));

    private static final String ALLOCATION_COLUMNS =
            "id, experiment_id, participant_id, block_no, seat_no, blind_code, feature1, feature2, "
                    + "status, assigned_actor, assigned_at, withdrawn_at";

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AllocationRow row) {
        jdbc.update("INSERT INTO allocation ("
                        + "experiment_id, participant_id, block_no, seat_no, blind_code, "
                        + "feature1, feature2, status, assigned_actor, assigned_at, withdrawn_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.blockNo(), row.seatNo(), row.blindCode(),
                row.feature1(), row.feature2(), row.status(), row.assignedActor(),
                row.assignedAt(), row.withdrawnAt());
    }

    public AllocationRow findByExperimentAndParticipant(String experimentId, String participantId) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT " + ALLOCATION_COLUMNS + " "
                        + "FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                ALLOCATION_MAPPER, experimentId, participantId);
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

    /**
     * 原子领取指定处理的第一个空位（最小化分配选定处理后使用）。
     *
     * @return 领取到的席位；该处理无空位时返回 null
     */
    public VacantSeat takeFirstVacantSeatByTreatment(String experimentId, String treatment) {
        List<VacantSeat> seats = jdbc.query(
                "SELECT s.block_no, s.seat_no FROM seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no "
                        + "AND a.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? AND s.treatment = ? AND a.id IS NULL "
                        + "ORDER BY s.block_no, s.seat_no LIMIT 1",
                (rs, n) -> new VacantSeat(rs.getInt(1), rs.getInt(2)),
                experimentId, treatment);
        return seats.isEmpty() ? null : seats.get(0);
    }

    /**
     * 历史边际格计数：按处理 × 特征组合统计全部分配（含已退组，退组仍计入历史平衡）。
     */
    public List<CellCount> countByTreatmentAndCell(String experimentId) {
        return jdbc.query(
                "SELECT s.treatment, a.feature1, a.feature2, COUNT(*) AS cnt "
                        + "FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = ? "
                        + "GROUP BY s.treatment, a.feature1, a.feature2",
                (rs, n) -> new CellCount(rs.getString("treatment"),
                        rs.getInt("feature1"), rs.getInt("feature2"), rs.getLong("cnt")),
                experimentId);
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
}
