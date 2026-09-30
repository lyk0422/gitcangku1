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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 研究中心启用门禁主流程与拒绝分支（真实 H2 MySQL 模式）：
 * 双人确认同一版本一次性启用、未启用冻结分配、10 个名额第 11 次拒绝且不泄露处理组、
 * 负责人/分配方视图隔离、缩容 409 不改状态、历史版本稳定、幂等重放与异参冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteActivationFlowTest extends AbstractBlindIntegrationTest {

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

    private void assertNoBlindingLeak(JsonNode node) {
        assertFalse(node.has("treatment"), "响应不得包含处理代码");
        assertFalse(node.has("seatNo"), "响应不得包含席位序号");
    }

    private String createSite(String siteId, String lead, String reqId) {
        ResponseEntity<String> resp = exchange("/api/sites/" + siteId, HttpMethod.POST,
                headers(lead, "COORDINATOR", reqId),
                "{\"leadActorId\":\"" + lead + "\"}");
        assertEquals(201, resp.getStatusCode().value(), "创建中心应成功");
        return resp.getBody();
    }

    /** 提交版本→负责人确认→独立复核人确认，返回启用申请编号。 */
    private String activateVersion(String siteId, String lead, String reviewer,
                                   String prefix, String blocksJson) {
        String requestId = submitConfig(siteId, lead, prefix + "-submit", blocksJson);
        assertEquals(200, exchange(
                "/api/sites/activation-requests/" + requestId + "/lead-confirmation",
                HttpMethod.POST, headers(lead, "COORDINATOR", prefix + "-lead"), null)
                .getStatusCode().value());
        ResponseEntity<String> reviewerResp = exchange(
                "/api/sites/activation-requests/" + requestId + "/reviewer-confirmation",
                HttpMethod.POST, headers(reviewer, "REVIEWER", prefix + "-reviewer"), null);
        assertEquals(200, reviewerResp.getStatusCode().value());
        return requestId;
    }

    private String submitConfig(String siteId, String lead, String reqId, String blocksJson) {
        ResponseEntity<String> resp = exchange(
                "/api/sites/" + siteId + "/activation-requests", HttpMethod.POST,
                headers(lead, "COORDINATOR", reqId),
                "{\"blocks\":" + blocksJson + "}");
        assertEquals(201, resp.getStatusCode().value(), "提交配置版本应成功: " + resp.getBody());
        try {
            return json(resp).path("requestId").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void siteC_blockA10Version3_dualConfirm_allowsTenAndRejectsEleventhWithoutTreatment()
            throws Exception {
        createSite("C", "lead-1", "c-create");
        // 未启用：分配被冻结
        ResponseEntity<String> before = exchange(
                "/api/sites/C/participants/PP0/allocations", HttpMethod.POST,
                headers("lead-1", "COORDINATOR", "c-alloc-before"), null);
        assertEquals(409, before.getStatusCode().value());
        assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE id = 'C'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'C'", Integer.class));

        // 先启用版本1（2个名额）、版本2（5个名额），版本3预设区组A=10个名额
        activateVersion("C", "lead-1", "rev-1", "c-v1", "[{\"blockNo\":1,\"quota\":2}]");
        activateVersion("C", "lead-1", "rev-2", "c-v2", "[{\"blockNo\":1,\"quota\":5}]");
        String v3Id = submitConfig("C", "lead-1", "c-v3-submit",
                "[{\"blockNo\":1,\"quota\":10}]");
        ResponseEntity<String> leadView = exchange(
                "/api/sites/activation-requests/" + v3Id + "/lead-confirmation",
                HttpMethod.POST, headers("lead-1", "COORDINATOR", "c-v3-lead"), null);
        assertEquals(200, leadView.getStatusCode().value());
        assertEquals("PENDING", json(leadView).path("status").asText(), "仅负责人确认不得启用");

        // 复核人不能与负责人为同一人
        assertEquals(403, exchange(
                "/api/sites/activation-requests/" + v3Id + "/reviewer-confirmation",
                HttpMethod.POST, headers("lead-1", "REVIEWER", "c-v3-self"), null)
                .getStatusCode().value());

        // 独立复核人确认版本3 → 一次性启用
        ResponseEntity<String> reviewed = exchange(
                "/api/sites/activation-requests/" + v3Id + "/reviewer-confirmation",
                HttpMethod.POST, headers("rev-3", "REVIEWER", "c-v3-reviewer"), null);
        assertEquals(200, reviewed.getStatusCode().value());
        JsonNode reviewedBody = json(reviewed);
        assertEquals("ACTIVATED", reviewedBody.path("status").asText());
        assertEquals(3, reviewedBody.path("version").asInt());
        assertEquals("rev-3", reviewedBody.path("reviewerActorId").asText());
        assertNoBlindingLeak(reviewedBody);
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE id = 'C'", String.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT active_version FROM site WHERE id = 'C'", Integer.class));

        // 连续分配 10 次成功，均落区组1，盲码无含义且唯一，响应无处理组
        Set<String> codes = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            ResponseEntity<String> alloc = exchange(
                    "/api/sites/C/participants/PA" + i + "/allocations", HttpMethod.POST,
                    headers("lead-1", "COORDINATOR", "c-alloc-" + i), null);
            assertEquals(201, alloc.getStatusCode().value(), "第 " + i + " 次分配应成功");
            JsonNode body = json(alloc);
            assertEquals(1, body.path("blockNo").asInt());
            // 视图不返回版本号以外的任何盲底字段
            assertNoBlindingLeak(body);
            String code = body.path("blindCode").asText();
            assertTrue(code.matches("[A-Z2-9]{12}"), "盲码应为12位无含义码: " + code);
            assertTrue(codes.add(code), "盲码必须唯一");
        }

        // 第 11 次被拒，错误响应不得泄露处理组信息
        ResponseEntity<String> eleventh = exchange(
                "/api/sites/C/participants/PA11/allocations", HttpMethod.POST,
                headers("lead-1", "COORDINATOR", "c-alloc-11"), null);
        assertEquals(409, eleventh.getStatusCode().value());
        assertNotNull(eleventh.getBody());
        assertFalse(eleventh.getBody().contains("treatment"));
        assertFalse(eleventh.getBody().contains("seatNo"));
        assertFalse(eleventh.getBody().contains("处理组"));
        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'C'", Integer.class));

        // 同参与者不可重复占席
        assertEquals(409, exchange(
                "/api/sites/C/participants/PA1/allocations", HttpMethod.POST,
                headers("lead-1", "COORDINATOR", "c-alloc-p1-again"), null)
                .getStatusCode().value());
    }

    @Test
    void reviewerCanConfirmFirst_siteStaysInactiveUntilLeadConfirms() throws Exception {
        createSite("D", "lead-2", "d-create");
        String requestId = submitConfig("D", "lead-2", "d-submit",
                "[{\"blockNo\":1,\"quota\":3}]");

        // 复核人先确认：留痕但不启用
        ResponseEntity<String> reviewerFirst = exchange(
                "/api/sites/activation-requests/" + requestId + "/reviewer-confirmation",
                HttpMethod.POST, headers("rev-9", "REVIEWER", "d-reviewer-first"), null);
        assertEquals(200, reviewerFirst.getStatusCode().value());
        JsonNode body = json(reviewerFirst);
        assertEquals("PENDING", body.path("status").asText());
        assertTrue(body.path("reviewerConfirmed").asBoolean());
        assertFalse(body.path("leadConfirmed").asBoolean());
        assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE id = 'D'", String.class));
        assertEquals(409, exchange("/api/sites/D/participants/Q1/allocations", HttpMethod.POST,
                headers("lead-2", "COORDINATOR", "d-alloc-blocked"), null)
                .getStatusCode().value());

        // 另一名复核人不能顶替确认
        assertEquals(409, exchange(
                "/api/sites/activation-requests/" + requestId + "/reviewer-confirmation",
                HttpMethod.POST, headers("rev-other", "REVIEWER", "d-reviewer-other"), null)
                .getStatusCode().value());

        // 负责人随后确认 → 第二人确认，一次性启用
        ResponseEntity<String> leadLater = exchange(
                "/api/sites/activation-requests/" + requestId + "/lead-confirmation",
                HttpMethod.POST, headers("lead-2", "COORDINATOR", "d-lead-later"), null);
        assertEquals(200, leadLater.getStatusCode().value());
        assertEquals("ACTIVATED", json(leadLater).path("status").asText());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE id = 'D'", String.class));
        assertEquals(201, exchange("/api/sites/D/participants/Q1/allocations", HttpMethod.POST,
                headers("lead-2", "COORDINATOR", "d-alloc-ok"), null).getStatusCode().value());
    }

    @Test
    void multiBlock_allocationsFillInBlockOrder_andQuotaViewsAreRoleSeparated() throws Exception {
        createSite("E", "lead-3", "e-create");
        activateVersion("E", "lead-3", "rev-4", "e-v1",
                "[{\"blockNo\":1,\"quota\":2},{\"blockNo\":2,\"quota\":3}]");

        int[] expectedBlocks = {1, 1, 2, 2, 2};
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<String> alloc = exchange(
                    "/api/sites/E/participants/M" + i + "/allocations", HttpMethod.POST,
                    headers("lead-3", "COORDINATOR", "e-alloc-" + i), null);
            assertEquals(201, alloc.getStatusCode().value());
            assertEquals(expectedBlocks[i - 1], json(alloc).path("blockNo").asInt());
            assertNoBlindingLeak(json(alloc));
        }
        assertEquals(409, exchange("/api/sites/E/participants/M6/allocations", HttpMethod.POST,
                headers("lead-3", "COORDINATOR", "e-alloc-6"), null).getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'E' AND block_no = 1",
                Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'E' AND block_no = 2",
                Integer.class));

        // 分配方视图：只有剩余额度，没有配额/冻结/已用/版本
        ResponseEntity<String> remainingResp = exchange("/api/sites/E/remaining-quota",
                HttpMethod.GET, headers("staff-1", "COORDINATOR", null), null);
        assertEquals(200, remainingResp.getStatusCode().value());
        JsonNode remaining = json(remainingResp);
        assertEquals(0, remaining.path("blocks").get(0).path("remaining").asInt());
        assertEquals(0, remaining.path("blocks").get(1).path("remaining").asInt());
        assertFalse(remaining.has("version"));
        assertFalse(remaining.path("blocks").get(0).has("quota"));
        assertFalse(remaining.path("blocks").get(0).has("used"));
        assertFalse(remaining.path("blocks").get(0).has("frozen"));
    }

    @Test
    void shrinkBelowFrozenOrUsed_conflicts_andStateUnchanged() throws Exception {
        createSite("F", "lead-4", "f-create");
        String v1 = activateVersion("F", "lead-4", "rev-5", "f-v1",
                "[{\"blockNo\":1,\"quota\":5}]");
        for (int i = 1; i <= 3; i++) {
            assertEquals(201, exchange(
                    "/api/sites/F/participants/S" + i + "/allocations", HttpMethod.POST,
                    headers("lead-4", "COORDINATOR", "f-alloc-" + i), null)
                    .getStatusCode().value());
        }

        // 低于旧冻结量：409
        ResponseEntity<String> shrinkFrozen = exchange(
                "/api/sites/F/activation-requests", HttpMethod.POST,
                headers("lead-4", "COORDINATOR", "f-v2-shrink-frozen"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":4}]}");
        assertEquals(409, shrinkFrozen.getStatusCode().value());
        // 低于已分配量：409
        ResponseEntity<String> shrinkUsed = exchange(
                "/api/sites/F/activation-requests", HttpMethod.POST,
                headers("lead-4", "COORDINATOR", "f-v2-shrink-used"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":2}]}");
        assertEquals(409, shrinkUsed.getStatusCode().value());
        // 失败不改变中心与区组状态：仍停留在版本1，无版本2残留
        assertEquals(1, jdbc.queryForObject(
                "SELECT active_version FROM site WHERE id = 'F'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_request WHERE site_id = 'F'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_block_snapshot WHERE site_id = 'F' AND version = 2",
                Integer.class));

        // 等额允许（非缩减），但保持待确认不影响当前分配版本
        String equalId = submitConfig("F", "lead-4", "f-v2-equal",
                "[{\"blockNo\":1,\"quota\":5}]");
        assertEquals(2, json(exchange("/api/sites/activation-requests/" + equalId,
                HttpMethod.GET, headers("lead-4", "COORDINATOR", null), null))
                .path("version").asInt());

        // 负责人视角：版本1 冻结5、已用3、剩余2
        JsonNode leadView = json(exchange("/api/sites/activation-requests/" + v1,
                HttpMethod.GET, headers("lead-4", "COORDINATOR", null), null));
        assertEquals("ACTIVATED", leadView.path("status").asText());
        JsonNode block = leadView.path("blocks").get(0);
        assertEquals(5, block.path("quota").asInt());
        assertEquals(5, block.path("frozen").asInt());
        assertEquals(3, block.path("used").asInt());
        assertEquals(2, block.path("remaining").asInt());

        // 非负责人（含复核人）不能看名额明细
        assertEquals(403, exchange("/api/sites/activation-requests/" + v1,
                HttpMethod.GET, headers("rev-5", "REVIEWER", null), null)
                .getStatusCode().value());
    }

    @Test
    void historyVersionsAndSnapshotsRemainStable_afterNewVersionActivated() throws Exception {
        createSite("G", "lead-5", "g-create");
        String v1 = activateVersion("G", "lead-5", "rev-6", "g-v1",
                "[{\"blockNo\":1,\"quota\":2}]");
        assertEquals(201, exchange("/api/sites/G/participants/H1/allocations", HttpMethod.POST,
                headers("lead-5", "COORDINATOR", "g-a1"), null).getStatusCode().value());
        assertEquals(201, exchange("/api/sites/G/participants/H2/allocations", HttpMethod.POST,
                headers("lead-5", "COORDINATOR", "g-a2"), null).getStatusCode().value());
        // 扩容到8并启用版本2
        activateVersion("G", "lead-5", "rev-7", "g-v2",
                "[{\"blockNo\":1,\"quota\":8}]");

        // 历史版本1 的确认记录、名额、快照与用量保持稳定
        JsonNode oldView = json(exchange("/api/sites/activation-requests/" + v1,
                HttpMethod.GET, headers("lead-5", "COORDINATOR", null), null));
        assertEquals(1, oldView.path("version").asInt());
        assertEquals("ACTIVATED", oldView.path("status").asText());
        assertEquals(2, oldView.path("blocks").get(0).path("frozen").asInt());
        assertEquals(2, oldView.path("blocks").get(0).path("used").asInt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_block_quota WHERE site_id = 'G' AND version = 1",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_block_snapshot WHERE site_id = 'G' AND version = 1",
                Integer.class));
        // 分配方读到的是新版本剩余：8 - 0(版本2用量) = 8
        JsonNode remaining = json(exchange("/api/sites/G/remaining-quota",
                HttpMethod.GET, headers("staff-2", "COORDINATOR", null), null));
        assertEquals(8, remaining.path("blocks").get(0).path("remaining").asInt());
    }

    @Test
    void submitConfig_replaysFirstSnapshot_sameKeyDifferentConfigConflicts() throws Exception {
        createSite("H", "lead-6", "h-create");
        String body1 = "{\"blocks\":[{\"blockNo\":1,\"quota\":4}]}";
        ResponseEntity<String> first = exchange(
                "/api/sites/H/activation-requests", HttpMethod.POST,
                headers("lead-6", "COORDINATOR", "h-same-key"), body1);
        assertEquals(201, first.getStatusCode().value());
        // 同 requestId 同参重放：首次区组快照原样返回
        ResponseEntity<String> replay = exchange(
                "/api/sites/H/activation-requests", HttpMethod.POST,
                headers("lead-6", "COORDINATOR", "h-same-key"), body1);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_request WHERE site_id = 'H'",
                Integer.class));

        // 同 requestId 换配置：409，不产生新版本
        ResponseEntity<String> different = exchange(
                "/api/sites/H/activation-requests", HttpMethod.POST,
                headers("lead-6", "COORDINATOR", "h-same-key"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":9}]}");
        assertEquals(409, different.getStatusCode().value());
        assertEquals(4, jdbc.queryForObject(
                "SELECT quota FROM site_block_quota WHERE site_id = 'H' AND block_no = 1",
                Integer.class));

        // 失败不占键：非法区组序列 400，换新参数后同键可成功
        ResponseEntity<String> invalid = exchange(
                "/api/sites/H/activation-requests", HttpMethod.POST,
                headers("lead-6", "COORDINATOR", "h-failed-key"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":1},{\"blockNo\":3,\"quota\":1}]}");
        assertEquals(400, invalid.getStatusCode().value());
        // 待确认版本存在，合法请求仍会因“存在待确认版本”409；先完成双人确认
        activatePendingOnly("H", "lead-6", "rev-8");
        // 激活上一版本后，失败键仍未被占用，可用于版本2
        ResponseEntity<String> retry = exchange(
                "/api/sites/H/activation-requests", HttpMethod.POST,
                headers("lead-6", "COORDINATOR", "h-failed-key"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":6}]}");
        assertEquals(201, retry.getStatusCode().value());
        assertEquals(2, json(retry).path("version").asInt());
    }

    /** 仅用于把站点 H 的唯一待确认版本激活：查出 requestId 后双人确认。 */
    private void activatePendingOnly(String siteId, String lead, String reviewer) {
        String rid = jdbc.queryForObject(
                "SELECT id FROM site_activation_request WHERE site_id = ? AND status = 'PENDING'",
                String.class, siteId);
        assertNotNull(rid);
        assertEquals(200, exchange(
                "/api/sites/activation-requests/" + rid + "/lead-confirmation",
                HttpMethod.POST, headers(lead, "COORDINATOR", "h-pending-lead"), null)
                .getStatusCode().value());
        assertEquals(200, exchange(
                "/api/sites/activation-requests/" + rid + "/reviewer-confirmation",
                HttpMethod.POST, headers(reviewer, "REVIEWER", "h-pending-reviewer"), null)
                .getStatusCode().value());
    }

    @Test
    void allocationWrites_areIdempotent_andAuthEnforced() throws Exception {
        createSite("I", "lead-7", "i-create");
        activateVersion("I", "lead-7", "rev-10", "i-v1",
                "[{\"blockNo\":1,\"quota\":3}]");

        // 同键重放原 201，不产生第二条分配
        ResponseEntity<String> a1 = exchange(
                "/api/sites/I/participants/K1/allocations", HttpMethod.POST,
                headers("lead-7", "COORDINATOR", "i-alloc-key"), null);
        assertEquals(201, a1.getStatusCode().value());
        ResponseEntity<String> a1Replay = exchange(
                "/api/sites/I/participants/K1/allocations", HttpMethod.POST,
                headers("lead-7", "COORDINATOR", "i-alloc-key"), null);
        assertEquals(201, a1Replay.getStatusCode().value());
        assertEquals(a1.getBody(), a1Replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'I'", Integer.class));

        // 鉴权边界
        assertEquals(403, exchange("/api/sites/I2", HttpMethod.POST,
                headers("rev-10", "REVIEWER", "i-create-by-reviewer"),
                "{\"leadActorId\":\"x\"}").getStatusCode().value());
        assertEquals(403, exchange(
                "/api/sites/I/participants/K2/allocations", HttpMethod.POST,
                headers("rev-10", "REVIEWER", "i-alloc-by-reviewer"), null)
                .getStatusCode().value());
        // 非负责人不能提交配置
        assertEquals(403, exchange(
                "/api/sites/I/activation-requests", HttpMethod.POST,
                headers("other-lead", "COORDINATOR", "i-config-by-other"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":9}]}").getStatusCode().value());
        // COORDINATOR 不能做复核确认
        String otherVersion = submitConfig("I", "lead-7", "i-v2-submit",
                "[{\"blockNo\":1,\"quota\":9}]");
        assertEquals(403, exchange(
                "/api/sites/activation-requests/" + otherVersion + "/reviewer-confirmation",
                HttpMethod.POST, headers("lead-7", "COORDINATOR", "i-reviewer-by-coord"), null)
                .getStatusCode().value());

        // 不存在资源
        assertEquals(404, exchange("/api/sites/NOPE", HttpMethod.GET,
                headers("lead-7", "COORDINATOR", null), null).getStatusCode().value());
        assertEquals(404, exchange(
                "/api/sites/NOPE/participants/X/allocations", HttpMethod.POST,
                headers("lead-7", "COORDINATOR", "i-missing-site"), null)
                .getStatusCode().value());
        assertEquals(404, exchange("/api/sites/NOPE/remaining-quota", HttpMethod.GET,
                headers("lead-7", "COORDINATOR", null), null).getStatusCode().value());

        // 未启用中心查询剩余额度：409
        createSite("J", "lead-8", "j-create");
        assertEquals(409, exchange("/api/sites/J/remaining-quota", HttpMethod.GET,
                headers("staff-3", "COORDINATOR", null), null).getStatusCode().value());
    }

    @Test
    void invalidConfig_isBadRequest_andPendingVersionBlocksNewSubmission() {
        createSite("K", "lead-9", "k-create");
        // 区组不连续
        assertEquals(400, exchange(
                "/api/sites/K/activation-requests", HttpMethod.POST,
                headers("lead-9", "COORDINATOR", "k-bad-gap"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":1},{\"blockNo\":2,\"quota\":0},"
                        + "{\"blockNo\":4,\"quota\":1}]}").getStatusCode().value());
        // 负名额
        assertEquals(400, exchange(
                "/api/sites/K/activation-requests", HttpMethod.POST,
                headers("lead-9", "COORDINATOR", "k-bad-negative"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":-1}]}").getStatusCode().value());
        // 空列表
        assertEquals(400, exchange(
                "/api/sites/K/activation-requests", HttpMethod.POST,
                headers("lead-9", "COORDINATOR", "k-bad-empty"),
                "{\"blocks\":[]}").getStatusCode().value());

        // 正常提交后存在待确认版本：再次提交 409
        assertEquals(201, exchange(
                "/api/sites/K/activation-requests", HttpMethod.POST,
                headers("lead-9", "COORDINATOR", "k-v1"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":1}]}").getStatusCode().value());
        ResponseEntity<String> secondPending = exchange(
                "/api/sites/K/activation-requests", HttpMethod.POST,
                headers("lead-9", "COORDINATOR", "k-v1-again-pending"),
                "{\"blocks\":[{\"blockNo\":1,\"quota\":2}]}");
        assertEquals(409, secondPending.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_request WHERE site_id = 'K'",
                Integer.class));
    }

    @Test
    void siteAndAllocationViews_neverExposeBlinding_andDbHasNoTreatmentColumn() throws Exception {
        createSite("L", "lead-0", "l-create");
        activateVersion("L", "lead-0", "rev-11", "l-v1",
                "[{\"blockNo\":1,\"quota\":2},{\"blockNo\":2,\"quota\":1}]");
        for (int i = 1; i <= 3; i++) {
            JsonNode alloc = json(exchange(
                    "/api/sites/L/participants/T" + i + "/allocations", HttpMethod.POST,
                    headers("lead-0", "COORDINATOR", "l-alloc-" + i), null));
            assertNoBlindingLeak(alloc);
        }
        JsonNode site = json(exchange("/api/sites/L", HttpMethod.GET,
                headers("rev-11", "REVIEWER", null), null));
        assertEquals("ACTIVE", site.path("status").asText());
        assertFalse(site.has("treatment"));
        assertFalse(site.has("seatNo"));

        // 库表层保证：site_allocation 不存在 treatment/seat_no 列
        List<String> columns = new ArrayList<>(jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'SITE_ALLOCATION'", String.class));
        assertFalse(columns.contains("treatment") || columns.contains("TREATMENT"));
        assertFalse(columns.contains("seat_no") || columns.contains("SEAT_NO"));
    }
}
