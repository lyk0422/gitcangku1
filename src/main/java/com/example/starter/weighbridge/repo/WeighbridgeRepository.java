package com.example.starter.weighbridge.repo;

import com.example.starter.weighbridge.domain.DisputeStatus;
import com.example.starter.weighbridge.domain.Phase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 地磅争议仓储：基于 JdbcClient 的参数绑定 SQL，覆盖称量、争议、裁决、冻结与勘误。
 */
@Repository
public class WeighbridgeRepository {

    private final JdbcClient jdbc;

    public WeighbridgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 运输单行。 */
    public record OrderRow(long id, String orderNo, String vehicleNo, LocalDateTime createdAt) {
    }

    /** 称量行。 */
    public record WeighingRow(long id, long transportOrderId, Long disputeId, Phase phase,
                              int weightKg, LocalDateTime weighedAt, String requestId,
                              String requestHash, LocalDateTime createdAt) {
    }

    /** 争议行。 */
    public record DisputeRow(long id, long transportOrderId, String reason, DisputeStatus status,
                             LocalDateTime createdAt, String requestId, String requestHash) {
    }

    /** 裁决行。 */
    public record AdjudicationRow(long id, long disputeId, long adoptedWeighingId, String reason,
                                  String decidedBy, LocalDateTime decidedAt, String requestId,
                                  String requestHash) {
    }

    /** 冻结行。 */
    public record FreezeRow(long id, long transportOrderId, int tareWeightKg, long adoptedWeighingId,
                            int grossWeightKg, int netWeightKg, int weighingVersion,
                            LocalDateTime frozenAt, String requestId, String requestHash) {
    }

    /** 勘误行。 */
    public record ErrataRow(long id, long freezeId, int weightKg, LocalDateTime weighedAt,
                            String reason, String status, LocalDateTime createdAt,
                            String requestId, String requestHash) {
    }

