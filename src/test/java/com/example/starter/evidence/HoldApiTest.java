package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 保全冻结创建与批量解除 API 测试：主流程、集合规范化、重叠校验、
 * 过去区间拒绝、批量回滚、幂等重放与失败不占键（固定时钟驱动，真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HoldApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createHold(String actor, String holdKey, List<String> evidenceKeys,
                                 LocalDateTime from, LocalDateTime to, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holdKey", holdKey);
        body.put("caseKey", "CASE-9");
        body.put("evidenceKeys", evidenceKeys);
        body.put("effectiveFrom", from == null ? null : from.toString());
        body.put("effectiveTo", to == null ? null : to.toString());
        body.put("reason", reason);
        return mockMvc.perform(post("/api/evidence/holds")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult release(String actor, String commandKey, Object[][] items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        List<Map<String, Object>> releases = new java.util.ArrayList<>();
        for (Object[] item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("holdKey", item[0]);
            entry.put("version", item[1]);
            releases.add(entry);
        }
        body.put("releases", releases);
        return mockMvc.perform(post("/api/evidence/holds/release")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode effectiveHolds(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/{key}/holds/effective", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode holdHistory(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/holds", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createHoldNormalizesSetAndShowsEffective() throws Exception {
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String holdKey = uniqueKey("HOLD");

        // 证物集合乱序且含重复：响应中须为规范化（去重、字典序）排序
        MvcResult result = createHold("alice", holdKey, List.of(ev2, ev1, ev2),
                utc(BASE), utc(BASE.plusSeconds(3600)), "案件保全需要");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode hold = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(hold.get("holdKey").asText()).isEqualTo(holdKey);
        assertThat(hold.get("caseKey").asText()).isEqualTo("CASE-9");
        List<String> keys = objectMapper.convertValue(hold.get("evidenceKeys"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(keys).containsExactlyElementsOf(
                List.of(ev1, ev2).stream().sorted().toList());
        assertThat(hold.get("version").asInt()).isEqualTo(1);
        assertThat(hold.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(hold.get("effective").asBoolean()).isTrue();
        assertThat(hold.get("createdBy").asText()).isEqualTo("alice");
        assertThat(hold.get("releasedBy").isNull()).isTrue();

        // 有效冻结查询与历史快照均可见
        assertThat(effectiveHolds(ev1)).hasSize(1);
        assertThat(effectiveHolds(ev2)).hasSize(1);
        assertThat(holdHistory(ev1)).hasSize(1);
    }

    @Test
    void createHoldCoveringPastOrInvalidIntervalReturns400() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        // 生效起点早于当前时刻：不能补建覆盖过去的冻结
        MvcResult past = createHold("alice", uniqueKey("HOLD"), List.of(evidenceKey),
                utc(BASE.minusSeconds(60)), utc(BASE.plusSeconds(3600)), "补建");
        assertThat(past.getResponse().getStatus()).isEqualTo(400);
        assertThat(past.getResponse().getContentAsString()).contains("覆盖过去");

        // 终点不晚于起点
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(evidenceKey),
                utc(BASE), utc(BASE), "空区间").getResponse().getStatus()).isEqualTo(400);
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(evidenceKey),
                utc(BASE.plusSeconds(3600)), utc(BASE), "倒置区间")
                .getResponse().getStatus()).isEqualTo(400);

        assertThat(holdHistory(evidenceKey)).isEmpty();
    }

    @Test
    void overlappingActiveHoldOnSameEvidenceReturns409() throws Exception {
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        createHold("alice", uniqueKey("HOLD"), List.of(ev1),
                utc(BASE), utc(BASE.plusSeconds(3600)), "第一笔冻结");

        // 同一证物区间重叠（部分重叠、包含、被包含）均拒绝
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(ev1),
                utc(BASE.plusSeconds(1800)), utc(BASE.plusSeconds(5400)), "重叠")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(ev1),
                utc(BASE), utc(BASE.plusSeconds(3600)), "相同区间")
                .getResponse().getStatus()).isEqualTo(409);
        // 同一证物区间相接（左闭右开，不重叠）允许
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(ev1),
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)), "相接")
                .getResponse().getStatus()).isEqualTo(201);
        // 不同证物相同区间允许
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(ev2),
                utc(BASE), utc(BASE.plusSeconds(3600)), "其他证物")
                .getResponse().getStatus()).isEqualTo(201);

        assertThat(holdHistory(ev1)).hasSize(2);
        assertThat(holdHistory(ev2)).hasSize(1);
    }

    @Test
    void releasedHoldDoesNotBlockNewOverlappingHold() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String holdKey = uniqueKey("HOLD");
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "初始冻结");

        assertThat(release("alice", uniqueKey("CMD"), new Object[][]{{holdKey, 1}})
                .getResponse().getStatus()).isEqualTo(200);

        // 已解除的冻结不再参与重叠校验
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "解除后重建")
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void createHoldOnMissingEvidenceReturns404() throws Exception {
        fixClock(BASE);
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(uniqueKey("MISSING")),
                utc(BASE), utc(BASE.plusSeconds(3600)), "不存在")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void createHoldIdempotentReplayAndKeyConflict() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String holdKey = uniqueKey("HOLD");

        MvcResult first = createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "保全");
        MvcResult replay = createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "保全");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(holdHistory(evidenceKey)).hasSize(1);

        // 同键改参（不同原因）返回 409
        assertThat(createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "改了原因")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(holdHistory(evidenceKey)).hasSize(1);
    }

    @Test
    void failedCreateDoesNotOccupyHoldKey() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String holdKey = uniqueKey("HOLD");

        // 首次因区间覆盖过去失败，不占键
        assertThat(createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE.minusSeconds(60)), utc(BASE.plusSeconds(3600)), "非法")
                .getResponse().getStatus()).isEqualTo(400);
        // 同键合法参数重试成功
        assertThat(createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "合法")
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void batchReleaseReleasesAllAndIncrementsVersion() throws Exception {
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev1),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结一");
        createHold("alice", hold2, List.of(ev2),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结二");

        MvcResult result = release("alice", uniqueKey("CMD"),
                new Object[][]{{hold1, 1}, {hold2, 1}});
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode views = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(views).hasSize(2);
        for (JsonNode view : views) {
            assertThat(view.get("status").asText()).isEqualTo("RELEASED");
            assertThat(view.get("version").asInt()).isEqualTo(2);
            assertThat(view.get("effective").asBoolean()).isFalse();
            assertThat(view.get("releasedBy").asText()).isEqualTo("alice");
            assertThat(view.get("releasedAt").isNull()).isFalse();
        }
        assertThat(effectiveHolds(ev1)).isEmpty();
        assertThat(effectiveHolds(ev2)).isEmpty();
        // 历史快照保留已解除冻结
        assertThat(holdHistory(ev1)).hasSize(1);
        assertThat(holdHistory(ev1).get(0).get("status").asText()).isEqualTo("RELEASED");
    }

    @Test
    void batchReleaseRollsBackWhenAnyRequesterMismatch() throws Exception {
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev1),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结一");
        createHold("bob", hold2, List.of(ev2),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结二");

        // alice 批量解除包含 bob 创建的冻结：请求方校验失败，整批回滚
        assertThat(release("alice", uniqueKey("CMD"), new Object[][]{{hold1, 1}, {hold2, 1}})
                .getResponse().getStatus()).isEqualTo(409);

        JsonNode history1 = holdHistory(ev1);
        JsonNode history2 = holdHistory(ev2);
        assertThat(history1.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(history1.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(history2.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(effectiveHolds(ev1)).hasSize(1);
        assertThat(effectiveHolds(ev2)).hasSize(1);
    }

    @Test
    void batchReleaseRollsBackWhenAnyVersionMismatch() throws Exception {
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev1),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结一");
        createHold("alice", hold2, List.of(ev2),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结二");

        // 第二笔版本不匹配：整批回滚，第一笔也不得解除
        assertThat(release("alice", uniqueKey("CMD"), new Object[][]{{hold1, 1}, {hold2, 99}})
                .getResponse().getStatus()).isEqualTo(409);

        assertThat(holdHistory(ev1).get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(holdHistory(ev2).get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(effectiveHolds(ev1)).hasSize(1);
    }

    @Test
    void batchReleaseRejectsDuplicateHoldKeyAndMissingHold() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String holdKey = uniqueKey("HOLD");
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结");

        assertThat(release("alice", uniqueKey("CMD"), new Object[][]{{holdKey, 1}, {holdKey, 1}})
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(release("alice", uniqueKey("CMD"), new Object[][]{{uniqueKey("MISSING"), 1}})
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(holdHistory(evidenceKey).get(0).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void releaseReplayReturnsFirstResultAndSecondReleaseConflicts() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String holdKey = uniqueKey("HOLD");
        String commandKey = uniqueKey("CMD");
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)), "冻结");

        MvcResult first = release("alice", commandKey, new Object[][]{{holdKey, 1}});
        MvcResult replay = release("alice", commandKey, new Object[][]{{holdKey, 1}});
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 新命令再次解除：版本已递增，旧版本 409；新版本也因已解除 409
        assertThat(release("alice", uniqueKey("CMD"), new Object[][]{{holdKey, 1}})
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(release("alice", uniqueKey("CMD"), new Object[][]{{holdKey, 2}})
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(holdHistory(evidenceKey)).hasSize(1);
    }

    @Test
    void holdNotEffectiveBeforeStartOrAfterEnd() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String holdKey = uniqueKey("HOLD");
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)), "未来冻结");

        // 开始前：存在但非有效
        assertThat(effectiveHolds(evidenceKey)).isEmpty();
        assertThat(holdHistory(evidenceKey)).hasSize(1);
        assertThat(holdHistory(evidenceKey).get(0).get("effective").asBoolean()).isFalse();

        // 生效起点（左闭）：有效
        fixClock(BASE.plusSeconds(3600));
        assertThat(effectiveHolds(evidenceKey)).hasSize(1);

        // 生效终点（右开）：不再有效
        fixClock(BASE.plusSeconds(7200));
        assertThat(effectiveHolds(evidenceKey)).isEmpty();
    }
}
