package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * 交叉淡化（混音草稿）端到端测试：合成样例、校验失败分支、授权撤销整组回退、
 * 紧急插播替代、幂等重放与并发发布。运行环境为 H2（MySQL 兼容模式）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossfadeApiTest {

    private static final String DAY = "2026-10-10";
    /** 片段基准时刻：2026-10-10T10:00:00+08:00，测试内以秒偏移表示。 */
    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final DateTimeFormatter RESP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 合成样例：A 20s、B 20s、淡化 4s，B 在第 16 秒开始，总时长 36s ----------

    @Test
    void crossfadeSynthesisAndGains() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        asset(p + "A", 20000);
        asset(p + "B", 20000);
        long grantA = grant(channel, p + "A");
        long grantB = grant(channel, p + "B");

        putJson(draftUrl(channel), draftBody("req-" + p + "-d1", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].start").value(respTime(0)))
                .andExpect(jsonPath("$.segments[1].start").value(respTime(16)))
                .andExpect(jsonPath("$.transitions[0].fromSegmentId").value(p + "sa"))
                .andExpect(jsonPath("$.transitions[0].toSegmentId").value(p + "sb"))
                .andExpect(jsonPath("$.transitions[0].durationMs").value(4000));
        publish(channel, "req-" + p + "-p1", 1, 0);

        // 非重叠区：仅前素材
        getJson("/api/channels/" + channel + "/playout", t(10))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "A"));

        // 重叠左端：u=0，前增益 1、后增益 0
        JsonNode left = readJson(getJson("/api/channels/" + channel + "/playout", t(16))
                .andExpect(status().isOk()).andReturn());
        assertThat(left.get("source").asText()).isEqualTo("MIX");
        assertThat(left.get("assetId").isNull()).isTrue();
        assertThat(left.get("mix").get("progress").asDouble()).isEqualTo(0.0);
        assertThat(left.get("mix").get("previous").get("gain").asDouble()).isEqualTo(1.0);
        assertThat(left.get("mix").get("next").get("gain").asDouble()).isEqualTo(0.0);

        // 第 18 秒：u=0.5，两增益各 0.5，随附两素材与各自发布授权
        JsonNode mid = readJson(getJson("/api/channels/" + channel + "/playout", t(18))
                .andExpect(status().isOk()).andReturn());
        assertThat(mid.get("source").asText()).isEqualTo("MIX");
        assertThat(mid.get("mix").get("progress").asDouble()).isEqualTo(0.5);
        JsonNode prev = mid.get("mix").get("previous");
        JsonNode next = mid.get("mix").get("next");
        assertThat(prev.get("segmentId").asText()).isEqualTo(p + "sa");
        assertThat(prev.get("assetId").asText()).isEqualTo(p + "A");
        assertThat(prev.get("grantId").asLong()).isEqualTo(grantA);
        assertThat(prev.get("gain").asDouble()).isEqualTo(0.5);
        assertThat(next.get("segmentId").asText()).isEqualTo(p + "sb");
        assertThat(next.get("assetId").asText()).isEqualTo(p + "B");
        assertThat(next.get("grantId").asLong()).isEqualTo(grantB);
        assertThat(next.get("gain").asDouble()).isEqualTo(0.5);

        // 第 19 秒：u=0.75
        JsonNode late = readJson(getJson("/api/channels/" + channel + "/playout", t(19))
                .andExpect(status().isOk()).andReturn());
        assertThat(late.get("mix").get("progress").asDouble()).isEqualTo(0.75);
        assertThat(late.get("mix").get("previous").get("gain").asDouble()).isEqualTo(0.25);
        assertThat(late.get("mix").get("next").get("gain").asDouble()).isEqualTo(0.75);

        // 右端（第 20 秒）起仅后素材
        getJson("/api/channels/" + channel + "/playout", t(20))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "B"));
        // 总时长 36 秒：第 36 秒起为空档保底
        getJson("/api/channels/" + channel + "/playout", t(35))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "B"));
        getJson("/api/channels/" + channel + "/playout", t(36))
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"));

        // 同一时刻的 UTC 表示（Asia/Shanghai 与 UTC 精确映射）：02:00:18Z == 10:00:18+08:00
        JsonNode utc = readJson(getJson("/api/channels/" + channel + "/playout",
                        "2026-10-10T02:00:18.000Z")
                .andExpect(status().isOk()).andReturn());
        assertThat(utc.get("source").asText()).isEqualTo("MIX");
        assertThat(utc.get("mix").get("progress").asDouble()).isEqualTo(0.5);
    }

    // ---------- 淡化校验失败分支 ----------

    @Test
    void crossfadeValidationFailures() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        asset(p + "A", 20000);
        asset(p + "B", 20000);
        asset(p + "C", 10000);
        asset(p + "D", 5000);
        grant(channel, p + "A");
        grant(channel, p + "B");
        grant(channel, p + "C");
        grant(channel, p + "D");

        // 422：淡化超过 10 秒
        putJson(draftUrl(channel), draftBody("req-" + p + "-f1", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 9, 29)),
                        List.of(transition(p + "sa", p + "sb", 11000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CROSSFADE_TOO_LONG"));

        // 422：淡化超过较短素材时长（D 仅 5 秒，淡化 6 秒）
        putJson(draftUrl(channel), draftBody("req-" + p + "-f2", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sd", p + "D", 14, 19)),
                        List.of(transition(p + "sa", p + "sd", 6000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CROSSFADE_TOO_LONG"));

        // 422：后一片段开始不等于前一片段结束减淡化时长
        putJson(draftUrl(channel), draftBody("req-" + p + "-f3", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 17, 37)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CROSSFADE_TIMING_MISMATCH"));

        // 422：三片段同时存在（A[0,20) B[18,28) 淡化2s，C[19,29) 淡化9s → 第19秒 A/B/C 重叠）
        putJson(draftUrl(channel), draftBody("req-" + p + "-f4", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sc1", p + "C", 18, 28),
                                segment(p + "sc2", p + "C", 19, 29)),
                        List.of(transition(p + "sa", p + "sc1", 2000),
                                transition(p + "sc1", p + "sc2", 9000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CROSSFADE_TRIPLE_OVERLAP"));

        // 422：重叠但未声明淡化（普通草稿保持原无重叠规则）
        putJson(draftUrl(channel), draftBody("req-" + p + "-f5", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_OVERLAP"));

        // 422：参与淡化的片段时长不等于素材登记时长（A 登记 20s，片段仅 15s）
        putJson(draftUrl(channel), draftBody("req-" + p + "-f6", 0,
                        List.of(segment(p + "sa", p + "A", 0, 15),
                                segment(p + "sb", p + "B", 11, 31)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_DURATION_MISMATCH"));

        // 422：淡化前后片段在时间上不相邻
        putJson(draftUrl(channel), draftBody("req-" + p + "-f7", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 20, 40),
                                segment(p + "sc", p + "C", 40, 50)),
                        List.of(transition(p + "sa", p + "sc", 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("TRANSITION_NOT_ADJACENT"));

        // 400：淡化引用草稿外的片段
        putJson(draftUrl(channel), draftBody("req-" + p + "-f8", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "ghost", 4000))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 授权不足与撤销：整组回退 ----------

    @Test
    void crossfadeGrantCoverageAndRevocation() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        asset(p + "A", 20000);
        asset(p + "B", 20000);
        long grantA = grant(channel, p + "A");

        // 422：B 的授权只覆盖非重叠部分（[20,36)），不覆盖完整实际播放区间 [16,36)
        postJson("/api/grants", Map.of("channelId", channel, "assetId", p + "B",
                        "validFrom", respTime(20), "validTo", respTime(36)))
                .andExpect(status().isOk());
        putJson(draftUrl(channel), draftBody("req-" + p + "-g1", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 授权覆盖完整区间后草稿与发布成功
        long grantB = grant(channel, p + "B");
        putJson(draftUrl(channel), draftBody("req-" + p + "-g2", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isOk());
        publish(channel, "req-" + p + "-p1", 1, 0);

        // 撤销后一素材授权：重叠区整组回退保底并说明原因，不能只播另一条
        postJson("/api/grants/" + grantB + "/revoke", Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        getJson("/api/channels/" + channel + "/playout", t(18))
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("CROSSFADE_GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"));
        // 非重叠区前素材授权仍有效：正常播出
        getJson("/api/channels/" + channel + "/playout", t(10))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "A"));

        // 撤销前一素材授权后再次发布：422 整次不发布
        postJson("/api/grants/" + grantA + "/revoke", Map.of("requestId", "req-" + p + "-rv2"))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));
    }

    // ---------- 紧急插播优先替代整组混音 ----------

    @Test
    void emergencyOverrideReplacesWholeMix() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        asset(p + "A", 20000);
        asset(p + "B", 20000);
        asset(p + "emg", 60000);
        grant(channel, p + "A");
        grant(channel, p + "B");
        long grantEmg = grant(channel, p + "emg");

        putJson(draftUrl(channel), draftBody("req-" + p + "-d1", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isOk());
        publish(channel, "req-" + p + "-p1", 1, 0);

        // 覆盖重叠区的紧急插播：替代整组混音
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-o1", "overrideKey", p + "ov1",
                        "channelId", channel, "assetId", p + "emg", "grantId", grantEmg,
                        "priority", 5, "start", t(15), "end", t(25)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getJson("/api/channels/" + channel + "/playout", t(18))
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(p + "emg"))
                .andExpect(jsonPath("$.overrideKey").value(p + "ov1"));

        // 取消后恢复混音决策
        postJson("/api/emergency-overrides/" + p + "ov1/cancel",
                        Map.of("requestId", "req-" + p + "-c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        getJson("/api/channels/" + channel + "/playout", t(18))
                .andExpect(jsonPath("$.source").value("MIX"));
    }

    // ---------- 幂等：重放、异参 409、失败不占键 ----------

    @Test
    void crossfadeDraftIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        asset(p + "A", 20000);
        asset(p + "B", 20000);
        grant(channel, p + "A");
        grant(channel, p + "B");

        Map<String, Object> body = draftBody("req-" + p + "-idem", 0,
                List.of(segment(p + "sa", p + "A", 0, 20),
                        segment(p + "sb", p + "B", 16, 36)),
                List.of(transition(p + "sa", p + "sb", 4000)));
        MvcResult first = putJson(draftUrl(channel), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        // 同键同参重放：返回首次完整响应（含系统生成语义一致），版本不重复递增
        MvcResult replay = putJson(draftUrl(channel), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键异参（改淡化时长）：409
        putJson(draftUrl(channel), draftBody("req-" + p + "-idem", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 3000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以非法淡化失败，再用同 requestId 成功
        putJson(draftUrl(channel), draftBody("req-" + p + "-retry", 1,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 11000))))
                .andExpect(status().isUnprocessableEntity());
        putJson(draftUrl(channel), draftBody("req-" + p + "-retry", 1,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 并发：同一发布版本仅一方成功 ----------

    @Test
    void concurrentPublishOfMixDraftOnlyOneWins() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        asset(p + "A", 20000);
        asset(p + "B", 20000);
        grant(channel, p + "A");
        grant(channel, p + "B");
        putJson(draftUrl(channel), draftBody("req-" + p + "-d1", 0,
                        List.of(segment(p + "sa", p + "A", 0, 20),
                                segment(p + "sb", p + "B", 16, 36)),
                        List.of(transition(p + "sa", p + "sb", 4000))))
                .andExpect(status().isOk());

        String url = "/api/channels/" + channel + "/drafts/" + DAY + "/publish";
        List<Integer> statuses = runConcurrently(2, i ->
                mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "requestId", "req-" + p + "-cp" + i,
                                        "draftVersion", 1,
                                        "expectedPublishedVersion", 0))))
                        .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 最终数据：只存在版本 1 的发布快照，播出决策为混音
        getJson("/api/channels/" + channel + "/playout", t(18))
                .andExpect(jsonPath("$.source").value("MIX"));
    }

    // ---------- 测试辅助 ----------

    private String newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        return p + "ch";
    }

    private void asset(String id, long durationMs) throws Exception {
        postJson("/api/assets", Map.of("id", id, "durationMs", durationMs))
                .andExpect(status().isOk());
    }

    /** 创建覆盖整个业务日的授权，返回授权 ID。 */
    private long grant(String channel, String assetId) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", assetId,
                        "validFrom", respTime(-36000), "validTo", respTime(50400)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private void publish(String channel, String requestId, long draftVersion,
                         long expectedPublishedVersion) throws Exception {
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", requestId, "draftVersion", draftVersion,
                                "expectedPublishedVersion", expectedPublishedVersion))
                .andExpect(status().isOk());
    }

    private static String draftUrl(String channel) {
        return "/api/channels/" + channel + "/drafts/" + DAY;
    }

    /** 基准时刻加秒偏移的 ISO 8601 请求时间。 */
    private static String t(long seconds) {
        return BASE.plusSeconds(seconds).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 基准时刻加秒偏移的响应时间格式（毫秒精度）。 */
    private static String respTime(long seconds) {
        return BASE.plusSeconds(seconds).format(RESP_FORMAT);
    }

    private static Map<String, Object> segment(String id, String assetId,
                                               long startSec, long endSec) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", t(startSec));
        segment.put("end", t(endSec));
        return segment;
    }

    private static Map<String, Object> transition(String fromSegmentId, String toSegmentId,
                                                  long fadeMs) {
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("fromSegmentId", fromSegmentId);
        transition.put("toSegmentId", toSegmentId);
        transition.put("durationMs", fadeMs);
        return transition;
    }

    private static Map<String, Object> draftBody(String requestId, long expectedVersion,
                                                 List<Map<String, Object>> segments,
                                                 List<Map<String, Object>> transitions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        if (transitions != null) {
            body.put("transitions", transitions);
        }
        return body;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getJson(String url, String at) throws Exception {
        return mvc.perform(get(url).param("at", at));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String prefix() {
        return "x" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
    }

    private interface ThrowingSupplier {
        Integer get(int index) throws Exception;
    }

    private static List<Integer> runConcurrently(int threads, ThrowingSupplier action)
            throws Exception {
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
