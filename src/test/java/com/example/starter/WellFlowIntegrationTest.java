package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 主流程与失败分支的 HTTP 集成测试（真实 H2，MODE=MySQL）。
 */
class WellFlowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void happyPathSwitchApprovedAndTimelineFrozen() throws Exception {
        createInjectionSegment("W-HAPPY");
        List<Long> sampleIds = addFourValidSamples("W-HAPPY");
        long requestId = createPendingSwitch("W-HAPPY", "sw-1");

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("requestId", "dec-1");
        decision.put("approve", true);
        JsonNode approved = callPost("/api/switch-requests/" + requestId + "/decisions", decision, 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("newModeStart").asText()).isEqualTo(T0.toString());
        long newSegmentId = approved.get("newSegmentId").asLong();

        JsonNode timeline = timeline("W-HAPPY");
        JsonNode segments = timeline.get("segments");
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).get("mode").asText()).isEqualTo("INJECTION");
        assertThat(segments.get(0).get("endTime").asText()).isEqualTo(T0.toString());
        assertThat(segments.get(1).get("id").asLong()).isEqualTo(newSegmentId);
        assertThat(segments.get(1).get("mode").asText()).isEqualTo("PRODUCTION");
        assertThat(segments.get(1).get("startTime").asText()).isEqualTo(T0.toString());
        assertThat(segments.get(1).get("endTime").isNull()).isTrue();

        JsonNode requests = timeline.get("switchRequests");
        assertThat(requests).hasSize(1);
        JsonNode frozen = requests.get(0).get("frozenSamples");
        assertThat(frozen).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(frozen.get(i).get("id").asLong()).isEqualTo(sampleIds.get(i));
        }
        assertThat(requests.get(0).get("request").get("rejectReason").isNull()).isTrue();
    }

    @Test
    void outOfRangeReadingRejectedAndOldModeKept() throws Exception {
        createInjectionSegment("W-RANGE");
        callPost("/api/wells/W-RANGE/samples", sampleBody("smp-1", "10.0", T0.minus(12, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-RANGE/samples", sampleBody("smp-2", "12.1", T0.minus(9, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-RANGE/samples", sampleBody("smp-3", "10.5", T0.minus(6, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-RANGE/samples", sampleBody("smp-4", "11.0", T0.minus(3, ChronoUnit.MINUTES)), 201);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "sw-1");
        body.put("targetMode", "PRODUCTION");
        JsonNode rejected = callPost("/api/wells/W-RANGE/switch-requests", body, 422);
        assertThat(rejected.get("message").asText()).contains("12.1");
        long switchRequestId = rejected.get("switchRequestId").asLong();

        JsonNode timeline = timeline("W-RANGE");
        JsonNode segments = timeline.get("segments");
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).get("mode").asText()).isEqualTo("INJECTION");
        assertThat(segments.get(0).get("endTime").isNull()).isTrue();
        JsonNode requests = timeline.get("switchRequests");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("request").get("id").asLong()).isEqualTo(switchRequestId);
        assertThat(requests.get(0).get("request").get("status").asText()).isEqualTo("REJECTED");
        assertThat(requests.get(0).get("request").get("rejectReason").asText()).contains("12.1");
    }

    @Test
    void insufficientSamplesRejected() throws Exception {
        createInjectionSegment("W-FEW");
        callPost("/api/wells/W-FEW/samples", sampleBody("smp-1", "10.0", T0.minus(9, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-FEW/samples", sampleBody("smp-2", "10.5", T0.minus(6, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-FEW/samples", sampleBody("smp-3", "11.0", T0.minus(3, ChronoUnit.MINUTES)), 201);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "sw-1");
        body.put("targetMode", "PRODUCTION");
        JsonNode rejected = callPost("/api/wells/W-FEW/switch-requests", body, 422);
        assertThat(rejected.get("message").asText()).contains("不足");
    }

    @Test
    void gapOverFiveMinutesRejected() throws Exception {
        createInjectionSegment("W-GAP");
        callPost("/api/wells/W-GAP/samples", sampleBody("smp-1", "10.0", T0.minus(14, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-GAP/samples", sampleBody("smp-2", "10.5", T0.minus(10, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-GAP/samples", sampleBody("smp-3", "11.0", T0.minus(4, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-GAP/samples", sampleBody("smp-4", "10.2", T0.minus(1, ChronoUnit.MINUTES)), 201);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "sw-1");
        body.put("targetMode", "PRODUCTION");
        JsonNode rejected = callPost("/api/wells/W-GAP/switch-requests", body, 422);
        assertThat(rejected.get("message").asText()).contains("间隔");
    }

    @Test
    void timeReversalSampleRejected() throws Exception {
        createInjectionSegment("W-TIME");
        callPost("/api/wells/W-TIME/samples",
                sampleBody("smp-0", "10.0", T0.minus(2, ChronoUnit.HOURS)), 422);
        callPost("/api/wells/W-TIME/samples", sampleBody("smp-1", "10.0", T0.minus(3, ChronoUnit.MINUTES)), 201);
        callPost("/api/wells/W-TIME/samples",
                sampleBody("smp-2", "10.5", T0.minus(6, ChronoUnit.MINUTES)), 422);
    }

    @Test
    void segmentOverlapConflict() throws Exception {
        createInjectionSegment("W-SEG");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("requestId", "seg-2");
        second.put("mode", "PRODUCTION");
        second.put("startTime", T0.toString());
        JsonNode conflict = callPost("/api/wells/W-SEG/run-segments", second, 409);
        assertThat(conflict.get("message").asText()).contains("进行中");

        // 模拟旧段已结束：新段起点早于旧段结束时刻 → 409 重叠
        jdbc.update("UPDATE run_segment SET end_time = ? WHERE well_no = ?", Timestamp.from(T0), "W-SEG");
        Map<String, Object> overlap = new LinkedHashMap<>();
        overlap.put("requestId", "seg-3");
        overlap.put("mode", "INJECTION");
        overlap.put("startTime", T0.minus(1, ChronoUnit.SECONDS).toString());
        JsonNode overlapped = callPost("/api/wells/W-SEG/run-segments", overlap, 409);
        assertThat(overlapped.get("message").asText()).contains("重叠");

        // 起点接在旧段结束之后 → 201
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requestId", "seg-4");
        after.put("mode", "INJECTION");
        after.put("startTime", T0.plus(1, ChronoUnit.MINUTES).toString());
        callPost("/api/wells/W-SEG/run-segments", after, 201);
    }
    @Test
    void sampleAndSwitchIdempotency() throws Exception {
        createInjectionSegment("W-IDEM");
        addFourValidSamples("W-IDEM");
        Map<String, Object> sample = sampleBody("smp-x", "10.5", T0.minus(1, ChronoUnit.MINUTES));
        JsonNode first = callPost("/api/wells/W-IDEM/samples", sample, 201);
        JsonNode replay = callPost("/api/wells/W-IDEM/samples", sample, 201);
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM pressure_sample", Long.class);
        assertThat(count).isEqualTo(5);

        JsonNode conflict = callPost("/api/wells/W-IDEM/samples",
                sampleBody("smp-x", "11.5", T0.minus(1, ChronoUnit.MINUTES)), 409);
        assertThat(conflict.get("message").asText()).contains("参数不一致");

        long requestId = createPendingSwitch("W-IDEM", "sw-1");
        Map<String, Object> sameBody = new LinkedHashMap<>();
        sameBody.put("requestId", "sw-1");
        sameBody.put("targetMode", "PRODUCTION");
        JsonNode replayed = callPost("/api/wells/W-IDEM/switch-requests", sameBody, 201);
        assertThat(replayed.get("id").asLong()).isEqualTo(requestId);

        Map<String, Object> differentBody = new LinkedHashMap<>();
        differentBody.put("requestId", "sw-1");
        differentBody.put("targetMode", "INJECTION");
        callPost("/api/wells/W-IDEM/switch-requests", differentBody, 409);
    }

    @Test
    void decisionIdempotencyAndDoubleDecision() throws Exception {
        createInjectionSegment("W-DEC");
        addFourValidSamples("W-DEC");
        long requestId = createPendingSwitch("W-DEC", "sw-1");

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("requestId", "dec-1");
        decision.put("approve", true);
        JsonNode approved = callPost("/api/switch-requests/" + requestId + "/decisions", decision, 200);
        JsonNode replayed = callPost("/api/switch-requests/" + requestId + "/decisions", decision, 200);
        assertThat(replayed.get("status").asText()).isEqualTo("APPROVED");
        assertThat(replayed.get("newSegmentId").asLong()).isEqualTo(approved.get("newSegmentId").asLong());

        Map<String, Object> different = new LinkedHashMap<>();
        different.put("requestId", "dec-1");
        different.put("approve", false);
        callPost("/api/switch-requests/" + requestId + "/decisions", different, 409);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("requestId", "dec-2");
        second.put("approve", true);
        callPost("/api/switch-requests/" + requestId + "/decisions", second, 409);
    }

    @Test
    void secondPendingSwitchConflict() throws Exception {
        createInjectionSegment("W-PEND");
        addFourValidSamples("W-PEND");
        createPendingSwitch("W-PEND", "sw-1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "sw-2");
        body.put("targetMode", "PRODUCTION");
        JsonNode conflict = callPost("/api/wells/W-PEND/switch-requests", body, 409);
        assertThat(conflict.get("message").asText()).contains("待审核");
    }

    @Test
    void voidKeepsApprovedSnapshotFrozen() throws Exception {
        createInjectionSegment("W-VOID");
        List<Long> sampleIds = addFourValidSamples("W-VOID");
        long requestId = createPendingSwitch("W-VOID", "sw-1");
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("requestId", "dec-1");
        decision.put("approve", true);
        callPost("/api/switch-requests/" + requestId + "/decisions", decision, 200);

        Map<String, Object> voidBody = new LinkedHashMap<>();
        voidBody.put("requestId", "void-1");
        voidBody.put("reason", "传感器离群读数");
        voidBody.put("replacementPressureMpa", "10.4");
        voidBody.put("replacementSampledAt", T0.minus(2, ChronoUnit.MINUTES).toString());
        JsonNode voided = callPost("/api/wells/W-VOID/samples/" + sampleIds.get(3) + "/void", voidBody, 200);
        assertThat(voided.get("voidedSample").get("voided").asBoolean()).isTrue();
        assertThat(voided.get("voidedSample").get("voidReason").asText()).contains("离群");
        long replacementId = voided.get("replacement").get("id").asLong();

        JsonNode timeline = timeline("W-VOID");
        JsonNode frozen = timeline.get("switchRequests").get(0).get("frozenSamples");
        assertThat(frozen).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(frozen.get(i).get("id").asLong()).isEqualTo(sampleIds.get(i));
        }
        assertThat(frozen.get(3).get("voided").asBoolean()).isTrue();
        assertThat(frozen.get(3).get("replacedById").asLong()).isEqualTo(replacementId);

        JsonNode replayed = callPost("/api/wells/W-VOID/samples/" + sampleIds.get(3) + "/void", voidBody, 200);
        assertThat(replayed.get("replacement").get("id").asLong()).isEqualTo(replacementId);

        Map<String, Object> different = new LinkedHashMap<>(voidBody);
        different.put("replacementPressureMpa", "9.9");
        callPost("/api/wells/W-VOID/samples/" + sampleIds.get(3) + "/void", different, 409);

        Map<String, Object> again = new LinkedHashMap<>(voidBody);
        again.put("requestId", "void-2");
        callPost("/api/wells/W-VOID/samples/" + sampleIds.get(3) + "/void", again, 409);
    }

    @Test
    void voidBreakingConditionFailsPendingApproval() throws Exception {
        createInjectionSegment("W-BREAK");
        List<Long> sampleIds = addFourValidSamples("W-BREAK");
        long requestId = createPendingSwitch("W-BREAK", "sw-1");

        Map<String, Object> voidBody = new LinkedHashMap<>();
        voidBody.put("requestId", "void-1");
        voidBody.put("reason", "读数漂移");
        voidBody.put("replacementPressureMpa", "12.5");
        voidBody.put("replacementSampledAt", T0.minus(2, ChronoUnit.MINUTES).toString());
        callPost("/api/wells/W-BREAK/samples/" + sampleIds.get(3) + "/void", voidBody, 200);

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("requestId", "dec-1");
        decision.put("approve", true);
        JsonNode rejected = callPost("/api/switch-requests/" + requestId + "/decisions", decision, 422);
        assertThat(rejected.get("message").asText()).contains("12.5");

        JsonNode timeline = timeline("W-BREAK");
        JsonNode segments = timeline.get("segments");
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).get("mode").asText()).isEqualTo("INJECTION");
        assertThat(segments.get(0).get("endTime").isNull()).isTrue();
        JsonNode request = timeline.get("switchRequests").get(0).get("request");
        assertThat(request.get("status").asText()).isEqualTo("REJECTED");
        assertThat(request.get("rejectReason").asText()).contains("12.5");
    }

    @Test
    void operatorRejectionKeepsOldMode() throws Exception {
        createInjectionSegment("W-REJ");
        addFourValidSamples("W-REJ");
        long requestId = createPendingSwitch("W-REJ", "sw-1");

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("requestId", "dec-1");
        decision.put("approve", false);
        decision.put("reason", "现场复核未通过");
        JsonNode rejected = callPost("/api/switch-requests/" + requestId + "/decisions", decision, 200);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectReason").asText()).contains("现场复核未通过");

        JsonNode timeline = timeline("W-REJ");
        assertThat(timeline.get("segments")).hasSize(1);
        assertThat(timeline.get("segments").get(0).get("endTime").isNull()).isTrue();
    }
}