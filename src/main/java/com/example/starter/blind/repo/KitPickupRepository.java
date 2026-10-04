package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 处理包领取记录数据访问；发过的包不回库、不转给别人，同一次分配只能领取一次。
 */
@Repository
public class KitPickupRepository {

    /** 领取记录行。 */
    public record KitPickupRow(
            String id,
            String experimentId,
            String participantId,
            long allocationId,
            long kitId,
            String packageCode,
            String pickedActor,
            long pickedAt) {
    }

    private static final RowMapper<KitPickupRow> PICKUP_MAPPER = (rs, n) -> new KitPickupRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getLong("allocation_id"),
            rs.getLong("kit_id"),
            rs.getString("package_code"),
            rs.getString("picked_actor"),
            rs.getLong("picked_at"));

    private final JdbcTemplate jdbc;

    public KitPickupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(KitPickupRow row) {
        jdbc.update("INSERT INTO kit_pickup ("
                        + "id, experiment_id, participant_id, allocation_id, kit_id, "
                        + "package_code, picked_actor, picked_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.experimentId(), row.participantId(), row.allocationId(),
                row.kitId(), row.packageCode(), row.pickedActor(), row.pickedAt());
    }

    public KitPickupRow findByAllocationId(long allocationId) {
        List<KitPickupRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, allocation_id, kit_id, "
                        + "package_code, picked_actor, picked_at "
                        + "FROM kit_pickup WHERE allocation_id = ?",
                PICKUP_MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public KitPickupRow findByKitId(long kitId) {
        List<KitPickupRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, allocation_id, kit_id, "
                        + "package_code, picked_actor, picked_at "
                        + "FROM kit_pickup WHERE kit_id = ?",
                PICKUP_MAPPER, kitId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 同一次分配重复领取。 */
    public boolean isDuplicateAllocation(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_pickup_allocation");
    }

    /** 同一个包被重复发放。 */
    public boolean isDuplicateKit(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_pickup_kit");
    }
}