    private static OrderRow mapOrder(ResultSet rs, int i) throws SQLException {
        return new OrderRow(rs.getLong("id"), rs.getString("order_no"), rs.getString("vehicle_no"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static WeighingRow mapWeighing(ResultSet rs, int i) throws SQLException {
        long disputeId = rs.getLong("dispute_id");
        return new WeighingRow(rs.getLong("id"), rs.getLong("transport_order_id"),
                rs.wasNull() ? null : disputeId, Phase.valueOf(rs.getString("phase")),
                rs.getInt("weight_kg"), rs.getTimestamp("weighed_at").toLocalDateTime(),
                rs.getString("request_id"), rs.getString("request_hash"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static DisputeRow mapDispute(ResultSet rs, int i) throws SQLException {
        return new DisputeRow(rs.getLong("id"), rs.getLong("transport_order_id"),
                rs.getString("reason"), DisputeStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("request_id"),
                rs.getString("request_hash"));
    }

    private static AdjudicationRow mapAdjudication(ResultSet rs, int i) throws SQLException {
        return new AdjudicationRow(rs.getLong("id"), rs.getLong("dispute_id"),
                rs.getLong("adopted_weighing_id"), rs.getString("reason"),
                rs.getString("decided_by"), rs.getTimestamp("decided_at").toLocalDateTime(),
                rs.getString("request_id"), rs.getString("request_hash"));
    }

    private static FreezeRow mapFreeze(ResultSet rs, int i) throws SQLException {
        return new FreezeRow(rs.getLong("id"), rs.getLong("transport_order_id"),
                rs.getInt("tare_weight_kg"), rs.getLong("adopted_weighing_id"),
                rs.getInt("gross_weight_kg"), rs.getInt("net_weight_kg"),
                rs.getInt("weighing_version"), rs.getTimestamp("frozen_at").toLocalDateTime(),
                rs.getString("request_id"), rs.getString("request_hash"));
    }

    private static ErrataRow mapErrata(ResultSet rs, int i) throws SQLException {
        return new ErrataRow(rs.getLong("id"), rs.getLong("freeze_id"), rs.getInt("weight_kg"),
                rs.getTimestamp("weighed_at").toLocalDateTime(), rs.getString("reason"),
                rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("request_id"), rs.getString("request_hash"));
    }

    private static long generatedKey(KeyHolder kh) {
        return kh.getKey().longValue();
    }

    public long insertOrder(String orderNo, String vehicleNo, LocalDateTime createdAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO transport_order (order_no, vehicle_no, created_at) VALUES (?, ?, ?)")
                .params(orderNo, vehicleNo, createdAt).update(kh);
        return generatedKey(kh);
    }

    public Optional<OrderRow> findOrderById(long id) {
        return jdbc.sql("SELECT * FROM transport_order WHERE id = ?").params(id)
                .query(WeighbridgeRepository::mapOrder).optional();
    }

    /** 按主键锁定运输单行（FOR UPDATE），用于串行化同单写操作。 */
    public Optional<OrderRow> lockOrderById(long id) {
        return jdbc.sql("SELECT * FROM transport_order WHERE id = ? FOR UPDATE").params(id)
                .query(WeighbridgeRepository::mapOrder).optional();
    }

    public long insertWeighing(long orderId, Long disputeId, Phase phase, int weightKg,
                               LocalDateTime weighedAt, String requestId, String requestHash,
                               LocalDateTime createdAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO weighing_record (transport_order_id, dispute_id, phase, weight_kg,"
                        + " weighed_at, request_id, request_hash, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(orderId, disputeId, phase.name(), weightKg, weighedAt, requestId,
                        requestHash, createdAt).update(kh);
        return generatedKey(kh);
    }

    public Optional<WeighingRow> findWeighingById(long id) {
        return jdbc.sql("SELECT * FROM weighing_record WHERE id = ?").params(id)
                .query(WeighbridgeRepository::mapWeighing).optional();
    }

    public Optional<WeighingRow> findWeighingByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM weighing_record WHERE request_id = ?").params(requestId)
                .query(WeighbridgeRepository::mapWeighing).optional();
    }

    public Optional<WeighingRow> findWeighing(long orderId, Phase phase) {
        return jdbc.sql("SELECT * FROM weighing_record WHERE transport_order_id = ? AND phase = ?"
                        + " ORDER BY id LIMIT 1").params(orderId, phase.name())
                .query(WeighbridgeRepository::mapWeighing).optional();
    }

    public List<WeighingRow> listWeighings(long orderId) {
        return jdbc.sql("SELECT * FROM weighing_record WHERE transport_order_id = ? ORDER BY id")
                .params(orderId).query(WeighbridgeRepository::mapWeighing).list();
    }

    public List<WeighingRow> listReweighWeighings(long disputeId) {
        return jdbc.sql("SELECT * FROM weighing_record WHERE dispute_id = ? ORDER BY id")
                .params(disputeId).query(WeighbridgeRepository::mapWeighing).list();
    }

    public int countWeighings(long orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM weighing_record WHERE transport_order_id = ?")
                .params(orderId).query(Integer.class).single();
    }
    public long insertDispute(long orderId, String reason, DisputeStatus status,
                              LocalDateTime createdAt, String requestId, String requestHash) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dispute (transport_order_id, reason, status, created_at,"
                        + " request_id, request_hash) VALUES (?, ?, ?, ?, ?, ?)")
                .params(orderId, reason, status.name(), createdAt, requestId, requestHash)
                .update(kh);
        return generatedKey(kh);
    }

    public Optional<DisputeRow> findDisputeById(long id) {
        return jdbc.sql("SELECT * FROM dispute WHERE id = ?").params(id)
                .query(WeighbridgeRepository::mapDispute).optional();
    }

    public Optional<DisputeRow> findDisputeByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM dispute WHERE request_id = ?").params(requestId)
                .query(WeighbridgeRepository::mapDispute).optional();
    }

    public List<DisputeRow> listDisputes(long orderId) {
        return jdbc.sql("SELECT * FROM dispute WHERE transport_order_id = ? ORDER BY id")
                .params(orderId).query(WeighbridgeRepository::mapDispute).list();
    }

    public void markDisputeAdjudicated(long disputeId) {
        jdbc.sql("UPDATE dispute SET status = ? WHERE id = ?")
                .params(DisputeStatus.ADJUDICATED.name(), disputeId).update();
    }

    public long insertAdjudication(long disputeId, long adoptedWeighingId, String reason,
                                   String decidedBy, LocalDateTime decidedAt, String requestId,
                                   String requestHash) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO adjudication (dispute_id, adopted_weighing_id, reason, decided_by,"
                        + " decided_at, request_id, request_hash) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(disputeId, adoptedWeighingId, reason, decidedBy, decidedAt, requestId,
                        requestHash).update(kh);
        return generatedKey(kh);
    }

    public Optional<AdjudicationRow> findAdjudicationByDisputeId(long disputeId) {
        return jdbc.sql("SELECT * FROM adjudication WHERE dispute_id = ?").params(disputeId)
                .query(WeighbridgeRepository::mapAdjudication).optional();
    }

    public Optional<AdjudicationRow> findAdjudicationByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM adjudication WHERE request_id = ?").params(requestId)
                .query(WeighbridgeRepository::mapAdjudication).optional();
    }

    public long insertFreeze(long orderId, int tareWeightKg, long adoptedWeighingId,
                             int grossWeightKg, int netWeightKg, int weighingVersion,
                             LocalDateTime frozenAt, String requestId, String requestHash) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO net_weight_freeze (transport_order_id, tare_weight_kg,"
                        + " adopted_weighing_id, gross_weight_kg, net_weight_kg, weighing_version,"
                        + " frozen_at, request_id, request_hash)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(orderId, tareWeightKg, adoptedWeighingId, grossWeightKg, netWeightKg,
                        weighingVersion, frozenAt, requestId, requestHash).update(kh);
        return generatedKey(kh);
    }

    public Optional<FreezeRow> findFreezeByOrderId(long orderId) {
        return jdbc.sql("SELECT * FROM net_weight_freeze WHERE transport_order_id = ?")
                .params(orderId).query(WeighbridgeRepository::mapFreeze).optional();
    }

    public Optional<FreezeRow> findFreezeByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM net_weight_freeze WHERE request_id = ?").params(requestId)
                .query(WeighbridgeRepository::mapFreeze).optional();
    }

    public long insertErrata(long freezeId, int weightKg, LocalDateTime weighedAt, String reason,
                             String status, LocalDateTime createdAt, String requestId,
                             String requestHash) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO errata_application (freeze_id, weight_kg, weighed_at, reason,"
                        + " status, created_at, request_id, request_hash)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(freezeId, weightKg, weighedAt, reason, status, createdAt, requestId,
                        requestHash).update(kh);
        return generatedKey(kh);
    }

    public Optional<ErrataRow> findErrataByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM errata_application WHERE request_id = ?").params(requestId)
                .query(WeighbridgeRepository::mapErrata).optional();
    }

    public List<ErrataRow> listErratas(long freezeId) {
        return jdbc.sql("SELECT * FROM errata_application WHERE freeze_id = ? ORDER BY id")
                .params(freezeId).query(WeighbridgeRepository::mapErrata).list();
    }
}