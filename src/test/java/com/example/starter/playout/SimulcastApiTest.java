package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多频道联播锁定 API 端到端测试：联播时点一致性、授权联合校验、占位不可独立修改、
 * 撤销原子性与时机、幂等重放及并发边界。运行环境为 H2（MODE=MySQL）内存库，
 * 唯一约束、行锁与事务提交顺序均由真实数据库验证；业务时钟为可控时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulcastApiTest {

    private static final String DAY = "2026-09-26";
    private static final String PLANNED = "2026-09-26T10:00:00.000+08:00";
    private static final String PLANNED_END = "2026-09-26T10:30:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-26T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-27T00:00:00.000+08:00";
    private static final String SEG_START = "2026-09-26T08:00:00.000+08:00";
    private static final String SEG_END = "2026-09-26T09:00:00.000+08:00";
    private static final String T_1015 = "2026-09-26T10:15:00.000+08:00";
    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    /** 可控业务时钟：测试可推进时间以覆盖撤销时机分支。 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(T0);
    }

    // ---------- 主流程：整组创建、占位写入草稿、发布包含占位、时刻一致 ----------

    @Test
    void createLockWritesGroupAndPlaceholdersThenPublishIncludesThem() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);

        // 创建联播锁定：整组 200，固化频道集合、素材、时刻与每频道授权
        MvcResult created = createLock(ctx, "req-create", "sc-1", List.of(ch0, ch1), PLANNED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulcastKey").value(ctx.key("sc-1")))
                .andExpect(jsonPath("$.assetId").value(ctx.simAsset))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.plannedAt").value(PLANNED))
                .andExpect(jsonPath("$.endAt").value(PLANNED_END))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-25T08:00:00.000+08:00"))
                .andExpect(jsonPath("$.channels.length()").value(2))
                .andReturn();
        JsonNode group = readJson(created);
        Map<String, JsonNode> byChannel = new HashMap<>();
        group.get("channels").forEach(e -> byChannel.put(e.get("channelId").asText(), e));
        String seg0 = byChannel.get(ch0).get("segmentId").asText();
        assertThat(byChannel.get(ch0).get("grantId").asLong()).isEqualTo(ctx.simGrants.get(ch0));
        assertThat(byChannel.get(ch1).get("grantId").asLong()).isEqualTo(ctx.simGrants.get(ch1));
        assertThat(byChannel.get(ch0).get("placeholderStatus").asText()).isEqualTo("ACTIVE");
        assertThat(byChannel.get(ch0).get("start").asText()).isEqualTo(PLANNED);
        assertThat(byChannel.get(ch0).get("end").asText()).isEqualTo(PLANNED_END);

        // 组查询与频道占位查询一致
        getGroup(ctx, "sc-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channels.length()").value(2));
        getPlaceholders(ch0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeholders.length()").value(1))
                .andExpect(jsonPath("$.placeholders[0].simulcastKey").value(ctx.key("sc-1")))
                .andExpect(jsonPath("$.placeholders[0].segmentId").value(seg0))
                .andExpect(jsonPath("$.placeholders[0].start").value(PLANNED))
                .andExpect(jsonPath("$.placeholders[0].end").value(PLANNED_END))
                .andExpect(jsonPath("$.placeholders[0].status").value("ACTIVE"));

        // 占位写入使草稿版本递增：旧版本替换返回 409
        replaceDraft(ctx, ch0, "req-stale", 1, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));

        // 保留占位的整份替换成功（版本 2 → 3）
        replaceDraft(ctx, ch0, "req-keep", 2, List.of(
                        progSegment(ctx, ch0), placeholderSegment(ctx, seg0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 发布包含联播占位；播出时刻命中占位素材，结束时刻不再命中（左闭右开）
        publish(ctx, ch0, "req-pub", 3, 0).andExpect(status().isOk());
        playout(ch0, T_1015)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.simAsset))
                .andExpect(jsonPath("$.segmentId").value(seg0));
        playout(ch0, PLANNED_END)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"));
    }

    // ---------- 参数边界 ----------

    @Test
    void requestValidationBoundaries() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);

        // 400：频道数不足 2 个 / 超过 8 个 / 重复频道 / 缺少 requestId
        createLock(ctx, "bad-1", "k1", List.of(ch0), PLANNED)
                .andExpect(status().isBadRequest());
        List<String> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(newChannel(ctx, "n" + i, true, true));
        }
        createLock(ctx, "bad-2", "k2", nine, PLANNED)
                .andExpect(status().isBadRequest());
        createLock(ctx, "bad-3", "k3", List.of(ch0, ch0), PLANNED)
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/simulcast-locks").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lockBody(
                                null, ctx.key("k4"), List.of(ch0, ch1), ctx.simAsset, PLANNED))))
                .andExpect(status().isBadRequest());

        // 404：素材不存在 / 组不存在 / 撤销不存在的组 / 占位查询频道不存在
        createLockRaw(lockBody(ctx.key("bad-5"), ctx.key("k5"), List.of(ch0, ch1),
                        ctx.asset("ghost"), PLANNED))
                .andExpect(status().isNotFound());
        getGroup(ctx, "ghost").andExpect(status().isNotFound());
        revokeLock(ctx, "ghost", "bad-6").andExpect(status().isNotFound());
        mvc.perform(get("/api/channels/" + ctx.asset("ghost")
                        + "/simulcast-placeholders").param("businessDay", DAY))
                .andExpect(status().isNotFound());

        // 8 个频道：合法上限
        Ctx ctx8 = newCtx();
        List<String> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(newChannel(ctx8, "c" + i, true, true));
        }
        createLock(ctx8, "ok-8", "sc-8", eight, PLANNED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(8));
    }

    // ---------- 422：逐频道原因，整次拒绝且不写入任何锁定 ----------

    @Test
    void validationFailuresReturn422WithPerChannelReasonsAndWriteNothing() throws Exception {
        Ctx ctx = newCtx();
        String ok0 = newChannel(ctx, "ok0", true, true);
        String ok1 = newChannel(ctx, "ok1", true, true);
        String noDraft = newChannel(ctx, "nodraft", true, false);
        String noGrant = newChannel(ctx, "nogrant", false, true);
        String occupied = newChannel(ctx, "occupied", true, true);
        // occupied 频道草稿增加与计划时刻相交的节目片段 09:45-10:15
        replaceDraft(ctx, occupied, "req-occ", 1, List.of(
                        progSegment(ctx, occupied),
                        segment(ctx.key("occ-seg"), ctx.progAsset,
                                "2026-09-26T09:45:00.000+08:00", T_1015)))
                .andExpect(status().isOk());
        String ghost = ctx.asset("ghost");

        MvcResult rejected = createLock(ctx, "req-422", "sc-422",
                        List.of(ok0, ok1, noDraft, noGrant, occupied, ghost), PLANNED)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULCAST_VALIDATION_FAILED"))
                .andReturn();
        Map<String, List<String>> reasons = reasonsByChannel(readJson(rejected));
        assertThat(reasons.keySet()).containsExactlyInAnyOrder(noDraft, noGrant, occupied, ghost);
        assertThat(reasons.get(noDraft)).contains("DRAFT_NOT_FOUND");
        assertThat(reasons.get(noGrant)).contains("NO_COVERING_GRANT");
        assertThat(reasons.get(occupied)).contains("TIME_OCCUPIED_BY_SEGMENT");
        assertThat(reasons.get(ghost)).contains("CHANNEL_NOT_FOUND");

        // 整次拒绝：不写入任何锁定与占位，草稿版本不变
        getGroup(ctx, "sc-422").andExpect(status().isNotFound());
        getPlaceholders(ok0).andExpect(jsonPath("$.placeholders.length()").value(0));
        getPlaceholders(ok1).andExpect(jsonPath("$.placeholders.length()").value(0));
        replaceDraft(ctx, ok0, "req-after-422", 1, List.of(progSegment(ctx, ok0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 计划时刻被 ACTIVE 紧急插播占用：422 TIME_OCCUPIED_BY_OVERRIDE
        Ctx ctx2 = newCtx();
        String ovCh0 = newChannel(ctx2, "ov0", true, true);
        String ovCh1 = newChannel(ctx2, "ov1", true, true);
        postJson("/api/emergency-overrides", overrideBody(ctx2.key("req-ov"), ctx2.key("ov-1"),
                        ovCh0, ctx2.progAsset, ctx2.progGrants.get(ovCh0), 5,
                        "2026-09-26T10:10:00.000+08:00", "2026-09-26T10:20:00.000+08:00"))
                .andExpect(status().isOk());
        MvcResult ovRejected = createLock(ctx2, "req-422ov", "sc-ov",
                        List.of(ovCh0, ovCh1), PLANNED)
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        Map<String, List<String>> ovReasons = reasonsByChannel(readJson(ovRejected));
        assertThat(ovReasons.keySet()).containsExactly(ovCh0);
        assertThat(ovReasons.get(ovCh0)).contains("TIME_OCCUPIED_BY_OVERRIDE");
        getGroup(ctx2, "sc-ov").andExpect(status().isNotFound());

        // 授权已撤销：422 NO_COVERING_GRANT
        Ctx ctx3 = newCtx();
        String rvCh0 = newChannel(ctx3, "rv0", true, true);
        String rvCh1 = newChannel(ctx3, "rv1", true, true);
        postJson("/api/grants/" + ctx3.simGrants.get(rvCh0) + "/revoke",
                        Map.of("requestId", ctx3.key("req-rv")))
                .andExpect(status().isOk());
        MvcResult rvRejected = createLock(ctx3, "req-422rv", "sc-rv",
                        List.of(rvCh0, rvCh1), PLANNED)
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        Map<String, List<String>> rvReasons = reasonsByChannel(readJson(rvRejected));
        assertThat(rvReasons.keySet()).containsExactly(rvCh0);
        assertThat(rvReasons.get(rvCh0)).contains("NO_COVERING_GRANT");
    }

    // ---------- 占位不可独立修改 ----------

    @Test
    void placeholderCannotBeRemovedOrRescheduledIndependently() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);
        MvcResult created = createLock(ctx, "req-create", "sc-1", List.of(ch0, ch1), PLANNED)
                .andExpect(status().isOk()).andReturn();
        String seg0 = segmentIdOf(readJson(created), ch0);

        // 409：删除占位
        replaceDraft(ctx, ch0, "req-drop", 2, List.of(progSegment(ctx, ch0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_PLACEHOLDER_LOCKED"));
        // 409：占位改时
        replaceDraft(ctx, ch0, "req-move", 2, List.of(
                        progSegment(ctx, ch0),
                        segment(seg0, ctx.simAsset,
                                "2026-09-26T11:00:00.000+08:00", "2026-09-26T11:30:00.000+08:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_PLACEHOLDER_LOCKED"));
        // 200：原样保留占位
        replaceDraft(ctx, ch0, "req-keep", 2, List.of(
                        progSegment(ctx, ch0), placeholderSegment(ctx, seg0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 占位仍生效
        getPlaceholders(ch0).andExpect(jsonPath("$.placeholders.length()").value(1));
    }

    // ---------- 撤销：整组原子释放、不可变历史、不改写已发布快照 ----------

    @Test
    void revokeReleasesAllPlaceholdersAtomicallyAndKeepsPublishedSnapshot() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);
        MvcResult created = createLock(ctx, "req-create", "sc-1", List.of(ch0, ch1), PLANNED)
                .andExpect(status().isOk()).andReturn();
        String seg0 = segmentIdOf(readJson(created), ch0);

        // ch0 先发布（快照包含占位），随后整组撤销不得改写该快照
        publish(ctx, ch0, "req-pub", 2, 0).andExpect(status().isOk());

        revokeLock(ctx, "sc-1", "req-revoke")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeRequestId").value(ctx.key("req-revoke")))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty())
                .andExpect(jsonPath("$.channels[0].placeholderStatus").value("RELEASED"))
                .andExpect(jsonPath("$.channels[1].placeholderStatus").value("RELEASED"));

        // 全部频道占位同时释放
        getPlaceholders(ch0).andExpect(jsonPath("$.placeholders.length()").value(0));
        getPlaceholders(ch1).andExpect(jsonPath("$.placeholders.length()").value(0));

        // 组明细保留撤销信息；撤销历史可查且不可变
        getGroup(ctx, "sc-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeRequestId").value(ctx.key("req-revoke")));
        MvcResult history = mvc.perform(get("/api/simulcast-revocations"))
                .andExpect(status().isOk()).andReturn();
        JsonNode revocations = readJson(history).get("revocations");
        assertThat(revocations).anyMatch(r -> r.get("simulcastKey").asText().equals(ctx.key("sc-1"))
                && r.get("revokeRequestId").asText().equals(ctx.key("req-revoke")));

        // 撤销后草稿可移除占位（版本：创建 1 → 占位 2 → 撤销 3）
        replaceDraft(ctx, ch1, "req-drop-after", 3, List.of(progSegment(ctx, ch1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));

        // 已发布快照不改写：ch0 在计划时刻仍播出联播素材
        playout(ch0, T_1015)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.simAsset))
                .andExpect(jsonPath("$.segmentId").value(seg0));

        // 409：重复撤销；撤销后键不释放
        revokeLock(ctx, "sc-1", "req-revoke-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_NOT_ACTIVE"));
        createLock(ctx, "req-recreate", "sc-1", List.of(ch0, ch1), PLANNED)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SIMULCAST_KEY"));
    }

    // ---------- 撤销时机：任一频道开始播出后撤销返回 409 ----------

    @Test
    void revokeAfterPlannedStartReturns409() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);
        String ch2 = newChannel(ctx, "ch2", true, true);
        String ch3 = newChannel(ctx, "ch3", true, true);
        createLock(ctx, "req-a", "sc-a", List.of(ch0, ch1), PLANNED).andExpect(status().isOk());
        createLock(ctx, "req-b", "sc-b", List.of(ch2, ch3), PLANNED).andExpect(status().isOk());

        // 开始播出前 1 毫秒：可撤销
        clock.set(Instant.parse("2026-09-26T01:59:59.999Z"));
        revokeLock(ctx, "sc-a", "req-rv-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 到达计划播出时刻：撤销 409，组与占位保持 ACTIVE
        clock.set(Instant.parse("2026-09-26T02:00:00Z"));
        revokeLock(ctx, "sc-b", "req-rv-b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_ALREADY_STARTED"));
        getGroup(ctx, "sc-b").andExpect(jsonPath("$.status").value("ACTIVE"));
        getPlaceholders(ch2).andExpect(jsonPath("$.placeholders.length()").value(1));
    }

    // ---------- 幂等：同键同参重放、异参 409、失败不占键 ----------

    @Test
    void idempotencyReplayChangedParamsAndFailureNotOccupying() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);

        Map<String, Object> body = lockBody(ctx.key("idem-1"), ctx.key("sc-1"),
                List.of(ch0, ch1), ctx.simAsset, PLANNED);
        createLockRaw(body).andExpect(status().isOk());
        // 同 requestId 同参数重放：返回首次结果
        createLockRaw(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulcastKey").value(ctx.key("sc-1")))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 同 requestId 改参数：409
        createLockRaw(lockBody(ctx.key("idem-1"), ctx.key("sc-1"), List.of(ch0, ch1),
                        ctx.simAsset, "2026-09-26T12:00:00.000+08:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        createLockRaw(lockBody(ctx.key("idem-1"), ctx.key("sc-2"), List.of(ch0, ch1),
                        ctx.simAsset, PLANNED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占 requestId：先因授权缺失 422，补齐授权后同 requestId 成功
        Ctx ctx2 = newCtx();
        String f0 = newChannel(ctx2, "f0", false, true);
        String f1 = newChannel(ctx2, "f1", true, true);
        createLock(ctx2, "idem-2", "sc-f", List.of(f0, f1), PLANNED)
                .andExpect(status().isUnprocessableEntity());
        long grant = createGrant(f0, ctx2.simAsset, GRANT_FROM, GRANT_TO);
        ctx2.simGrants.put(f0, grant);
        createLock(ctx2, "idem-2", "sc-f", List.of(f0, f1), PLANNED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulcastKey").value(ctx2.key("sc-f")));

        // 撤销幂等：重放返回首次 REVOKED 结果；同 requestId 撤销其他组 409
        String revokeReq = ctx2.key("idem-3");
        revokeLockRaw(ctx2.key("sc-f"), revokeReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        revokeLockRaw(ctx2.key("sc-f"), revokeReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        revokeLockRaw(ctx.key("sc-1"), revokeReq)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 并发：同时刻两组创建仅一组成功，不留部分频道占位 ----------

    @Test
    void concurrentCreatesSameMomentOnlyOneGroupWins() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);

        List<Integer> statuses = runConcurrently(2, i -> createLockRaw(lockBody(
                        ctx.key("cc-req-" + i), ctx.key("sc-" + i), List.of(ch0, ch1),
                        ctx.simAsset, PLANNED))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 恰一组 ACTIVE，另一组不存在；两个频道占位同时存在，不存在部分占位
        int activeGroups = 0;
        for (int i = 0; i < 2; i++) {
            MvcResult group = getGroup(ctx, "sc-" + i).andReturn();
            if (group.getResponse().getStatus() == 200) {
                activeGroups++;
                assertThat(readJson(group).get("status").asText()).isEqualTo("ACTIVE");
            }
        }
        assertThat(activeGroups).isEqualTo(1);
        getPlaceholders(ch0).andExpect(jsonPath("$.placeholders.length()").value(1));
        getPlaceholders(ch1).andExpect(jsonPath("$.placeholders.length()").value(1));
    }

    // ---------- 并发：联播创建与授权撤销按提交顺序裁决 ----------

    @Test
    void concurrentCreateAndGrantRevokeFollowsCommitOrder() throws Exception {
        Ctx ctx = newCtx();
        String ch0 = newChannel(ctx, "ch0", true, true);
        String ch1 = newChannel(ctx, "ch1", true, true);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> createFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return createLockRaw(lockBody(ctx.key("race-create"), ctx.key("sc-race"),
                                List.of(ch0, ch1), ctx.simAsset, PLANNED))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postJson("/api/grants/" + ctx.simGrants.get(ch0) + "/revoke",
                                Map.of("requestId", ctx.key("race-rv")))
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int createStatus = createFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

            // 撤销本身成功；创建要么 422（撤销先提交），要么 200（创建先提交并固化授权）
            assertThat(revokeStatus).isEqualTo(200);
            assertThat(createStatus).isIn(200, 422);

            MvcResult group = getGroup(ctx, "sc-race").andReturn();
            if (createStatus == 422) {
                // 撤销先提交：整组不存在，且任何频道都不留占位
                assertThat(group.getResponse().getStatus()).isEqualTo(404);
                getPlaceholders(ch0).andExpect(jsonPath("$.placeholders.length()").value(0));
                getPlaceholders(ch1).andExpect(jsonPath("$.placeholders.length()").value(0));
            } else {
                // 创建先提交：组 ACTIVE，ch0 占位固化了创建时选定的授权（该授权现已撤销）
                JsonNode json = readJson(group);
                assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
                assertThat(segmentIdOf(json, ch0)).isNotBlank();
                getPlaceholders(ch0).andExpect(jsonPath("$.placeholders.length()").value(1));
                getPlaceholders(ch1).andExpect(jsonPath("$.placeholders.length()").value(1));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、节目素材、联播素材；requestId 等均带随机前缀避免跨用例冲突。 */
    private final class Ctx {
        private final String prefix =
                "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private final String fbAsset = prefix + "fb";
        private final String progAsset = prefix + "prog";
        private final String simAsset = prefix + "sim";
        private final Map<String, Long> simGrants = new HashMap<>();
        private final Map<String, Long> progGrants = new HashMap<>();

        private String asset(String name) {
            return prefix + name;
        }

        private String key(String shortKey) {
            return prefix + shortKey;
        }
    }

    private Ctx newCtx() throws Exception {
        Ctx ctx = new Ctx();
        postJson("/api/assets", Map.of("id", ctx.fbAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.progAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        // 联播素材时长 30 分钟：占位区间 [plannedAt, plannedAt+30min)
        postJson("/api/assets", Map.of("id", ctx.simAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        return ctx;
    }

    /** 创建频道及其节目授权、可选联播授权、可选当日草稿（含 08:00-09:00 节目片段）。 */
    private String newChannel(Ctx ctx, String name, boolean withSimGrant, boolean withDraft)
            throws Exception {
        String channel = ctx.asset(name);
        postJson("/api/channels", Map.of("id", channel, "fallbackAssetId", ctx.fbAsset))
                .andExpect(status().isOk());
        ctx.progGrants.put(channel, createGrant(channel, ctx.progAsset, GRANT_FROM, GRANT_TO));
        if (withSimGrant) {
            ctx.simGrants.put(channel,
                    createGrant(channel, ctx.simAsset, GRANT_FROM, GRANT_TO));
        }
        if (withDraft) {
            replaceDraft(ctx, channel, "draft-" + name, 0, List.of(progSegment(ctx, channel)))
                    .andExpect(status().isOk());
        }
        return channel;
    }

    private Map<String, Object> progSegment(Ctx ctx, String channel) {
        String name = channel.substring(channel.lastIndexOf('-') + 1);
        return segment(ctx.key("seg-" + name), ctx.progAsset, SEG_START, SEG_END);
    }

    private Map<String, Object> placeholderSegment(Ctx ctx, String segmentId) {
        return segment(segmentId, ctx.simAsset, PLANNED, PLANNED_END);
    }

    private Map<String, Object> segment(String id, String assetId, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("assetId", assetId);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private Map<String, Object> lockBody(String requestId, String simulcastKey,
                                         List<String> channelIds, String assetId,
                                         String plannedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("simulcastKey", simulcastKey);
        body.put("channelIds", channelIds);
        body.put("assetId", assetId);
        body.put("plannedAt", plannedAt);
        return body;
    }

    private Map<String, Object> overrideBody(String requestId, String overrideKey,
                                             String channelId, String assetId, long grantId,
                                             int priority, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("overrideKey", overrideKey);
        body.put("channelId", channelId);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private ResultActions createLock(Ctx ctx, String requestId, String shortKey,
                                     List<String> channelIds, String plannedAt) throws Exception {
        return createLockRaw(lockBody(ctx.key(requestId), ctx.key(shortKey), channelIds,
                ctx.simAsset, plannedAt));
    }

    private ResultActions createLockRaw(Map<String, Object> body) throws Exception {
        return postJson("/api/simulcast-locks", body);
    }

    private ResultActions revokeLock(Ctx ctx, String shortKey, String requestId) throws Exception {
        return revokeLockRaw(ctx.key(shortKey), ctx.key(requestId));
    }

    private ResultActions revokeLockRaw(String simulcastKey, String requestId) throws Exception {
        return postJson("/api/simulcast-locks/" + simulcastKey + "/revoke",
                Map.of("requestId", requestId));
    }

    private ResultActions getGroup(Ctx ctx, String shortKey) throws Exception {
        return mvc.perform(get("/api/simulcast-locks/" + ctx.key(shortKey)));
    }

    private ResultActions getPlaceholders(String channel) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/simulcast-placeholders")
                .param("businessDay", DAY));
    }

    private ResultActions replaceDraft(Ctx ctx, String channel, String requestId,
                                       long expectedVersion,
                                       List<Map<String, Object>> segments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", ctx.key(requestId));
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        return mvc.perform(put("/api/channels/" + channel + "/drafts/" + DAY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions publish(Ctx ctx, String channel, String requestId, long draftVersion,
                                  long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", ctx.key(requestId), "draftVersion", draftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    private ResultActions playout(String channel, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/playout").param("at", at));
    }

    private long createGrant(String channel, String asset, String from, String to)
            throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset, "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String segmentIdOf(JsonNode group, String channelId) {
        for (JsonNode entry : group.get("channels")) {
            if (entry.get("channelId").asText().equals(channelId)) {
                return entry.get("segmentId").asText();
            }
        }
        throw new AssertionError("联播组中不存在频道: " + channelId);
    }

    private static Map<String, List<String>> reasonsByChannel(JsonNode errorBody) {
        Map<String, List<String>> reasons = new HashMap<>();
        for (JsonNode rejection : errorBody.get("rejections")) {
            reasons.computeIfAbsent(rejection.get("channelId").asText(), k -> new ArrayList<>())
                    .add(rejection.get("code").asText());
        }
        return reasons;
    }

    private interface ThrowingSupplier {
        Integer get(int index) throws Exception;
    }

    private List<Integer> runConcurrently(int threads, ThrowingSupplier action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return action.get(index);
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }
}
