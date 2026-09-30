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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 研究中心启用门主流程与拒绝分支（真实 H2）：
 * 双人确认同一版本后启用；未启用禁分配；额度耗尽拒绝且不泄露处理组；
 * 版本不符 409 且状态不变；申请幂等重放首次快照、换配置 409；
 * 缩减名额低于冻结量或已分配量 409；角色边界与历史确认稳定。
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

    private void createExperiment(String expId, String reqId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", reqId),
                "{\"blockCount\":2}").getStatusCode().value());
    }

    /** 以 LEAD 提交一个只含单区组 A 的配置版本。 */
    private int submitSingleBlockVersion(String expId, String siteId, int quota, String reqId)
            throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/sites/" + siteId + "/config-versions",
                HttpMethod.POST, headers("lead-1", "LEAD", reqId),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":" + quota + "}]}");
        assertEquals(201, resp.getStatusCode().value());
        return json(resp).path("version").asInt();
    }

    /** LEAD 申请启用指定版本，返回启用申请编号。 */
    private String applyActivation(String expId, String siteId, int version,
                                   String actor, String role, String reqId) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/sites/" + siteId + "/activations",
                HttpMethod.POST, headers(actor, role, reqId),
                "{\"configVersion\":" + version + "}");
        assertEquals(201, resp.getStatusCode().value());
        try {
            return json(resp).path("activationId").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<String> siteAllocate(String expId, String siteId, String block,
                                                String participant, String reqId) {
        return exchange("/api/experiments/" + expId + "/sites/" + siteId
                        + "/blocks/" + block + "/participants/" + participant + "/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", reqId), null);
    }

    @Test
    void version3ConfirmedByLeadAndConfirmer_tenAllocationsSucceed_eleventhRejectedWithoutTreatment()
            throws Exception {
        String expId = "SITE-MAIN";
        createExperiment(expId, "sm-exp");

        // 启用前提交三个草稿版本：7、8、10；草稿不冻结任何名额
        assertEquals(1, submitSingleBlockVersion(expId, "C", 7, "sm-cfg-1"));
        assertEquals(2, submitSingleBlockVersion(expId, "C", 8, "sm-cfg-2"));
        assertEquals(3, submitSingleBlockVersion(expId, "C", 10, "sm-cfg-3"));

        // 未启用：负责人视角 INACTIVE、无启用名额；分配方 409，且查不到剩余额度
        ResponseEntity<String> inactiveSite = exchange(
                "/api/experiments/" + expId + "/sites/C", HttpMethod.GET,
                headers("lead-1", "LEAD", null), null);
        assertEquals(200, inactiveSite.getStatusCode().value());
        JsonNode siteBody = json(inactiveSite);
        assertEquals("INACTIVE", siteBody.path("status").asText());
        assertTrue(siteBody.path("activeVersion").isNull());
        assertEquals(0, siteBody.path("blocks").size());

        assertEquals(409, siteAllocate(expId, "C", "A", "P0", "sm-alloc-before").getStatusCode().value());
        assertEquals(409, exchange(
                "/api/experiments/" + expId + "/sites/C/remaining-quota", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());

        // 负责人对版本 3 提交启用申请（LEAD 确认），冻结区组 A=10 的快照
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/sites/C/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "sm-apply"), "{\"configVersion\":3}");
        assertEquals(201, apply.getStatusCode().value());
        JsonNode applyBody = json(apply);
        String activationId = applyBody.path("activationId").asText();
        assertEquals("PENDING_CONFIRMATION", applyBody.path("status").asText());
        assertEquals(3, applyBody.path("configVersion").asInt());
        assertEquals("A", applyBody.path("blocks").get(0).path("blockCode").asText());
        assertEquals(10, applyBody.path("blocks").get(0).path("quota").asInt());
        assertFalse(applyBody.has("treatment"));
        assertFalse(applyBody.has("seatNo"));
        // 待复核期间仍不得分配
        assertEquals(409, siteAllocate(expId, "C", "A", "P0", "sm-alloc-pending").getStatusCode().value());

        // 另一名 CONFIRMER 核对版本 3 后确认：一次性启用
        clock.advance(10_000L);
        ResponseEntity<String> confirm = exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sm-confirm"),
                "{\"configVersion\":3}");
        assertEquals(200, confirm.getStatusCode().value());
        JsonNode confirmBody = json(confirm);
        assertEquals("ACTIVE", confirmBody.path("status").asText());
        assertEquals("confirmer-2", confirmBody.path("reviewerActor").asText());
        assertEquals(1_700_000_010_000L, confirmBody.path("confirmedAt").asLong());
        assertFalse(confirmBody.has("treatment"));
        assertFalse(confirmBody.has("seatNo"));

        // 启用快照：负责人可查版本与名额；分配方只见剩余额度
        JsonNode activeSite = json(exchange(
                "/api/experiments/" + expId + "/sites/C", HttpMethod.GET,
                headers("lead-1", "LEAD", null), null));
        assertEquals("ACTIVE", activeSite.path("status").asText());
        assertEquals(3, activeSite.path("activeVersion").asInt());
        assertEquals(10, activeSite.path("blocks").get(0).path("quota").asInt());

        JsonNode remaining = json(exchange(
                "/api/experiments/" + expId + "/sites/C/remaining-quota", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals(10, remaining.path("blocks").get(0).path("remaining").asInt());
        assertFalse(remaining.has("quota"));
        assertFalse(remaining.has("activeVersion"));
        assertFalse(remaining.has("frozenQuota"));

        // 连续 10 次分配全部成功，顺序号 1..10；第 11 次被拒（额度不足）
        for (int i = 1; i <= 10; i++) {
            ResponseEntity<String> ok = siteAllocate(expId, "C", "A", "P" + i, "sm-alloc-" + i);
            assertEquals(201, ok.getStatusCode().value(), "第 " + i + " 次分配应成功");
            JsonNode alloc = json(ok);
            assertEquals(i, alloc.path("serialNo").asInt());
            assertEquals("A", alloc.path("blockCode").asText());
            assertEquals("P" + i, alloc.path("participantId").asText());
            assertFalse(alloc.has("treatment"), "分配响应不得包含处理代码");
            assertFalse(alloc.has("seatNo"), "分配响应不得包含席位号");
        }
        ResponseEntity<String> eleventh = siteAllocate(expId, "C", "A", "P11", "sm-alloc-11");
        assertEquals(422, eleventh.getStatusCode().value());
        JsonNode errorBody = json(eleventh);
        assertFalse(errorBody.toString().contains("treatment"));
        assertFalse(errorBody.toString().contains("A") && errorBody.toString().contains("B"),
                "拒绝响应不得泄露处理组");

        // 剩余额度归零
        JsonNode remainingZero = json(exchange(
                "/api/experiments/" + expId + "/sites/C/remaining-quota", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals(0, remainingZero.path("blocks").get(0).path("remaining").asInt());

        // 最终数据：10 条中心分配、顺序号不重复；双人确认历史各一条且不可变
        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE experiment_id = ? AND site_id = 'C'",
                Integer.class, expId));
        assertEquals(10, jdbc.queryForObject(
                "SELECT MAX(serial_no) FROM site_allocation WHERE experiment_id = ? "
                        + "AND site_id = 'C' AND block_code = 'A'", Integer.class, expId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?",
                Integer.class, activationId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT actor_id) FROM site_activation_confirmation "
                        + "WHERE activation_id = ? AND config_version = 3",
                Integer.class, activationId));
        // 启用快照冻结 10，未被分配消耗改写
        assertEquals(10, jdbc.queryForObject(
                "SELECT frozen_quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId));
    }

    @Test
    void confirmWrongVersion_conflicts_andLeavesEveryStateUntouched_thenCorrectVersionActivates()
            throws Exception {
        String expId = "SITE-VER";
        createExperiment(expId, "sv-exp");
        submitSingleBlockVersion(expId, "C", 10, "sv-cfg-1");
        submitSingleBlockVersion(expId, "C", 12, "sv-cfg-2");
        String activationId = applyActivation(expId, "C", 2, "lead-1", "LEAD", "sv-apply");

        // 复核人确认了错误的版本 1：409，不写复核记录，中心与申请保持原状
        ResponseEntity<String> wrong = exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sv-confirm-wrong"),
                "{\"configVersion\":1}");
        assertEquals(409, wrong.getStatusCode().value());
        assertEquals("PENDING_CONFIRMATION", jdbc.queryForObject(
                "SELECT status FROM site_activation WHERE activation_id = ?",
                String.class, activationId));
        assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE experiment_id = ? AND site_id = 'C'",
                String.class, expId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?",
                Integer.class, activationId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_block WHERE experiment_id = ? AND site_id = 'C'",
                Integer.class, expId));
        // 版本不符后仍不得分配
        assertEquals(409, siteAllocate(expId, "C", "A", "P1", "sv-alloc-still-inactive")
                .getStatusCode().value());

        // 同一复核人改确认正确版本 2：启用，冻结 12
        ResponseEntity<String> right = exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sv-confirm-right"),
                "{\"configVersion\":2}");
        assertEquals(200, right.getStatusCode().value());
        assertEquals("ACTIVE", json(right).path("status").asText());
        assertEquals(12, jdbc.queryForObject(
                "SELECT frozen_quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId));
    }

    @Test
    void confirmerMustBeDifferentPerson_andRolesAreEnforced() {
        String expId = "SITE-AUTH";
        createExperiment(expId, "sa-exp");
        // 只有 LEAD 能提交配置版本
        assertEquals(403, exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "sa-cfg-by-coord"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":10}]}").getStatusCode().value());
        assertEquals(403, exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("rev-9", "REVIEWER", "sa-cfg-by-reviewer"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":10}]}").getStatusCode().value());

        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "sa-cfg"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":10}]}").getStatusCode().value());
        // LEAD 才能申请启用
        assertEquals(403, exchange(
                "/api/experiments/" + expId + "/sites/C/activations", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sa-apply-by-confirmer"),
                "{\"configVersion\":1}").getStatusCode().value());
        String activationId = applyActivation(expId, "C", 1, "lead-1", "LEAD", "sa-apply");

        // CONFIRMER 才能复核；申请人本人换 CONFIRMER 角色也禁止（两人必须独立）
        assertEquals(403, exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "sa-confirm-by-coord"),
                "{\"configVersion\":1}").getStatusCode().value());
        assertEquals(403, exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("lead-1", "CONFIRMER", "sa-confirm-self"),
                "{\"configVersion\":1}").getStatusCode().value());

        // 分配方视角：COORDINATOR 才能查剩余额度与分配；LEAD/CONFIRMER 不暴露该视角
        assertEquals(200, exchange(
                "/api/activations/" + activationId, HttpMethod.GET,
                headers("confirmer-2", "CONFIRMER", null), null).getStatusCode().value());
        assertEquals(403, exchange(
                "/api/experiments/" + expId + "/sites/C/remaining-quota", HttpMethod.GET,
                headers("lead-1", "LEAD", null), null).getStatusCode().value());
        assertEquals(403, exchange(
                "/api/experiments/" + expId + "/sites/C", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null).getStatusCode().value());

        // 正常复核启用后，LEAD 不能替代 COORDINATOR 分配
        assertEquals(200, exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sa-confirm"),
                "{\"configVersion\":1}").getStatusCode().value());
        assertEquals(403, exchange(
                "/api/experiments/" + expId + "/sites/C/blocks/A/participants/P1/allocations",
                HttpMethod.POST, headers("lead-1", "LEAD", "sa-alloc-by-lead"), null)
                .getStatusCode().value());
    }

    @Test
    void activationRequestId_replaysFirstSnapshot_changedConfigConflicts_failureDoesNotConsumeKey()
            throws Exception {
        String expId = "SITE-IDEM";
        createExperiment(expId, "si-exp");
        submitSingleBlockVersion(expId, "C", 5, "si-cfg-1");
        submitSingleBlockVersion(expId, "C", 8, "si-cfg-2");

        // 首次以版本 1 申请，冻结快照 A=5
        ResponseEntity<String> first = exchange(
                "/api/experiments/" + expId + "/sites/C/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "si-apply-key"), "{\"configVersion\":1}");
        assertEquals(201, first.getStatusCode().value());
        String firstBody = first.getBody();
        assertEquals(5, json(first).path("blocks").get(0).path("quota").asInt());

        // 同键同参重放：原样返回首次快照，不产生第二条申请
        ResponseEntity<String> replay = exchange(
                "/api/experiments/" + expId + "/sites/C/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "si-apply-key"), "{\"configVersion\":1}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(firstBody, replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation WHERE experiment_id = ? AND site_id = 'C'",
                Integer.class, expId));
        assertEquals(5, jdbc.queryForObject(
                "SELECT quota FROM site_activation_block WHERE block_code = 'A'",
                Integer.class));

        // 同键换配置（版本 2）：409，快照仍是首次的 5
        ResponseEntity<String> changed = exchange(
                "/api/experiments/" + expId + "/sites/C/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "si-apply-key"), "{\"configVersion\":2}");
        assertEquals(409, changed.getStatusCode().value());
        assertEquals(5, jdbc.queryForObject(
                "SELECT quota FROM site_activation_block WHERE block_code = 'A'",
                Integer.class));

        // 失败不占键：另一新键先打不存在的版本失败，再用同一键申请版本 2 成功（另一中心）
        ResponseEntity<String> missing = exchange(
                "/api/experiments/" + expId + "/sites/D/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "si-apply-new-key"), "{\"configVersion\":9}");
        assertEquals(404, missing.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'si-apply-new-key'",
                Integer.class));
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/sites/D/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "si-d-cfg"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":3}]}").getStatusCode().value());
        ResponseEntity<String> retry = exchange(
                "/api/experiments/" + expId + "/sites/D/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "si-apply-new-key"), "{\"configVersion\":1}");
        assertEquals(201, retry.getStatusCode().value());
    }

    @Test
    void quotaShrinkBelowFrozenOrAllocated_conflicts_withoutAnyStateChange() throws Exception {
        String expId = "SITE-QUOTA";
        createExperiment(expId, "sq-exp");
        // 版本 1：冻结 10
        submitSingleBlockVersion(expId, "C", 10, "sq-cfg-1");
        String activationId = applyActivation(expId, "C", 1, "lead-1", "LEAD", "sq-apply");
        assertEquals(200, exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sq-confirm"),
                "{\"configVersion\":1}").getStatusCode().value());

        // 启用后缩减到 9（低于冻结量 10）：409，当前名额仍是 10，且不产生新版本
        ResponseEntity<String> belowFrozen = exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "sq-cfg-bad"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":9}]}");
        assertEquals(409, belowFrozen.getStatusCode().value());
        assertEquals(10, jdbc.queryForObject(
                "SELECT quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT MAX(version) FROM site_config_version WHERE experiment_id = ? "
                        + "AND site_id = 'C'", Integer.class, expId));

        // 合规上调到 12（不低于冻结量）：成功
        ResponseEntity<String> raised = exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "sq-cfg-2"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":12}]}");
        assertEquals(201, raised.getStatusCode().value());
        assertEquals(2, json(raised).path("version").asInt());
        assertEquals(12, jdbc.queryForObject(
                "SELECT quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId));
        assertEquals(10, jdbc.queryForObject(
                "SELECT frozen_quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId), "冻结量不得被改写");

        // 分配 11 人次后，回调到 10：不低于冻结量但低于已分配量 11 -> 409，名额仍 12
        for (int i = 1; i <= 11; i++) {
            assertEquals(201, siteAllocate(expId, "C", "A", "Q" + i, "sq-alloc-" + i)
                    .getStatusCode().value());
        }
        ResponseEntity<String> belowAllocated = exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "sq-cfg-bad-3"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":10}]}");
        assertEquals(409, belowAllocated.getStatusCode().value());
        assertEquals(12, jdbc.queryForObject(
                "SELECT quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT MAX(version) FROM site_config_version WHERE experiment_id = ? "
                        + "AND site_id = 'C'", Integer.class, expId));

        // 回调到 11（恰好等于已分配量且不低于冻结量）：成功，剩余 0，再分配被拒
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "sq-cfg-3"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":11}]}").getStatusCode().value());
        assertEquals(11, jdbc.queryForObject(
                "SELECT quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId));
        assertEquals(422, siteAllocate(expId, "C", "A", "Q12", "sq-alloc-12")
                .getStatusCode().value());

        // 启用后增删区组：400，状态不变
        assertEquals(400, exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "sq-cfg-add-block"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":11},{\"blockCode\":\"B\",\"quota\":1}]}")
                .getStatusCode().value());
    }

    @Test
    void confirmRequestId_replaysOriginalResult_andDuplicateConfirmConflicts() throws Exception {
        String expId = "SITE-CIDEM";
        createExperiment(expId, "sc-exp");
        submitSingleBlockVersion(expId, "C", 3, "sc-cfg");
        String activationId = applyActivation(expId, "C", 1, "lead-1", "LEAD", "sc-apply");

        ResponseEntity<String> first = exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sc-confirm-key"),
                "{\"configVersion\":1}");
        assertEquals(200, first.getStatusCode().value());
        // 同键同参重放：原 ACTIVE 结果，不重复改写
        ResponseEntity<String> replay = exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sc-confirm-key"),
                "{\"configVersion\":1}");
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());

        // 其他复核人对已启用申请再确认：409；REVIEWER 确认历史仍只有一条
        assertEquals(409, exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-3", "CONFIRMER", "sc-confirm-again"),
                "{\"configVersion\":1}").getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_confirmation "
                        + "WHERE activation_id = ? AND confirm_role = 'REVIEWER'",
                Integer.class, activationId));
    }

    @Test
    void missingResourcesAndInvalidBodies_areRejected_andDuplicateParticipantConflicts()
            throws Exception {
        // 实验不存在：404
        assertEquals(404, exchange(
                "/api/experiments/NOPE/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "miss-cfg"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":1}]}").getStatusCode().value());

        createExperiment("SITE-MISS", "smi-exp");
        // 中心不存在
        assertEquals(404, exchange("/api/experiments/SITE-MISS/sites/C", HttpMethod.GET,
                headers("lead-1", "LEAD", null), null).getStatusCode().value());
        // 配额非法：0 与重复区组 400
        assertEquals(400, exchange(
                "/api/experiments/SITE-MISS/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "miss-bad-quota"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":0}]}").getStatusCode().value());
        assertEquals(400, exchange(
                "/api/experiments/SITE-MISS/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "miss-dup-block"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":1},{\"blockCode\":\"A\",\"quota\":2}]}")
                .getStatusCode().value());
        // 空清单 400
        assertEquals(400, exchange(
                "/api/experiments/SITE-MISS/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "miss-empty"), "{\"blocks\":[]}")
                .getStatusCode().value());

        submitSingleBlockVersion("SITE-MISS", "C", 2, "miss-cfg-ok");
        // 不存在的配置版本：404
        assertEquals(404, exchange(
                "/api/experiments/SITE-MISS/sites/C/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "miss-apply-version"),
                "{\"configVersion\":5}").getStatusCode().value());
        // 启用申请缺版本字段：400
        assertEquals(400, exchange(
                "/api/experiments/SITE-MISS/sites/C/activations", HttpMethod.POST,
                headers("lead-1", "LEAD", "miss-apply-body"), "{}").getStatusCode().value());

        String activationId = applyActivation("SITE-MISS", "C", 1, "lead-1", "LEAD", "miss-apply");
        // 不存在的启用申请：404
        assertEquals(404, exchange("/api/activations/SA-MISSING/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "miss-confirm-404"),
                "{\"configVersion\":1}").getStatusCode().value());
        assertEquals(200, exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "miss-confirm"),
                "{\"configVersion\":1}").getStatusCode().value());

        // 快照外区组：404；同参与者重复占名额：409
        assertEquals(404, siteAllocate("SITE-MISS", "C", "Z", "P1", "miss-block")
                .getStatusCode().value());
        assertEquals(201, siteAllocate("SITE-MISS", "C", "A", "P1", "miss-alloc-1")
                .getStatusCode().value());
        assertEquals(409, siteAllocate("SITE-MISS", "C", "A", "P1", "miss-alloc-dup")
                .getStatusCode().value());
        // 第二条参与者在同中心不同区组同样被拒（参与者中心内唯一）
        // 注：本中心启用快照只有区组 A，跨区组场景在多区组中心验证。
    }

    @Test
    void multiBlockCenter_freezesEachBlockIndependently_andParticipantUniqueWithinSite()
            throws Exception {
        String expId = "SITE-MULTI";
        createExperiment(expId, "sm2-exp");
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/sites/C/config-versions", HttpMethod.POST,
                headers("lead-1", "LEAD", "sm2-cfg"),
                "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":2},{\"blockCode\":\"B\",\"quota\":1}]}")
                .getStatusCode().value());
        String activationId = applyActivation(expId, "C", 1, "lead-1", "LEAD", "sm2-apply");
        assertEquals(200, exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                headers("confirmer-2", "CONFIRMER", "sm2-confirm"),
                "{\"configVersion\":1}").getStatusCode().value());

        JsonNode remaining = json(exchange(
                "/api/experiments/" + expId + "/sites/C/remaining-quota", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals(2, remaining.path("blocks").size());
        assertEquals("A", remaining.path("blocks").get(0).path("blockCode").asText());
        assertEquals(2, remaining.path("blocks").get(0).path("remaining").asInt());
        assertEquals(1, remaining.path("blocks").get(1).path("remaining").asInt());
        // 只暴露剩余额度，不暴露总名额
        assertFalse(remaining.toString().contains("quota"));

        assertEquals(201, siteAllocate(expId, "C", "A", "P1", "sm2-a1").getStatusCode().value());
        // P1 已在中心 C 占名额，换区组 B 也拒绝（中心内唯一）
        assertEquals(409, siteAllocate(expId, "C", "B", "P1", "sm2-b1-dup").getStatusCode().value());
        assertEquals(201, siteAllocate(expId, "C", "B", "P2", "sm2-b1").getStatusCode().value());
        // B 仅 1 个名额：第二次 422
        assertEquals(422, siteAllocate(expId, "C", "B", "P3", "sm2-b2-full").getStatusCode().value());
        // A 还可再分一个
        assertEquals(201, siteAllocate(expId, "C", "A", "P4", "sm2-a2").getStatusCode().value());
        assertEquals(422, siteAllocate(expId, "C", "A", "P5", "sm2-a3-full").getStatusCode().value());

        JsonNode remainingAfter = json(exchange(
                "/api/experiments/" + expId + "/sites/C/remaining-quota", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals(0, remainingAfter.path("blocks").get(0).path("remaining").asInt());
        assertEquals(0, remainingAfter.path("blocks").get(1).path("remaining").asInt());
    }
}
