package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分配数据访问；seat_no/version_no/treatment 仅在库内与揭盲流程使用，不进入普通响应与日志。
 * 分配按区组、版本、区内序号的顺序领取空位；余量已关闭（CLOSED）的封存版本跳过。
 */
@Repository
public class AllocationRepository {

    /** 分配行（含盲底，仅供服务层内部使用）。 */
    public record AllocationRow(
            long id,
            String experimentId,
            String participantId,
            int blockNo,
            Integer versionNo,
            int seatNo,
            String sealId,
            String blindCode,
            String status,
            String assignedActor,
            long assignedAt,
            Long withdrawnAt) {
    }

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("block_no"),
            (Integer) rs.getObject("version_no"),
            rs.getInt("seat_no"),
            rs.getString("seal_id"),
            rs.getString("blind_code"),
            rs.getString("status"),
            rs.getString("assigned_actor"),
            rs.getLong("assigned_at"),
            (Long) rs.getObject("withdrawn_at"));

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AllocationRow row) {
        jdbc.update("INSERT INTO allocation ("
                        + "experiment_id, participant_id, block_no, version_no, seat_no, seal_id, "
                        + "blind_code, status, assigned_actor, assigned_at, withdrawn_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.blockNo(), row.versionNo(),
                row.seatNo(), row.sealId(), row.blindCode(), row.status(), row.assignedActor(),
                row.assignedAt(), row.withdrawnAt());
    }

    public AllocationRow findByExperimentAndParticipant(String experimentId, String participantId) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, block_no, version_no, seat_no, seal_id, "
                        + "blind_code, status, assigned_actor, assigned_at, withdrawn_at "
                        + "FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                ALLOCATION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 原子领取按区组、版本、区内序号排列的第一个可分配空位：
     * 关联封存记录，跳过余量已关闭（CLOSED）的版本；未封存版本（无封存行）仍可分配，
     * 此时返回的版本号与封存编号为 null（封存前历史分配的 null 语义）。
     * 依赖 allocation(experiment_id, block_no, seat_no) 唯一索引兜底并发。
     *
     * @return 领取到的空位；无可分配名额时返回 null
     */
    public VacantSeat takeFirstVacantSeat(String experimentId) {
        List<VacantSeat> seats = jdbc.query(
                "SELECT s.block_no, "
                        + "CASE WHEN se.id IS NULL THEN NULL ELSE s.version_no END, "
                        + "s.seat_no, se.id "
                        + "FROM seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no "
                        + "AND a.seat_no = s.seat_no "
                        + "LEFT JOIN random_table_seal se "
                        + "ON se.experiment_id = s.experiment_id "
                        + "AND se.block_no = s.block_no "
                        + "AND se.version_no = s.version_no "
                        + "WHERE s.experiment_id = ? AND a.id IS NULL "
                        + "AND (se.id IS NULL OR se.status = 'SEALED') "
                        + "ORDER BY s.block_no, s.version_no, s.seat_no LIMIT 1",
                (rs, n) -> new VacantSeat(rs.getInt(1), (Integer) rs.getObject(2),
                        rs.getInt(3), rs.getString(4)),
                experimentId);
        return seats.isEmpty() ? null : seats.get(0);
    }

    /** 原子领取结果。versionNo/sealId 为 null 表示空位属于尚未封存的版本1。 */
    public record VacantSeat(int blockNo, Integer versionNo, int seatNo, String sealId) {
    }

    public long countOccupied(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = ?", Long.class, experimentId);
        return count == null ? 0 : count;
    }

    /**
     * 区组内已有分配数（含已退组；退组不释放序号）。
     */
    public long countByBlock(String experimentId, int blockNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = ? AND block_no = ?",
                Long.class, experimentId, blockNo);
        return count == null ? 0 : count;
    }

    /**
     * 指定版本上的已有分配数。
     */
    public long countByVersion(String experimentId, int blockNo, int versionNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = ? AND block_no = ? AND version_no = ?",
                Long.class, experimentId, blockNo, versionNo);
        return count == null ? 0 : count;
    }

    /**
     * 区组内全部分配，按分配时刻、主键升序（诊断/明细查询，只读）。
     */
    public List<AllocationRow> findByBlock(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT id, experiment_id, participant_id, block_no, version_no, seat_no, seal_id, "
                        + "blind_code, status, assigned_actor, assigned_at, withdrawn_at "
                        + "FROM allocation WHERE experiment_id = ? AND block_no = ? "
                        + "ORDER BY assigned_at, id",
                ALLOCATION_MAPPER, experimentId, blockNo);
    }

    /**
     * 指定封存版本上的全部分配，按分配时刻、主键升序（明细查询，只读）。
     */
    public List<AllocationRow> findByVersion(String experimentId, int blockNo, int versionNo) {
        return jdbc.query(
                "SELECT id, experiment_id, participant_id, block_no, version_no, seat_no, seal_id, "
                        + "blind_code, status, assigned_actor, assigned_at, withdrawn_at "
                        + "FROM allocation WHERE experiment_id = ? AND block_no = ? AND version_no = ? "
                        + "ORDER BY assigned_at, id",
                ALLOCATION_MAPPER, experimentId, blockNo, versionNo);
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
