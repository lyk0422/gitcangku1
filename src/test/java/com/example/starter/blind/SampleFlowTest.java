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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二级盲码交接主流程与失败分支：
 * 协调员建样得一级码、分析员接收得二级码并录结果、分角色视图隔离、
 * CLOSED 拒绝新增样本/接收/结果、退组与汇总边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleFlowTest extends AbstractBlindIntegrationTest {

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

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String id, int blockCount, String requestId) {
        assertEquals(201, post("/api/experiments/" + id,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private void register(String expId, String pid, String requestId) {
        assertEquals(201, post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private String createSample(String expId, String pid, String requestId) throws Exception {
        ResponseEntity<String> resp = post(
                "/api/experiments/" + expId + "/participants/" + pid + "/sample",
                headers("c1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value());
        return json(resp).path("primaryCode").asText();
    }

    private String receive(String primaryCode, String analyst, String requestId) throws Exception {
        ResponseEntity<String> resp = post("/api/samples/" + primaryCode + "/receipt",
                headers(analyst, "ANALYST", requestId), null);
        assertEquals(200, resp.getStatusCode().value());
        return json(resp).path("secondaryCode").asText();
    }

    @Test
    void mainFlow_roleScopedViews_noCrossRoleLeak() throws Exception {
        createExperiment("SE-1", 2, "se1-create");
        register("SE-1", "P1", "se1-alloc");

        ResponseEntity<String> created = post("/api/experiments/SE-1/participants/P1/sample",
                headers("c1", "COORDINATOR", "se1-sample"), null);
        assertEquals(201, created.getStatusCode().value());
        JsonNode createdBody = json(created);
        String primaryCode = createdBody.path("primaryCode").asText();
        assertTrue(primaryCode.matches("[A-Z2-9]{12}"), "一级码应为12位无含义随机码");
        assertEquals("CREATED", createdBody.path("status").asText());
        assertFalse(createdBody.has("secondaryCode"), "协调员响应不得含二级码");
        assertFalse(createdBody.has("resultValue"));

        // 协调员视图：一级码 + 接收/有结果标识，无二级码与结果值
        JsonNode view = json(get("/api/experiments/SE-1/participants/P1/sample",
                headers("c1", "COORDINATOR", null)));
        assertEquals(primaryCode, view.path("primaryCode").asText());
        assertFalse(view.path("received").asBoolean());
        assertFalse(view.path("hasResult").asBoolean());
        assertFalse(view.has("secondaryCode"));
        assertFalse(view.has("resultValue"));

        // 分析员 L 接收：获得独立二级码，响应不含参与者/一级码/处理
        ResponseEntity<String> received = post("/api/samples/" + primaryCode + "/receipt",
                headers("L", "ANALYST", "se1-receive"), null);
        assertEquals(200, received.getStatusCode().value());
        JsonNode receivedBody = json(received);
        String secondaryCode = receivedBody.path("secondaryCode").asText();
        assertTrue(secondaryCode.matches("[A-Z2-9]{12}"));
        assertNotEquals(primaryCode, secondaryCode);
        assertFalse(receivedBody.has("participantId"));
        assertFalse(receivedBody.has("primaryCode"));
        assertFalse(receivedBody.has("treatment"));

        view = json(get("/api/experiments/SE-1/participants/P1/sample",
                headers("c1", "COORDINATOR", null)));
        assertTrue(view.path("received").asBoolean());
        assertFalse(view.path("hasResult").asBoolean());
        assertFalse(view.has("secondaryCode"), "协调员不能取得 L 的二级码");

        // L 录入一个整数结果
        ResponseEntity<String> result = post("/api/samples/" + secondaryCode + "/result",
                headers("L", "ANALYST", "se1-result"), "{\"value\":42}");
        assertEquals(200, result.getStatusCode().value());
        assertEquals(42, json(result).path("resultValue").asInt());

        // 分析员视图：仅本人二级码与结果，无参与者/一级码/处理
        JsonNode analystView = json(get("/api/samples/" + secondaryCode,
                headers("L", "ANALYST", null)));
        assertEquals(secondaryCode, analystView.path("secondaryCode").asText());
        assertEquals("RESULT", analystView.path("status").asText());
        assertEquals(42, analystView.path("resultValue").asInt());
        assertFalse(analystView.has("participantId"));
        assertFalse(analystView.has("primaryCode"));
        assertFalse(analystView.has("treatment"));

        // 协调员只见有结果标识，仍无二级码与值
        view = json(get("/api/experiments/SE-1/participants/P1/sample",
                headers("c1", "COORDINATOR", null)));
        assertTrue(view.path("hasResult").asBoolean());
        assertFalse(view.has("secondaryCode"));
        assertFalse(view.has("resultValue"));

        assertEquals("RESULT", jdbc.queryForObject(
                "SELECT status FROM blind_sample WHERE experiment_id = 'SE-1'", String.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT version FROM blind_sample WHERE experiment_id = 'SE-1'", Integer.class));
    }

    @Test
    void createSample_rejectsWithdrawnUnknownDuplicateAndClosed() {
        createExperiment("SE-2", 2, "se2-create");
        register("SE-2", "P1", "se2-alloc-1");
        register("SE-2", "P2", "se2-alloc-2");
        register("SE-2", "P3", "se2-alloc-3");
        assertEquals(200, post("/api/experiments/SE-2/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "se2-withdraw"), null).getStatusCode().value());

        // 已退组参与者不能创建样本
        assertEquals(409, post("/api/experiments/SE-2/participants/P1/sample",
                headers("c1", "COORDINATOR", "se2-sample-w"), null).getStatusCode().value());
        // 未登记参与者 404
        assertEquals(404, post("/api/experiments/SE-2/participants/P9/sample",
                headers("c1", "COORDINATOR", "se2-sample-404"), null).getStatusCode().value());
        // 正常创建；同参与者重复创建（换键）409
        assertEquals(201, post("/api/experiments/SE-2/participants/P2/sample",
                headers("c1", "COORDINATOR", "se2-sample-p2"), null).getStatusCode().value());
        assertEquals(409, post("/api/experiments/SE-2/participants/P2/sample",
                headers("c1", "COORDINATOR", "se2-sample-p2-again"), null).getStatusCode().value());
        // 关闭后拒绝新增样本
        assertEquals(200, post("/api/experiments/SE-2/close",
                headers("c1", "COORDINATOR", "se2-close"), null).getStatusCode().value());
        assertEquals(409, post("/api/experiments/SE-2/participants/P3/sample",
                headers("c1", "COORDINATOR", "se2-sample-closed"), null).getStatusCode().value());
    }

    @Test
    void closedRejectsReceiptAndResult() throws Exception {
        createExperiment("SE-3", 2, "se3-create");
        register("SE-3", "P1", "se3-alloc-1");
        register("SE-3", "P2", "se3-alloc-2");
        String primary1 = createSample("SE-3", "P1", "se3-sample-1");
        String primary2 = createSample("SE-3", "P2", "se3-sample-2");
        String secondary1 = receive(primary1, "L", "se3-receive-1");

        assertEquals(200, post("/api/experiments/SE-3/close",
                headers("c1", "COORDINATOR", "se3-close"), null).getStatusCode().value());

        // CLOSED 拒绝接收
        assertEquals(409, post("/api/samples/" + primary2 + "/receipt",
                headers("M", "ANALYST", "se3-receive-2"), null).getStatusCode().value());
        // CLOSED 拒绝结果
        assertEquals(409, post("/api/samples/" + secondary1 + "/result",
                headers("L", "ANALYST", "se3-result"), "{\"value\":1}").getStatusCode().value());
        // 数据未被写入
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM blind_sample WHERE primary_code = '" + primary2 + "'",
                String.class));
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT status FROM blind_sample WHERE primary_code = '" + primary1 + "'",
                String.class));
    }

    @Test
    void oneSampleOneAnalyst_andRoleBoundaries() throws Exception {
        createExperiment("SE-4", 2, "se4-create");
        register("SE-4", "P1", "se4-alloc");
        String primary = createSample("SE-4", "P1", "se4-sample");
        String secondary = receive(primary, "L", "se4-receive");

        // 一样本仅绑定一名分析员：第二名分析员接收 409
        assertEquals(409, post("/api/samples/" + primary + "/receipt",
                headers("M", "ANALYST", "se4-receive-m"), null).getStatusCode().value());
        // 另一分析员用 L 的二级码录入 403
        assertEquals(403, post("/api/samples/" + secondary + "/result",
                headers("M", "ANALYST", "se4-result-m"), "{\"value\":1}").getStatusCode().value());
        // 协调员不能取得/使用二级码
        assertEquals(403, post("/api/samples/" + secondary + "/result",
                headers("c1", "COORDINATOR", "se4-result-c"), "{\"value\":1}").getStatusCode().value());
        assertEquals(403, get("/api/samples/" + secondary,
                headers("c1", "COORDINATOR", null)).getStatusCode().value());
        // 另一分析员不能查看 L 的样本
        assertEquals(403, get("/api/samples/" + secondary,
                headers("M", "ANALYST", null)).getStatusCode().value());
        // 未知二级码 404
        assertEquals(404, get("/api/samples/ZZZZZZZZZZZZ",
                headers("L", "ANALYST", null)).getStatusCode().value());

        // 本人录入成功；重复录入（换键）409
        assertEquals(200, post("/api/samples/" + secondary + "/result",
                headers("L", "ANALYST", "se4-result"), "{\"value\":5}").getStatusCode().value());
        assertEquals(409, post("/api/samples/" + secondary + "/result",
                headers("L", "ANALYST", "se4-result-again"), "{\"value\":5}").getStatusCode().value());

        // 角色边界：非协调员建样 403，非审阅员查汇总 403
        assertEquals(403, post("/api/experiments/SE-4/participants/P1/sample",
                headers("r1", "REVIEWER", "se4-sample-r"), null).getStatusCode().value());
        assertEquals(403, post("/api/experiments/SE-4/participants/P1/sample",
                headers("L", "ANALYST", "se4-sample-a"), null).getStatusCode().value());
        assertEquals(403, get("/api/experiments/SE-4/sample-summary",
                headers("c1", "COORDINATOR", null)).getStatusCode().value());
        assertEquals(403, get("/api/experiments/SE-4/sample-summary",
                headers("L", "ANALYST", null)).getStatusCode().value());
    }

    @Test
    void reviewerSummary_requiresClosedAndMinThree_excludesWithdrawn() throws Exception {
        createExperiment("SE-5", 2, "se5-create");
        String[] analysts = {"a1", "a2", "a3", "a4"};
        int[] values = {10, 20, 30, 40};
        for (int i = 1; i <= 4; i++) {
            register("SE-5", "P" + i, "se5-alloc-" + i);
            String primary = createSample("SE-5", "P" + i, "se5-sample-" + i);
            String secondary = receive(primary, analysts[i - 1], "se5-receive-" + i);
            assertEquals(200, post("/api/samples/" + secondary + "/result",
                    headers(analysts[i - 1], "ANALYST", "se5-result-" + i),
                    "{\"value\":" + values[i - 1] + "}").getStatusCode().value());
        }

        // 未关闭不可汇总
        assertEquals(409, get("/api/experiments/SE-5/sample-summary",
                headers("r1", "REVIEWER", null)).getStatusCode().value());

        // 退组样本的结果保留但标退组：P1 已有结果 10，退组后不进汇总
        assertEquals(200, post("/api/experiments/SE-5/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "se5-withdraw-1"), null).getStatusCode().value());
        // 退组后的结果可保留但不进汇总：P5 接收后退组，再录结果 50
        register("SE-5", "P5", "se5-alloc-5");
        String primary5 = createSample("SE-5", "P5", "se5-sample-5");
        String secondary5 = receive(primary5, "a5", "se5-receive-5");
        assertEquals(200, post("/api/experiments/SE-5/participants/P5/withdrawal",
                headers("c1", "COORDINATOR", "se5-withdraw-5"), null).getStatusCode().value());
        assertEquals(200, post("/api/samples/" + secondary5 + "/result",
                headers("a5", "ANALYST", "se5-result-5"), "{\"value\":50}").getStatusCode().value());

        assertEquals(200, post("/api/experiments/SE-5/close",
                headers("c1", "COORDINATOR", "se5-close"), null).getStatusCode().value());

        // 汇总：仅 3 份有效（20+30+40=90），退组的两份被排除，无逐样本关联
        JsonNode summary = json(get("/api/experiments/SE-5/sample-summary",
                headers("r1", "REVIEWER", null)));
        assertEquals(3, summary.path("resultCount").asInt());
        assertEquals(90, summary.path("resultSum").asInt());
        assertFalse(summary.has("samples"));
        assertFalse(summary.has("secondaryCode"));
        // 退组结果仍保留在库中（5 份已录结果）
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blind_sample WHERE experiment_id = 'SE-5' "
                        + "AND result_value IS NOT NULL", Integer.class));

        // 不足 3 份：SE-6 仅 2 份有效结果，闭组后 422
        createExperiment("SE-6", 2, "se6-create");
        for (int i = 1; i <= 2; i++) {
            register("SE-6", "P" + i, "se6-alloc-" + i);
            String primary = createSample("SE-6", "P" + i, "se6-sample-" + i);
            String secondary = receive(primary, "a" + i, "se6-receive-" + i);
            assertEquals(200, post("/api/samples/" + secondary + "/result",
                    headers("a" + i, "ANALYST", "se6-result-" + i),
                    "{\"value\":" + i + "}").getStatusCode().value());
        }
        assertEquals(200, post("/api/experiments/SE-6/close",
                headers("c1", "COORDINATOR", "se6-close"), null).getStatusCode().value());
        assertEquals(422, get("/api/experiments/SE-6/sample-summary",
                headers("r1", "REVIEWER", null)).getStatusCode().value());
        // 不存在的实验 404
        assertEquals(404, get("/api/experiments/NOPE/sample-summary",
                headers("r1", "REVIEWER", null)).getStatusCode().value());
    }

    @Test
    void receiveAfterWithdrawalCommitted_rejected() throws Exception {
        createExperiment("SE-7", 2, "se7-create");
        register("SE-7", "P1", "se7-alloc");
        String primary = createSample("SE-7", "P1", "se7-sample");
        // 退组先提交，接收被拒
        assertEquals(200, post("/api/experiments/SE-7/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "se7-withdraw"), null).getStatusCode().value());
        assertEquals(409, post("/api/samples/" + primary + "/receipt",
                headers("L", "ANALYST", "se7-receive"), null).getStatusCode().value());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM blind_sample WHERE primary_code = '" + primary + "'",
                String.class));
    }

    @Test
    void sampleWrites_idempotent_replayConflictAndFailureNotConsumed() throws Exception {
        createExperiment("SE-8", 2, "se8-create");
        register("SE-8", "P1", "se8-alloc-1");
        register("SE-8", "P2", "se8-alloc-2");

        // 建样：同键同参重放原响应，不产生第二条样本
        ResponseEntity<String> first = post("/api/experiments/SE-8/participants/P1/sample",
                headers("c1", "COORDINATOR", "se8-k1"), null);
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = post("/api/experiments/SE-8/participants/P1/sample",
                headers("c1", "COORDINATOR", "se8-k1"), null);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blind_sample WHERE experiment_id = 'SE-8'", Integer.class));
        // 同键异参 409
        assertEquals(409, post("/api/experiments/SE-8/participants/P2/sample",
                headers("c1", "COORDINATOR", "se8-k1"), null).getStatusCode().value());

        String primary1 = json(first).path("primaryCode").asText();
        String primary2 = createSample("SE-8", "P2", "se8-sample-2");

        // 接收：同键重放返回同一二级码，样本仍只绑定 L
        ResponseEntity<String> recv = post("/api/samples/" + primary1 + "/receipt",
                headers("L", "ANALYST", "se8-k2"), null);
        assertEquals(200, recv.getStatusCode().value());
        ResponseEntity<String> recvReplay = post("/api/samples/" + primary1 + "/receipt",
                headers("L", "ANALYST", "se8-k2"), null);
        assertEquals(200, recvReplay.getStatusCode().value());
        assertEquals(recv.getBody(), recvReplay.getBody());
        String secondary = json(recv).path("secondaryCode").asText();

        // 结果：L 重复同键同结果不增总数；同键异参 409
        ResponseEntity<String> result = post("/api/samples/" + secondary + "/result",
                headers("L", "ANALYST", "se8-k3"), "{\"value\":10}");
        assertEquals(200, result.getStatusCode().value());
        ResponseEntity<String> resultReplay = post("/api/samples/" + secondary + "/result",
                headers("L", "ANALYST", "se8-k3"), "{\"value\":10}");
        assertEquals(200, resultReplay.getStatusCode().value());
        assertEquals(result.getBody(), resultReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blind_sample WHERE experiment_id = 'SE-8' "
                        + "AND result_value IS NOT NULL", Integer.class));
        assertEquals(409, post("/api/samples/" + secondary + "/result",
                headers("L", "ANALYST", "se8-k3"), "{\"value\":11}").getStatusCode().value());

        // 协调员重放分析员的键：权限先于幂等，403 而非回放
        assertEquals(403, post("/api/samples/" + secondary + "/result",
                headers("c1", "COORDINATOR", "se8-k3"), "{\"value\":10}").getStatusCode().value());

        // 失败不占键：未知一级码 404 后，同键可用于有效接收
        assertEquals(404, post("/api/samples/ZZZZZZZZZZZZ/receipt",
                headers("M", "ANALYST", "se8-k4"), null).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'se8-k4'",
                Integer.class));
        assertEquals(200, post("/api/samples/" + primary2 + "/receipt",
                headers("M", "ANALYST", "se8-k4"), null).getStatusCode().value());
    }

    @Test
    void recordResult_expectedVersionAndDecimalCanonicalization() throws Exception {
        createExperiment("SE-9", 2, "se9-create");
        register("SE-9", "P1", "se9-alloc-1");
        register("SE-9", "P2", "se9-alloc-2");
        String secondary1 = receive(createSample("SE-9", "P1", "se9-sample-1"), "L", "se9-recv-1");
        String secondary2 = receive(createSample("SE-9", "P2", "se9-sample-2"), "L", "se9-recv-2");

        // 期望版本不匹配（接收后版本为 2）：409
        assertEquals(409, post("/api/samples/" + secondary1 + "/result",
                headers("L", "ANALYST", "se9-r1"), "{\"value\":7,\"expectedVersion\":1}")
                .getStatusCode().value());
        // 版本匹配：成功；失败的前一次未占键可换键重试
        assertEquals(200, post("/api/samples/" + secondary1 + "/result",
                headers("L", "ANALYST", "se9-r2"), "{\"value\":7,\"expectedVersion\":2}")
                .getStatusCode().value());

        // 十进制同值同参：10.0 与 10 指纹一致，同键重放原响应
        ResponseEntity<String> decimal = post("/api/samples/" + secondary2 + "/result",
                headers("L", "ANALYST", "se9-r3"), "{\"value\":10.0}");
        assertEquals(200, decimal.getStatusCode().value());
        ResponseEntity<String> integer = post("/api/samples/" + secondary2 + "/result",
                headers("L", "ANALYST", "se9-r3"), "{\"value\":10}");
        assertEquals(200, integer.getStatusCode().value());
        assertEquals(decimal.getBody(), integer.getBody());
        // 同键多带期望版本字段：异参 409
        assertEquals(409, post("/api/samples/" + secondary2 + "/result",
                headers("L", "ANALYST", "se9-r3"), "{\"value\":10,\"expectedVersion\":2}")
                .getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blind_sample WHERE experiment_id = 'SE-9' "
                        + "AND result_value IS NOT NULL", Integer.class));
    }
}
