package com.example.starter.blind.repo;

import com.example.starter.blind.service.RetirementPlanner.SeatState;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 盲态代码退役数据访问；退役四类记录均为只追加（INSERT），不提供更新/删除，
 * 封存序号与分配席位的互斥由 sealed_seat/allocation 各自唯一索引共同保证。
 */
@Repository
public class RetirementRepository {

    /** 退役批次行（不可变事件头）。 */
    public record RetirementRow(
            String id,
            String experimentId,
            String reason,
            String operatorActor,
            String status,
            long createdAt,
            long committedAt) {
    }

    /** 批次-代码配额明细行（不可变快照）。 */
    public record RetiredCodeRow(
            long id,
            String retirementId,
            String experimentId,
            String treatment,
            int quotaBefore,
            int sealedCount,
            int quotaAfter,
            int assignedCount,
            long createdAt) {
    }

    /** 封存序号行（不可变；seatNo/treatment 为盲底，仅库内与诊断使用）。 */
    public record SealedSeatRow(
            long id,
            String retirementId,
            String experimentId,
            int blockNo,
            int seatNo,
            String treatment,
            long createdAt) {
    }

    /** 区组配额重算快照行（不可变）。 */
    public record BlockQuotaRow(
            long id,
            String retirementId,
            String experimentId,
            int blockNo,
            int availableBefore,
            int sealedInBlock,
            int availableAfter,
            int quotaAAfter,
            int quotaBAfter,
            long createdAt) {
    }

