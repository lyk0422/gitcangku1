package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 代码退役数据访问：退役批次、退役代码配额快照与封存序号明细。
 * 三张表只插不改，退役、封存序号、配额快照与理由均不可变。
 */
@Repository
public class RetirementRepository {

    /** 退役批次行。createdAt 为 Unix 毫秒 UTC。 */
    public record RetirementRow(
            String id,
            String experimentId,
            String reason,
            String actorId,
            long createdAt) {
    }

    /** 退役代码配额快照行；quotaAfter 恒为 0。 */
    public record RetiredCodeRow(
            String retirementId,
            String experimentId,
            String treatment,
            int allocatedCount,
            int sealedCount,
            int quotaBefore,
            int quotaAfter) {
    }

    /** 封存序号明细行。 */
    public record SealedSeatRow(
            long id,
            String retirementId,
            String experimentId,
            String treatment,
            int blockNo,
            int seatNo,
            long sealedAt) {
    }

    private static final RowMapper<RetirementRow> RETIREMENT_MAPPER = (rs, n) -> new RetirementRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("reason"),
            rs.getString("actor_id"),
            rs.getLong("created_at"));

    private static final RowMapper<RetiredCodeRow> RETIRED_CODE_MAPPER = (rs, n) -> new RetiredCodeRow(
            rs.getString("retirement_id"),
            rs.getString("experiment_id"),
            rs.getString("treatment"),
            rs.getInt("allocated_count"),
            rs.getInt("sealed_count"),
            rs.getInt("quota_before"),
            rs.getInt("quota_after"));

    private static final RowMapper<SealedSeatRow> SEALED_SEAT_MAPPER = (rs, n) -> new SealedSeatRow(
            rs.getLong("id"),
            rs.getString("retirement_id"),
            rs.getString("experiment_id"),
            rs.getString("treatment"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getLong("sealed_at"));

    private final JdbcTemplate jdbc;

    public RetirementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRetirement(RetirementRow row) {
        jdbc.update("INSERT INTO code_retirement (id, experiment_id, reason, actor_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.id(), row.experimentId(), row.reason(), row.actorId(), row.createdAt());
    }

    /**
     * 插入退役代码快照；同一实验同一代码重复退役由 uq_retired_code 唯一约束兜底。
     */
    public void insertRetiredCode(RetiredCodeRow row) {
        jdbc.update("INSERT INTO retired_code ("
                        + "retirement_id, experiment_id, treatment, allocated_count, "
                        + "sealed_count, quota_before, quota_after"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.retirementId(), row.experimentId(), row.treatment(), row.allocatedCount(),
                row.sealedCount(), row.quotaBefore(), row.quotaAfter());
    }

    /**
     * 插入封存序号明细；同一席位重复封存由 uq_sealed_seat 唯一约束兜底。
     */
    public void insertSealedSeat(SealedSeatRow row) {
        jdbc.update("INSERT INTO sealed_seat ("
                        + "retirement_id, experiment_id, treatment, block_no, seat_no, sealed_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                row.retirementId(), row.experimentId(), row.treatment(),
                row.blockNo(), row.seatNo(), row.sealedAt());
    }

    public RetirementRow findRetirementById(String retirementId) {
        List<RetirementRow> rows = jdbc.query(
                "SELECT id, experiment_id, reason, actor_id, created_at "
                        + "FROM code_retirement WHERE id = ?",
                RETIREMENT_MAPPER, retirementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 退役历史：按退役时间、批次编号升序；from/to 为 Unix 毫秒 UTC，左闭右开，均可为 null。
     */
    public List<RetirementRow> findRetirements(String experimentId, Long from, Long to) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, experiment_id, reason, actor_id, created_at "
                        + "FROM code_retirement WHERE experiment_id = ?");
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(experimentId);
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            params.add(to);
        }
        sql.append(" ORDER BY created_at, id");
        return jdbc.query(sql.toString(), RETIREMENT_MAPPER, params.toArray());
    }

    /** 某实验已退役的处理代码集合（升序）。 */
    public List<String> findRetiredTreatments(String experimentId) {
        return jdbc.queryForList(
                "SELECT treatment FROM retired_code WHERE experiment_id = ? ORDER BY treatment",
                String.class, experimentId);
    }

    /** 某批次的退役代码快照，按处理代码升序。 */
    public List<RetiredCodeRow> findRetiredCodes(String retirementId) {
        return jdbc.query(
                "SELECT retirement_id, experiment_id, treatment, allocated_count, sealed_count, "
                        + "quota_before, quota_after FROM retired_code "
                        + "WHERE retirement_id = ? ORDER BY treatment",
                RETIRED_CODE_MAPPER, retirementId);
    }

    /** 某批次的封存序号明细，按处理代码、区组号、席位号升序。 */
    public List<SealedSeatRow> findSealedSeats(String retirementId) {
        return jdbc.query(
                "SELECT id, retirement_id, experiment_id, treatment, block_no, seat_no, sealed_at "
                        + "FROM sealed_seat WHERE retirement_id = ? "
                        + "ORDER BY treatment, block_no, seat_no",
                SEALED_SEAT_MAPPER, retirementId);
    }
}
