package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多频道联播锁定 API 端到端测试：时点一致性、授权联合校验、撤销原子性、
 * 占位不可独立修改、并发裁决与幂等。运行环境为 H2（MODE=MySQL）内存库，
 * 唯一约束、行锁与事务提交顺序均由真实数据库验证。
 *
 * <p>撤销时间窗用固定时刻界定：未来业务日 2099-01-05（可撤销）与过去业务日 2020-01-05
 * （已开始播出，不可撤销），相对真实时钟确定，无需外部时间源。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulcastLockApiTest {

    private static final String DAY = "2099-01-05";
    private static final String AT = "2099-01-05T10:00:00.000+08:00";
    private static final String AT_1030 = "2099-01-05T10:30:00.000+08:00";
    private static final String AT_1045 = "2099-01-05T10:45:00.000+08:00";
    private static final String PAST_DAY = "2020-01-05";
    private static final String PAST_AT = "2020-01-05T10:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程：创建、查询、占位保护、发布、撤销、历史 ----------

    @Test
    void createQueryProtectPublishRevokeAndHistory() throws Exception {
        Ctx ctx = newContext(2, DAY);
        String ch1 = ctx.channels.get(0);
        String ch2 = ctx.channels.get(1);
        String key = ctx.key("lock-1");

        // 创建：整组 ACTIVE，全部频道同一素材同一时刻，逐频道固化授权版本
        JsonNode lock = readJson(createLock(ctx.key("req-create-1"), key, List.of(ch1, ch2), ctx.asset, DAY, AT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulcastKey").value(key))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.assetId").value(ctx.asset))
                .andExpect(jsonPath("$.at").value(AT))
                .andExpect(jsonPath("$.placeholders.length()").value(2))
                .andExpect(jsonPath("$.revokeRequestId").isEmpty())
                .andExpect(jsonPath("$.revokedAt").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn());
        String ph1 = placeholderId(lock, ch1);
        String ph2 = placeholderId(lock, ch2);
        assertThat(ph1).isNotEqualTo(ph2);
        // 联播时点一致性：全部频道占位共用同一时刻与素材，授权版本按频道固化
        for (JsonNode p : lock.get("placeholders")) {
            assertThat(p.get("simulcastKey").asText()).isEqualTo(key);
            assertThat(p.get("at").asText()).isEqualTo(AT);
            assertThat(p.get("assetId").asText()).isEqualTo(ctx.asset);
            assertThat(p.get("grantId").asLong()).isEqualTo(ctx.grants.get(p.get("channelId").asText()));
        }

        // 查询：联播组与频道占位
        getLock(key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.placeholders.length()").value(2));
        placeholders(ch1, DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].simulcastKey").value(key))
                .andExpect(jsonPath("$[0].placeholderSegmentId").value(ph1));
        placeholders(ch1, null).andExpect(jsonPath("$.length()").value(1));
        placeholders(ctx.prefix + "ghost", DAY).andExpect(status().isNotFound());

        // 删除占位 → 409
        replaceDraftRaw(ch1, DAY, "req-rm-" + ctx.prefix, 1,
                List.of(segment(ctx.prefix + "s2", ctx.asset, dayAt(DAY, 14), dayAt(DAY, 15))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_PLACEHOLDER_REQUIRED"));
        // 改时 → 409
        replaceDraftRaw(ch1, DAY, "req-mv-" + ctx.prefix, 1,
                List.of(segment(ph1, ctx.asset, AT_1030, AT_1030)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_PLACEHOLDER_MODIFIED"));
        // 原样携带占位 + 普通片段 → 200，草稿响应随附占位
        replaceDraftRaw(ch1, DAY, "req-ok-" + ctx.prefix, 1,
                List.of(segment(ph1, ctx.asset, AT, AT),
                        segment(ctx.prefix + "s2", ctx.asset, dayAt(DAY, 14), dayAt(DAY, 15))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.simulcastPlaceholders.length()").value(1))
                .andExpect(jsonPath("$.simulcastPlaceholders[0].placeholderSegmentId").value(ph1));

        // 发布必须包含联播占位：快照中出现零长度占位片段并固化授权版本
        postJson("/api/channels/" + ch1 + "/drafts/" + DAY + "/publish",
                Map.of("requestId", "req-pub-" + ctx.prefix, "draftVersion", 2,
                        "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
        Long snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_publication_segment ps"
                        + " JOIN playout_publication p ON p.id = ps.publication_id"
                        + " WHERE p.channel_id = ? AND ps.segment_id = ?"
                        + " AND ps.grant_id = ? AND ps.start_ms = ps.end_ms",
                Long.class, ch1, ph1, ctx.grants.get(ch1));
        assertThat(snapshotCount).isEqualTo(1L);
        // 播出决定不受占位影响：14:30 命中已发布节目
        playout(ch1, dayAt(DAY, 14, 30))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.asset));

        // 撤销整组：同时释放全部频道占位，写入不可变历史
        revokeLock(key, ctx.key("req-revoke-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.placeholders.length()").value(0))
                .andExpect(jsonPath("$.revokeRequestId").value(ctx.key("req-revoke-1")))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());
        getLock(key)
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.placeholders.length()").value(0));
        placeholders(ch1, DAY).andExpect(jsonPath("$.length()").value(0));
        placeholders(ch2, DAY).andExpect(jsonPath("$.length()").value(0));
        // 撤销不改写已发布快照
        Long snapshotAfterRevoke = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_publication_segment ps"
                        + " JOIN playout_publication p ON p.id = ps.publication_id"
                        + " WHERE p.channel_id = ? AND ps.segment_id = ?",
                Long.class, ch1, ph1);
        assertThat(snapshotAfterRevoke).isEqualTo(1L);
        // 撤销历史查询
        mvc.perform(get("/api/simulcast-revocations").param("simulcastKey", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].simulcastKey").value(key))
                .andExpect(jsonPath("$[0].revokeRequestId").value(ctx.key("req-revoke-1")))
                .andExpect(jsonPath("$[0].channelCount").value(2))
                .andExpect(jsonPath("$[0].revokedAt").isNotEmpty());
        // 撤销后草稿不再要求占位
        replaceDraftRaw(ch1, DAY, "req-after-" + ctx.prefix, 2, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.simulcastPlaceholders.length()").value(0));
        // 重复撤销 → 409
        revokeLock(key, ctx.key("req-revoke-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_ALREADY_REVOKED"));
    }

    // ---------- 参数与逐频道校验：整次 422、不写入任何锁定、失败不占键 ----------

    @Test
    void parameterAndPerChannelValidationFailures() throws Exception {
        Ctx ctx = newContext(2, DAY);
        String ch1 = ctx.channels.get(0);
        String ch2 = ctx.channels.get(1);

        // 400：频道数越界（1 个 / 9 个）
        createLock(ctx.key("r1"), ctx.key("k1"), List.of(ch1), ctx.asset, DAY, AT)
                .andExpect(status().isBadRequest());
        createLock(ctx.key("r2"), ctx.key("k2"), List.of(ch1, ch2, "c3", "c4", "c5", "c6", "c7", "c8", "c9"),
                ctx.asset, DAY, AT)
                .andExpect(status().isBadRequest());
        // 400：重复频道
        createLock(ctx.key("r3"), ctx.key("k3"), List.of(ch1, ch1), ctx.asset, DAY, AT)
                .andExpect(status().isBadRequest());
        // 400：计划时刻不在业务日内
        createLock(ctx.key("r4"), ctx.key("k4"), List.of(ch1, ch2), ctx.asset, DAY,
                "2099-01-06T10:00:00.000+08:00")
                .andExpect(status().isBadRequest());
        // 400：业务日格式非法
        createLock(ctx.key("r5"), ctx.key("k5"), List.of(ch1, ch2), ctx.asset, "2099-13-01", AT)
                .andExpect(status().isBadRequest());
        // 404：素材不存在
        createLock(ctx.key("r6"), ctx.key("k6"), List.of(ch1, ch2), ctx.prefix + "ghost-asset", DAY, AT)
                .andExpect(status().isNotFound());

        // 422：频道不存在，逐频道原因；整次不写入
        String ghost = ctx.prefix + "ghost-ch";
        createLock(ctx.key("r7"), ctx.key("k7"), List.of(ch1, ghost), ctx.asset, DAY, AT)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULCAST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].channelId").value(ghost))
                .andExpect(jsonPath("$.failures[0].code").value("CHANNEL_NOT_FOUND"));
        getLock(ctx.key("k7")).andExpect(status().isNotFound());
        placeholders(ch1, DAY).andExpect(jsonPath("$.length()").value(0));
        // 失败不占 requestId、不占 simulcastKey：修复后同 requestId 同键成功
        createLock(ctx.key("r7"), ctx.key("k7"), List.of(ch1, ch2), ctx.asset, DAY, AT)
                .andExpect(status().isOk());

        // 422：频道无该业务日草稿
        String chNoDraft = ctx.prefix + "ch-nodraft";
        postJson("/api/channels", Map.of("id", chNoDraft, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        createLock(ctx.key("r8"), ctx.key("k8"), List.of(ch1, chNoDraft), ctx.asset, DAY, AT_1030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].channelId").value(chNoDraft))
                .andExpect(jsonPath("$.failures[0].code").value("DRAFT_NOT_FOUND"));

        // 422：授权未覆盖某频道该时刻（授权仅覆盖 11:00-12:00；时刻避开已锁定的 10:00）
        String chLimited = ctx.prefix + "ch-limited";
        postJson("/api/channels", Map.of("id", chLimited, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        createGrant(chLimited, ctx.asset, dayAt(DAY, 11), dayAt(DAY, 12));
        replaceDraftRaw(chLimited, DAY, "req-dl-" + ctx.prefix, 0, List.of())
                .andExpect(status().isOk());
        createLock(ctx.key("r9"), ctx.key("k9"), List.of(ch1, chLimited), ctx.asset, DAY, AT_1045)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].channelId").value(chLimited))
                .andExpect(jsonPath("$.failures[0].code").value("NO_COVERING_GRANT"));
        getLock(ctx.key("k9")).andExpect(status().isNotFound());
        placeholders(ch1, DAY).andExpect(jsonPath("$.length()").value(1));
        placeholders(chLimited, DAY).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void momentOccupiedBySegmentOrOverride() throws Exception {
        // 时刻被草稿片段占用 → 422 SEGMENT_OCCUPIED
        Ctx ctx = newContext(2, DAY);
        String ch1 = ctx.channels.get(0);
        replaceDraftRaw(ch1, DAY, "req-seg-" + ctx.prefix, 1,
                List.of(segment(ctx.prefix + "occ", ctx.asset,
                        "2099-01-05T09:30:00.000+08:00", "2099-01-05T10:30:00.000+08:00")))
                .andExpect(status().isOk());
        createLock(ctx.key("r10"), ctx.key("k10"), ctx.channels, ctx.asset, DAY, AT)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].channelId").value(ch1))
                .andExpect(jsonPath("$.failures[0].code").value("SEGMENT_OCCUPIED"));

        // 时刻被生效中的紧急插播占用 → 422 OVERRIDE_OCCUPIED；取消插播后同键放行
        Ctx ctx2 = newContext(2, DAY);
        String ocCh1 = ctx2.channels.get(0);
        postJson("/api/emergency-overrides", overrideBody("req-ov-" + ctx2.prefix,
                ctx2.key("ov"), ocCh1, ctx2.asset, ctx2.grants.get(ocCh1), 5,
                "2099-01-05T09:50:00.000+08:00", "2099-01-05T10:10:00.000+08:00"))
                .andExpect(status().isOk());
        createLock(ctx2.key("r11"), ctx2.key("k11"), ctx2.channels, ctx2.asset, DAY, AT)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].channelId").value(ocCh1))
                .andExpect(jsonPath("$.failures[0].code").value("OVERRIDE_OCCUPIED"));
        postJson("/api/emergency-overrides/" + ctx2.key("ov") + "/cancel",
                Map.of("requestId", "req-oc-" + ctx2.prefix))
                .andExpect(status().isOk());
        createLock(ctx2.key("r11"), ctx2.key("k11"), ctx2.channels, ctx2.asset, DAY, AT)
                .andExpect(status().isOk());
    }

    @Test
    void momentOccupiedByOtherSimulcastGroup() throws Exception {
        Ctx ctx = newContext(3, DAY);
        String ch1 = ctx.channels.get(0);
        String ch2 = ctx.channels.get(1);
        String ch3 = ctx.channels.get(2);

        createLock(ctx.key("r1"), ctx.key("g1"), List.of(ch1, ch2), ctx.asset, DAY, AT)
                .andExpect(status().isOk());
        // 同时刻与其他联播组占位冲突 → 422 SIMULCAST_OCCUPIED，仅报冲突频道
        createLock(ctx.key("r2"), ctx.key("g2"), List.of(ch2, ch3), ctx.asset, DAY, AT)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].channelId").value(ch2))
                .andExpect(jsonPath("$.failures[0].code").value("SIMULCAST_OCCUPIED"));
        // 不同时刻不冲突；失败不占 requestId
        createLock(ctx.key("r2"), ctx.key("g2"), List.of(ch2, ch3), ctx.asset, DAY, AT_1030)
                .andExpect(status().isOk());
        placeholders(ch2, DAY).andExpect(jsonPath("$.length()").value(2));
    }

    // ---------- 撤销时间窗：开始播出后 409 ----------

    @Test
    void revokeAfterStartRejected() throws Exception {
        Ctx ctx = newContext(2, PAST_DAY);
        String key = ctx.key("past");
        createLock(ctx.key("r1"), key, ctx.channels, ctx.asset, PAST_DAY, PAST_AT)
                .andExpect(status().isOk());
        revokeLock(key, ctx.key("r2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SIMULCAST_ALREADY_STARTED"));
        // 撤销失败不释放占位
        getLock(key)
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.placeholders.length()").value(2));
        placeholders(ctx.channels.get(0), PAST_DAY).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/simulcast-revocations").param("simulcastKey", key))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 幂等：同键同参重放、异参 409、键不可复用 ----------

    @Test
    void idempotencyReplayChangedParamsAndKeyReuse() throws Exception {
        Ctx ctx = newContext(2, DAY);
        String ch1 = ctx.channels.get(0);
        String ch2 = ctx.channels.get(1);
        String key = ctx.key("g1");

        Map<String, Object> body = lockBody(ctx.key("idem-1"), key, List.of(ch1, ch2), ctx.asset, DAY, AT);
        JsonNode first = readJson(postJson("/api/simulcast-locks", body)
                .andExpect(status().isOk()).andReturn());
        // 同 requestId 同参数重放：返回首次结果（含相同占位片段 ID）
        JsonNode replay = readJson(postJson("/api/simulcast-locks", body)
                .andExpect(status().isOk()).andReturn());
        assertThat(replay).isEqualTo(first);
        // 频道顺序不同视为同参：归一化后重放首次结果
        postJson("/api/simulcast-locks", lockBody(ctx.key("idem-1"), key, List.of(ch2, ch1), ctx.asset, DAY, AT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulcastKey").value(key));
        // 同 requestId 异参 → 409
        postJson("/api/simulcast-locks", lockBody(ctx.key("idem-1"), key, List.of(ch1, ch2), ctx.asset, DAY, AT_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 simulcastKey 新 requestId → 409
        createLock(ctx.key("idem-2"), key, List.of(ch1, ch2), ctx.asset, DAY, AT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SIMULCAST_KEY"));

        // 撤销幂等：同 requestId 重放返回首次 REVOKED 结果
        revokeLock(key, ctx.key("idem-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        revokeLock(key, ctx.key("idem-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeRequestId").value(ctx.key("idem-rv")));
        // 同 requestId 用于其他联播组 → 409
        createLock(ctx.key("idem-3"), ctx.key("g2"), List.of(ch1, ch2), ctx.asset, DAY, AT_1030)
                .andExpect(status().isOk());
        revokeLock(ctx.key("g2"), ctx.key("idem-rv"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 撤销后 simulcastKey 不可复用
        createLock(ctx.key("idem-4"), key, List.of(ch1, ch2), ctx.asset, DAY, AT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SIMULCAST_KEY"));
    }

    // ---------- 占位保护：普通片段与插播不得覆盖占位时刻，撤销后放行 ----------

    @Test
    void segmentAndOverrideCannotCoverLockedMoment() throws Exception {
        Ctx ctx = newContext(2, DAY);
        String ch1 = ctx.channels.get(0);
        String key = ctx.key("g1");
        JsonNode lock = readJson(createLock(ctx.key("r1"), key, ctx.channels, ctx.asset, DAY, AT)
                .andExpect(status().isOk()).andReturn());
        String ph1 = placeholderId(lock, ch1);

        // 普通片段覆盖占位时刻 → 422
        replaceDraftRaw(ch1, DAY, "r2-" + ctx.prefix, 1,
                List.of(segment(ph1, ctx.asset, AT, AT),
                        segment(ctx.prefix + "cov", ctx.asset,
                                "2099-01-05T09:30:00.000+08:00", "2099-01-05T10:30:00.000+08:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULCAST_MOMENT_OCCUPIED"));
        // 插播区间覆盖占位时刻 → 422
        postJson("/api/emergency-overrides", overrideBody("r3-" + ctx.prefix, ctx.key("ov"),
                ch1, ctx.asset, ctx.grants.get(ch1), 5,
                "2099-01-05T09:50:00.000+08:00", "2099-01-05T10:10:00.000+08:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULCAST_MOMENT_OCCUPIED"));
        // 撤销整组后插播放行
        revokeLock(key, ctx.key("r4")).andExpect(status().isOk());
        postJson("/api/emergency-overrides", overrideBody("r5-" + ctx.prefix, ctx.key("ov"),
                ch1, ctx.asset, ctx.grants.get(ch1), 5,
                "2099-01-05T09:50:00.000+08:00", "2099-01-05T10:10:00.000+08:00"))
                .andExpect(status().isOk());
    }

    // ---------- 并发：重叠频道同时刻两组联播仅一组成功 ----------

    @Test
    void concurrentOverlappingLocksOnlyOneWins() throws Exception {
        Ctx ctx = newContext(3, DAY);
        String ch1 = ctx.channels.get(0);
        String ch2 = ctx.channels.get(1);
        String ch3 = ctx.channels.get(2);

        List<Integer> statuses = runConcurrently(2, i -> {
            List<String> channels = i == 0 ? List.of(ch1, ch2) : List.of(ch2, ch3);
            return postJson("/api/simulcast-locks", lockBody("cc-req-" + i + ctx.prefix,
                    ctx.key("cc" + i), channels, ctx.asset, DAY, AT))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 最终状态：只有胜出组的占位存在，任何时刻不得留下部分频道占位
        int total = placeholderCount(ch1, DAY) + placeholderCount(ch2, DAY) + placeholderCount(ch3, DAY);
        assertThat(total).isEqualTo(2);
        assertThat(placeholderCount(ch2, DAY)).isEqualTo(1);
    }

    // ---------- 并发：联播创建与授权撤销按提交顺序裁决 ----------

    @Test
    void concurrentCreateAndGrantRevokeFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext(2, DAY);
        String ch1 = ctx.channels.get(0);
        String ch2 = ctx.channels.get(1);
        String key = ctx.key("race");
        long grantToRevoke = ctx.grants.get(ch1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> createFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postJson("/api/simulcast-locks", lockBody("race-c-" + ctx.prefix, key,
                        List.of(ch1, ch2), ctx.asset, DAY, AT))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postJson("/api/grants/" + grantToRevoke + "/revoke",
                        Map.of("requestId", "race-r-" + ctx.prefix))
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int createStatus = createFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

            assertThat(revokeStatus).isEqualTo(200);
            assertThat(createStatus).isIn(200, 422);
            if (createStatus == 200) {
                // 创建先提交：锁定保留并固化已撤销的授权版本，授权撤销不释放占位
                JsonNode lock = readJson(getLock(key).andExpect(status().isOk()).andReturn());
                assertThat(lock.get("placeholders")).hasSize(2);
                assertThat(placeholderId(lock, ch1)).isNotBlank();
                for (JsonNode p : lock.get("placeholders")) {
                    if (p.get("channelId").asText().equals(ch1)) {
                        assertThat(p.get("grantId").asLong()).isEqualTo(grantToRevoke);
                    }
                }
            } else {
                // 撤销先提交：整次 422，不留下任何部分占位
                getLock(key).andExpect(status().isNotFound());
                assertThat(placeholderCount(ch1, DAY)).isZero();
                assertThat(placeholderCount(ch2, DAY)).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 并发：插播创建与联播创建互斥裁决 ----------

    @Test
    void concurrentOverrideAndLockAreMutuallyExclusive() throws Exception {
        Ctx ctx = newContext(2, DAY);
        String ch1 = ctx.channels.get(0);
        String lockKey = ctx.key("rc-lock");
        String overrideKey = ctx.key("rc-ov");

        List<Integer> statuses = runConcurrently(2, i -> {
            if (i == 0) {
                return postJson("/api/emergency-overrides", overrideBody("rc-ov-" + ctx.prefix,
                        overrideKey, ch1, ctx.asset, ctx.grants.get(ch1), 5,
                        "2099-01-05T09:50:00.000+08:00", "2099-01-05T10:10:00.000+08:00"))
                        .andReturn().getResponse().getStatus();
            }
            return postJson("/api/simulcast-locks", lockBody("rc-lk-" + ctx.prefix, lockKey,
                    ctx.channels, ctx.asset, DAY, AT))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        boolean overrideExists = mvc.perform(get("/api/emergency-overrides/" + overrideKey))
                .andReturn().getResponse().getStatus() == 200;
        boolean lockExists = getLock(lockKey).andReturn().getResponse().getStatus() == 200;
        // 提交顺序裁决：恰有一方生效，不会同时占用也不会双重拒绝
        assertThat(lockExists).isNotEqualTo(overrideExists);
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、联播素材、n 个频道及各自全天授权、各频道当日草稿（14:00-15:00 普通片段）。 */
    private final class Ctx {
        private final String prefix = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String fallbackAsset;
        private String asset;
        private final List<String> channels = new ArrayList<>();
        private final Map<String, Long> grants = new LinkedHashMap<>();

        private String key(String shortKey) {
            return prefix + shortKey;
        }
    }

    private Ctx newContext(int channelCount, String day) throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fb";
        ctx.asset = ctx.prefix + "asset";
        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.asset, "durationMs", 3600000))
                .andExpect(status().isOk());
        for (int i = 1; i <= channelCount; i++) {
            String channel = ctx.prefix + "ch" + i;
            postJson("/api/channels", Map.of("id", channel, "fallbackAssetId", ctx.fallbackAsset))
                    .andExpect(status().isOk());
            ctx.channels.add(channel);
            ctx.grants.put(channel, createGrant(channel, ctx.asset, dayStart(day), dayEnd(day)));
            replaceDraftRaw(channel, day, "draft-" + channel, 0,
                    List.of(segment(ctx.prefix + "seg" + i, ctx.asset, dayAt(day, 14), dayAt(day, 15))))
                    .andExpect(status().isOk());
        }
        return ctx;
    }

    private static String dayStart(String day) {
        return day + "T00:00:00.000+08:00";
    }

    private static String dayEnd(String day) {
        return LocalDate.parse(day).plusDays(1) + "T00:00:00.000+08:00";
    }

    private static String dayAt(String day, int hour) {
        return day + String.format("T%02d:00:00.000+08:00", hour);
    }

    private static String dayAt(String day, int hour, int minute) {
        return day + String.format("T%02d:%02d:00.000+08:00", hour, minute);
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset, "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private static Map<String, Object> segment(String id, String assetId, String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private ResultActions replaceDraftRaw(String channel, String day, String requestId,
                                          long expectedVersion, List<Map<String, Object>> segments)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        return mvc.perform(put("/api/channels/" + channel + "/drafts/" + day)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> lockBody(String requestId, String simulcastKey,
                                                List<String> channelIds, String assetId,
                                                String day, String at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("simulcastKey", simulcastKey);
        body.put("businessDay", day);
        body.put("channelIds", channelIds);
        body.put("assetId", assetId);
        body.put("at", at);
        return body;
    }

    private ResultActions createLock(String requestId, String simulcastKey, List<String> channelIds,
                                     String assetId, String day, String at) throws Exception {
        return postJson("/api/simulcast-locks",
                lockBody(requestId, simulcastKey, channelIds, assetId, day, at));
    }

    private ResultActions revokeLock(String simulcastKey, String requestId) throws Exception {
        return postJson("/api/simulcast-locks/" + simulcastKey + "/revoke",
                Map.of("requestId", requestId));
    }

    private ResultActions getLock(String simulcastKey) throws Exception {
        return mvc.perform(get("/api/simulcast-locks/" + simulcastKey));
    }

    private ResultActions placeholders(String channel, String day) throws Exception {
        var request = get("/api/channels/" + channel + "/simulcast-placeholders");
        if (day != null) {
            request = request.param("businessDay", day);
        }
        return mvc.perform(request);
    }

    private int placeholderCount(String channel, String day) throws Exception {
        return readJson(placeholders(channel, day).andExpect(status().isOk()).andReturn()).size();
    }

    private ResultActions playout(String channel, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/playout").param("at", at));
    }

    private static Map<String, Object> overrideBody(String requestId, String overrideKey,
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

    private static String placeholderId(JsonNode lockJson, String channel) {
        for (JsonNode p : lockJson.get("placeholders")) {
            if (p.get("channelId").asText().equals(channel)) {
                return p.get("placeholderSegmentId").asText();
            }
        }
        throw new AssertionError("联播响应缺少频道占位: " + channel);
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
