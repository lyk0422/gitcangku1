package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分配数据访问；seat_no 与 treatment 仅在库内与揭盲流程使用，不进入普通响应与日志。
 * 每条分配固化 table_version_id + seat_no；CLOSED 版本的未分配席位不再可领。
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
            long tableVersionId) {
    }

    private static final String COLUMNS =
            "id, experiment_id, participant_id, block_no, seat_no, blind_code, status, "
                    + "assigned_actor, assigned_at, withdrawn_at, table_version_id";

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
            rs.getLong("table_version_id"));

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AllocationRow row) {
        jdbc.update("INSERT INTO allocation ("
                        + "experiment_id, participant_id, block_no, seat_no, blind_code, "
                        + "status, assigned_actor, assigned_at, withdrawn_at, table_version_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.blockNo(), row.seatNo(), row.blindCode(),
                row.status(), row.assignedActor(), row.assignedAt(), row.withdrawnAt(),
                row.tableVersionId());
    }

    public AllocationRow findByExperimentAndParticipant(String experimentId, String participantId) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation "
                        + "WHERE experiment_id = ? AND participant_id = ?",
                ALLOCATION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 原子领取全实验第一个可分配序号：仅在 ACTIVE 随机表版本的席位中，按区组号、
     * 版本号、版本内序号排序取第一个未被占用的席位；CLOSED 版本的未分配余量不可领。
     * 并发由 allocation(table_version_id, seat_no) 唯一索引兜底。
     *
     * @return 领取到的席位；无 ACTIVE 空位时返回 null
     */
    public VacantSeat takeFirstVacantSeat(String experimentId) {
        List<VacantSeat> seats = jdbc.query(
                "SELECT v.id, s.block_no, s.seat_no FROM seat s "
                        + "JOIN random_table_version v ON v.id = s.table_version_id "
                        + "LEFT JOIN allocation a "
                        + "ON a.table_version_id = s.table_version_id "
                        + "AND a.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? AND v.status = 'ACTIVE' AND a.id IS NULL "
                        + "ORDER BY s.block_no, v.version_no, s.seat_no LIMIT 1",
                (rs, n) -> new VacantSeat(rs.getLong(1), rs.getInt(2), rs.getInt(3)),
                experimentId);
        return seats.isEmpty() ? null : seats.get(0);
    }

    /** 原子领取结果。 */
    public record VacantSeat(long tableVersionId, int blockNo, int seatNo) {
    }

    public long countOccupied(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = ?", Long.class, experimentId);
        return count == null ? 0 : count;
    }

    /** 某随机表版本的已分配数（含退组；退组不释放序号）。 */
    public int countAssignedByVersion(long tableVersionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE table_version_id = ?",
                Integer.class, tableVersionId);
        return count == null ? 0 : count;
    }

    /** 实验的全部分配，按分配时刻、主键升序（明细/历史查询，只读不改状态）。 */
    public List<AllocationRow> findByExperimentOrdered(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation WHERE experiment_id = ? "
                        + "ORDER BY assigned_at, id",
                ALLOCATION_MAPPER, experimentId);
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
        return e.getMessage() != null
                && (e.getMessage().contains("uq_allocation_table_seq")
                        || e.getMessage().contains("uq_allocation_seat"));
    }

    public boolean isDuplicateBlindCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_allocation_blind_code");
    }
}
