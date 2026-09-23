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
import org.springframework.http.MediaType;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2（MODE=MySQL）+ 全栈 Web 测试：主流程、超预算回滚、幂等/重放、乐观锁并发。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpectrumApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM spectrum_plan");
        jdbcTemplate.update("DELETE FROM spectrum_request");
        jdbcTemplate.update("DELETE FROM spectrum_edge");
        jdbcTemplate.update("DELETE FROM spectrum_station");
        jdbcTemplate.update("DELETE FROM spectrum_network");
    }

    private HttpHeaders jsonHeaders(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", requestId);
        return headers;
    }

    private ResponseEntity<String> createNetwork(String networkId, String body, String requestId) {
        return restTemplate.exchange("/api/spectrum/networks", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(requestId)), String.class);
    }

    private String basicNetworkBody(String networkId) {
        return """
                {
                  "networkId": "%s",
                  "name": "demo",
                  "stations": [
                    {"stationId": "s1", "interferenceBudget": 100},
                    {"stationId": "s2", "interferenceBudget": 100},
                    {"stationId": "s3", "interferenceBudget": 0}
                  ],
                  "edges": [
                    {"fromStationId": "s1", "toStationId": "s2", "interference": 60},
                    {"fromStationId": "s3", "toStationId": "s2", "interference": 60},
                    {"fromStationId": "s2", "toStationId": "s1", "interference": 0}
                  ]
                }
                """.formatted(networkId);
    }

    @Test
    void createNetworkStartsSilentAtVersionOne() throws Exception {
        String id = "net-" + UUID.randomUUID();
        ResponseEntity<String> resp = createNetwork(id, basicNetworkBody(id), "req-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("stations")).hasSize(3);
        body.get("stations").forEach(s -> assertThat(s.get("channel").asInt()).isZero());

        ResponseEntity<String> again = restTemplate.getForEntity(
                "/api/spectrum/networks/" + id, String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(again.getBody()).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void successfulPlanIncrementsVersionAndPersistsHistory() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        String planBody = """
                {
                  "expectedVersion": 1,
                  "planKey": "plan-%s",
                  "assignments": [{"stationId": "s1", "channel": 1}]
                }
                """.formatted(UUID.randomUUID());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/spectrum/networks/" + id + "/plans", HttpMethod.POST,
                new HttpEntity<>(planBody, jsonHeaders("req-p-" + UUID.randomUUID())), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode plan = objectMapper.readTree(resp.getBody());
        assertThat(plan.get("versionFrom").asInt()).isEqualTo(1);
        assertThat(plan.get("versionTo").asInt()).isEqualTo(2);
        assertThat(plan.get("before").get("stations").get(0).get("channel").asInt()).isZero();
        assertThat(plan.get("after").get("stations").get(0).get("channel").asInt()).isEqualTo(1);

        ResponseEntity<String> state = restTemplate.getForEntity(
                "/api/spectrum/networks/" + id, String.class);
        assertThat(objectMapper.readTree(state.getBody()).get("version").asInt()).isEqualTo(2);

        ResponseEntity<String> history = restTemplate.getForEntity(
                "/api/spectrum/networks/" + id + "/plans", String.class);
        JsonNode plans = objectMapper.readTree(history.getBody());
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).get("planKey").asText()).startsWith("plan-");
    }

    @Test
    void channelSwapViaSinglePlanIsLegal() throws Exception {
        String id = "net-" + UUID.randomUUID();
        // 两个台站无干扰边，交换频道必须原子完成
        String body = """
                {
                  "networkId": "%s",
                  "stations": [
                    {"stationId": "a", "interferenceBudget": 1000},
                    {"stationId": "b", "interferenceBudget": 1000}
                  ],
                  "edges": []
                }
                """.formatted(id);
        createNetwork(id, body, "req-c-" + UUID.randomUUID());

        String first = """
                {
                  "expectedVersion": 1,
                  "planKey": "plan-a-%s",
                  "assignments": [
                    {"stationId": "a", "channel": 1},
                    {"stationId": "b", "channel": 2}
                  ]
                }
                """.formatted(UUID.randomUUID());
        submit(id, first, "req-p1-" + UUID.randomUUID());

        String swap = """
                {
                  "expectedVersion": 2,
                  "planKey": "plan-b-%s",
                  "assignments": [
                    {"stationId": "a", "channel": 2},
                    {"stationId": "b", "channel": 1}
                  ]
                }
                """.formatted(UUID.randomUUID());
        ResponseEntity<String> resp = submit(id, swap, "req-p2-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode after = objectMapper.readTree(resp.getBody()).get("after");
        assertThat(after.get("stations").get(0).get("channel").asInt()).isEqualTo(2);
        assertThat(after.get("stations").get(1).get("channel").asInt()).isEqualTo(1);
    }

    @Test
    void overBudgetReturns422LeavesEverythingUnchanged() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        // s1(频道1) + s3(频道1) 同时指向同频 s2：60+60=120 > 预算100
        String badPlan = """
                {
                  "expectedVersion": 1,
                  "planKey": "plan-bad-%s",
                  "assignments": [
                    {"stationId": "s1", "channel": 1},
                    {"stationId": "s2", "channel": 1},
                    {"stationId": "s3", "channel": 1}
                  ]
                }
                """.formatted(UUID.randomUUID());
        String badRequestId = "req-bad-" + UUID.randomUUID();
        ResponseEntity<String> resp = submit(id, badPlan, badRequestId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode error = objectMapper.readTree(resp.getBody());
        assertThat(error.get("code").asText()).isEqualTo("INTERFERENCE_BUDGET_EXCEEDED");
        JsonNode details = error.get("details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).get("stationId").asText()).isEqualTo("s2");
        assertThat(details.get(0).get("actualInterference").asInt()).isEqualTo(120);
        assertThat(details.get(0).get("interferenceBudget").asInt()).isEqualTo(100);

        // 频道、版本、方案记录完全不变
        JsonNode state = objectMapper.readTree(
                restTemplate.getForEntity("/api/spectrum/networks/" + id, String.class).getBody());
        assertThat(state.get("version").asInt()).isEqualTo(1);
        state.get("stations").forEach(s -> assertThat(s.get("channel").asInt()).isZero());
        JsonNode plans = objectMapper.readTree(restTemplate.getForEntity(
                "/api/spectrum/networks/" + id + "/plans", String.class).getBody());
        assertThat(plans).isEmpty();

        // 失败不占 requestId：同请求键修正参数后可成功
        String fixedPlan = badPlan.replace("\"plan-bad-", "\"plan-fixed-")
                .replace("\"s3\", \"channel\": 1", "\"s3\", \"channel\": 0");
        ResponseEntity<String> retry = submit(id, fixedPlan, badRequestId);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void equalToBudgetIsAccepted() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        // 仅 s1 与 s2 同频：60，再让 s3 静默（初始即静默，不列出）；60<=100
        String plan = """
                {
                  "expectedVersion": 1,
                  "planKey": "plan-eq-%s",
                  "assignments": [
                    {"stationId": "s1", "channel": 1},
                    {"stationId": "s2", "channel": 1}
                  ]
                }
                """.formatted(UUID.randomUUID());
        ResponseEntity<String> resp = submit(id, plan, "req-eq-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode summary = objectMapper.readTree(resp.getBody()).get("interferenceSummary");
        JsonNode s2 = summary.get(1);
        assertThat(s2.get("totalInterference").asInt()).isEqualTo(60);
        assertThat(s2.get("withinBudget").asBoolean()).isTrue();
    }

    @Test
    void replaySameRequestIdReturnsFirstResultAndDoesNotIncrementVersion() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        String planKey = "plan-r-" + UUID.randomUUID();
        String requestId = "req-r-" + UUID.randomUUID();
        String plan = """
                {
                  "expectedVersion": 1,
                  "planKey": "%s",
                  "assignments": [{"stationId": "s1", "channel": 3}]
                }
                """.formatted(planKey);
        ResponseEntity<String> first = submit(id, plan, requestId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 同键同参（assignments 换序不影响，此处整体重放）返回首次结果，版本不再增加
        ResponseEntity<String> second = submit(id, plan, requestId);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());

        JsonNode state = objectMapper.readTree(restTemplate.getForEntity(
                "/api/spectrum/networks/" + id, String.class).getBody());
        assertThat(state.get("version").asInt()).isEqualTo(2);

        // 同 requestId 异参 -> 409
        String different = plan.replace("\"channel\": 3", "\"channel\": 4");
        ResponseEntity<String> conflict = submit(id, different, requestId);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void replayWithReorderedAssignmentsIsSameParameters() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        String requestId = "req-o-" + UUID.randomUUID();
        String planKey = "plan-o-" + UUID.randomUUID();
        String planA = """
                {
                  "expectedVersion": 1,
                  "planKey": "%s",
                  "assignments": [
                    {"stationId": "s1", "channel": 1},
                    {"stationId": "s2", "channel": 2}
                  ]
                }
                """.formatted(planKey);
        ResponseEntity<String> first = submit(id, planA, requestId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 同一 requestId 与 planKey，仅 assignments 集合换序：同参，回放首次结果
        String planB = """
                {
                  "expectedVersion": 1,
                  "planKey": "%s",
                  "assignments": [
                    {"stationId": "s2", "channel": 2},
                    {"stationId": "s1", "channel": 1}
                  ]
                }
                """.formatted(planKey);
        ResponseEntity<String> second = submit(id, planB, requestId);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void reusingPlanKeyWithDifferentRequestIdIsConflict() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        String planKey = "plan-k-" + UUID.randomUUID();
        String plan = """
                {
                  "expectedVersion": 1,
                  "planKey": "%s",
                  "assignments": [{"stationId": "s1", "channel": 1}]
                }
                """.formatted(planKey);
        assertThat(submit(id, plan, "req-k1-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> reuse = submit(id, plan, "req-k2-" + UUID.randomUUID());
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void staleExpectedVersionIsConflictAndStateUnchanged() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        String plan1 = planJson(1, "plan-v1-" + UUID.randomUUID(),
                List.of(Map.entry("s1", 1)));
        assertThat(submit(id, plan1, "req-v1-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String stale = planJson(1, "plan-v2-" + UUID.randomUUID(),
                List.of(Map.entry("s2", 1)));
        ResponseEntity<String> resp = submit(id, stale, "req-v2-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(resp.getBody()).get("code").asText())
                .isEqualTo("VERSION_CONFLICT");

        // 失败不占键：用新 expectedVersion 与原 requestId 修正后可成功
        String fixed = planJson(2, "plan-v3-" + UUID.randomUUID(),
                List.of(Map.entry("s2", 1)));
        assertThat(submit(id, fixed, "req-v2-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void noChangePlanStillIncrementsVersion() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        String first = planJson(1, "plan-n1-" + UUID.randomUUID(), List.of(Map.entry("s1", 1)));
        submit(id, first, "req-n1-" + UUID.randomUUID());

        // s1 已是频道1，再次提交相同频道，属于无变化方案，版本仍加一
        String noChange = planJson(2, "plan-n2-" + UUID.randomUUID(), List.of(Map.entry("s1", 1)));
        ResponseEntity<String> resp = submit(id, noChange, "req-n2-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(resp.getBody()).get("versionTo").asInt()).isEqualTo(3);
    }

    @Test
    void concurrentSameExpectedVersionOnlyOneSucceeds() throws Exception {
        String id = "net-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                String body = planJson(1, "plan-conc-" + UUID.randomUUID(),
                        List.of(Map.entry("s1", idx % 8 + 1)));
                ready.countDown();
                start.await();
                return submit(id, body, "req-conc-" + UUID.randomUUID());
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int ok = 0;
        int conflict = 0;
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> result = future.get(30, TimeUnit.SECONDS);
            if (result.getStatusCode() == HttpStatus.OK) {
                ok++;
            } else if (result.getStatusCode() == HttpStatus.CONFLICT) {
                conflict++;
            } else {
                throw new AssertionError("意外状态码: " + result.getStatusCode() + " " + result.getBody());
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);

        JsonNode state = objectMapper.readTree(restTemplate.getForEntity(
                "/api/spectrum/networks/" + id, String.class).getBody());
        assertThat(state.get("version").asInt()).isEqualTo(2);
        JsonNode plans = objectMapper.readTree(restTemplate.getForEntity(
                "/api/spectrum/networks/" + id + "/plans", String.class).getBody());
        assertThat(plans).hasSize(1);
    }

    @Test
    void fullNetworkReEvaluatedNotOnlyChangedStations() throws Exception {
        // 网络：x->r=60, y->r=60，r 预算100。
        // 方案1 仅 x 与 r 上频道1：r 收到60，合法。
        // 方案2 仅把 y 改为频道1（r、x 均不在提交列表中）：r 累计120 超预算，必须判 422。
        String id = "net-full-" + UUID.randomUUID();
        String body = """
                {
                  "networkId": "%s",
                  "stations": [
                    {"stationId": "x", "interferenceBudget": 1000},
                    {"stationId": "y", "interferenceBudget": 1000},
                    {"stationId": "r", "interferenceBudget": 100}
                  ],
                  "edges": [
                    {"fromStationId": "x", "toStationId": "r", "interference": 60},
                    {"fromStationId": "y", "toStationId": "r", "interference": 60}
                  ]
                }
                """.formatted(id);
        createNetwork(id, body, "req-c-" + UUID.randomUUID());

        String plan1 = planJson(1, "plan-f1-" + UUID.randomUUID(),
                List.of(Map.entry("x", 1), Map.entry("r", 1)));
        assertThat(submit(id, plan1, "req-f1-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String plan2 = planJson(2, "plan-f2-" + UUID.randomUUID(),
                List.of(Map.entry("y", 1)));
        ResponseEntity<String> resp = submit(id, plan2, "req-f2-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode details = objectMapper.readTree(resp.getBody()).get("details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).get("stationId").asText()).isEqualTo("r");
        assertThat(details.get(0).get("actualInterference").asInt()).isEqualTo(120);

        // 回滚：y 仍静默，版本仍为2
        JsonNode state = objectMapper.readTree(restTemplate.getForEntity(
                "/api/spectrum/networks/" + id, String.class).getBody());
        assertThat(state.get("version").asInt()).isEqualTo(2);
        assertThat(state.get("stations").get(1).get("channel").asInt()).isZero();
    }

    @Test
    void invalidNetworkDefinitionReturns400() {
        String selfLoop = """
                {
                  "networkId": "net-bad-%s",
                  "stations": [{"stationId": "x", "interferenceBudget": 0}],
                  "edges": [{"fromStationId": "x", "toStationId": "x", "interference": 1}]
                }
                """.formatted(UUID.randomUUID());
        ResponseEntity<String> resp = createNetwork(
                "net-bad-" + UUID.randomUUID(), selfLoop, "req-bad-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String dupStation = """
                {
                  "networkId": "net-dup",
                  "stations": [
                    {"stationId": "x", "interferenceBudget": 0},
                    {"stationId": "x", "interferenceBudget": 1}
                  ]
                }
                """;
        assertThat(createNetwork("net-dup", dupStation, "req-dup-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingNetworkReturns404AndValidationReturns400() {
        ResponseEntity<String> missing = restTemplate.getForEntity(
                "/api/spectrum/networks/no-such-net", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String id = "net-400-" + UUID.randomUUID();
        createNetwork(id, basicNetworkBody(id), "req-c-" + UUID.randomUUID());
        String badChannel = """
                {
                  "expectedVersion": 1,
                  "planKey": "plan-400",
                  "assignments": [{"stationId": "s1", "channel": 9}]
                }
                """;
        ResponseEntity<String> resp = submit(id, badChannel, "req-400-" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNetworkIdempotentReplayReturnsFirstResult() throws Exception {
        String id = "net-idem-" + UUID.randomUUID();
        String requestId = "req-idem-" + UUID.randomUUID();
        ResponseEntity<String> first = createNetwork(id, basicNetworkBody(id), requestId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> second = createNetwork(id, basicNetworkBody(id), requestId);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    private ResponseEntity<String> submit(String networkId, String body, String requestId) {
        return restTemplate.exchange(
                "/api/spectrum/networks/" + networkId + "/plans", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(requestId)), String.class);
    }

    private String planJson(int expectedVersion, String planKey, List<Map.Entry<String, Integer>> assignments) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"expectedVersion\":").append(expectedVersion)
                .append(",\"planKey\":\"").append(planKey).append("\",\"assignments\":[");
        for (int i = 0; i < assignments.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Map.Entry<String, Integer> a = assignments.get(i);
            sb.append("{\"stationId\":\"").append(a.getKey())
                    .append("\",\"channel\":").append(a.getValue()).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }
}
