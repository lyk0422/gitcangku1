package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分配数据访问；seat_no 与 treatment 仅在库内与揭盲流程使用，不进入普通响应与日志。
 * 每条分配记录登记中心与生效协议版本；席位唯一性按 (实验, 协议版本, 区组, 席位) 隔离。
 */
@Repository
public class AllocationRepository {

    /** 分配行（含盲底，仅供服务层内部使用）。centerId 为 null 表示旧的无中心全局登记。 */
    public record AllocationRow(
            long id,
            String experimentId,
            String participantId,
            String centerId,
            int protocolVersion,
            int blockNo,
            int seatNo,
            String blindCode,
            String status,
            String assignedActor,
            long assignedAt,
            Long withdrawnAt) {
    }

    /** 可领取席位结果：版本内区组号与席位号。 */
    public record VacantSeat(int blockNo, int seatNo) {
    }

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("center_id"),
            rs.getInt("protocol_version"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("blind_code"),
            rs.getString("status"),
            rs.getString("assigned_actor"),
            rs.getLong("assigned_at"),
            (Long) rs.getObject("withdrawn_at"));

    private static final String COLUMNS =
            "id, experiment_id, participant_id, center_id, protocol_version, block_no, seat_no, "
                    + "blind_code, status, assigned_actor, assigned_at, withdrawn_at";

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AllocationRow row) {
        jdbc.update("INSERT INTO allocation ("
                        + "experiment_id, participant_id, center_id, protocol_version, block_no, seat_no, "
                        + "blind_code, status, assigned_actor, assigned_at, withdrawn_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.centerId(), row.protocolVersion(),
                row.blockNo(), row.seatNo(), row.blindCode(), row.status(),
                row.assignedActor(), row.assignedAt(), row.withdrawnAt());
    }

    public AllocationRow findById(long id) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation WHERE id = ?", ALLOCATION_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AllocationRow findByExperimentAndParticipant(String experimentId, String participantId) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation WHERE experiment_id = ? AND participant_id = ?",
                ALLOCATION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 旧的无中心全局登记：在初始协议 V1（seat 表）中按区组、席位顺序领取第一个空位。
     *
     * @return 领取到的席位；实验满额时返回 null
     */
    public VacantSeat takeFirstVacantSeat(String experimentId) {
        List<VacantSeat> seats = jdbc.query(
                "SELECT s.block_no, s.seat_no FROM seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.protocol_version = 1 "
                        + "AND a.block_no = s.block_no "
                        + "AND a.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? AND a.id IS NULL "
                        + "ORDER BY s.block_no, s.seat_no LIMIT 1",
                (rs, n) -> new VacantSeat(rs.getInt(1), rs.getInt(2)),
                experimentId);
        return seats.isEmpty() ? null : seats.get(0);
    }

    /**
     * 中心登记：在该版本的中心席位池（protocol_seat，含 V1 中心池）中领取第一个空位，
     * 只与中心登记（center_id 非空）比较，不与旧的全局登记冲突。
     * 调用方须持有实验行级锁以串行化多中心并发取座。
     *
     * @return 领取到的席位；中心池满时返回 null
     */
    public VacantSeat takeFirstCenterVacantSeat(String experimentId, int version) {
        List<VacantSeat> seats = jdbc.query(
                "SELECT s.block_no, s.seat_no FROM protocol_seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.protocol_version = s.version "
                        + "AND a.block_no = s.block_no "
                        + "AND a.seat_no = s.seat_no "
                        + "AND a.center_id IS NOT NULL "
                        + "WHERE s.experiment_id = ? AND s.version = ? AND a.id IS NULL "
                        + "ORDER BY s.block_no, s.seat_no LIMIT 1",
                (rs, n) -> new VacantSeat(rs.getInt(1), rs.getInt(2)),
                experimentId, version);
        return seats.isEmpty() ? null : seats.get(0);
    }

    /** 实验内累计已分配数（含退组，席位不释放）。 */
    public long countByExperiment(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = ?", Long.class, experimentId);
        return count == null ? 0 : count;
    }

    /** 中心累计已分配数（含退组），即剩余容量计算口径中的“累计已分配数”。 */
    public long countByCenter(String experimentId, String centerId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = ? AND center_id = ?",
                Long.class, experimentId, centerId);
        return count == null ? 0 : count;
    }

    /** 实验内某协议版本的中心登记总数（不含旧的无中心全局登记）。 */
    public long countCenterByVersion(String experimentId, int version) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = ? AND protocol_version = ? AND center_id IS NOT NULL",
                Long.class, experimentId, version);
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
