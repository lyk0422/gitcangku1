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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲法区组扩容：配比校验、权限先于幂等、扩容幂等与全局键、席位顺序连续（不回填退组席位）、
 * 版本/上限/CLOSED 冲突、容量与历史查询不泄露盲底、关闭竞争与并发登记/扩容裁决。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BlockExtensionTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        if (actor != null) {
            h.set("X-Actor-Id", actor);
        }
        if (role != null) {
            h.set("X-Role", role);
        }
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

    private void createExperiment(String id, int blockCount, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private void registerOk(String expId, String pid, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    /** 单个标准 AABB 区组。 */
    private String oneBlockBody(String extensionKey, int expectedVersion) {
        return "{\"extensionKey\":\"" + extensionKey + "\",\"expectedVersion\":"
                + expectedVersion + ",\"blocks\":[{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}";
    }

    // ---------------- 主流程与席位连续性 ----------------

    @Test
    void extendAfterFull_nextRegistrationTakesFirstSeatOfNewBlock_notWithdrawnSeat()
            throws Exception {
        createExperiment("EXT-1", 2, "ext1-create");
        for (int i = 1; i <= 8; i++) {
            registerOk("EXT-1", "P" + i, "ext1-alloc-" + i);
        }
        // 退组 P1（第1区组第1席），席位保留
        assertEquals(200, exchange("/api/experiments/EXT-1/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "ext1-withdraw"), null)
                .getStatusCode().value());
        // 满额：P9 扩容前 422
        assertEquals(422, exchange(
                "/api/experiments/EXT-1/participants/P9/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext1-alloc-9-full"), null).getStatusCode().value());

        // 扩容 1 个区组
        ResponseEntity<String> ext = exchange("/api/experiments/EXT-1/block-extensions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "ext1-extend"),
                oneBlockBody("ext1-key", 1));
        assertEquals(201, ext.getStatusCode().value());
        JsonNode extBody = json(ext);
        assertEquals("EXT-1", extBody.path("experimentId").asText());
        assertEquals("ext1-key", extBody.path("extensionKey").asText());
        assertEquals(1, extBody.path("expectedVersion").asInt());
        assertEquals(1, extBody.path("fromVersion").asInt());
        assertEquals(2, extBody.path("version").asInt());
        assertEquals(1, extBody.path("addedBlockCount").asInt());
        assertEquals(3, extBody.path("firstBlockNo").asInt());
        assertEquals(3, extBody.path("lastBlockNo").asInt());
        assertEquals(3, extBody.path("blockCount").asInt());
        assertEquals(4, extBody.path("seatsPerBlock").asInt());
        assertEquals(12, extBody.path("totalSeats").asInt());
        assertEquals("OPEN", extBody.path("status").asText());
        assertFalse(extBody.has("treatment"), "扩容响应不得暴露处理代码");
        assertFalse(extBody.has("seatNo"), "扩容响应不得暴露席位序号");

        // 库内新区组：编号连续，按提交顺序两个 A 两个 B；原有席位不改写
        List<String> newTreatments = jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXT-1' AND block_no = 3 "
                        + "ORDER BY seat_no", String.class);
        assertEquals(List.of("A", "A", "B", "B"), newTreatments);
        assertEquals(12, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-1'", Integer.class));
        // 已有分配不改写：P1 仍在第1区组且已退组
        assertEquals("WITHDRAWN", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'EXT-1' "
                        + "AND participant_id = 'P1'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE experiment_id = 'EXT-1' "
                        + "AND participant_id = 'P1'", Integer.class));

        // 扩容前 422 的参与者现在成功，且落在新区组首个空位（3区组1席），不回填退组席位
        ResponseEntity<String> p9 = exchange(
                "/api/experiments/EXT-1/participants/P9/allocations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext1-alloc-9"), null);
        assertEquals(201, p9.getStatusCode().value());
        JsonNode p9Body = json(p9);
        assertEquals(3, p9Body.path("blockNo").asInt(), "应领取新区组首个空位");
        assertFalse(p9Body.has("treatment"));
        assertFalse(p9Body.has("seatNo"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'EXT-1' "
                        + "AND participant_id = 'P9'", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE experiment_id = 'EXT-1' "
                        + "AND participant_id = 'P9'", Integer.class));

        // 后续登记继续按区组、席位顺序领取（3区组第2席）
        registerOk("EXT-1", "P10", "ext1-alloc-10");
        assertEquals(2, jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'EXT-1' "
                        + "AND participant_id = 'P10'", Integer.class));
        // 退组席位仍空：P1 的席位没有被任何新分配占用
        Integer occupantsOfSeat11 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXT-1' "
                        + "AND block_no = 1 AND seat_no = 1 AND participant_id <> 'P1'",
                Integer.class);
        assertEquals(0, occupantsOfSeat11);
    }

    @Test
    void extendMultipleBlocks_continuousNumberingAndVersionIncrement() throws Exception {
        createExperiment("EXT-2", 2, "ext2-create");
        String body = "{\"extensionKey\":\"ext2-key\",\"expectedVersion\":1,\"blocks\":["
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"B\",\"A\",\"B\"]},"
                + "{\"treatments\":[\"B\",\"B\",\"A\",\"A\"]}]}";
        ResponseEntity<String> ext = exchange("/api/experiments/EXT-2/block-extensions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "ext2-extend"), body);
        assertEquals(201, ext.getStatusCode().value());
        JsonNode extBody = json(ext);
        assertEquals(3, extBody.path("addedBlockCount").asInt());
        assertEquals(3, extBody.path("firstBlockNo").asInt());
        assertEquals(5, extBody.path("lastBlockNo").asInt());
        assertEquals(5, extBody.path("blockCount").asInt());
        assertEquals(2, extBody.path("version").asInt());
        assertEquals(20, extBody.path("totalSeats").asInt());

        // 新区组按提交顺序保留各自配比内容（顺序属于参数）
        assertEquals(List.of("A", "B", "A", "B"), jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXT-2' AND block_no = 4 "
                        + "ORDER BY seat_no", String.class));
        assertEquals(List.of("B", "B", "A", "A"), jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXT-2' AND block_no = 5 "
                        + "ORDER BY seat_no", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-2'", Integer.class));
        assertEquals(5, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-2'", Integer.class));
    }

    // ---------------- 配比与上限 422 ----------------

    @Test
    void invalidRatioOrSeatCount_wholeRequest422_andNothingPersisted() {
        createExperiment("EXT-3", 2, "ext3-create");
        String[][] badBlocks = {
                {"A", "A", "A", "B"},              // 三个 A
                {"A", "B", "B", "B"},              // 三个 B
                {"A", "A", "B", "C"},              // 非法处理代码
        };
        String[] reqIds = {"ext3-bad-1", "ext3-bad-2", "ext3-bad-3"};
        for (int i = 0; i < badBlocks.length; i++) {
            String body = "{\"extensionKey\":\"ext3-key-" + i + "\",\"expectedVersion\":1,"
                    + "\"blocks\":[{\"treatments\":[\"" + String.join("\",\"", badBlocks[i])
                    + "\"]}]}";
            ResponseEntity<String> resp = exchange(
                    "/api/experiments/EXT-3/block-extensions", HttpMethod.POST,
                    headers("c1", "COORDINATOR", reqIds[i]), body);
            assertEquals(422, resp.getStatusCode().value(), "配比不符整次 422");
        }

        // 席位数不是 4：422
        String shortBody = "{\"extensionKey\":\"ext3-key-short\",\"expectedVersion\":1,"
                + "\"blocks\":[{\"treatments\":[\"A\",\"A\",\"B\"]}]}";
        assertEquals(422, exchange("/api/experiments/EXT-3/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext3-bad-short"), shortBody).getStatusCode().value());
        String longBody = "{\"extensionKey\":\"ext3-key-long\",\"expectedVersion\":1,"
                + "\"blocks\":[{\"treatments\":[\"A\",\"A\",\"B\",\"B\",\"A\"]}]}";
        assertEquals(422, exchange("/api/experiments/EXT-3/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext3-bad-long"), longBody).getStatusCode().value());

        // 多个区组中任一配比不符：整次 422，不追加任何席位/版本
        String mixedBody = "{\"extensionKey\":\"ext3-key-mixed\",\"expectedVersion\":1,\"blocks\":["
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"A\",\"B\"]}]}";
        assertEquals(422, exchange("/api/experiments/EXT-3/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext3-bad-mixed"), mixedBody).getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-3'", Integer.class));
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-3'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE experiment_id = 'EXT-3'",
                Integer.class));
    }

    @Test
    void zeroOrFiveBlocks_areBadRequest() {
        createExperiment("EXT-4", 2, "ext4-create");
        String zero = "{\"extensionKey\":\"ext4-zero\",\"expectedVersion\":1,\"blocks\":[]}";
        assertEquals(400, exchange("/api/experiments/EXT-4/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext4-zero-req"), zero).getStatusCode().value());
        String five = "{\"extensionKey\":\"ext4-five\",\"expectedVersion\":1,\"blocks\":["
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}";
        assertEquals(400, exchange("/api/experiments/EXT-4/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext4-five-req"), five).getStatusCode().value());
    }

    @Test
    void totalBlocksCannotExceedSixteen() {
        createExperiment("EXT-5", 8, "ext5-create");
        // 8 + 4 = 12
        assertEquals(201, exchange("/api/experiments/EXT-5/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext5-ext-1"),
                fourBlockBody("ext5-key-1", 1)).getStatusCode().value());
        // 12 + 4 = 16：允许
        assertEquals(201, exchange("/api/experiments/EXT-5/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext5-ext-2"),
                fourBlockBody("ext5-key-2", 2)).getStatusCode().value());
        assertEquals(16, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-5'", Integer.class));
        // 再追加哪怕 1 个：422
        ResponseEntity<String> overflow = exchange(
                "/api/experiments/EXT-5/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext5-ext-3"), oneBlockBody("ext5-key-3", 3));
        assertEquals(422, overflow.getStatusCode().value());
        assertEquals(16, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-5'", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-5'", Integer.class),
                "超限失败不得推进版本");
    }

    private String fourBlockBody(String extensionKey, int expectedVersion) {
        return "{\"extensionKey\":\"" + extensionKey + "\",\"expectedVersion\":"
                + expectedVersion + ",\"blocks\":["
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}";
    }

    // ---------------- 版本、关闭与幂等 ----------------

    @Test
    void staleExpectedVersion_conflicts_andFailureKeepsRequestIdUsable() {
        createExperiment("EXT-6", 2, "ext6-create");
        assertEquals(201, exchange("/api/experiments/EXT-6/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext6-ext-1"),
                oneBlockBody("ext6-key-1", 1)).getStatusCode().value());
        // expectedVersion 落后：409
        ResponseEntity<String> stale = exchange(
                "/api/experiments/EXT-6/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext6-ext-stale"),
                oneBlockBody("ext6-key-stale", 1));
        assertEquals(409, stale.getStatusCode().value());
        // 失败不占 requestId：同一 requestId 以当前版本重新提交成功
        assertEquals(201, exchange("/api/experiments/EXT-6/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext6-ext-stale"),
                oneBlockBody("ext6-key-2", 2)).getStatusCode().value());
        assertEquals(3, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-6'", Integer.class));
    }

    @Test
    void closedExperiment_extension409_butHistoryAndStatsRemainQueryable() throws Exception {
        createExperiment("EXT-7", 2, "ext7-create");
        assertEquals(201, exchange("/api/experiments/EXT-7/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext7-ext"),
                oneBlockBody("ext7-key", 1)).getStatusCode().value());
        assertEquals(200, exchange("/api/experiments/EXT-7/close", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext7-close"), null).getStatusCode().value());

        // CLOSED 扩容：409
        ResponseEntity<String> afterClose = exchange(
                "/api/experiments/EXT-7/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext7-ext-after-close"),
                oneBlockBody("ext7-key-late", 2));
        assertEquals(409, afterClose.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE extension_key = 'ext7-key-late'",
                Integer.class));

        // 关闭后扩容历史仍保留可查
        ResponseEntity<String> history = exchange(
                "/api/experiments/EXT-7/block-extensions", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode historyBody = json(history);
        assertEquals(2, historyBody.path("version").asInt());
        assertEquals(1, historyBody.path("extensions").size());
        JsonNode record = historyBody.path("extensions").get(0);
        assertEquals("ext7-key", record.path("extensionKey").asText());
        assertEquals(3, record.path("firstBlockNo").asInt());
        assertFalse(record.has("treatment"));
        assertFalse(record.has("seatNo"));

        // 容量统计仍可查，状态 CLOSED
        ResponseEntity<String> stats = exchange("/api/experiments/EXT-7/capacity",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, stats.getStatusCode().value());
        JsonNode statsBody = json(stats);
        assertEquals("CLOSED", statsBody.path("status").asText());
        assertEquals(3, statsBody.path("blockCount").asInt());
        assertEquals(2, statsBody.path("version").asInt());
        assertEquals(12, statsBody.path("totalSeats").asInt());
        assertFalse(statsBody.has("treatment"));
        assertFalse(statsBody.has("seatNo"));
    }

    @Test
    void capacityStats_countsWithdrawnAndVacant() throws Exception {
        createExperiment("EXT-8", 2, "ext8-create");
        registerOk("EXT-8", "P1", "ext8-a1");
        registerOk("EXT-8", "P2", "ext8-a2");
        assertEquals(200, exchange("/api/experiments/EXT-8/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "ext8-w"), null)
                .getStatusCode().value());

        ResponseEntity<String> stats = exchange("/api/experiments/EXT-8/capacity",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(200, stats.getStatusCode().value());
        JsonNode body = json(stats);
        assertEquals(2, body.path("blockCount").asInt());
        assertEquals(1, body.path("version").asInt());
        assertEquals(8, body.path("totalSeats").asInt());
        assertEquals(2, body.path("occupiedSeats").asInt(), "退组席位仍计入已占用");
        assertEquals(1, body.path("withdrawnSeats").asInt());
        assertEquals(6, body.path("vacantSeats").asInt());
    }

    @Test
    void extensionIdempotency_replaySameParams_reorderedConflicts_keyGloballyUnique() {
        createExperiment("EXT-9", 2, "ext9-create");
        String abba = "{\"extensionKey\":\"ext9-key\",\"expectedVersion\":1,\"blocks\":["
                + "{\"treatments\":[\"A\",\"B\",\"B\",\"A\"]}]}";
        ResponseEntity<String> first = exchange(
                "/api/experiments/EXT-9/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext9-req-1"), abba);
        assertEquals(201, first.getStatusCode().value());

        // 同键同参（同 requestId）重放首次结果，不重复追加
        ResponseEntity<String> replay = exchange(
                "/api/experiments/EXT-9/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext9-req-1"), abba);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE experiment_id = 'EXT-9'",
                Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-9'", Integer.class));

        // 同 requestId 换区组顺序（ABBA -> BAAB）视为异参：409
        String reordered = "{\"extensionKey\":\"ext9-key\",\"expectedVersion\":1,\"blocks\":["
                + "{\"treatments\":[\"B\",\"A\",\"A\",\"B\"]}]}";
        assertEquals(409, exchange("/api/experiments/EXT-9/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext9-req-1"), reordered).getStatusCode().value());

        // extensionKey 全局唯一：换新 requestId 复用旧键 → 409，失败不占 requestId
        assertEquals(409, exchange("/api/experiments/EXT-9/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext9-req-2"),
                oneBlockBody("ext9-key", 2)).getStatusCode().value());
        // ext9-req-2 未被占用：用它携带新键与当前版本再次扩容成功
        assertEquals(201, exchange("/api/experiments/EXT-9/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext9-req-2"),
                oneBlockBody("ext9-key-2", 2)).getStatusCode().value());
        assertEquals(4, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-9'", Integer.class));

        // extensionKey 跨实验同样不可复用
        createExperiment("EXT-9B", 2, "ext9b-create");
        assertEquals(409, exchange("/api/experiments/EXT-9B/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext9b-req"),
                oneBlockBody("ext9-key", 1)).getStatusCode().value());
    }

    // ---------------- 权限先于幂等 ----------------

    @Test
    void reviewerExtension_forbidden_andPermissionPrecedesReplay() {
        createExperiment("EXT-10", 2, "ext10-create");
        // REVIEWER 直接扩容：403
        assertEquals(403, exchange("/api/experiments/EXT-10/block-extensions", HttpMethod.POST,
                headers("r1", "REVIEWER", "ext10-req"),
                oneBlockBody("ext10-key", 1)).getStatusCode().value());
        // 缺身份头：401
        assertEquals(401, exchange("/api/experiments/EXT-10/block-extensions", HttpMethod.POST,
                headers(null, null, "ext10-req"),
                oneBlockBody("ext10-key", 1)).getStatusCode().value());

        // 协调员用该 requestId 成功
        assertEquals(201, exchange("/api/experiments/EXT-10/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext10-req"),
                oneBlockBody("ext10-key", 1)).getStatusCode().value());

        // REVIEWER 用同一 requestId 重放：权限先于幂等，仍 403 而非回放 201
        assertEquals(403, exchange("/api/experiments/EXT-10/block-extensions", HttpMethod.POST,
                headers("r1", "REVIEWER", "ext10-req"),
                oneBlockBody("ext10-key", 1)).getStatusCode().value());
        // 异操作者（另一名协调员）重放：幂等参数含操作者，409
        assertEquals(409, exchange("/api/experiments/EXT-10/block-extensions", HttpMethod.POST,
                headers("c2", "COORDINATOR", "ext10-req"),
                oneBlockBody("ext10-key", 1)).getStatusCode().value());
    }

    // ---------------- 并发裁决 ----------------

    @Test
    void concurrentExtensionsSameExperiment_exactlyOneWins() throws Exception {
        createExperiment("EXT-11", 2, "ext11-create");
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String key = "ext11-key-" + i;
                final String reqId = "ext11-req-" + i;
                futures.add(pool.submit(() -> {
                    awaitQuietly(barrier);
                    return exchange("/api/experiments/EXT-11/block-extensions", HttpMethod.POST,
                            headers("c1", "COORDINATOR", reqId), oneBlockBody(key, 1))
                            .getStatusCode().value();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 201).count(),
                    "仅一个扩容成功");
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count(),
                    "版本落后者 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-11'", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-11'", Integer.class));
        assertEquals(12, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-11'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE experiment_id = 'EXT-11'",
                Integer.class));
    }

    @Test
    void extensionRacesWithClose_adjudicatedByCommitOrder_noInconsistentState() throws Exception {
        createExperiment("EXT-12", 2, "ext12-create");
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<Integer> extFuture = pool.submit(() -> {
                awaitQuietly(barrier);
                return exchange("/api/experiments/EXT-12/block-extensions", HttpMethod.POST,
                        headers("c1", "COORDINATOR", "ext12-req-ext"),
                        oneBlockBody("ext12-key", 1)).getStatusCode().value();
            });
            Future<Integer> closeFuture = pool.submit(() -> {
                awaitQuietly(barrier);
                return exchange("/api/experiments/EXT-12/close", HttpMethod.POST,
                        headers("c1", "COORDINATOR", "ext12-req-close"), null)
                        .getStatusCode().value();
            });
            int extStatus = extFuture.get(30, TimeUnit.SECONDS);
            int closeStatus = closeFuture.get(30, TimeUnit.SECONDS);

            assertEquals(200, closeStatus, "关闭必然成功一次");
            int version = jdbc.queryForObject(
                    "SELECT version FROM experiment WHERE id = 'EXT-12'", Integer.class);
            int blockCount = jdbc.queryForObject(
                    "SELECT block_count FROM experiment WHERE id = 'EXT-12'", Integer.class);
            int seatCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-12'", Integer.class);
            if (extStatus == 201) {
                // 扩容先提交：随后关闭成功
                assertEquals(2, version);
                assertEquals(3, blockCount);
            } else {
                // 关闭先提交：扩容 409
                assertEquals(409, extStatus);
                assertEquals(1, version);
                assertEquals(2, blockCount);
            }
            assertEquals(blockCount * 4, seatCount, "席位数必须与区组总数一致");
            assertEquals("CLOSED", jdbc.queryForObject(
                    "SELECT status FROM experiment WHERE id = 'EXT-12'", String.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void extensionRacesWithRegistrations_neverOverAllocate() throws Exception {
        createExperiment("EXT-13", 2, "ext13-create");
        for (int i = 1; i <= 8; i++) {
            registerOk("EXT-13", "P" + i, "ext13-init-" + i);
        }

        int participants = 4;
        int threads = participants + 1;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> registerFutures = new ArrayList<>();
        try {
            Future<Integer> extFuture = pool.submit(() -> {
                awaitQuietly(barrier);
                return exchange("/api/experiments/EXT-13/block-extensions", HttpMethod.POST,
                        headers("c1", "COORDINATOR", "ext13-req-ext"),
                        oneBlockBody("ext13-key", 1)).getStatusCode().value();
            });
            for (int i = 9; i <= 12; i++) {
                final String pid = "P" + i;
                final String reqId = "ext13-req-" + i;
                registerFutures.add(pool.submit(() -> {
                    awaitQuietly(barrier);
                    return exchange(
                            "/api/experiments/EXT-13/participants/" + pid + "/allocations",
                            HttpMethod.POST,
                            headers("c1", "COORDINATOR", reqId), null).getStatusCode().value();
                }));
            }
            // 扩容先/后提交均可能，但裁决结果必须一致：扩容最终成功，登记只可能 201 或满额 422。
            assertEquals(201, extFuture.get(30, TimeUnit.SECONDS), "扩容最终成功一次");
            List<Integer> registerStatuses = new ArrayList<>();
            for (Future<Integer> future : registerFutures) {
                registerStatuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long registerSuccess = registerStatuses.stream().filter(s -> s == 201).count();
            long registerFull = registerStatuses.stream().filter(s -> s == 422).count();
            assertEquals(participants, registerSuccess + registerFull,
                    "扩容未提交前登记满额 422，提交后按空位成功，不允许其他结果");

            int totalAllocations = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXT-13'",
                    Integer.class);
            assertEquals(8 + registerSuccess, totalAllocations);
            assertEquals(12, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-13'", Integer.class));
            List<String> seats = jdbc.queryForList(
                    "SELECT CONCAT(block_no, '-', seat_no) AS k FROM allocation "
                            + "WHERE experiment_id = 'EXT-13'", String.class);
            assertEquals(totalAllocations, new HashSet<>(seats).size(), "同席位不得被占用两次");
            assertTrue(totalAllocations <= 12, "分配总数不得超过席位总数");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentRegistrationSameParticipant_afterExtension_succeedsAtMostOnce()
            throws Exception {
        createExperiment("EXT-14", 2, "ext14-create");
        assertEquals(201, exchange("/api/experiments/EXT-14/block-extensions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "ext14-req-ext"),
                oneBlockBody("ext14-key", 1)).getStatusCode().value());

        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "ext14-same-pid-" + i;
                futures.add(pool.submit(() -> {
                    awaitQuietly(barrier);
                    return exchange(
                            "/api/experiments/EXT-14/participants/PX/allocations",
                            HttpMethod.POST,
                            headers("c1", "COORDINATOR", reqId), null).getStatusCode().value();
                }));
            }
            Set<Integer> statuses = new HashSet<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(Set.of(201, 409), statuses, "恰好一次成功，其余重复参与者冲突");
            long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXT-14' "
                            + "AND participant_id = 'PX'", Long.class);
            assertEquals(1, count, "同一参与者只占一席");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
}
