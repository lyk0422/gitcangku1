package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 盐负荷账本 API 测试：题干主场景（上限 20 克、灌 4 累计 8 预留 12、取消只释放 12、
 * 再申请 7 同浓度超限 422）、绑定校验、实灌门禁与跨窗口累积。全部走真实 H2（MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SaltLedgerApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    private MvcResult postJson(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        return objectMapper.readTree(
                mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString());
    }

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-salt-" + run + "-" + seq);
        body.put("startUtc", "2026-10-20T00:00:00Z");
        body.put("endUtc", "2026-10-20T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private long createField(String farmerId, String saltCapGrams) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("fc"));
        body.put("fieldKey", key("fk"));
        body.put("farmerId", farmerId);
        body.put("saltCapGrams", saltCapGrams);
        return postOk("/api/fields", body, "operator").get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, Long fieldId,
                          String concentration, String actor, int expectedStatus) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (fieldId != null) {
            body.put("fieldId", fieldId);
        }
        if (concentration != null) {
            body.put("concentration", concentration);
        }
        postJson("/api/allocations", body, actor, expectedStatus);
        return allocationKey;
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private JsonNode irrigate(String allocationKey, String amount, String actor,
                              int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ir"));
        body.put("amount", amount);
        MvcResult result = postJson("/api/allocations/" + allocationKey + "/irrigate", body, actor,
                expectedStatus);
        if (expectedStatus != 200) {
            return null;
        }
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode fieldSalt(long fieldId) throws Exception {
        return getOk("/api/fields/" + fieldId + "/salt");
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        for (JsonNode node : getOk("/api/windows/" + windowId + "/history").get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 题干主场景
    // ------------------------------------------------------------------

    @Test
    void specScenarioCap20Irrigate4CancelThenOverLimit() throws Exception {
        long fieldId = createField("user-1", "20");
        long windowId = createWindow("100");
        // 10 立方米浓度 2 批准后预留 20 克
        String a1 = submit(windowId, "user-1", "10", fieldId, "2", "alice", 200);
        approve(a1, 200);
        JsonNode salt = fieldSalt(fieldId);
        assertEquals("0", salt.get("irrigatedSalt").asText());
        assertEquals("20", salt.get("reservedSalt").asText());
        // 灌 4：累计实灌 8 克、预留 12 克
        JsonNode irrigation = irrigate(a1, "4", "alice", 200);
        assertEquals("8", irrigation.get("saltGrams").asText());
        assertEquals("8", irrigation.get("cumulativeSaltGrams").asText());
        assertEquals("4", irrigation.get("irrigatedAmount").asText());
        salt = fieldSalt(fieldId);
        assertEquals("8", salt.get("irrigatedSalt").asText());
        assertEquals("12", salt.get("reservedSalt").asText());
        assertEquals("0", salt.get("remainingSalt").asText());
        // 取消只释放未灌的 12 克与 6 立方米；已灌 8 克与 4 立方米保留
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode cancelled = allocation(windowId, a1);
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("4", cancelled.get("heldAmount").asText());
        assertEquals("4", cancelled.get("irrigatedAmount").asText());
        salt = fieldSalt(fieldId);
        assertEquals("8", salt.get("irrigatedSalt").asText());
        assertEquals("0", salt.get("reservedSalt").asText());
        assertEquals("12", salt.get("remainingSalt").asText());
        assertEquals(1, salt.get("entries").size());
        // 已灌水额度不释放回窗口
        assertEquals("4", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        // 随后 7 立方米同浓度申请：8 + 14 = 22 > 20 -> 422
        String a2 = submit(windowId, "user-1", "7", fieldId, "2", "alice", 200);
        approve(a2, 422);
        // 6 立方米同浓度：8 + 12 = 20 -> 恰好允许
        String a3 = submit(windowId, "user-1", "6", fieldId, "2", "alice", 200);
        approve(a3, 200);
        assertEquals("12", fieldSalt(fieldId).get("reservedSalt").asText());
    }

    // ------------------------------------------------------------------
    // 田块创建与绑定校验
    // ------------------------------------------------------------------

    @Test
    void fieldCreationValidationAndReplay() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("fc"));
        body.put("fieldKey", key("fk"));
        body.put("farmerId", "user-1");
        // 非正整数上限 -> 400
        body.put("saltCapGrams", "0");
        postJson("/api/fields", body, "operator", 400);
        body.put("saltCapGrams", "-5");
        postJson("/api/fields", body, "operator", 400);
        body.put("saltCapGrams", "2.5");
        postJson("/api/fields", body, "operator", 400);
        body.put("saltCapGrams", "abc");
        postJson("/api/fields", body, "operator", 400);
        // 缺少操作人 -> 400
        body.put("saltCapGrams", "20");
        postJson("/api/fields", body, null, 400);
        // 成功创建并重放同键同参
        String commandKey = key("fc");
        body.put("commandKey", commandKey);
        body.put("fieldKey", key("fk"));
        JsonNode first = postOk("/api/fields", body, "operator");
        assertEquals("20", first.get("saltCapGrams").asText());
        JsonNode replay = postOk("/api/fields", body, "operator");
        assertEquals(first, replay);
        // 同键改上限 -> 409；同键改期望版本 -> 409
        Map<String, Object> changed = new HashMap<>(body);
        changed.put("saltCapGrams", "21");
        postJson("/api/fields", changed, "operator", 409);
        changed = new HashMap<>(body);
        changed.put("expectedVersion", 1);
        postJson("/api/fields", changed, "operator", 409);
        // 换 commandKey 复用 fieldKey -> 409
        Map<String, Object> reused = new HashMap<>(body);
        reused.put("commandKey", key("fc"));
        postJson("/api/fields", reused, "operator", 409);
    }

    @Test
    void bindingValidationRejectsBadOwnershipAndConcentration() throws Exception {
        long fieldId = createField("user-1", "20");
        long windowId = createWindow("100");
        // 田块不存在 -> 404
        submit(windowId, "user-1", "1", 999999999L, "2", "alice", 404);
        // 田块属于其他农户 -> 403
        submit(windowId, "user-2", "1", fieldId, "2", "bob", 403);
        // 浓度非法 -> 400
        submit(windowId, "user-1", "1", fieldId, "-1", "alice", 400);
        submit(windowId, "user-1", "1", fieldId, "1.0001", "alice", 400);
        // fieldId 与 concentration 必须同时提供 -> 400
        submit(windowId, "user-1", "1", fieldId, null, "alice", 400);
        submit(windowId, "user-1", "1", null, "2", "alice", 400);
        // 旧未绑定申请仍按原流程
        String legacy = submit(windowId, "user-9", "3", null, null, "zoe", 200);
        approve(legacy, 200);
        JsonNode node = allocation(windowId, legacy);
        assertTrue(node.get("fieldId").isNull());
        assertTrue(node.get("concentration").isNull());
        assertEquals("0", node.get("irrigatedAmount").asText());
        assertEquals("3", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 实灌门禁与精度
    // ------------------------------------------------------------------

    @Test
    void irrigateGuardsAndExactDecimalProduct() throws Exception {
        long fieldId = createField("user-1", "100");
        long windowId = createWindow("100");
        String requested = submit(windowId, "user-1", "2", fieldId, "1.001", "alice", 200);
        // 未批准 -> 409；非申请人 -> 403；非法水量 -> 400
        irrigate(requested, "1", "alice", 409);
        approve(requested, 200);
        irrigate(requested, "1", "bob", 403);
        irrigate(requested, "0", "alice", 400);
        irrigate(requested, "-1", "alice", 400);
        irrigate(requested, "1.0001", "alice", 400);
        // 精确乘积不先舍入：0.333 立方米 × 1.001 克/立方米 = 0.333333 克
        JsonNode irrigation = irrigate(requested, "0.333", "alice", 200);
        assertEquals("0.333333", irrigation.get("saltGrams").asText());
        assertEquals("0.333333", irrigation.get("cumulativeSaltGrams").asText());
        // 超过未灌量 -> 422（剩余 1.667）
        irrigate(requested, "1.668", "alice", 422);
        irrigate(requested, "1.667", "alice", 200);
        // 全部灌完后再灌 -> 422
        irrigate(requested, "0.001", "alice", 422);
        JsonNode salt = fieldSalt(fieldId);
        assertEquals("2.002", salt.get("irrigatedSalt").asText());
        assertEquals("0", salt.get("reservedSalt").asText());
        assertEquals(2, salt.get("entries").size());
        // 已取消申请不能实灌
        String a2 = submit(windowId, "user-1", "1", fieldId, "1", "alice", 200);
        postOk("/api/allocations/" + a2 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        irrigate(a2, "1", "alice", 409);
    }

    @Test
    void saltBudgetAccumulatesAcrossWindows() throws Exception {
        long fieldId = createField("user-1", "10");
        long w1 = createWindow("100");
        long w2 = createWindow("100");
        String a1 = submit(w1, "user-1", "3", fieldId, "2", "alice", 200);
        approve(a1, 200);
        // 同一田块跨窗口累积：6 + 6 = 12 > 10 -> 422
        String a2 = submit(w2, "user-1", "3", fieldId, "2", "alice", 200);
        approve(a2, 422);
        // 6 + 4 = 10 -> 恰好允许
        String a3 = submit(w2, "user-1", "2", fieldId, "2", "alice", 200);
        approve(a3, 200);
        JsonNode salt = fieldSalt(fieldId);
        assertEquals("10", salt.get("reservedSalt").asText());
        assertEquals("0", salt.get("remainingSalt").asText());
    }
}
