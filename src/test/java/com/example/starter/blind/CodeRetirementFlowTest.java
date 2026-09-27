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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲态代码退役主流程与失败分支（真实 H2）：
 * 原子封存、配额重算、新分配跳过封存序号、既有分配有效且不暴露代码、
 * 代码不存在/已全部分配/待揭盲申请/剩余代码不足的可区分错误、多代码整批回滚。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CodeRetirementFlowTest extends AbstractBlindIntegrationTest {

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

    private String allocate(String expId, String pid, String requestId) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value());
        return resp.getBody();
    }

    private String retire(String expId, String reason, List<String> codes, String requestId) {
        String body = "{\"reason\":\"" + reason + "\",\"codes\":["
                + String.join(",", codes.stream().map(c -> "\"" + c + "\"").toList()) + "]}";
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId), body);
        assertEquals(201, resp.getStatusCode().value(), "退役应成功: " + resp.getBody());
        return resp.getBody();
    }

    @Test
    void retireCodeB_sealsAllUnassignedSeats_recomputesQuotas_andNewAllocationsSkipSealed()
            throws Exception {
        createExperiment("RT-1", 2, "rt1-create");
        // 先分配 3 人：块1 的 seat1(A)、seat2(A)、seat3(B) 被占用（按区组/席位顺序）
        allocate("RT-1", "P1", "rt1-alloc-1");
        allocate("RT-1", "P2", "rt1-alloc-2");
        allocate("RT-1", "P3", "rt1-alloc-3");
        clock.advance(60_000L);

        String body = retire("RT-1", "B 代码供应终止，封存剩余序号", List.of("B"), "rt1-retire");
        JsonNode view = mapper.readTree(body);

        String retirementId = view.path("retirementId").asText();
        assertTrue(retirementId.startsWith("RT-"));
        assertEquals("RT-1", view.path("experimentId").asText());
        assertEquals("B 代码供应终止，封存剩余序号", view.path("reason").asText());
        assertEquals("c1", view.path("operatorActor").asText());
        assertEquals("COMMITTED", view.path("status").asText());
        assertEquals(1_700_000_060_000L, view.path("createdAt").asLong());
        assertEquals(view.path("createdAt").asLong(), view.path("committedAt").asLong());

        // 代码明细：B 总配额 4，已分配 1，封存 3，后态 0
        JsonNode codeB = view.path("codes").get(0);
        assertEquals("B", codeB.path("treatment").asText());
        assertEquals(4, codeB.path("quotaBefore").asInt());
        assertEquals(1, codeB.path("assignedCount").asInt());
        assertEquals(3, codeB.path("sealedCount").asInt());
        assertEquals(0, codeB.path("quotaAfter").asInt());

        // 区组快照：块1 仅剩 seat4(B) 可分配，封存 1 个后名额 0（块2 仍有 2 个 A 名额，全局通过）
        JsonNode block1 = view.path("blocks").get(0);
        assertEquals(1, block1.path("availableBefore").asInt());
        assertEquals(1, block1.path("sealedInBlock").asInt());
        assertEquals(0, block1.path("availableAfter").asInt());
        assertEquals(0, block1.path("quotaAAfter").asInt());
        assertEquals(0, block1.path("quotaBAfter").asInt());
        JsonNode block2 = view.path("blocks").get(1);
        assertEquals(4, block2.path("availableBefore").asInt());
        assertEquals(2, block2.path("sealedInBlock").asInt());
        assertEquals(2, block2.path("availableAfter").asInt());

        // 库内实际封存：3 行，均为 B，序号为块1 seat4、块2 seat3/seat4
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE retirement_id = ?", Integer.class,
                retirementId));
        List<String> sealedKeys = jdbc.queryForList(
                "SELECT CONCAT(block_no,'-',seat_no) FROM sealed_seat WHERE retirement_id = ? "
                        + "ORDER BY block_no, seat_no", String.class, retirementId);
        assertEquals(List.of("1-4", "2-3", "2-4"), sealedKeys);
        List<String> sealedTreatments = jdbc.queryForList(
                "SELECT DISTINCT treatment FROM sealed_seat WHERE retirement_id = ?",
                String.class, retirementId);
        assertEquals(List.of("B"), sealedTreatments);

        // 封存明细视图不暴露席位号与处理代码
        ResponseEntity<String> sealedView = exchange(
                "/api/code-retirements/" + retirementId + "/sealed-seats", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, sealedView.getStatusCode().value());
        JsonNode sealedArray = json(sealedView);
        assertEquals(3, sealedArray.size());
        for (JsonNode entry : sealedArray) {
            assertFalse(entry.has("seatNo"), "封存明细不得暴露席位序号");
            assertFalse(entry.has("treatment"), "封存明细不得暴露处理代码");
            assertTrue(entry.has("entryId"));
            assertTrue(entry.has("blockNo"));
        }
        // 稳定排序：块1 在前
        assertEquals(1, sealedArray.get(0).path("blockNo").asInt());

        // 退役后继续分配：剩余可分配为 A 序号 1-2(块1 已占用)→实际 seat：块1 A 已满(seat1/2占用)，
        // 故下一空位是块2 的 seat1(A)、seat2(A)，共还能分配 2 人。
        allocate("RT-1", "P4", "rt1-alloc-4");
        allocate("RT-1", "P5", "rt1-alloc-5");
        Integer blockNoP4 = jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE experiment_id='RT-1' AND participant_id='P4'",
                Integer.class);
        Integer seatNoP4 = jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id='RT-1' AND participant_id='P4'",
                Integer.class);
        assertEquals(2, blockNoP4);
        assertEquals(1, seatNoP4, "新分配必须跳过已封存的 B 序号");
        Integer blockNoP5 = jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE experiment_id='RT-1' AND participant_id='P5'",
                Integer.class);
        Integer seatNoP5 = jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id='RT-1' AND participant_id='P5'",
                Integer.class);
        assertEquals(2, blockNoP5);
        assertEquals(2, seatNoP5);

        // 再分配：有效名额耗尽（A 全占用、B 全占用或封存）→ 422
        ResponseEntity<String> full = exchange(
                "/api/experiments/RT-1/participants/P6/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt1-alloc-6"), null);
        assertEquals(422, full.getStatusCode().value());

        // 诊断：B availableQuota=0；区组名额实际值
        ResponseEntity<String> diagResp = exchange(
                "/api/experiments/RT-1/retirement-diagnostics", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, diagResp.getStatusCode().value());
        JsonNode diag = json(diagResp);
        JsonNode codeADiag = diag.path("codes").get(0);
        JsonNode codeBDiag = diag.path("codes").get(1);
        assertEquals("A", codeADiag.path("treatment").asText());
        assertEquals(4, codeADiag.path("quotaTotal").asInt());
        assertEquals(4, codeADiag.path("assignedCount").asInt());
        assertEquals(0, codeADiag.path("sealedCount").asInt());
        assertEquals(0, codeADiag.path("availableQuota").asInt());
        assertEquals("B", codeBDiag.path("treatment").asText());
        assertEquals(4, codeBDiag.path("quotaTotal").asInt());
        assertEquals(1, codeBDiag.path("assignedCount").asInt());
        assertEquals(3, codeBDiag.path("sealedCount").asInt());
        assertEquals(0, codeBDiag.path("availableQuota").asInt(), "退役代码剩余有效配额为 0");
        JsonNode diagBlock1 = diag.path("blocks").get(0);
        assertEquals(4, diagBlock1.path("capacity").asInt());
        assertEquals(3, diagBlock1.path("occupiedCount").asInt(), "块1 占 3 席（含 1 个 B）");
        assertEquals(1, diagBlock1.path("sealedCount").asInt(), "块1 封存 1 个 B 序号");
        assertEquals(0, diagBlock1.path("availableCount").asInt());
        JsonNode diagBlock2 = diag.path("blocks").get(1);
        assertEquals(2, diagBlock2.path("occupiedCount").asInt());
        assertEquals(2, diagBlock2.path("sealedCount").asInt());
        assertEquals(0, diagBlock2.path("availableCount").asInt());

        // 既有参与者视图保持有效且不暴露代码
        ResponseEntity<String> existing = exchange(
                "/api/experiments/RT-1/participants/P3", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, existing.getStatusCode().value());
        JsonNode existingBody = json(existing);
        assertEquals("ASSIGNED", existingBody.path("status").asText());
        assertFalse(existingBody.has("treatment"));
        assertFalse(existingBody.has("seatNo"));
    }

    @Test
    void retirementFailures_returnDistinguishable409Or422_andRollbackWholeBatch() throws Exception {
        createExperiment("RT-2", 2, "rt2-create");
        allocate("RT-2", "P1", "rt2-alloc-1");

        // 代码不存在：409 CODE_NOT_FOUND
        ResponseEntity<String> notFound = exchange(
                "/api/experiments/RT-2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt2-r-notfound"),
                "{\"reason\":\"x\",\"codes\":[\"C\"]}");
        assertEquals(409, notFound.getStatusCode().value());
        assertEquals("CODE_NOT_FOUND", json(notFound).path("code").asText());

        // 占满全部 8 席（2A2B/块），此时 B（与 A）已全部分配
        for (int i = 2; i <= 8; i++) {
            allocate("RT-2", "P" + i, "rt2-alloc-" + i);
        }
        ResponseEntity<String> fullyAllocated = exchange(
                "/api/experiments/RT-2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt2-r-full"),
                "{\"reason\":\"x\",\"codes\":[\"B\"]}");
        assertEquals(422, fullyAllocated.getStatusCode().value());
        assertEquals("CODE_FULLY_ALLOCATED", json(fullyAllocated).path("code").asText());
        assertTrue(json(fullyAllocated).path("message").asText().contains("差额"));

        // 多代码整批后态失败：同时退役 A、B，二者均无未分配序号 → 整批回滚。
        ResponseEntity<String> batchFail = exchange(
                "/api/experiments/RT-2/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt2-r-batch"),
                "{\"reason\":\"x\",\"codes\":[\"A\",\"B\"]}");
        assertEquals(422, batchFail.getStatusCode().value());
        assertEquals("CODE_FULLY_ALLOCATED", json(batchFail).path("code").asText());
        // 回滚：无任何批次与封存记录
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RT-2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id='RT-2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM retired_code WHERE experiment_id='RT-2'", Integer.class));
        // 失败不占幂等键：同一 requestId 在另一实验执行合法 B 退役可以成功
        createExperiment("RT-2B", 2, "rt2b-create");
        ResponseEntity<String> reuse = exchange(
                "/api/experiments/RT-2B/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt2-r-batch"),
                "{\"reason\":\"新实验合法退役B\",\"codes\":[\"B\"]}");
        assertEquals(201, reuse.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RT-2B'", Integer.class));

        // 已退役代码再次退役：409 CODE_ALREADY_RETIRED
        ResponseEntity<String> again = exchange(
                "/api/experiments/RT-2B/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt2-r-again"),
                "{\"reason\":\"x\",\"codes\":[\"B\"]}");
        assertEquals(409, again.getStatusCode().value());
        assertEquals("CODE_ALREADY_RETIRED", json(again).path("code").asText());
    }

    @Test
    void pendingEmergencyUnblindRequest_blocksRetirement_409() throws Exception {
        createExperiment("RT-3", 2, "rt3-create");
        allocate("RT-3", "P1", "rt3-alloc-1");
        // 提出待审揭盲申请
        ResponseEntity<String> apply = exchange(
                "/api/experiments/RT-3/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt3-apply"), "{\"reason\":\"紧急医疗处置需揭盲\"}");
        assertEquals(201, apply.getStatusCode().value());

        ResponseEntity<String> blocked = exchange(
                "/api/experiments/RT-3/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt3-r-blocked"),
                "{\"reason\":\"x\",\"codes\":[\"B\"]}");
        assertEquals(409, blocked.getStatusCode().value());
        assertEquals("PENDING_UNBLIND_REQUEST", json(blocked).path("code").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RT-3'", Integer.class));

        // 批准后不再有待审申请，退役成功
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("r2", "REVIEWER", "rt3-approve"), null).getStatusCode().value());
        ResponseEntity<String> ok = exchange(
                "/api/experiments/RT-3/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt3-r-ok"),
                "{\"reason\":\"紧急申请已处理\",\"codes\":[\"B\"]}");
        assertEquals(201, ok.getStatusCode().value());
    }

    @Test
    void existingAllocationOnRetiredCode_staysEffective_andControlledUnblindingStillRevealsIt()
            throws Exception {
        // P1/P2=A(1-1/1-2)，P3=B(1-3)；退役 B 后 P3 的分配保持有效。
        createExperiment("RT-7", 2, "rt7-create");
        allocate("RT-7", "P1", "rt7-alloc-1");
        allocate("RT-7", "P2", "rt7-alloc-2");
        allocate("RT-7", "P3", "rt7-alloc-3");
        retire("RT-7", "B 代码退役，既有参与者不受影响", List.of("B"), "rt7-retire");

        // 普通视图仍只见盲码、区组号，不暴露 B
        ResponseEntity<String> view = exchange(
                "/api/experiments/RT-7/participants/P3", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        JsonNode v = json(view);
        assertEquals("ASSIGNED", v.path("status").asText());
        assertFalse(v.has("treatment"));
        assertFalse(v.has("seatNo"));

        // 受控揭盲链路完整保留：申请（退役后无待审申请时允许）→ 另一 REVIEWER 批准 → 申请人看到原代码 B
        ResponseEntity<String> apply = exchange(
                "/api/experiments/RT-7/participants/P3/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt7-apply"), "{\"reason\":\"退役代码参与者需医疗核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("r9", "REVIEWER", "rt7-approve"), null).getStatusCode().value());
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertEquals("B", json(result).path("treatment").asText(),
                "退役不改变既有分配的处理代码，批准揭盲后仍可揭示");
    }

    @Test
    void insufficientRemainingCode_422_withActualRequiredDeficit() throws Exception {
        // 2 区组 8 席。顺序分配无法只占用 A，这里用 JDBC 直接构造占用态：
        // 4 个 A 全部占用，B 占用 1-3/1-4/2-3，全局唯一空位为 2-4(B)。
        createExperiment("RT-4", 2, "rt4-create");
        long now = clock.nowMillis();
        int ordinal = 0;
        int[][] occupied = {{1, 1}, {1, 2}, {2, 1}, {2, 2}, {1, 3}, {1, 4}, {2, 3}};
        for (int[] seat : occupied) {
            ordinal++;
            jdbc.update("INSERT INTO allocation (experiment_id, participant_id, block_no, seat_no, "
                            + "blind_code, status, assigned_actor, assigned_at, withdrawn_at) "
                            + "VALUES ('RT-4', ?, ?, ?, ?, 'ASSIGNED', 'c1', ?, NULL)",
                    "SEED" + ordinal, seat[0], seat[1], "BLINDSEED" + String.format("%06d", ordinal), now);
        }

        // 退役 B 会封存唯一全局空位 2-4，实验未满员（7/8）但后态名额 0 → 422，含实际/要求/差额
        ResponseEntity<String> resp = exchange(
                "/api/experiments/RT-4/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt4-r"),
                "{\"reason\":\"x\",\"codes\":[\"B\"]}");
        assertEquals(422, resp.getStatusCode().value());
        JsonNode err = json(resp);
        assertEquals("INSUFFICIENT_REMAINING_CODE", err.path("code").asText());
        String message = err.path("message").asText();
        assertTrue(message.contains("实际 0"), "消息应含实际值: " + message);
        assertTrue(message.contains("要求至少 1"), "消息应含要求值: " + message);
        assertTrue(message.contains("差额 1"), "消息应含差额: " + message);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id='RT-4'", Integer.class),
                "失败不留半成品");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RT-4'", Integer.class),
                "失败不留半成品批次");

        // 对照组：先在 2-4 占满第 8 席（实验满员），退役 B 变为 CODE_FULLY_ALLOCATED 422
        jdbc.update("INSERT INTO allocation (experiment_id, participant_id, block_no, seat_no, "
                        + "blind_code, status, assigned_actor, assigned_at, withdrawn_at) "
                        + "VALUES ('RT-4', 'SEED8', 2, 4, 'BLINDSEED000008', 'ASSIGNED', 'c1', ?, NULL)",
                now);
        ResponseEntity<String> full = exchange(
                "/api/experiments/RT-4/code-retirements", HttpMethod.POST,
                headers("c1", "COORDINATOR", "rt4-r-full"),
                "{\"reason\":\"x\",\"codes\":[\"B\"]}");
        assertEquals(422, full.getStatusCode().value());
        assertEquals("CODE_FULLY_ALLOCATED", json(full).path("code").asText());
    }

    @Test
    void retirementHistory_leftClosedRightOpenAndDetailsStable() throws Exception {
        createExperiment("RT-6", 2, "rt6-create");
        // 每个区组占 2 个 A 与 1 个 B，使退役 B 后每块仍保留可分配空间：
        // 顺序分配占满 6 人：块1 seat1A seat2A seat3B seat4B、块2 seat1A seat2A →
        // 此时退役 B：块1 B 全占用(封存0)，块2 封存 seat3B seat4B 后已无空位 → 块2 后态0 被拒。
        // 改为：占 5 人（块1 四席 + 块2 seat1A）。退役 B：块2 剩 seat2A、seat3B、seat4B，
        // 封存 seat3/4 后保留 seat2A（名额1），通过。
        for (int i = 1; i <= 5; i++) {
            allocate("RT-6", "P" + i, "rt6-alloc-" + i);
        }
        long t1 = clock.nowMillis();
        String r1 = retire("RT-6", "首批退役B", List.of("B"), "rt6-r1");
        clock.advance(10_000L);
        long t2 = clock.nowMillis();

        // 第二次退役：A 在块2 只剩 seat2A 可封存；块1 A 全占用。
        // 块2 封存 seat2A 后名额 0（块2 未满：已占 seat1A + 封存 seat2A/3B/4B）→ 被规则拒绝。
        // 因此用仅查询历史验证不可变，不再制造第二次退役；改为校验单次批次区间边界。
        JsonNode first = mapper.readTree(r1);
        String firstId = first.path("retirementId").asText();
        assertEquals(t1, first.path("createdAt").asLong());

        // [t1, t2)：包含唯一一批
        ResponseEntity<String> window1 = exchange(
                "/api/experiments/RT-6/code-retirements?from=" + t1 + "&to=" + t2,
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, window1.getStatusCode().value());
        JsonNode arr1 = json(window1);
        assertEquals(1, arr1.size());
        assertEquals(firstId, arr1.get(0).path("retirementId").asText());

        // [t2, ...)：左闭，t2 之后无批次 → 空
        ResponseEntity<String> window2 = exchange(
                "/api/experiments/RT-6/code-retirements?from=" + t2,
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, window2.getStatusCode().value());
        assertEquals(0, json(window2).size());

        // [..., t1)：右开，不含 t1 批次 → 空
        ResponseEntity<String> window3 = exchange(
                "/api/experiments/RT-6/code-retirements?to=" + t1,
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, window3.getStatusCode().value());
        assertEquals(0, json(window3).size());

        // 全量历史
        ResponseEntity<String> all = exchange(
                "/api/experiments/RT-6/code-retirements", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(1, json(all).size());

        // 批次详情可直接读取，内容不可变
        ResponseEntity<String> detail = exchange(
                "/api/code-retirements/" + firstId, HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, detail.getStatusCode().value());
        assertEquals(r1, detail.getBody(), "详情应为同一不可变快照");

        // 不存在批次 404；实验不存在 404
        assertEquals(404, exchange("/api/code-retirements/RT-MISSING", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/NOPE/code-retirements", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null).getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/NOPE/retirement-diagnostics", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null).getStatusCode().value());

        // REVIEWER 不能退役（权限先于幂等）
        assertEquals(403, exchange("/api/experiments/RT-6/code-retirements", HttpMethod.POST,
                headers("r1", "REVIEWER", "rt6-r-by-reviewer"),
                "{\"reason\":\"x\",\"codes\":[\"A\"]}").getStatusCode().value());
    }
}
