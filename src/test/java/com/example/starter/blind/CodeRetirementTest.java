package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲态代码退役：主流程（封存、配额重算、既有分配保持有效）、
 * 失败分支（代码不存在/已退役/已耗尽/待揭盲/剩余代码不足）、
 * 整批回滚、历史与明细查询、幂等回放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CodeRetirementTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String id, int blockCount, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private void allocate(String expId, String participantId, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private ResponseEntity<String> retire(String expId, String codesJsonArray, String requestId) {
        return exchange("/api/experiments/" + expId + "/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"codes\":" + codesJsonArray + ",\"reason\":\"供应批次召回\"}");
    }

    @Test
    void retireSingleCode_sealsUnallocatedSeatsAndRecomputesQuota_existingAllocationsIntact()
            throws Exception {
        createExperiment("RET-1", 2, "r1-create");
        allocate("RET-1", "P1", "r1-a1");
        allocate("RET-1", "P2", "r1-a2");
        allocate("RET-1", "P3", "r1-a3");
        String p1BlindCode = json(exchange("/api/experiments/RET-1/participants/P1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null))
                .path("blindCode").asText();

        clock.advance(1_000L);
        // 小写代码也应被规范化受理
        ResponseEntity<String> retired = retire("RET-1", "[\"a\"]", "r1-retire");
        assertEquals(201, retired.getStatusCode().value());
        JsonNode body = json(retired);
        String retirementId = body.path("retirementId").asText();
        assertTrue(retirementId.startsWith("RET-"));
        assertEquals("RET-1", body.path("experimentId").asText());
        assertEquals("供应批次召回", body.path("reason").asText());
        assertEquals("c1", body.path("actorId").asText());
        assertEquals(1_700_000_001_000L, body.path("createdAt").asLong());
        // 配额快照：A 已分配 2（P1、P2），封存 2（区组2的A席），配额 4 -> 0
        JsonNode codeA = body.path("codes").get(0);
        assertEquals("A", codeA.path("treatment").asText());
        assertEquals(2, codeA.path("allocatedCount").asInt());
        assertEquals(2, codeA.path("sealedCount").asInt());
        assertEquals(4, codeA.path("quotaBefore").asInt());
        assertEquals(0, codeA.path("quotaAfter").asInt());
        // 封存序号：区组2的席位1、2（均为未分配的 A 席）
        assertEquals(2, body.path("sealedSeats").size());
        assertEquals(2, body.path("sealedSeats").get(0).path("blockNo").asInt());
        assertEquals(1, body.path("sealedSeats").get(0).path("seatNo").asInt());
        assertEquals(2, body.path("sealedSeats").get(1).path("seatNo").asInt());
        assertEquals(1_700_000_001_000L, body.path("sealedSeats").get(0).path("sealedAt").asLong());

        // 库内终态：两个 A 席 SEALED 且归属本批次；封存明细与快照一致
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'RET-1' AND status = 'SEALED' "
                        + "AND sealed_by_retirement_id = ?", Integer.class, retirementId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE retirement_id = ?", Integer.class,
                retirementId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retired_code WHERE experiment_id = 'RET-1' "
                        + "AND treatment = 'A' AND allocated_count = 2 AND sealed_count = 2 "
                        + "AND quota_before = 4 AND quota_after = 0", Integer.class));
        assertEquals("供应批次召回", jdbc.queryForObject(
                "SELECT reason FROM code_retirement WHERE id = ?", String.class, retirementId));

        // 既有参与者分配保持有效且继续不暴露代码
        JsonNode p1 = json(exchange("/api/experiments/RET-1/participants/P1",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null));
        assertEquals(p1BlindCode, p1.path("blindCode").asText());
        assertEquals("ASSIGNED", p1.path("status").asText());
        assertFalse(p1.has("treatment"));
        assertFalse(p1.has("seatNo"));

        // 退役后新分配不得使用该代码：剩余可分配席位只有 3 个 B 席
        allocate("RET-1", "P4", "r1-a4");
        allocate("RET-1", "P5", "r1-a5");
        allocate("RET-1", "P6", "r1-a6");
        assertEquals(422, exchange("/api/experiments/RET-1/participants/P7/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "r1-a7"), null)
                .getStatusCode().value());
        // 新分配全部落在 B 席（区组1席4、区组2席3/4）
        List<String> newSeats = jdbc.queryForList(
                "SELECT CONCAT(a.block_no, '-', a.seat_no) FROM allocation a "
                        + "WHERE a.experiment_id = 'RET-1' AND a.participant_id IN ('P4','P5','P6') "
                        + "ORDER BY a.block_no, a.seat_no", String.class);
        assertEquals(List.of("1-4", "2-3", "2-4"), newSeats);

        // 容量诊断：区组可用名额与代码配额按封存数量重算
        JsonNode capacity = json(exchange("/api/experiments/RET-1/capacity",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null));
        assertEquals(2, capacity.path("blockCount").asInt());
        JsonNode block1 = capacity.path("blocks").get(0);
        assertEquals(1, block1.path("blockNo").asInt());
        assertEquals(4, block1.path("totalSeats").asInt());
        assertEquals(4, block1.path("occupiedSeats").asInt());
        assertEquals(0, block1.path("sealedSeats").asInt());
        assertEquals(0, block1.path("availableSeats").asInt());
        JsonNode block2 = capacity.path("blocks").get(1);
        assertEquals(2, block2.path("occupiedSeats").asInt());
        assertEquals(2, block2.path("sealedSeats").asInt());
        assertEquals(0, block2.path("availableSeats").asInt());
        JsonNode capA = capacity.path("codes").get(0);
        assertEquals("A", capA.path("treatment").asText());
        assertEquals("RETIRED", capA.path("status").asText());
        assertEquals(4, capA.path("quotaTotal").asInt());
        assertEquals(2, capA.path("allocatedCount").asInt());
        assertEquals(2, capA.path("sealedCount").asInt());
        assertEquals(0, capA.path("remainingAllocatable").asInt());
        JsonNode capB = capacity.path("codes").get(1);
        assertEquals("ACTIVE", capB.path("status").asText());
        assertEquals(4, capB.path("allocatedCount").asInt());
        assertEquals(0, capB.path("remainingAllocatable").asInt());

        // 历史与明细：只读、排序稳定；明细仅 COORDINATOR 可见
        JsonNode history = json(exchange("/api/experiments/RET-1/code-retirements",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null));
        assertEquals(1, history.path("items").size());
        assertEquals(retirementId, history.path("items").get(0).path("retirementId").asText());
        assertFalse(history.path("items").get(0).has("sealedSeats"), "历史摘要不含封存序号");

        JsonNode detail = json(exchange(
                "/api/experiments/RET-1/code-retirements/" + retirementId,
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals(2, detail.path("sealedSeats").size());
        assertEquals("供应批次召回", detail.path("reason").asText());
        assertEquals(403, exchange("/api/experiments/RET-1/code-retirements/" + retirementId,
                HttpMethod.GET, headers("r1", "REVIEWER", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/RET-1/code-retirements/RET-MISSING",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null).getStatusCode().value());

        // 历史区间过滤：UTC 毫秒左闭右开
        long t0 = 1_700_000_001_000L;
        assertEquals(1, json(exchange("/api/experiments/RET-1/code-retirements?from=" + t0
                        + "&to=" + (t0 + 1), HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null)).path("items").size());
        assertEquals(0, json(exchange("/api/experiments/RET-1/code-retirements?to=" + t0,
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)).path("items").size());
        assertEquals(0, json(exchange("/api/experiments/RET-1/code-retirements?from=" + (t0 + 1),
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)).path("items").size());
        assertEquals(400, exchange("/api/experiments/RET-1/code-retirements?from=" + (t0 + 5)
                        + "&to=" + t0, HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
    }

    @Test
    void retireFailures_returnDistinctReasons_andBatchRollsBackEntirely() throws Exception {
        createExperiment("RET-2", 2, "r2-create");
        allocate("RET-2", "P1", "r2-a1");

        // 代码不存在：422 CODE_NOT_FOUND；同批的 A 也不得被处理（整批回滚）
        ResponseEntity<String> notFound = retire("RET-2", "[\"A\",\"C\"]", "r2-retire-c");
        assertEquals(422, notFound.getStatusCode().value());
        JsonNode nfBody = json(notFound);
        assertEquals(1, nfBody.path("violations").size());
        assertEquals("CODE_NOT_FOUND",
                nfBody.path("violations").get(0).path("reasonCode").asText());
        assertEquals("C", nfBody.path("violations").get(0).path("treatment").asText());

        // 退役全部代码：422 INSUFFICIENT_REMAINING_CODES，含实际值/要求值/差额
        ResponseEntity<String> insufficient = retire("RET-2", "[\"A\",\"B\"]", "r2-retire-all");
        assertEquals(422, insufficient.getStatusCode().value());
        JsonNode insViolation = json(insufficient).path("violations").get(0);
        assertEquals("INSUFFICIENT_REMAINING_CODES", insViolation.path("reasonCode").asText());
        assertEquals(0, insViolation.path("actual").asLong());
        assertEquals(1, insViolation.path("required").asLong());
        assertEquals(1, insViolation.path("difference").asLong());

        // 多个不存在代码：逐条列出
        ResponseEntity<String> twoMissing = retire("RET-2", "[\"Y\",\"Z\"]", "r2-retire-yz");
        assertEquals(422, twoMissing.getStatusCode().value());
        assertEquals(2, json(twoMissing).path("violations").size());

        // 以上失败均整批回滚：无任何退役、封存痕迹
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM code_retirement", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE status = 'SEALED'", Integer.class));

        // 正常退役 A 后：重复退役 409 CODE_ALREADY_RETIRED
        assertEquals(201, retire("RET-2", "[\"A\"]", "r2-retire-a").getStatusCode().value());
        ResponseEntity<String> again = retire("RET-2", "[\"A\"]", "r2-retire-a2");
        assertEquals(409, again.getStatusCode().value());
        assertEquals("CODE_ALREADY_RETIRED",
                json(again).path("violations").get(0).path("reasonCode").asText());

        // 混合批次：A 已退役 + B 将使剩余代码为空 → 两类违规同时列出，整体 409
        ResponseEntity<String> mixed = retire("RET-2", "[\"A\",\"B\"]", "r2-retire-ab");
        assertEquals(409, mixed.getStatusCode().value());
        Set<String> reasonCodes = new HashSet<>();
        json(mixed).path("violations").forEach(v -> reasonCodes.add(v.path("reasonCode").asText()));
        assertEquals(Set.of("CODE_ALREADY_RETIRED", "INSUFFICIENT_REMAINING_CODES"), reasonCodes);
        // 整批回滚：B 仍未退役
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retired_code WHERE treatment = 'B'", Integer.class));

        // 参数校验：空集合、空理由、非法代码格式、缺请求体
        assertEquals(400, exchange("/api/experiments/RET-2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r2-bad-1"),
                "{\"codes\":[],\"reason\":\"x\"}").getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/RET-2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r2-bad-2"),
                "{\"codes\":[\"B\"],\"reason\":\" \"}").getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/RET-2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r2-bad-3"),
                "{\"codes\":[\"AB\"],\"reason\":\"x\"}").getStatusCode().value());
        assertEquals(400, exchange("/api/experiments/RET-2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r2-bad-4"), null).getStatusCode().value());

        // 实验不存在 404；REVIEWER 无权退役 403
        assertEquals(404, retire("NOPE", "[\"A\"]", "r2-retire-nope").getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/RET-2/code-retirements", HttpMethod.POST,
                headers("r1", "REVIEWER", "r2-retire-reviewer"),
                "{\"codes\":[\"B\"],\"reason\":\"x\"}").getStatusCode().value());
    }

    @Test
    void retireExhaustedCode_conflicts_withQuantifiedViolation() throws Exception {
        createExperiment("RET-3", 2, "r3-create");
        // 顺序分配 6 人：A 席（1-1,1-2,2-1,2-2）全部占满，B 席占 2
        for (int i = 1; i <= 6; i++) {
            allocate("RET-3", "P" + i, "r3-a" + i);
        }
        ResponseEntity<String> exhausted = retire("RET-3", "[\"A\"]", "r3-retire-a");
        assertEquals(409, exhausted.getStatusCode().value());
        JsonNode violation = json(exhausted).path("violations").get(0);
        assertEquals("CODE_EXHAUSTED", violation.path("reasonCode").asText());
        assertEquals("A", violation.path("treatment").asText());
        assertEquals(0, violation.path("actual").asLong());
        assertEquals(1, violation.path("required").asLong());
        assertEquals(1, violation.path("difference").asLong());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE status = 'SEALED'", Integer.class));

        // B 仍有 2 个未分配序号，可正常退役
        ResponseEntity<String> retireB = retire("RET-3", "[\"B\"]", "r3-retire-b");
        assertEquals(201, retireB.getStatusCode().value());
        assertEquals(2, json(retireB).path("codes").get(0).path("sealedCount").asInt());
    }

    @Test
    void pendingUnblindRequest_blocksRetirement_approvalUnblocks() throws Exception {
        createExperiment("RET-4", 2, "r4-create");
        allocate("RET-4", "P1", "r4-a1");
        // P1 在 A 席；对其提出揭盲申请（待审）
        ResponseEntity<String> apply = exchange(
                "/api/experiments/RET-4/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r4-apply"), "{\"reason\":\"紧急核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();

        // 存在待揭盲紧急申请：退役被阻断，409 且给出申请数量
        ResponseEntity<String> blocked = retire("RET-4", "[\"A\"]", "r4-retire-blocked");
        assertEquals(409, blocked.getStatusCode().value());
        JsonNode violation = json(blocked).path("violations").get(0);
        assertEquals("PENDING_UNBLIND_REQUEST", violation.path("reasonCode").asText());
        assertEquals(1, violation.path("actual").asLong());
        assertEquals(0, violation.path("required").asLong());
        assertEquals(1, violation.path("difference").asLong());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE status = 'SEALED'", Integer.class));

        // 另一名 REVIEWER 批准后不再是“待揭盲”状态，退役放行
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", "r4-approve"), null)
                .getStatusCode().value());
        assertEquals(201, retire("RET-4", "[\"A\"]", "r4-retire-ok").getStatusCode().value());
        // 已批准揭盲结果不受退役影响
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)
                .getStatusCode().value());
    }

    @Test
    void retirementWrites_areIdempotent_andNormalizedCodeSetSharesFingerprint() throws Exception {
        createExperiment("RET-5", 2, "r5-create");
        allocate("RET-5", "P1", "r5-a1");

        // 同键同参重放：原样返回首次快照，不产生第二个批次
        ResponseEntity<String> first = retire("RET-5", "[\"A\"]", "r5-retire-key");
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = retire("RET-5", "[\"A\"]", "r5-retire-key");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id = 'RET-5'",
                Integer.class));

        // 同键异参（换理由 / 换代码集合）：409
        assertEquals(409, exchange("/api/experiments/RET-5/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r5-retire-key"),
                "{\"codes\":[\"A\"],\"reason\":\"另一个理由\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/RET-5/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r5-retire-key"),
                "{\"codes\":[\"B\"],\"reason\":\"供应批次召回\"}").getStatusCode().value());

        // 规范化对象集合：["b","A","a"] 与 ["A","B"] 是同一逻辑操作，共享幂等指纹
        createExperiment("RET-6", 2, "r6-create");
        ResponseEntity<String> messy = exchange("/api/experiments/RET-6/code-retirements",
                HttpMethod.POST, headers("c1", "COORDINATOR", "r6-retire-key"),
                "{\"codes\":[\"b\",\"A\",\"a\"],\"reason\":\"合并批次\"}");
        // A、B 同批退役会使剩余有效代码为空：422，失败不占键
        assertEquals(422, messy.getStatusCode().value());
        ResponseEntity<String> canonical = exchange("/api/experiments/RET-6/code-retirements",
                HttpMethod.POST, headers("c1", "COORDINATOR", "r6-retire-key"),
                "{\"codes\":[\"A\"],\"reason\":\"合并批次\"}");
        // 换为规范化后的不同集合：与上一失败请求无关，正常裁决（201）
        assertEquals(201, canonical.getStatusCode().value());
        // 同一键再以等价的未规范化集合重放：命中首次成功快照
        ResponseEntity<String> equivalent = exchange("/api/experiments/RET-6/code-retirements",
                HttpMethod.POST, headers("c1", "COORDINATOR", "r6-retire-key"),
                "{\"codes\":[\"a\",\"A\"],\"reason\":\"合并批次\"}");
        assertEquals(201, equivalent.getStatusCode().value());
        assertEquals(canonical.getBody(), equivalent.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id = 'RET-6'",
                Integer.class));
    }

    @Test
    void retirementRecord_isImmutable_andSurvivesLaterOperations() throws Exception {
        createExperiment("RET-7", 2, "r7-create");
        allocate("RET-7", "P1", "r7-a1");
        ResponseEntity<String> retired = retire("RET-7", "[\"A\"]", "r7-retire");
        String retirementId = json(retired).path("retirementId").asText();
        String snapshot = retired.getBody();

        clock.advance(60_000L);
        // 后续业务操作：退组、关闭实验，均不改写退役记录
        assertEquals(200, exchange("/api/experiments/RET-7/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "r7-withdraw"), null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/experiments/RET-7/close", HttpMethod.POST,
                headers("c1", "COORDINATOR", "r7-close"), null).getStatusCode().value());

        JsonNode detail = json(exchange("/api/experiments/RET-7/code-retirements/" + retirementId,
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals(1_700_000_000_000L, detail.path("createdAt").asLong());
        assertEquals("供应批次召回", detail.path("reason").asText());
        assertEquals("c1", detail.path("actorId").asText());
        assertEquals(1, detail.path("codes").get(0).path("allocatedCount").asInt());
        assertEquals(3, detail.path("codes").get(0).path("sealedCount").asInt());
        // 退役响应的完整快照不随后续操作改写
        assertEquals(mapper.readTree(snapshot), detail);
        // 库内快照未被改写
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retired_code WHERE retirement_id = ? "
                        + "AND allocated_count = 1 AND sealed_count = 3",
                Integer.class, retirementId));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE retirement_id = ?",
                Integer.class, retirementId));

        // 已关闭实验上的容量诊断仍只读可用
        assertEquals(200, exchange("/api/experiments/RET-7/capacity",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null).getStatusCode().value());
    }
}