    private static final RowMapper<RetirementRow> RETIREMENT_MAPPER = (rs, n) -> new RetirementRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("reason"),
            rs.getString("operator_actor"),
            rs.getString("status"),
            rs.getLong("created_at"),
            rs.getLong("committed_at"));

    private static final RowMapper<RetiredCodeRow> CODE_MAPPER = (rs, n) -> new RetiredCodeRow(
            rs.getLong("id"),
            rs.getString("retirement_id"),
            rs.getString("experiment_id"),
            rs.getString("treatment"),
            rs.getInt("quota_before"),
            rs.getInt("sealed_count"),
            rs.getInt("quota_after"),
            rs.getInt("assigned_count"),
            rs.getLong("created_at"));

    private static final RowMapper<SealedSeatRow> SEALED_MAPPER = (rs, n) -> new SealedSeatRow(
            rs.getLong("id"),
            rs.getString("retirement_id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("treatment"),
            rs.getLong("created_at"));

    private static final RowMapper<BlockQuotaRow> BLOCK_QUOTA_MAPPER = (rs, n) -> new BlockQuotaRow(
            rs.getLong("id"),
            rs.getString("retirement_id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("available_before"),
            rs.getInt("sealed_in_block"),
            rs.getInt("available_after"),
            rs.getInt("quota_a_after"),
            rs.getInt("quota_b_after"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public RetirementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRetirement(RetirementRow row) {
        jdbc.update("INSERT INTO code_retirement ("
                        + "id, experiment_id, reason, operator_actor, status, created_at, committed_at"
                        + ") VALUES (?, ?, ?, ?, 'COMMITTED', ?, ?)",
                row.id(), row.experimentId(), row.reason(), row.operatorActor(),
                row.createdAt(), row.committedAt());
    }

    public void insertRetiredCode(RetiredCodeRow row) {
        jdbc.update("INSERT INTO retired_code ("
                        + "retirement_id, experiment_id, treatment, quota_before, sealed_count, "
                        + "quota_after, assigned_count, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.retirementId(), row.experimentId(), row.treatment(), row.quotaBefore(),
                row.sealedCount(), row.quotaAfter(), row.assignedCount(), row.createdAt());
    }

    public void insertSealedSeat(SealedSeatRow row) {
        jdbc.update("INSERT INTO sealed_seat ("
                        + "retirement_id, experiment_id, block_no, seat_no, treatment, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                row.retirementId(), row.experimentId(), row.blockNo(), row.seatNo(),
                row.treatment(), row.createdAt());
    }

    public void insertBlockQuota(BlockQuotaRow row) {
        jdbc.update("INSERT INTO retirement_block_quota ("
                        + "retirement_id, experiment_id, block_no, available_before, sealed_in_block, "
                        + "available_after, quota_a_after, quota_b_after, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.retirementId(), row.experimentId(), row.blockNo(), row.availableBefore(),
                row.sealedInBlock(), row.availableAfter(), row.quotaAAfter(),
                row.quotaBAfter(), row.createdAt());
    }

    public RetirementRow findRetirementById(String retirementId) {
        List<RetirementRow> rows = jdbc.query(
                "SELECT id, experiment_id, reason, operator_actor, status, created_at, committed_at "
                        + "FROM code_retirement WHERE id = ?",
                RETIREMENT_MAPPER, retirementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RetiredCodeRow> findCodesByRetirement(String retirementId) {
        return jdbc.query(
                "SELECT id, retirement_id, experiment_id, treatment, quota_before, sealed_count, "
                        + "quota_after, assigned_count, created_at "
                        + "FROM retired_code WHERE retirement_id = ? ORDER BY treatment",
                CODE_MAPPER, retirementId);
    }

    public List<BlockQuotaRow> findBlockQuotasByRetirement(String retirementId) {
        return jdbc.query(
                "SELECT id, retirement_id, experiment_id, block_no, available_before, sealed_in_block, "
                        + "available_after, quota_a_after, quota_b_after, created_at "
                        + "FROM retirement_block_quota WHERE retirement_id = ? ORDER BY block_no",
                BLOCK_QUOTA_MAPPER, retirementId);
    }

    /**
     * 退役批次历史；时间区间左闭右开 [fromInclusive, toExclusive)，按提交时间、批次编号稳定升序。
     * 任一端点为 null 时该侧不设界。
     */
    public List<RetirementRow> findRetirements(String experimentId, Long fromInclusive, Long toExclusive) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, experiment_id, reason, operator_actor, status, created_at, committed_at "
                        + "FROM code_retirement WHERE experiment_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(experimentId);
        if (fromInclusive != null) {
            sql.append(" AND created_at >= ?");
            args.add(fromInclusive);
        }
        if (toExclusive != null) {
            sql.append(" AND created_at < ?");
            args.add(toExclusive);
        }
        sql.append(" ORDER BY created_at, id");
        return jdbc.query(sql.toString(), RETIREMENT_MAPPER, args.toArray());
    }

    public List<SealedSeatRow> findSealedSeats(String retirementId) {
        return jdbc.query(
                "SELECT id, retirement_id, experiment_id, block_no, seat_no, treatment, created_at "
                        + "FROM sealed_seat WHERE retirement_id = ? ORDER BY block_no, seat_no, id",
                SEALED_MAPPER, retirementId);
    }

    /**
     * 读取实验全部席位在提交时刻的现状：是否已被分配占用（含退组）、是否已被历次退役封存。
     * LEFT JOIN 两侧均受唯一约束保护，每个席位至多匹配一条，不会翻倍。
     */
    public List<SeatState> loadSeatStates(String experimentId) {
        return jdbc.query(
                "SELECT s.block_no, s.seat_no, s.treatment, "
                        + "CASE WHEN a.id IS NULL THEN FALSE ELSE TRUE END AS occupied, "
                        + "CASE WHEN ss.id IS NULL THEN FALSE ELSE TRUE END AS sealed "
                        + "FROM seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no AND a.seat_no = s.seat_no "
                        + "LEFT JOIN sealed_seat ss "
                        + "ON ss.experiment_id = s.experiment_id "
                        + "AND ss.block_no = s.block_no AND ss.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? ORDER BY s.block_no, s.seat_no",
                (rs, n) -> new SeatState(rs.getInt("block_no"), rs.getInt("seat_no"),
                        rs.getString("treatment"),
                        rs.getBoolean("occupied"), rs.getBoolean("sealed")),
                experimentId);
    }

    /** 代码级配额聚合行。 */
    public record CodeQuotaAggregate(
            String treatment, int quotaTotal, int assignedCount, int sealedCount) {
    }

    /** 区组级名额聚合行。 */
    public record BlockAvailabilityAggregate(
            int blockNo, int capacity, int occupiedCount, int sealedCount,
            int availableCount, int quotaA, int quotaB) {
    }

    /**
     * 各处理代码当前配额诊断：设计总配额、已分配（含退组）、累计封存、剩余有效配额。
     * 未出现的代码（理论上不存在）由调用方补齐。
     */
    public List<CodeQuotaAggregate> aggregateCodeQuotas(String experimentId) {
        return jdbc.query(
                "SELECT s.treatment AS treatment, "
                        + "COUNT(*) AS quota_total, "
                        + "COUNT(a.id) AS assigned_count, "
                        + "COUNT(ss.id) AS sealed_count "
                        + "FROM seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no AND a.seat_no = s.seat_no "
                        + "LEFT JOIN sealed_seat ss "
                        + "ON ss.experiment_id = s.experiment_id "
                        + "AND ss.block_no = s.block_no AND ss.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? GROUP BY s.treatment ORDER BY s.treatment",
                (rs, n) -> new CodeQuotaAggregate(rs.getString("treatment"),
                        rs.getInt("quota_total"), rs.getInt("assigned_count"),
                        rs.getInt("sealed_count")),
                experimentId);
    }

    /**
     * 各区组当前名额诊断：容量、占用、累计封存、可分配数及各代码有效配额。
     */
    public List<BlockAvailabilityAggregate> aggregateBlockAvailability(String experimentId) {
        return jdbc.query(
                "SELECT s.block_no AS block_no, COUNT(*) AS capacity, "
                        + "COUNT(a.id) AS occupied_count, COUNT(ss.id) AS sealed_count, "
                        + "SUM(CASE WHEN a.id IS NULL AND ss.id IS NULL THEN 1 ELSE 0 END) AS available_count, "
                        + "SUM(CASE WHEN s.treatment = 'A' AND a.id IS NULL AND ss.id IS NULL "
                        + "THEN 1 ELSE 0 END) AS quota_a, "
                        + "SUM(CASE WHEN s.treatment = 'B' AND a.id IS NULL AND ss.id IS NULL "
                        + "THEN 1 ELSE 0 END) AS quota_b "
                        + "FROM seat s "
                        + "LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no AND a.seat_no = s.seat_no "
                        + "LEFT JOIN sealed_seat ss "
                        + "ON ss.experiment_id = s.experiment_id "
                        + "AND ss.block_no = s.block_no AND ss.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? GROUP BY s.block_no ORDER BY s.block_no",
                (rs, n) -> new BlockAvailabilityAggregate(rs.getInt("block_no"),
                        rs.getInt("capacity"), rs.getInt("occupied_count"),
                        rs.getInt("sealed_count"), rs.getInt("available_count"),
                        rs.getInt("quota_a"), rs.getInt("quota_b")),
                experimentId);
    }
}
