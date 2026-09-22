package com.example.starter.baggage;

import com.example.starter.baggage.api.dto.ArrivalRequest;
import com.example.starter.baggage.api.dto.LoadRequest;
import com.example.starter.baggage.api.dto.LoadResult;
import com.example.starter.baggage.api.dto.ManifestView;
import com.example.starter.baggage.api.dto.RegisterBagRequest;
import com.example.starter.baggage.api.dto.RegisterLegRequest;
import com.example.starter.baggage.api.dto.SealRequest;
import com.example.starter.baggage.error.BusinessException;
import com.example.starter.baggage.service.BaggageGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 联程行李装载交接 API 测试：主流程、失败分支、幂等与并发边界。
 * 使用独立 H2 内存库（baggage_test），每个用例前清空全部表。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BaggageGateway gateway;

    private final AtomicInteger requestSeq = new AtomicInteger();

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM load_entry");
        jdbc.update("DELETE FROM manifest_entry");
        jdbc.update("DELETE FROM bag_itinerary");
        jdbc.update("DELETE FROM bag");
        jdbc.update("DELETE FROM leg");
    }

    // ---------- 主流程 ----------

    @Test
    void fullJourneyDeliversBagAndTracesSegments() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerLeg("LEG2", "S2", "S3");
        registerBag("BAG1", List.of("LEG1", "LEG2"));

        // 首段装载 -> 封舱 -> 到达
        load("LEG1", 0, List.of("BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.loadedCount").value(1));
        seal("LEG1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.bagTags[0]").value("BAG1"));
        arrive("LEG1", List.of("BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.version").value(3));

        // 行李被置于到达站并推进待乘索引
        mockMvc.perform(get("/api/bags/BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.currentStation").value("S2"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 第二段完成全部行程 -> DELIVERED
        load("LEG2", 0, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG2", 1).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG1")).andExpect(status().isOk());

        mockMvc.perform(get("/api/bags/BAG1"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentStation").value("S3"))
                .andExpect(jsonPath("$.nextLegIndex").value(2));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].legId").value("LEG1"))
                .andExpect(jsonPath("$.segments[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.segments[1].legId").value("LEG2"))
                .andExpect(jsonPath("$.segments[1].state").value("COMPLETED"));

        // 封舱清单在到达后仍可查询（只读）
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.bagTags[0]").value("BAG1"));
    }

    @Test
    void emptyManifestCanBeSealedAndArrived() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        seal("LEG1", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.bagTags.length()").value(0));
        arrive("LEG1", List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    // ---------- 登记失败分支 ----------

    @Test
    void duplicateLegOrBagRegistrationRejected() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        postJson("/api/legs", new RegisterLegRequest(nextRequestId(), "LEG1", "S1", "S2"))
                .andExpect(status().isUnprocessableEntity());

        registerBag("BAG1", List.of("LEG1"));
        postJson("/api/bags", new RegisterBagRequest(nextRequestId(), "BAG1", List.of("LEG1")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void bagRegistrationRequiresConnectedDistinctLegs() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerLeg("LEG2", "S3", "S4");

        // 相邻航段首尾站不衔接
        postJson("/api/bags", new RegisterBagRequest(nextRequestId(), "BAG1", List.of("LEG1", "LEG2")))
                .andExpect(status().isUnprocessableEntity());
        // 行程航段重复
        postJson("/api/bags", new RegisterBagRequest(nextRequestId(), "BAG2", List.of("LEG1", "LEG1")))
                .andExpect(status().isUnprocessableEntity());
        // 引用不存在的航段
        postJson("/api/bags", new RegisterBagRequest(nextRequestId(), "BAG3", List.of("LEG1", "LEG_X")))
                .andExpect(status().isNotFound());
        // 行程为空或超过 5 段 -> 400
        postJson("/api/bags", new RegisterBagRequest(nextRequestId(), "BAG4", List.of()))
                .andExpect(status().isBadRequest());
        postJson("/api/bags", new RegisterBagRequest(nextRequestId(), "BAG5",
                List.of("A", "B", "C", "D", "E", "F")))
                .andExpect(status().isBadRequest());

        assertThat(countRows("bag")).isZero();
    }

    // ---------- 装载失败分支 ----------

    @Test
    void loadRejectsVersionMismatchAndNonOpenLeg() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));

        load("LEG1", 5, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        seal("LEG1", 0).andExpect(status().isOk());
        // 封舱后不得加装
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        assertThat(countRows("load_entry")).isZero();
        assertThat(bagLoadedLeg("BAG1")).isNull();
    }

    @Test
    void loadRejectsUnknownDuplicateAndForeignBags() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerLeg("LEG2", "S2", "S3");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        registerBag("BAG2", List.of("LEG2"));

        // 批内牌号重复
        load("LEG1", 0, List.of("BAG1", "BAG1")).andExpect(status().isUnprocessableEntity());
        // 行李不存在
        load("LEG1", 0, List.of("BAG_X")).andExpect(status().isUnprocessableEntity());
        // 航段非行李当前待乘航段（BAG2 首段为 LEG2）
        load("LEG1", 0, List.of("BAG2")).andExpect(status().isUnprocessableEntity());
        // 数量边界：空批与超 20 件 -> 400
        load("LEG1", 0, List.of()).andExpect(status().isBadRequest());
        load("LEG1", 0, manyTags(21)).andExpect(status().isBadRequest());

        assertThat(countRows("load_entry")).isZero();
        assertThat(legVersion("LEG1")).isZero();
    }

    @Test
    void loadBatchIsAtomicWhenAnyBagFails() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));

        // 批内一件不存在 -> 整批 422，无一件移动
        load("LEG1", 0, List.of("BAG1", "BAG_X")).andExpect(status().isUnprocessableEntity());

        assertThat(countRows("load_entry")).isZero();
        assertThat(bagLoadedLeg("BAG1")).isNull();
        assertThat(legVersion("LEG1")).isZero();
    }

    @Test
    void loadRejectsBagNotAtOriginAndAlreadyLoaded() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));

        // 行李不在始发站（通过直接改库构造防御性校验场景）
        jdbc.update("UPDATE bag SET current_station = 'ELSEWHERE' WHERE bag_tag = 'BAG1'");
        load("LEG1", 0, List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        jdbc.update("UPDATE bag SET current_station = 'S1' WHERE bag_tag = 'BAG1'");

        load("LEG1", 0, List.of("BAG1")).andExpect(status().isOk());
        // 已装载行李不得再次装载（版本已推进到 1）
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        assertThat(countRows("load_entry")).isEqualTo(1);
    }

    // ---------- 封舱 / 到达失败分支 ----------

    @Test
    void sealRejectsVersionMismatchAndRepeatedSeal() throws Exception {
        registerLeg("LEG1", "S1", "S2");

        seal("LEG1", 3).andExpect(status().isUnprocessableEntity());
        seal("LEG1", 0).andExpect(status().isOk());
        seal("LEG1", 1).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void arrivalRequiresExactManifestMatchOrNothingChanges() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        load("LEG1", 0, List.of("BAG1", "BAG2")).andExpect(status().isOk());
        seal("LEG1", 1).andExpect(status().isOk());

        // 少一件
        arrive("LEG1", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 多一件
        arrive("LEG1", List.of("BAG1", "BAG2", "BAG3")).andExpect(status().isUnprocessableEntity());
        // 袋号不同
        arrive("LEG1", List.of("BAG1", "BAG3")).andExpect(status().isUnprocessableEntity());
        // 集合内重复
        arrive("LEG1", List.of("BAG1", "BAG1")).andExpect(status().isUnprocessableEntity());

        // 航段与全部行李保持不变
        assertThat(legStatus("LEG1")).isEqualTo("SEALED");
        assertThat(legVersion("LEG1")).isEqualTo(2);
        assertThat(bagLoadedLeg("BAG1")).isEqualTo("LEG1");
        assertThat(bagLoadedLeg("BAG2")).isEqualTo("LEG1");
        assertThat(bagStation("BAG1")).isEqualTo("S1");

        // 完全匹配（顺序无关）-> 原子到达
        arrive("LEG1", List.of("BAG2", "BAG1")).andExpect(status().isOk());
        assertThat(legStatus("LEG1")).isEqualTo("ARRIVED");
        assertThat(bagStation("BAG1")).isEqualTo("S2");
        assertThat(bagStation("BAG2")).isEqualTo("S2");
    }

    @Test
    void arrivalRejectedOnOpenLeg() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        arrive("LEG1", List.of()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownResourcesReturn404() throws Exception {
        mockMvc.perform(get("/api/legs/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/NOPE/trace")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/NOPE/manifest")).andExpect(status().isNotFound());
        load("NOPE", 0, List.of("BAG1")).andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysOriginalSuccess() throws Exception {
        String requestId = nextRequestId();
        MvcResult first = postJson("/api/legs", new RegisterLegRequest(requestId, "LEG1", "S1", "S2"))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = postJson("/api/legs", new RegisterLegRequest(requestId, "LEG1", "S1", "S2"))
                .andExpect(status().isOk()).andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(countRows("leg")).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        String requestId = nextRequestId();
        postJson("/api/legs", new RegisterLegRequest(requestId, "LEG1", "S1", "S2"))
                .andExpect(status().isOk());
        postJson("/api/legs", new RegisterLegRequest(requestId, "LEG2", "S1", "S2"))
                .andExpect(status().isConflict());
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));

        String requestId = nextRequestId();
        // 版本不匹配 -> 422，失败不占键
        load("LEG1", 9, List.of("BAG1"), requestId).andExpect(status().isUnprocessableEntity());
        // 同一 requestId 修正参数后成功
        load("LEG1", 0, List.of("BAG1"), requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void replayedLoadDoesNotBumpVersionTwice() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));

        String requestId = nextRequestId();
        load("LEG1", 0, List.of("BAG1"), requestId).andExpect(status().isOk());
        load("LEG1", 0, List.of("BAG1"), requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        assertThat(legVersion("LEG1")).isEqualTo(1);
        assertThat(countRows("load_entry")).isEqualTo(1);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentLoadsWithSameVersionOnlyOneSucceeds() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        registerBag("BAG3", List.of("LEG1"));
        registerBag("BAG4", List.of("LEG1"));

        List<Outcome> outcomes = runConcurrently(
                () -> gateway.load("LEG1", new LoadRequest(nextRequestId(), 0, List.of("BAG1", "BAG2"))),
                () -> gateway.load("LEG1", new LoadRequest(nextRequestId(), 0, List.of("BAG3", "BAG4"))));

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.success()).count()).isEqualTo(1);
        // 版本只推进一次，装载明细恰为胜者的一批
        assertThat(legVersion("LEG1")).isEqualTo(1);
        assertThat(countRows("load_entry")).isEqualTo(2);
    }

    @Test
    void concurrentSameBagNeverEntersTwoManifests() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));

        List<Outcome> outcomes = runConcurrently(
                () -> gateway.load("LEG1", new LoadRequest(nextRequestId(), 0, List.of("BAG1"))),
                () -> gateway.load("LEG1", new LoadRequest(nextRequestId(), 0, List.of("BAG1", "BAG2"))));

        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(countRowsWhere("load_entry", "bag_tag = 'BAG1'")).isEqualTo(1);
        assertThat(bagLoadedLeg("BAG1")).isEqualTo("LEG1");
    }

    @Test
    void concurrentLoadAndSealResolvedByVersion() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));

        List<Outcome> outcomes = runConcurrently(
                () -> gateway.load("LEG1", new LoadRequest(nextRequestId(), 0, List.of("BAG1"))),
                () -> gateway.seal("LEG1", new SealRequest(nextRequestId(), 0)));

        // 版本决定唯一先后：恰有一个成功
        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(legVersion("LEG1")).isEqualTo(1);

        if (legStatus("LEG1").equals("SEALED")) {
            // 封舱先成功：清单为空，行李未装载
            assertThat(countRows("manifest_entry")).isZero();
            assertThat(bagLoadedLeg("BAG1")).isNull();
        } else {
            // 装载先成功：封舱可基于新版本继续，清单包含该行李
            assertThat(legStatus("LEG1")).isEqualTo("OPEN");
            seal("LEG1", 1).andExpect(status().isOk())
                    .andExpect(jsonPath("$.bagTags[0]").value("BAG1"));
        }
    }

    @Test
    void concurrentSameRequestIdCommitsOnce() throws Exception {
        registerLeg("LEG1", "S1", "S2");
        registerBag("BAG1", List.of("LEG1"));

        String requestId = nextRequestId();
        List<Outcome> outcomes = runConcurrently(
                () -> gateway.load("LEG1", new LoadRequest(requestId, 0, List.of("BAG1"))),
                () -> gateway.load("LEG1", new LoadRequest(requestId, 0, List.of("BAG1"))));

        // 两个并发同键请求都拿到成功结果，但业务只生效一次
        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(2);
        assertThat(legVersion("LEG1")).isEqualTo(1);
        assertThat(countRows("load_entry")).isEqualTo(1);
        assertThat(countRowsWhere("request_log", "request_id = '" + requestId + "'")).isEqualTo(1);
    }

    // ---------- 测试辅助 ----------

    private String nextRequestId() {
        return "req-" + System.nanoTime() + "-" + requestSeq.incrementAndGet();
    }

    private void registerLeg(String legId, String origin, String destination) throws Exception {
        postJson("/api/legs", new RegisterLegRequest(nextRequestId(), legId, origin, destination))
                .andExpect(status().isOk());
    }

    private void registerBag(String bagTag, List<String> legIds) throws Exception {
        postJson("/api/bags", new RegisterBagRequest(nextRequestId(), bagTag, legIds))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions load(String legId, int version, List<String> tags)
            throws Exception {
        return load(legId, version, tags, nextRequestId());
    }

    private org.springframework.test.web.servlet.ResultActions load(String legId, int version, List<String> tags,
                                                                    String requestId) throws Exception {
        return postJson("/api/legs/" + legId + "/loads", new LoadRequest(requestId, version, tags));
    }

    private org.springframework.test.web.servlet.ResultActions seal(String legId, int version) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", new SealRequest(nextRequestId(), version));
    }

    private org.springframework.test.web.servlet.ResultActions arrive(String legId, List<String> tags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrival", new ArrivalRequest(nextRequestId(), tags));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countRowsWhere(String table, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
    }

    private int legVersion(String legId) {
        return jdbc.queryForObject("SELECT version FROM leg WHERE leg_id = ?", Integer.class, legId);
    }

    private String legStatus(String legId) {
        return jdbc.queryForObject("SELECT status FROM leg WHERE leg_id = ?", String.class, legId);
    }

    private String bagLoadedLeg(String bagTag) {
        return jdbc.queryForObject("SELECT loaded_leg_id FROM bag WHERE bag_tag = ?", String.class, bagTag);
    }

    private String bagStation(String bagTag) {
        return jdbc.queryForObject("SELECT current_station FROM bag WHERE bag_tag = ?", String.class, bagTag);
    }

    private List<String> manyTags(int count) {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tags.add("TAG" + i);
        }
        return tags;
    }

    /**
     * 并发执行两个动作，使用闩锁确保同时起跑，带超时防止悬挂。
     */
    private List<Outcome> runConcurrently(ThrowingAction first, ThrowingAction second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = List.of(
                    executor.submit(() -> runAction(first, ready, start)),
                    executor.submit(() -> runAction(second, ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome runAction(ThrowingAction action, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            action.run();
            return Outcome.ok();
        } catch (BusinessException e) {
            return Outcome.business(e.getMessage());
        } catch (Exception e) {
            return Outcome.error(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record Outcome(boolean success, String message, Throwable error) {
        static Outcome ok() {
            return new Outcome(true, null, null);
        }

        static Outcome business(String message) {
            return new Outcome(false, message, null);
        }

        static Outcome error(Throwable error) {
            return new Outcome(false, null, error);
        }
    }
}
