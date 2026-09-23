package com.example.starter.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主流程、422 回滚、幂等与并发裁决的真实 H2 + HTTP 全链路测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpectrumIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String networkId;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM spectrum_request");
        jdbcTemplate.update("DELETE FROM spectrum_plan");
        jdbcTemplate.update("DELETE FROM spectrum_edge");
        jdbcTemplate.update("DELETE FROM spectrum_station");
        jdbcTemplate.update("DELETE FROM spectrum_network");
        networkId = "net-" + UUID.randomUUID();
    }

    private HttpHeaders headers(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", requestId);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    private String station(String id, int budget) {
        return "{\"stationId\":\"" + id + "\",\"budget\":" + budget + "}";
    }

    private String edge(String from, String to, int amount) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"amount\":" + amount + "}";
    }

    private ResponseEntity<String> createNetwork(String requestId, String body) {
        return restTemplate.exchange("/api/spectrum/networks", HttpMethod.POST,
                new HttpEntity<>(body, headers(requestId)), String.class);
    }

    private ResponseEntity<String> submitPlan(String requestId, String body) {
        return restTemplate.exchange("/api/spectrum/networks/" + networkId + "/plans",
                HttpMethod.POST, new HttpEntity<>(body, headers(requestId)), String.class);
    }

    private ResponseEntity<String> getState() {
        return restTemplate.getForEntity(
                "/api/spectrum/networks/" + networkId, String.class);
    }

    private ResponseEntity<String> getHistory() {
        return restTemplate.getForEntity(
                "/api/spectrum/networks/" + networkId + "/plans", String.class);
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private String standardNetworkBody() {
        // s1->s2 干扰 10，s2->s1 干扰 3；两站预算均为 10。
        return "{\"networkId\":\"" + networkId + "\",\"stations\":["
                + station("s1", 10) + "," + station("s2", 10) + "],"
                + "\"edges\":[" + edge("s1", "s2", 10) + "," + edge("s2", "s1", 3) + "]}";
    }

    @Test
    void createNetworkStartsSilentAtVersionOne() {
        ResponseEntity<String> response = createNetwork("req-create", standardNetworkBody());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        JsonNode body = json(response.getBody());
        assertEquals(networkId, body.get("networkId").asText());
        assertEquals(1, body.get("version").asInt());
        assertEquals(0, body.get("stations").get(0).get("channel").asInt());
        assertEquals(0, body.get("stations").get(1).get("channel").asInt());
        assertEquals(10, body.get("edges").get(0).get("amount").asInt());

        // 同 requestId 同参重放：返回首次结果，不新增网络（仍是版本1）。
        ResponseEntity<String> replay = createNetwork("req-create", standardNetworkBody());
        assertEquals(HttpStatus.CREATED, replay.getStatusCode());
        assertEquals(1, json(replay.getBody()).get("version").asInt());
        Integer networkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spectrum_network WHERE network_id = ?",
                Integer.class, networkId);
        assertEquals(1, networkCount);
    }

    @Test
    void validPlanAtomicallyBumpsVersionAndRecordsSnapshot() {
        createNetwork("req-c", standardNetworkBody());

        String planBody = "{\"expectedVersion\":1,\"planKey\":\"plan-1\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1},"
                + "{\"stationId\":\"s2\",\"channel\":2}]}";
        ResponseEntity<String> response = submitPlan("req-p1", planBody);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = json(response.getBody());
        assertEquals(1, body.get("versionBefore").asInt());
        assertEquals(2, body.get("versionAfter").asInt());
        assertEquals(0, body.get("channelsBefore").get("s1").asInt());
        assertEquals(1, body.get("channelsAfter").get("s1").asInt());
        assertEquals(2, body.get("channelsAfter").get("s2").asInt());
        // 不同频道：两站累计干扰均为 0。
        assertEquals(0, body.get("interferenceSummary").get(0).get("accumulated").asInt());

        JsonNode state = json(getState().getBody());
        assertEquals(2, state.get("version").asInt());
        assertEquals(1, state.get("stations").get(0).get("channel").asInt());
        assertEquals(2, state.get("stations").get(1).get("channel").asInt());

        JsonNode history = json(getHistory().getBody());
        assertEquals(1, history.get("plans").size());
        assertEquals("plan-1", history.get("plans").get(0).get("planKey").asText());
        assertEquals("req-p1", history.get("plans").get(0).get("requestId").asText());
        assertEquals(2, history.get("plans").get(0).get("version").asInt());
    }

    @Test
    void budgetExceededReturns422AndChangesNothing() {
        createNetwork("req-c", standardNetworkBody());

        // s1、s2 同在频道1：s2 累计 s1->s2 = 10（等于预算，合法）；
        // s1 累计 s2->s1 = 3。为制造超标，再提高同频道干扰：改用 3 站网络。
        networkId = networkId + "-x";
        String body = "{\"networkId\":\"" + networkId + "\",\"stations\":["
                + station("a", 5) + "," + station("b", 5) + "," + station("c", 5) + "],"
                + "\"edges\":[" + edge("b", "a", 6) + "," + edge("c", "a", 2)
                + "," + edge("a", "b", 5) + "]}";
        assertEquals(HttpStatus.CREATED, createNetwork("req-c2", body).getStatusCode());

        String planBody = "{\"expectedVersion\":1,\"planKey\":\"plan-bad\",\"changes\":["
                + "{\"stationId\":\"a\",\"channel\":1},"
                + "{\"stationId\":\"b\",\"channel\":1},"
                + "{\"stationId\":\"c\",\"channel\":1}]}";
        ResponseEntity<String> response = submitPlan("req-bad", planBody);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        JsonNode error = json(response.getBody());
        assertEquals("BUDGET_EXCEEDED", error.get("code").asText());
        // 仅 a 超标：6+2=8 > 5；b 收到 a->b=5 恰好等于预算合法。按台站ID排序。
        assertEquals(1, error.get("violations").size());
        assertEquals("a", error.get("violations").get(0).get("stationId").asText());
        assertEquals(8, error.get("violations").get(0).get("actual").asInt());
        assertEquals(5, error.get("violations").get(0).get("budget").asInt());

        // 频道、版本、方案记录完全不变。
        JsonNode state = json(getState().getBody());
        assertEquals(1, state.get("version").asInt());
        for (JsonNode station : state.get("stations")) {
            assertEquals(0, station.get("channel").asInt());
        }
        assertEquals(0, json(getHistory().getBody()).get("plans").size());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spectrum_plan", Integer.class));
    }

    @Test
    void allViolatingStationsAreListedSorted() {
        networkId = networkId + "-y";
        String body = "{\"networkId\":\"" + networkId + "\",\"stations\":["
                + station("z1", 1) + "," + station("m2", 1) + "," + station("a3", 1) + "],"
                + "\"edges\":[" + edge("m2", "z1", 5) + "," + edge("a3", "z1", 5)
                + "," + edge("z1", "m2", 5) + "," + edge("z1", "a3", 5) + "]}";
        assertEquals(HttpStatus.CREATED, createNetwork("req-c3", body).getStatusCode());

        String planBody = "{\"expectedVersion\":1,\"planKey\":\"plan-multi\",\"changes\":["
                + "{\"stationId\":\"z1\",\"channel\":4},"
                + "{\"stationId\":\"m2\",\"channel\":4},"
                + "{\"stationId\":\"a3\",\"channel\":4}]}";
        ResponseEntity<String> response = submitPlan("req-multi", planBody);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        JsonNode violations = json(response.getBody()).get("violations");
        assertEquals(3, violations.size());
        // 按台站ID排序：a3, m2, z1
        assertEquals(List.of("a3", "m2", "z1"), List.of(
                violations.get(0).get("stationId").asText(),
                violations.get(1).get("stationId").asText(),
                violations.get(2).get("stationId").asText()));
        assertEquals(5, violations.get(0).get("actual").asInt());
        assertEquals(10, violations.get(2).get("actual").asInt());
    }

    @Test
    void failedRequestDoesNotOccupyKeyAndPlanCanBeCorrected() {
        createNetwork("req-c", standardNetworkBody());

        // 先制造一个合法版本2（s1 在频道1，s2 静默，累计0）。
        String ok = "{\"expectedVersion\":1,\"planKey\":\"plan-ok\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1}]}";
        assertEquals(HttpStatus.OK, submitPlan("req-ok", ok).getStatusCode());

        // 再让 s2 也进入频道1：s2 收 s1->s2=10 等于预算合法；s1 收 s2->s1=3 <= 10。
        // 改为会超预算的提交：expectedVersion 错误以外的失败 —— 超预算。
        // s2 预算改成很小的网络：直接构造超标（s2 预算 10，10 不超标），
        // 因此用 s2 进频道1 同时额外让 s1 超标不可行；这里用重复台站触发400失败。
        String badRequest = "{\"expectedVersion\":2,\"planKey\":\"plan-400\",\"changes\":["
                + "{\"stationId\":\"s2\",\"channel\":1},"
                + "{\"stationId\":\"s2\",\"channel\":2}]}";
        assertEquals(HttpStatus.BAD_REQUEST, submitPlan("req-reuse", badRequest).getStatusCode());

        // 失败不占键：同 requestId 用修正后的合法参数可成功。
        String fixed = "{\"expectedVersion\":2,\"planKey\":\"plan-fixed\",\"changes\":["
                + "{\"stationId\":\"s2\",\"channel\":1}]}";
        ResponseEntity<String> retry = submitPlan("req-reuse", fixed);
        assertEquals(HttpStatus.OK, retry.getStatusCode());
        assertEquals(3, json(retry.getBody()).get("versionAfter").asInt());
    }

    @Test
    void sameRequestIdDifferentParamsConflictsAndReplayReturnsFirstResult() {
        createNetwork("req-c", standardNetworkBody());

        String plan = "{\"expectedVersion\":1,\"planKey\":\"plan-a\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1}]}";
        ResponseEntity<String> first = submitPlan("req-same", plan);
        assertEquals(HttpStatus.OK, first.getStatusCode());

        // 同键重放不增加版本，返回首次结果。
        ResponseEntity<String> replay = submitPlan("req-same", plan);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(2, json(replay.getBody()).get("versionAfter").asInt());
        assertEquals(2, json(getState().getBody()).get("version").asInt());

        // 方案集合换序同参：仍视为同一请求，返回首次结果。
        String reordered = "{\"planKey\":\"plan-a\",\"expectedVersion\":1,\"changes\":["
                + "{\"channel\":1,\"stationId\":\"s1\"}]}";
        ResponseEntity<String> reorderedResponse = submitPlan("req-same", reordered);
        assertEquals(HttpStatus.OK, reorderedResponse.getStatusCode());
        assertEquals(2, json(reorderedResponse.getBody()).get("versionAfter").asInt());

        // 同 requestId 异参：409。
        String different = "{\"expectedVersion\":1,\"planKey\":\"plan-a\",\"changes\":["
                + "{\"stationId\":\"s2\",\"channel\":1}]}";
        ResponseEntity<String> conflict = submitPlan("req-same", different);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    @Test
    void reusedPlanKeyWithNewRequestIdConflicts() {
        createNetwork("req-c", standardNetworkBody());

        String plan = "{\"expectedVersion\":1,\"planKey\":\"shared-key\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1}]}";
        assertEquals(HttpStatus.OK, submitPlan("req-first", plan).getStatusCode());

        // 换请求键复用 planKey：409（参数顺序无关，键冲突）。
        String again = "{\"expectedVersion\":2,\"planKey\":\"shared-key\",\"changes\":["
                + "{\"stationId\":\"s2\",\"channel\":2}]}";
        ResponseEntity<String> conflict = submitPlan("req-second", again);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("PLAN_KEY_USED", json(conflict.getBody()).get("code").asText());
        assertEquals(2, json(getState().getBody()).get("version").asInt());
    }

    @Test
    void noChangePlanStillBumpsVersionButReplayDoesNot() {
        createNetwork("req-c", standardNetworkBody());

        String noChange = "{\"expectedVersion\":1,\"planKey\":\"plan-noop\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":0}]}";
        ResponseEntity<String> response = submitPlan("req-noop", noChange);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, json(response.getBody()).get("versionAfter").asInt());
        // 前后配置相同。
        assertEquals(json(response.getBody()).get("channelsBefore"),
                json(response.getBody()).get("channelsAfter"));

        // 同键重放不增加版本。
        assertEquals(2, json(submitPlan("req-noop", noChange).getBody())
                .get("versionAfter").asInt());
        assertEquals(2, json(getState().getBody()).get("version").asInt());
    }

    @Test
    void channelSwapWithinOnePlanIsAllowed() {
        createNetwork("req-c", standardNetworkBody());

        // 先让两站上不同频道。
        String first = "{\"expectedVersion\":1,\"planKey\":\"plan-up\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1},"
                + "{\"stationId\":\"s2\",\"channel\":2}]}";
        assertEquals(HttpStatus.OK, submitPlan("req-up", first).getStatusCode());

        // 同一方案内交换频道：s1->2, s2->1。因为频道不同，干扰累计均为0，合法。
        String swap = "{\"expectedVersion\":2,\"planKey\":\"plan-swap\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":2},"
                + "{\"stationId\":\"s2\",\"channel\":1}]}";
        ResponseEntity<String> response = submitPlan("req-swap", swap);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode after = json(response.getBody()).get("channelsAfter");
        assertEquals(2, after.get("s1").asInt());
        assertEquals(1, after.get("s2").asInt());
    }

    @Test
    void staleExpectedVersionConflicts() {
        createNetwork("req-c", standardNetworkBody());

        String first = "{\"expectedVersion\":1,\"planKey\":\"plan-v1\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1}]}";
        assertEquals(HttpStatus.OK, submitPlan("req-v1", first).getStatusCode());

        String stale = "{\"expectedVersion\":1,\"planKey\":\"plan-stale\",\"changes\":["
                + "{\"stationId\":\"s2\",\"channel\":2}]}";
        ResponseEntity<String> conflict = submitPlan("req-stale", stale);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("VERSION_CONFLICT", json(conflict.getBody()).get("code").asText());
        assertEquals(2, json(getState().getBody()).get("version").asInt());
    }

    @Test
    void concurrentPlansWithSameExpectedVersionOnlyOneSucceeds() throws Exception {
        createNetwork("req-c", standardNetworkBody());

        String planA = "{\"expectedVersion\":1,\"planKey\":\"plan-con-a\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1}]}";
        String planB = "{\"expectedVersion\":1,\"planKey\":\"plan-con-b\",\"changes\":["
                + "{\"stationId\":\"s2\",\"channel\":8}]}";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (String[] request : List.of(
                new String[]{"req-con-a", planA}, new String[]{"req-con-b", planB})) {
            String requestId = request[0];
            String body = request[1];
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return submitPlan(requestId, body);
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get(10, TimeUnit.SECONDS);
            if (response.getStatusCode() == HttpStatus.OK) {
                ok++;
            } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                conflict++;
                assertEquals("VERSION_CONFLICT", json(response.getBody()).get("code").asText());
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "同一期望版本最多一份成功");
        assertEquals(1, conflict, "另一份必须409");

        // 最终版本恰好加一，且方案记录只有一条。
        JsonNode state = json(getState().getBody());
        assertEquals(2, state.get("version").asInt());
        assertEquals(1, json(getHistory().getBody()).get("plans").size());
    }

    @Test
    void concurrentSameRequestIdNeverProducesServerError() throws Exception {
        createNetwork("req-c", standardNetworkBody());

        String plan = "{\"expectedVersion\":1,\"planKey\":\"plan-same-req\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1}]}";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return submitPlan("req-shared", plan);
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get(10, TimeUnit.SECONDS);
            if (response.getStatusCode() == HttpStatus.OK) {
                ok++;
            } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                conflict++;
            } else {
                throw new AssertionError("unexpected status: " + response.getStatusCode());
            }
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(1, conflict);
        assertEquals(2, json(getState().getBody()).get("version").asInt());
        assertEquals(1, json(getHistory().getBody()).get("plans").size());
    }

    @Test
    void illegalParametersAndMissingResources() {
        createNetwork("req-c", standardNetworkBody());

        // 频道越界 9 -> 400
        String badChannel = "{\"expectedVersion\":1,\"planKey\":\"plan-x\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":9}]}";
        assertEquals(HttpStatus.BAD_REQUEST, submitPlan("req-x", badChannel).getStatusCode());

        // 引用未知台站 -> 400
        String unknownStation = "{\"expectedVersion\":1,\"planKey\":\"plan-y\",\"changes\":["
                + "{\"stationId\":\"nope\",\"channel\":1}]}";
        assertEquals(HttpStatus.BAD_REQUEST, submitPlan("req-y", unknownStation).getStatusCode());

        // 缺少 X-Request-Id -> 400
        HttpHeaders noRequestHeader = new HttpHeaders();
        noRequestHeader.set("Content-Type", "application/json");
        ResponseEntity<String> noHeader = restTemplate.exchange(
                "/api/spectrum/networks/" + networkId + "/plans", HttpMethod.POST,
                new HttpEntity<>(unknownStation, noRequestHeader), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, noHeader.getStatusCode());

        // 网络不存在 -> 404
        ResponseEntity<String> missingNetwork = restTemplate.getForEntity(
                "/api/spectrum/networks/no-such-network", String.class);
        assertEquals(HttpStatus.NOT_FOUND, missingNetwork.getStatusCode());

        // 自环 -> 400
        String selfLoop = "{\"networkId\":\"" + networkId + "-loop\",\"stations\":["
                + station("s1", 1) + "],\"edges\":[" + edge("s1", "s1", 1) + "]}";
        assertEquals(HttpStatus.BAD_REQUEST, createNetwork("req-loop", selfLoop).getStatusCode());

        // 重复边 -> 400
        String duplicateEdge = "{\"networkId\":\"" + networkId + "-dup\",\"stations\":["
                + station("s1", 1) + "," + station("s2", 1) + "],\"edges\":["
                + edge("s1", "s2", 1) + "," + edge("s1", "s2", 2) + "]}";
        assertEquals(HttpStatus.BAD_REQUEST, createNetwork("req-dup", duplicateEdge).getStatusCode());

        // 重复台站 -> 400
        String duplicateStation = "{\"networkId\":\"" + networkId + "-dups\",\"stations\":["
                + station("s1", 1) + "," + station("s1", 2) + "]}";
        assertEquals(HttpStatus.BAD_REQUEST, createNetwork("req-dups", duplicateStation).getStatusCode());
    }

    @Test
    void rejectedPlanDoesNotOccupyKeysAndCanBeCorrectedWithSameKeys() {
        networkId = networkId + "-r";
        // s2->s1 = 500，s1 预算 0；只要两站同频道就必然 422。
        String body = "{\"networkId\":\"" + networkId + "\",\"stations\":["
                + station("s1", 0) + "," + station("s2", 1000) + "],"
                + "\"edges\":[" + edge("s2", "s1", 500) + "]}";
        assertEquals(HttpStatus.CREATED, createNetwork("req-cr", body).getStatusCode());

        String violating = "{\"expectedVersion\":1,\"planKey\":\"plan-retry\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1},"
                + "{\"stationId\":\"s2\",\"channel\":1}]}";
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                submitPlan("req-retry-key", violating).getStatusCode());

        // 失败不占键：用同一个 requestId 与同一个 planKey 提交修正后的合法方案成功。
        String corrected = "{\"expectedVersion\":1,\"planKey\":\"plan-retry\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":0},"
                + "{\"stationId\":\"s2\",\"channel\":1}]}";
        ResponseEntity<String> retry = submitPlan("req-retry-key", corrected);
        assertEquals(HttpStatus.OK, retry.getStatusCode());
        assertEquals(2, json(retry.getBody()).get("versionAfter").asInt());
        assertEquals(1, json(getHistory().getBody()).get("plans").size());
    }

    @Test
    void silentStationDoesNotTransmitAndUnlistedStationsKeepState() {
        networkId = networkId + "-s";
        String body = "{\"networkId\":\"" + networkId + "\",\"stations\":["
                + station("s1", 0) + "," + station("s2", 0) + "],"
                + "\"edges\":[" + edge("s2", "s1", 500) + "]}";
        assertEquals(HttpStatus.CREATED, createNetwork("req-cs", body).getStatusCode());

        // s1 上频道1、s2 保持静默：s2 不发射，s1 累计0，即使预算为0也合法。
        String plan = "{\"expectedVersion\":1,\"planKey\":\"plan-silent\",\"changes\":["
                + "{\"stationId\":\"s1\",\"channel\":1}]}";
        ResponseEntity<String> response = submitPlan("req-silent", plan);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode result = json(response.getBody());
        assertEquals(0, result.get("channelsAfter").get("s2").asInt());
        assertEquals(1, result.get("interferenceSummary").size());
        assertEquals("s1", result.get("interferenceSummary").get(0).get("stationId").asText());
        assertEquals(0, result.get("interferenceSummary").get(0).get("accumulated").asInt());

        // 现在 s2 也进入频道1：s1 将收到 500 > 0 预算 -> 422，状态不变。
        String violating = "{\"expectedVersion\":2,\"planKey\":\"plan-loud\",\"changes\":["
                + "{\"stationId\":\"s2\",\"channel\":1}]}";
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                submitPlan("req-loud", violating).getStatusCode());
        assertEquals(2, json(getState().getBody()).get("version").asInt());
        assertEquals(0, json(getState().getBody()).get("stations").get(1).get("channel").asInt());
    }
}
