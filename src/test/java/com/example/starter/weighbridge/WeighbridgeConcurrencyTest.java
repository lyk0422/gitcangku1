package com.example.starter.weighbridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 幂等与并发边界：同参重放、异参 409、并发裁决/冻结/复称竞争，均设超时并核对最终数据。
 */
class WeighbridgeConcurrencyTest extends AbstractWebTest {

    @Test
    void idempotent_sameParamsReplayed_differentParams409() throws Exception {
        long orderId = createOrder("TO-2001", "沪D-T2001");
        Map<String, Object> tare = Map.of("phase", "ENTRY_TARE", "weightKg", 8000,
                "weighedAt", "2026-10-04T09:00:00", "requestId", "req-idem-1");
        JsonNode first = postJson("/api/transport-orders/" + orderId + "/weighings", tare, 201);
        JsonNode replay = postJson("/api/transport-orders/" + orderId + "/weighings", tare, 201);
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing_record WHERE transport_order_id = ?",
                Integer.class, orderId)).isEqualTo(1);

        Map<String, Object> different = Map.of("phase", "ENTRY_TARE", "weightKg", 8100,
                "weighedAt", "2026-10-04T09:00:00", "requestId", "req-idem-1");
        postJson("/api/transport-orders/" + orderId + "/weighings", different, 409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM weighing_record WHERE transport_order_id = ?",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void idempotent_freezeReplay_sameSnapshot() throws Exception {
        long orderId = createOrder("TO-2002", "沪D-T2002");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-t-2002");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-g-2002");
        JsonNode first = freeze(orderId, "req-f-2002");
        JsonNode replay = freeze(orderId, "req-f-2002");
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM net_weight_freeze WHERE transport_order_id = ?",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void concurrent_adjudication_onlyOneFinalDecision() throws Exception {
        long orderId = createOrder("TO-2003", "沪D-T2003");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-t-2003");
        long grossId = weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-g-2003");
        long disputeId = applyReweigh(orderId, "异议", "req-d-2003");
        long reweighId = reweigh(disputeId, 12100, "2026-10-04T10:10:00", "req-r-2003");

        List<Integer> statuses = runConcurrently(
                adjudicateCall(disputeId, grossId, "req-a-1"),
                adjudicateCall(disputeId, reweighId, "req-a-2"));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM adjudication WHERE dispute_id = ?",
                Integer.class, disputeId)).isEqualTo(1);

        JsonNode frozen = freeze(orderId, "req-f-2003");
        long adopted = jdbc.queryForObject(
                "SELECT adopted_weighing_id FROM adjudication WHERE dispute_id = ?",
                Long.class, disputeId);
        assertThat(frozen.get("adoptedWeighingId").asLong()).isEqualTo(adopted);
        assertThat(frozen.get("netWeightKg").asInt())
                .isEqualTo(adopted == grossId ? 4000 : 4100);
    }

    @Test
    void concurrent_freeze_onlyOneSnapshot() throws Exception {
        long orderId = createOrder("TO-2004", "沪D-T2004");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-t-2004");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-g-2004");

        List<Integer> statuses = runConcurrently(
                freezeCall(orderId, "req-f-a"), freezeCall(orderId, "req-f-b"));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM net_weight_freeze WHERE transport_order_id = ?",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void concurrent_reweighVsFreeze_noSettleWithOpenDispute() throws Exception {
        long orderId = createOrder("TO-2005", "沪D-T2005");
        weigh(orderId, "ENTRY_TARE", 8000, "2026-10-04T09:00:00", "req-t-2005");
        weigh(orderId, "EXIT_GROSS", 12000, "2026-10-04T10:00:00", "req-g-2005");
        long disputeId = applyReweigh(orderId, "异议", "req-d-2005");

        List<Integer> statuses = runConcurrently(
                reweighCall(disputeId, 12100, "req-r-2005"), freezeCall(orderId, "req-f-2005"));
        int reweighStatus = statuses.get(0);
        int freezeStatus = statuses.get(1);
        assertThat(reweighStatus).isIn(201, 202);
        assertThat(freezeStatus).isIn(201, 409);

        Integer freezeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM net_weight_freeze WHERE transport_order_id = ?",
                Integer.class, orderId);
        Integer openDisputes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispute WHERE transport_order_id = ? AND status = 'OPEN'",
                Integer.class, orderId);
        if (freezeCount == 1) {
            // 冻结成功则争议必已不在 OPEN 状态之外的路径：本场景争议仍 OPEN，故冻结不应存在
            assertThat(openDisputes).isZero();
        } else {
            assertThat(freezeStatus).isEqualTo(409);
        }
        // 最终数据一致：冻结与复称记录不同时缺失版本引用
        JsonNode overview = getJson("/api/transport-orders/" + orderId + "/overview", 200);
        if (overview.get("freeze") != null && !overview.get("freeze").isNull()) {
            int version = overview.get("freeze").get("weighingVersion").asInt();
            assertThat(version).isEqualTo(overview.get("weighings").size());
        }
    }

    private Callable<Integer> adjudicateCall(long disputeId, long adoptedId, String requestId) {
        return () -> statusOf(post("/api/disputes/" + disputeId + "/adjudications"),
                Map.of("adoptedWeighingId", adoptedId, "reason", "并发裁决",
                        "decidedBy", "referee-01", "requestId", requestId));
    }

    private Callable<Integer> freezeCall(long orderId, String requestId) {
        return () -> statusOf(post("/api/transport-orders/" + orderId + "/net-weight-freeze"),
                Map.of("requestId", requestId));
    }

    private Callable<Integer> reweighCall(long disputeId, int weightKg, String requestId) {
        return () -> statusOf(post("/api/disputes/" + disputeId + "/weighings"),
                Map.of("weightKg", weightKg, "weighedAt", "2026-10-04T10:20:00",
                        "requestId", requestId));
    }

    private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                         Object body) throws Exception {
        MvcResult result = mvc.perform(builder
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        return result.getResponse().getStatus();
    }

    private List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> wrapFirst = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return first.call();
            };
            Callable<Integer> wrapSecond = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return second.call();
            };
            Future<Integer> f1 = pool.submit(wrapFirst);
            Future<Integer> f2 = pool.submit(wrapSecond);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}