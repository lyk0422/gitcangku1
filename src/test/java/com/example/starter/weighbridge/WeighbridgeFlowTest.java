package com.example.starter.weighbridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 主流程与拒绝分支：合成皮重 8000、毛重 12000、复称 12100，净重 4000/4100。
 */
class WeighbridgeFlowTest extends AbstractWebTest {

    @Test
    void mainFlow_tareGrossFreeze_netWeight4000() throws Exception {
        long orderId = createOrder("TO-1001", "沪D-T0001");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-1");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-gross-1");

        JsonNode frozen = freeze(orderId, "req-freeze-1");
        assertThat(frozen.get("tareWeightKg").asInt()).isEqualTo(8000);
        assertThat(frozen.get("grossWeightKg").asInt()).isEqualTo(12000);
        assertThat(frozen.get("netWeightKg").asInt()).isEqualTo(4000);
        assertThat(frozen.get("weighingVersion").asInt()).isEqualTo(2);

        JsonNode overview = getJson("/api/transport-orders/" + orderId + "/overview", 200);
        assertThat(overview.get("weighings")).hasSize(2);
        assertThat(overview.get("freeze").get("netWeightKg").asInt()).isEqualTo(4000);
        assertThat(overview.get("order").get("vehicleNo").asText()).isEqualTo("沪D-T0001");
    }

    @Test
    void disputeFlow_reweigh12100Adopted_freeze4100() throws Exception {
        long orderId = createOrder("TO-1002", "沪D-T0002");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-2");
        long grossId = weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-gross-2");
        clock.plus(Duration.ofMinutes(10));

        long disputeId = applyReweigh(orderId, "司机对净重有异议", "req-dispute-2");
        long reweighId = reweigh(disputeId, 12100, "2026-10-04T10:20:00", "req-reweigh-2");
        adjudicate(disputeId, reweighId, "req-adj-2");

        JsonNode frozen = freeze(orderId, "req-freeze-2");
        assertThat(frozen.get("netWeightKg").asInt()).isEqualTo(4100);
        assertThat(frozen.get("adoptedWeighingId").asLong()).isEqualTo(reweighId);
        assertThat(frozen.get("adoptedWeighingId").asLong()).isNotEqualTo(grossId);

        JsonNode disputes = getJson("/api/transport-orders/" + orderId + "/disputes", 200);
        assertThat(disputes).hasSize(1);
        assertThat(disputes.get(0).get("status").asText()).isEqualTo("ADJUDICATED");
        assertThat(disputes.get(0).get("adjudication").get("reason").asText())
                .isEqualTo("裁决留痕理由");
        assertThat(disputes.get(0).get("adjudication").get("adoptedWeighingId").asLong())
                .isEqualTo(reweighId);
    }

    @Test
    void reweighRejected_originalGrossStillAdoptable() throws Exception {
        long orderId = createOrder("TO-1003", "沪D-T0003");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-3");
        long grossId = weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-gross-3");
        clock.plus(Duration.ofMinutes(5));

        long disputeId = applyReweigh(orderId, "司机异议", "req-dispute-3");
        reweigh(disputeId, 12100, "2026-10-04T10:15:00", "req-reweigh-3");
        adjudicate(disputeId, grossId, "req-adj-3");

        JsonNode frozen = freeze(orderId, "req-freeze-3");
        assertThat(frozen.get("netWeightKg").asInt()).isEqualTo(4000);
        assertThat(frozen.get("adoptedWeighingId").asLong()).isEqualTo(grossId);
    }

    @Test
    void reject_exitGrossWithoutEntryTare_422() throws Exception {
        long orderId = createOrder("TO-1004", "沪D-T0004");
        postJson("/api/transport-orders/" + orderId + "/weighings",
                Map.of("phase", "EXIT_GROSS", "weightKg", 12000,
                        "weighedAt", "2026-10-04T10:00:00", "requestId", "req-g-4"), 422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM net_weight_freeze WHERE transport_order_id = ?",
                Integer.class, orderId)).isZero();
    }

    @Test
    void reject_negativeNetWeight_422() throws Exception {
        long orderId = createOrder("TO-1005", "沪D-T0005");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-5");
        postJson("/api/transport-orders/" + orderId + "/weighings",
                Map.of("phase", "EXIT_GROSS", "weightKg", 7999,
                        "weighedAt", "2026-10-04T10:00:00", "requestId", "req-g-5"), 422);
    }

    @Test
    void reject_timeRegression_422() throws Exception {
        long orderId = createOrder("TO-1006", "沪D-T0006");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-6");
        postJson("/api/transport-orders/" + orderId + "/weighings",
                Map.of("phase", "EXIT_GROSS", "weightKg", 12000,
                        "weighedAt", "2026-10-04T08:59:00", "requestId", "req-g-6"), 422);
    }

    @Test
    void reject_overdueReweigh_422() throws Exception {
        long orderId = createOrder("TO-1007", "沪D-T0007");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-7");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-gross-7");
        clock.plus(Duration.ofMinutes(91));
        postJson("/api/transport-orders/" + orderId + "/reweigh-requests",
                Map.of("reason", "逾期异议", "requestId", "req-dispute-7"), 422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispute WHERE transport_order_id = ?",
                Integer.class, orderId)).isZero();
    }

    @Test
    void reject_freezeWithOpenDispute_409() throws Exception {
        long orderId = createOrder("TO-1008", "沪D-T0008");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-8");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-gross-8");
        applyReweigh(orderId, "未裁决异议", "req-dispute-8");
        postJson("/api/transport-orders/" + orderId + "/net-weight-freeze",
                Map.of("requestId", "req-freeze-8"), 409);
    }

    @Test
    void reject_adjudicateInvalidWeighing_422() throws Exception {
        long orderId = createOrder("TO-1009", "沪D-T0009");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-9");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-gross-9");
        long disputeId = applyReweigh(orderId, "异议", "req-dispute-9");
        postJson("/api/disputes/" + disputeId + "/adjudications",
                Map.of("adoptedWeighingId", 999999, "reason", "非法采纳",
                        "decidedBy", "referee-01", "requestId", "req-adj-9"), 422);
    }

    @Test
    void postFreeze_newWeighingBecomesErrata_snapshotUntouched() throws Exception {
        long orderId = createOrder("TO-1010", "沪D-T0010");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-tare-10");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-gross-10");
        long disputeId = applyReweigh(orderId, "异议", "req-dispute-10");
        long grossId = jdbc.queryForObject(
                "SELECT id FROM weighing_record WHERE transport_order_id = ? AND phase = 'EXIT_GROSS'",
                Long.class, orderId);
        adjudicate(disputeId, grossId, "req-adj-10");
        freeze(orderId, "req-freeze-10");

        JsonNode errata = postJson("/api/disputes/" + disputeId + "/weighings",
                Map.of("weightKg", 12300, "weighedAt", "2026-10-04T11:00:00",
                        "requestId", "req-reweigh-10"), 202);
        assertThat(errata.get("status").asText()).isEqualTo("PENDING");

        JsonNode overview = getJson("/api/transport-orders/" + orderId + "/overview", 200);
        assertThat(overview.get("freeze").get("netWeightKg").asInt()).isEqualTo(4000);
        assertThat(overview.get("freeze").get("weighingVersion").asInt()).isEqualTo(2);
        assertThat(overview.get("erratas")).hasSize(1);
        assertThat(overview.get("weighings")).hasSize(2);
    }
}