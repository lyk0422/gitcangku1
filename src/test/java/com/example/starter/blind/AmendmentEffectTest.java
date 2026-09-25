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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修订原子生效与既有盲态隔离（真实 H2）：
 * 每个 ACTIVE 中心按剩余容量预留序列；容量不足或存在待审揭盲即 422 且无半成品；
 * 生效后旧版本盲码/分组/揭盲归属不变，新受试者使用新版本；登记惰性触发到点生效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AmendmentEffectTest extends AbstractBlindIntegrationTest {

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

    private JsonNode json(ResponseEntity<String> resp) throws Exception {
        assertNotNull(resp.getBody());
        return mapper.readTree(resp.getBody());
    }

    private void createExperiment(String id, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private void activateCenter(String expId, String centerId, int cap, String reqId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/centers/" + centerId, HttpMethod.POST,
                headers("c1", "COORDINATOR", reqId), "{\"targetCap\":" + cap + "}")
                .getStatusCode().value());
    }

    private JsonNode centerAllocate(String expId, String centerId, String pid, String reqId)
            throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/centers/" + centerId
                        + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", reqId), null);
        assertEquals(201, resp.getStatusCode().value(), "中心登记应成功: " + pid);
        return json(resp);
    }

    private void createAndEffectAmendment(String expId, int ratioA, int ratioB,
                                          long effectiveAt, String prefix) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", prefix + "-create"),
                "{\"ratioA\":" + ratioA + ",\"ratioB\":" + ratioB
                        + ",\"effectiveAt\":" + effectiveAt + "}").getStatusCode().value());
        List<Integer> versions = jdbc.queryForList(
                "SELECT version FROM protocol_version WHERE experiment_id = '" + expId
                        + "' AND status = 'PENDING'", Integer.class);
        assertEquals(1, versions.size());
        clock.setTime(effectiveAt);
        assertEquals(200, exchange(
                "/api/experiments/" + expId + "/protocol-amendments/" + versions.get(0)
                        + "/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", prefix + "-effect"), null)
                .getStatusCode().value());
    }

    @Test
    void effect_provisionsPerActiveCenterRemainingCapacity_andSwitchesAllAtomicly() {
        createExperiment("AE-1", "ae1-create");
        activateCenter("AE-1", "CTR-A", 5, "ae1-a");
        activateCenter("AE-1", "CTR-B", 7, "ae1-b");

        createAndEffectAmendment("AE-1", 30, 70, 1_700_000_010_000L, "ae1-amd");

        // 版本状态
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AE-1' AND version = 2",
                String.class));
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AE-1' AND version = 1",
                String.class));
        // 两个 ACTIVE 中心都切到 V2
        assertEquals(2, jdbc.queryForObject(
                "SELECT current_version FROM center WHERE experiment_id = 'AE-1' AND center_id = 'CTR-A'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT current_version FROM center WHERE experiment_id = 'AE-1' AND center_id = 'CTR-B'",
                Integer.class));
        // 每个 ACTIVE 中心按剩余容量预留恰好对应条数的 V2 独立序列
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-1' "
                        + "AND center_id = 'CTR-A' AND version = 2", Integer.class));
        assertEquals(7, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-1' "
                        + "AND center_id = 'CTR-B' AND version = 2", Integer.class));
        // V1 既有序列不改变
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-1' "
                        + "AND center_id = 'CTR-A' AND version = 1", Integer.class));
        assertEquals(7, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-1' "
                        + "AND center_id = 'CTR-B' AND version = 1", Integer.class));
        // V2 规范化区组：gcd(30,70)=10 → 每区组 10 席（3A7B）；总剩余 12 → 2 个区组共 20 席
        assertEquals(20, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_seat WHERE experiment_id = 'AE-1' AND version = 2",
                Integer.class));
        assertEquals(6L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_seat WHERE experiment_id = 'AE-1' "
                        + "AND version = 2 AND treatment = 'A'", Long.class));
        assertEquals(14L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_seat WHERE experiment_id = 'AE-1' "
                        + "AND version = 2 AND treatment = 'B'", Long.class));
    }

    @Test
    void effect_usesRemainingCapacity_afterAllocations_andOldSequencesStayUnused() throws Exception {
        createExperiment("AE-2", "ae2-create");
        activateCenter("AE-2", "CTR-A", 6, "ae2-a");
        centerAllocate("AE-2", "CTR-A", "P1", "ae2-p1");
        centerAllocate("AE-2", "CTR-A", "P2", "ae2-p2");

        clock.setTime(1_700_000_010_000L);
        createAndEffectAmendment("AE-2", 30, 70, 1_700_000_010_000L, "ae2-amd");

        // 剩余容量 4：V2 序列恰好 4 条
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-2' "
                        + "AND center_id = 'CTR-A' AND version = 2", Integer.class));
        // V1 旧序列保留且仍有 4 条未消耗，但新登记不再使用 V1
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-2' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NOT NULL",
                Integer.class));

        JsonNode p3 = centerAllocate("AE-2", "CTR-A", "P3", "ae2-p3");
        assertEquals(2, p3.path("protocolVersion").asInt());
        assertEquals(2, jdbc.queryForObject(
                "SELECT protocol_version FROM allocation WHERE experiment_id = 'AE-2' "
                        + "AND participant_id = 'P3'", Integer.class));
        // V1 消耗数不变，V2 消耗 1
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-2' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NOT NULL",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-2' "
                        + "AND center_id = 'CTR-A' AND version = 2 AND allocation_id IS NOT NULL",
                Integer.class));
    }

    @Test
    void effect_capacityShortfall_returns422_andLeavesNoHalfFinishedState() {
        createExperiment("AE-3", "ae3-create");
        activateCenter("AE-3", "CTR-A", 4, "ae3-a");
        activateCenter("AE-3", "CTR-B", 4, "ae3-b");
        for (int i = 1; i <= 4; i++) {
            try {
                centerAllocate("AE-3", "CTR-A", "P" + i, "ae3-p" + i);
            } catch (Exception ignored) {
                // 断言在响应状态层完成；此处保证 4 条占满 CTR-A
            }
        }
        assertEquals(4L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'AE-3' AND center_id = 'CTR-A'",
                Long.class));

        // 创建到点修订
        clock.setTime(1_700_000_010_000L);
        assertEquals(201, exchange("/api/experiments/AE-3/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae3-amd"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}")
                .getStatusCode().value());

        ResponseEntity<String> failed = exchange(
                "/api/experiments/AE-3/protocol-amendments/2/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae3-effect"), null);
        assertEquals(422, failed.getStatusCode().value());

        // 无半成品：V2 仍 PENDING、未生成 V2 序列与席位、中心仍 V1、V1 仍 EFFECTIVE
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AE-3' AND version = 2",
                String.class));
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AE-3' AND version = 1",
                String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AE-3' AND version = 2",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_seat WHERE experiment_id = 'AE-3' AND version = 2",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT current_version FROM center WHERE experiment_id = 'AE-3' AND center_id = 'CTR-B'",
                Integer.class));
    }

    @Test
    void effect_withPendingUnblindRequest_returns422_thenApprovedAllowsEffect() throws Exception {
        createExperiment("AE-4", "ae4-create");
        activateCenter("AE-4", "CTR-A", 6, "ae4-a");
        centerAllocate("AE-4", "CTR-A", "P1", "ae4-p1");

        // 提出待审揭盲
        ResponseEntity<String> apply = exchange(
                "/api/experiments/AE-4/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae4-unblind"), "{\"reason\":\"待审阻断修订\"}");
        assertEquals(201, apply.getStatusCode().value());

        clock.setTime(1_700_000_010_000L);
        assertEquals(201, exchange("/api/experiments/AE-4/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae4-amd"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}")
                .getStatusCode().value());

        ResponseEntity<String> blocked = exchange(
                "/api/experiments/AE-4/protocol-amendments/2/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae4-effect-blocked"), null);
        assertEquals(422, blocked.getStatusCode().value());
        assertTrue(json(blocked).path("message").asText().contains("待处理揭盲"));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AE-4' AND version = 2",
                String.class));

        // 批准揭盲后可生效
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers("r1", "REVIEWER", "ae4-approve"), null).getStatusCode().value());
        assertEquals(200, exchange(
                "/api/experiments/AE-4/protocol-amendments/2/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae4-effect"), null).getStatusCode().value());
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AE-4' AND version = 2",
                String.class));
    }

    @Test
    void existingBlindnessAndUnblinding_stayOnOldVersion_newSubjectsUseNewVersion() throws Exception {
        createExperiment("AE-5", "ae5-create");
        activateCenter("AE-5", "CTR-A", 20, "ae5-a");
        // V1：P1 落在中心 V1 池首个席位（实验 2 区组 → 池区组号 3，席 1，处理 A）
        JsonNode p1 = centerAllocate("AE-5", "CTR-A", "P1", "ae5-p1");
        assertEquals(1, p1.path("protocolVersion").asInt());

        clock.setTime(1_700_000_010_000L);
        createAndEffectAmendment("AE-5", 30, 70, 1_700_000_010_000L, "ae5-amd");

        // V2：P2..P5 落在 V2 池第一区组（3A7B），第 4 席为 B
        for (int i = 2; i <= 5; i++) {
            JsonNode p = centerAllocate("AE-5", "CTR-A", "P" + i, "ae5-p" + i);
            assertEquals(2, p.path("protocolVersion").asInt());
        }
        // 库内校验 P5 归属 V2 且处理为 B，P1 归属 V1 中心池且处理为 A
        assertEquals("B", jdbc.queryForObject(
                "SELECT s.treatment FROM allocation a JOIN protocol_seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.version = a.protocol_version "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'AE-5' AND a.participant_id = 'P5'",
                String.class));
        assertEquals("A", jdbc.queryForObject(
                "SELECT s.treatment FROM allocation a JOIN protocol_seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.version = a.protocol_version "
                        + "AND s.block_no = a.block_no AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'AE-5' AND a.participant_id = 'P1'",
                String.class));

        // 受控揭盲：P1（旧版本 A）与 P5（新版本 B）各自按登记版本解析
        String ub1 = json(exchange(
                "/api/experiments/AE-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae5-ub1"), "{\"reason\":\"核对旧版本\"}"))
                .path("requestId").asText();
        String ub5 = json(exchange(
                "/api/experiments/AE-5/participants/P5/unblind-requests", HttpMethod.POST,
                headers("c2", "COORDINATOR", "ae5-ub5"), "{\"reason\":\"核对新版本\"}"))
                .path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ub1 + "/approval", HttpMethod.POST,
                headers("r1", "REVIEWER", "ae5-ub1-ok"), null).getStatusCode().value());
        assertEquals(200, exchange("/api/unblind-requests/" + ub5 + "/approval", HttpMethod.POST,
                headers("r2", "REVIEWER", "ae5-ub5-ok"), null).getStatusCode().value());

        assertEquals("A", json(exchange("/api/unblind-requests/" + ub1 + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null))
                .path("treatment").asText());
        assertEquals("B", json(exchange("/api/unblind-requests/" + ub5 + "/result",
                HttpMethod.GET, headers("c2", "COORDINATOR", null), null))
                .path("treatment").asText());
    }

    @Test
    void dueAmendment_isAppliedLazily_onCenterAllocation_andCommitOrderWins() throws Exception {
        createExperiment("AE-6", "ae6-create");
        activateCenter("AE-6", "CTR-A", 10, "ae6-a");
        // 创建到点但不手动生效的修订
        clock.setTime(1_700_000_010_000L);
        assertEquals(201, exchange("/api/experiments/AE-6/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ae6-amd"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}")
                .getStatusCode().value());
        // 尚未到点前创建的登记若在到点后提交：登记事务惰性生效，新受试者归属 V2
        JsonNode p1 = centerAllocate("AE-6", "CTR-A", "P1", "ae6-p1");
        assertEquals(2, p1.path("protocolVersion").asInt(), "到点修订应在登记时惰性生效");
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AE-6' AND version = 2",
                String.class));
    }
}
