package com.example.starter.baggage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.PlacementRequest;
import com.example.starter.baggage.BaggageDtos.RearrangeRequest;
import com.example.starter.baggage.BaggageDtos.RearrangeResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 整批重排测试：合成 10x10 舱底两件 5x10 行李并排，覆盖整批交换合法、
 * 单件搬入仍占用位置 422 且全部不变、版本共享、操作者非空、幂等重放与并发竞争。
 * 全部基于 H2 MySQL 兼容模式真实数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageRearrangeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rearrange_swapBothPositionsIsLegal() throws Exception {
        prepareTwoBagsSideBySide();

        // 整批交换左右位置：先移除两件旧位置再统一检查，合法
        rearrange("LEG1", "op-1", 2,
                List.of(placement("BAG1", 5, 0, 0), placement("BAG2", 0, 0, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.rearranged", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.placements[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.placements[0].xMm").value(5))
                .andExpect(jsonPath("$.placements[1].bagTag").value("BAG2"))
                .andExpect(jsonPath("$.placements[1].xMm").value(0));

        assertThat(positionOf("BAG1")).isEqualTo(5);
        assertThat(positionOf("BAG2")).isEqualTo(0);

        // 封舱冻结的是重排后的位置
        seal("LEG1", 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.placements[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.placements[0].xMm").value(5))
                .andExpect(jsonPath("$.placements[1].bagTag").value("BAG2"))
                .andExpect(jsonPath("$.placements[1].xMm").value(0));
    }

    @Test
    void rearrange_singleMoveToOccupiedPositionRejectedAndAllUnchanged() throws Exception {
        prepareTwoBagsSideBySide();

        // 单独把右件搬到仍被左件占用的 (0,0)：422，两件位置与航段版本均不变
        rearrange("LEG1", "op-1", 2, List.of(placement("BAG2", 0, 0, 0)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(positionOf("BAG1")).isEqualTo(0);
        assertThat(positionOf("BAG2")).isEqualTo(5);
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void rearrange_rotationAndSelfOverlapChecks() throws Exception {
        prepareTwoBagsSideBySide();

        // 旋转 90 度后 BAG1 占 10x5，与仍位于 (5,0) 的 BAG2 重叠 -> 422
        rearrange("LEG1", "op-1", 2, List.of(placement("BAG1", 0, 0, 90)))
                .andExpect(status().isUnprocessableEntity());
        // 越界 -> 422
        rearrange("LEG1", "op-1", 2, List.of(placement("BAG1", 6, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 两件同时旋转 90 度上下叠放合法
        rearrange("LEG1", "op-1", 2,
                List.of(placement("BAG1", 0, 0, 90), placement("BAG2", 0, 5, 90)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        assertThat(rotationOf("BAG1")).isEqualTo(90);
        assertThat(rotationOf("BAG2")).isEqualTo(90);
    }
    // __MORE_TESTS__

    /** 合成场景：10x10 舱底，两件 5x10 行李左右并排装载于 (0,0) 与 (5,0)，航段版本 2。 */
    private void prepareTwoBagsSideBySide() throws Exception {
        postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", "LEG1", "origin", "PEK", "destination", "SHA",
                "floorLengthMm", 10, "floorWidthMm", 10)).andExpect(status().isCreated());
        for (String bagTag : List.of("BAG1", "BAG2")) {
            postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                    "bagTag", bagTag, "legIds", List.of("LEG1"),
                    "lengthMm", 5, "widthMm", 10)).andExpect(status().isCreated());
        }
        Map<String, Object> loadBody = new LinkedHashMap<>();
        loadBody.put("requestId", UUID.randomUUID().toString());
        loadBody.put("expectedVersion", 1);
        loadBody.put("bagTags", List.of("BAG1", "BAG2"));
        loadBody.put("placements", List.of(
                Map.of("bagTag", "BAG1", "xMm", 0, "yMm", 0, "rotation", 0),
                Map.of("bagTag", "BAG2", "xMm", 5, "yMm", 0, "rotation", 0)));
        postJson("/api/legs/LEG1/load", loadBody).andExpect(status().isOk());
    }

    private Map<String, Object> placement(String bagTag, int xMm, int yMm, int rotation) {
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("bagTag", bagTag);
        placement.put("xMm", xMm);
        placement.put("yMm", yMm);
        placement.put("rotation", rotation);
        return placement;
    }

    private ResultActions rearrange(String legId, String operator, int expectedVersion,
                                    List<Map<String, Object>> placements) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        if (operator != null) {
            body.put("operator", operator);
        }
        body.put("expectedVersion", expectedVersion);
        body.put("placements", placements);
        return postJson("/api/legs/" + legId + "/rearrange", body);
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private Integer positionOf(String bagTag) {
        return jdbcTemplate.queryForObject(
                "SELECT x_mm FROM load_record WHERE bag_tag = ?", Integer.class, bagTag);
    }

    private Integer rotationOf(String bagTag) {
        return jdbcTemplate.queryForObject(
                "SELECT rotation FROM load_record WHERE bag_tag = ?", Integer.class, bagTag);
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
    @Test
    void rearrange_validationAndStateErrors() throws Exception {
        prepareTwoBagsSideBySide();

        // 操作者缺失或空白 -> 400
        rearrange("LEG1", null, 2, List.of(placement("BAG1", 5, 0, 0)))
                .andExpect(status().isBadRequest());
        rearrange("LEG1", "  ", 2, List.of(placement("BAG1", 5, 0, 0)))
                .andExpect(status().isBadRequest());
        // 数量越界 -> 400
        rearrange("LEG1", "op-1", 2, List.of()).andExpect(status().isBadRequest());
        List<Map<String, Object>> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(placement("BAG_X" + i, 0, 0, 0));
        }
        rearrange("LEG1", "op-1", 2, eleven).andExpect(status().isBadRequest());
        // 重排袋号重复 -> 422
        rearrange("LEG1", "op-1", 2,
                List.of(placement("BAG1", 5, 0, 0), placement("BAG1", 0, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 行李未装载到本航段 -> 422
        rearrange("LEG1", "op-1", 2, List.of(placement("BAG_UNKNOWN", 0, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        // 版本冲突 -> 409
        rearrange("LEG1", "op-1", 99, List.of(placement("BAG1", 5, 0, 0)))
                .andExpect(status().isConflict());
        // 航段不存在 -> 404
        rearrange("LEG_MISSING", "op-1", 1, List.of(placement("BAG1", 5, 0, 0)))
                .andExpect(status().isNotFound());

        // 无布局旧航段禁止重排
        postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", "LEG_LEGACY", "origin", "PEK", "destination", "SHA"))
                .andExpect(status().isCreated());
        rearrange("LEG_LEGACY", "op-1", 1, List.of(placement("BAG1", 0, 0, 0)))
                .andExpect(status().isUnprocessableEntity());

        // 封舱后禁止重排（版本继续推进后仍是 422）
        seal("LEG1", 2).andExpect(status().isOk());
        rearrange("LEG1", "op-1", 3, List.of(placement("BAG1", 5, 0, 0)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(positionOf("BAG1")).isEqualTo(0);
    }

    @Test
    void rearrange_idempotencyReplayConflictAndFailureNotOccupyingKey() throws Exception {
        prepareTwoBagsSideBySide();

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "op-1");
        body.put("expectedVersion", 2);
        body.put("placements", List.of(
                Map.of("bagTag", "BAG1", "xMm", 5, "yMm", 0, "rotation", 0),
                Map.of("bagTag", "BAG2", "xMm", 0, "yMm", 0, "rotation", 0)));
        // 同参重放：返回首次完整结果，版本只推进一次
        postJson("/api/legs/LEG1/rearrange", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        postJson("/api/legs/LEG1/rearrange", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(3));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);

        // 同键改参 -> 409
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put("placements", List.of(
                Map.of("bagTag", "BAG1", "xMm", 0, "yMm", 0, "rotation", 0),
                Map.of("bagTag", "BAG2", "xMm", 5, "yMm", 0, "rotation", 0)));
        postJson("/api/legs/LEG1/rearrange", changed).andExpect(status().isConflict());

        // 失败不占键：先以错误版本失败，再用同键正确参数成功（整批交换回原位，合法）
        String retryKey = UUID.randomUUID().toString();
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("requestId", retryKey);
        retry.put("operator", "op-2");
        retry.put("expectedVersion", 99);
        retry.put("placements", List.of(
                Map.of("bagTag", "BAG1", "xMm", 0, "yMm", 0, "rotation", 0),
                Map.of("bagTag", "BAG2", "xMm", 5, "yMm", 0, "rotation", 0)));
        postJson("/api/legs/LEG1/rearrange", retry).andExpect(status().isConflict());
        retry.put("expectedVersion", 3);
        postJson("/api/legs/LEG1/rearrange", retry).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        assertThat(positionOf("BAG1")).isEqualTo(0);
        assertThat(positionOf("BAG2")).isEqualTo(5);
    }

    @Test
    void rearrange_concurrentWithSeal_versionDecidesSingleWinner() throws Exception {
        prepareTwoBagsSideBySide();

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.rearrange("LEG1", new RearrangeRequest(
                        UUID.randomUUID().toString(), "op-1", 2,
                        List.of(new PlacementRequest("BAG1", 5, 0, 0),
                                new PlacementRequest("BAG2", 0, 0, 0)))),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof RearrangeResponse || r instanceof SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        if ("OPEN".equals(status)) {
            // 重排先成功：封舱可随后完成，冻结的是交换后的位置
            SealResponse sealed = baggageService.seal("LEG1",
                    new SealRequest(UUID.randomUUID().toString(), 3));
            assertThat(sealed.manifest()).containsExactly("BAG1", "BAG2");
            assertThat(positionOf("BAG1")).isEqualTo(5);
            assertThat(positionOf("BAG2")).isEqualTo(0);
        } else {
            // 封舱先成功：冻结原位置，重排失败者位置不变
            assertThat(status).isEqualTo("SEALED");
            assertThat(positionOf("BAG1")).isEqualTo(0);
            assertThat(positionOf("BAG2")).isEqualTo(5);
        }
    }

    @Test
    void rearrange_concurrentSameRequestId_replaysSingleEffect() throws Exception {
        prepareTwoBagsSideBySide();

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.rearrange("LEG1", new RearrangeRequest(
                    requestId, "op-1", 2,
                    List.of(new PlacementRequest("BAG1", 5, 0, 0),
                            new PlacementRequest("BAG2", 0, 0, 0)))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(RearrangeResponse.class);
            assertThat(((RearrangeResponse) result).version()).isEqualTo(3);
        });
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
        assertThat(positionOf("BAG1")).isEqualTo(5);
        assertThat(positionOf("BAG2")).isEqualTo(0);
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（异常包装为返回值）。 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
