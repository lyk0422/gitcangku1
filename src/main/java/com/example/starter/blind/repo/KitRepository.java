package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 处理包与领取记录数据访问；treatment 仅库内使用，不进入普通响应与日志。
 */
@Repository
public class KitRepository {

    /** 处理包行（含盲底 treatment，仅供服务层内部使用）。 */
    public record KitRow(
            long id,
            String experimentId,
            String packageNo,
            String treatment,
            String status,
            String registeredActor,
            long registeredAt,
            Long issuedAt,
            Long allocationId) {
    }

    /** 领取记录行。 */
    public record KitPickupRow(
            String id,
            String experimentId,
            String participantId,
            long allocationId,
            long kitId,
            String wrapperCode,
            String actorId,
            long createdAt) {
    }

    private static final RowMapper<KitRow> KIT_MAPPER = (rs, n) -> new KitRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("package_no"),
            rs.getString("treatment"),
            rs.getString("status"),
            rs.getString("registered_actor"),
            rs.getLong("registered_at"),
            (Long) rs.getObject("issued_at"),
            (Long) rs.getObject("allocation_id"));

    private static final RowMapper<KitPickupRow> PICKUP_MAPPER = (rs, n) -> new KitPickupRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getLong("allocation_id"),
            rs.getLong("kit_id"),
            rs.getString("wrapper_code"),
            rs.getString("actor_id"),
            rs.getLong("created_at"));

    private static final String KIT_COLUMNS =
            "id, experiment_id, package_no, treatment, status, registered_actor, "
                    + "registered_at, issued_at, allocation_id";

    private final JdbcTemplate jdbc;

    public KitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertKit(KitRow row) {
        jdbc.update("INSERT INTO treatment_kit ("
                        + "experiment_id, package_no, treatment, status, registered_actor, "
                        + "registered_at, issued_at, allocation_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.packageNo(), row.treatment(), row.status(),
                row.registeredActor(), row.registeredAt(), row.issuedAt(), row.allocationId());
    }

    public KitRow findKit(String experimentId, String packageNo) {
        List<KitRow> rows = jdbc.query(
                "SELECT " + KIT_COLUMNS + " FROM treatment_kit "
                        + "WHERE experiment_id = ? AND package_no = ?",
                KIT_MAPPER, experimentId, packageNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按包编号字典序取匹配处理代码的第一个在库包；调用方需持有实验行锁串行化并发领取。
     */
    public KitRow findFirstStockKit(String experimentId, String treatment) {
        List<KitRow> rows = jdbc.query(
                "SELECT " + KIT_COLUMNS + " FROM treatment_kit "
                        + "WHERE experiment_id = ? AND treatment = ? AND status = 'STOCK' "
                        + "ORDER BY package_no ASC LIMIT 1",
                KIT_MAPPER, experimentId, treatment);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 原子发放：仅在库状态可更新，返回受影响行数；0 表示已被并发发放。
     */
    public int markIssued(long kitId, long allocationId, long issuedAt) {
        return jdbc.update(
                "UPDATE treatment_kit SET status = 'ISSUED', issued_at = ?, allocation_id = ? "
                        + "WHERE id = ? AND status = 'STOCK'",
                issuedAt, allocationId, kitId);
    }

    public void insertPickup(KitPickupRow row) {
        jdbc.update("INSERT INTO kit_pickup ("
                        + "id, experiment_id, participant_id, allocation_id, kit_id, "
                        + "wrapper_code, actor_id, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.experimentId(), row.participantId(), row.allocationId(),
                row.kitId(), row.wrapperCode(), row.actorId(), row.createdAt());
    }

    public KitPickupRow findPickupByAllocation(long allocationId) {
        List<KitPickupRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, allocation_id, kit_id, wrapper_code, "
                        + "actor_id, created_at FROM kit_pickup WHERE allocation_id = ?",
                PICKUP_MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}