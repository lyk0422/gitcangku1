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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 区组扩容主流程与失败分支（真实 H2 MySQL 兼容库）：
 * 配比校验、区组上限、版本递增与连续编号、席位顺序连续不回填退组席、
 * 满额参与者扩容后可登记、权限先于幂等、扩容幂等与 extensionKey 全局唯一、
 * 容量/历史统计、关闭后保留历史且拒绝扩容；所有普通视图不泄露处理映射。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BlockExtensionApiTest extends AbstractBlindIntegrationTest {

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

    private ResponseEntity<String> post(String path, String actor, String role,
                                        String requestId, String body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(actor, role, requestId)), String.class);
    }

    private ResponseEntity<String> get(String path, String actor, String role) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(null, headers(actor, role, null)), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    /** 两个合法追加区组（各 4 席，两 A 两 B）。 */
    private static final String TWO_VALID_BLOCKS = """
            {"extensionKey":"EXT-1","expectedVersion":1,"blocks":[
              {"treatments":["A","A","B","B"]},
              {"treatments":["A","B","A","B"]}
            ]}""";

    private void createExperiment(String id, int blockCount, String requestId) {
        ResponseEntity<String> resp = post("/api/experiments/" + id, "c1", "COORDINATOR",
                requestId, "{\"blockCount\":" + blockCount + "}");
        assertEquals(201, resp.getStatusCode().value(), "建实验应成功: " + resp.getBody());
    }

    private void registerUntilFull(String expId, int seats, String reqPrefix) {
        for (int i = 1; i <= seats; i++) {
            ResponseEntity<String> resp = post(
                    "/api/experiments/" + expId + "/participants/P" + i + "/allocations",
                    "c1", "COORDINATOR", reqPrefix + i, null);
            assertEquals(201, resp.getStatusCode().value(),
                    "P" + i + " 应登记成功: " + resp.getStatusCode());
        }
    }

    @Test
    void extendBlocks_appendsContiguousBlocksAndIncrementsVersion_withoutLeakingBlindBottom()
            throws Exception {
        createExperiment("EXT-MAIN", 2, "ext-main-create");

        ResponseEntity<String> resp = post(
                "/api/experiments/EXT-MAIN/block-extensions",
                "c1", "COORDINATOR", "ext-main-req", TWO_VALID_BLOCKS);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        JsonNode body = json(resp);
        assertEquals("EXT-MAIN", body.path("experimentId").asText());
        assertEquals("EXT-1", body.path("extensionKey").asText());
        assertEquals(1, body.path("expectedVersion").asInt());
        assertEquals(2, body.path("version").asInt(), "扩容后版本应为 2");
        assertEquals(4, body.path("blockCount").asInt());
        assertEquals(4, body.path("seatsPerBlock").asInt());
        assertEquals(16, body.path("totalSeats").asInt());
        assertEquals(2, body.path("addedBlockCount").asInt());
        assertEquals("OPEN", body.path("status").asText());
        // 扩容响应不得暴露处理代码或席位序号
        assertFalse(body.has("treatment"), "扩容响应不得包含处理代码");
        assertFalse(body.has("seatNo"), "扩容响应不得包含席位序号");
        assertFalse(body.has("treatments"), "扩容响应不得包含席位处理序列");

        // 新区组号从现有最大区组号加一起连续编号；新区组各自两 A 两 B
        List<String> block3 = jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXT-MAIN' AND block_no = 3 "
                        + "ORDER BY seat_no", String.class);
        List<String> block4 = jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXT-MAIN' AND block_no = 4 "
                        + "ORDER BY seat_no", String.class);
        assertEquals(List.of("A", "A", "B", "B"), block3);
        assertEquals(List.of("A", "B", "A", "B"), block4);
        assertEquals(16, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-MAIN'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-MAIN'", Integer.class));

        // 普通实验查询反映新容量与版本，且不含盲底字段
        ResponseEntity<String> view = get("/api/experiments/EXT-MAIN", "r1", "REVIEWER");
        assertEquals(200, view.getStatusCode().value());
        JsonNode viewBody = json(view);
        assertEquals(4, viewBody.path("blockCount").asInt());
        assertEquals(16, viewBody.path("totalSeats").asInt());
        assertEquals(2, viewBody.path("version").asInt());
        assertFalse(viewBody.has("treatment"));
        assertFalse(viewBody.has("seatNo"));
    }

    @Test
    void ratioMismatch_returns422_andWritesNothingAndConsumesNoKey() {
        createExperiment("EXT-RATIO", 2, "ext-ratio-create");
        String badBody = """
                {"extensionKey":"EXT-RATIO-1","expectedVersion":1,"blocks":[
                  {"treatments":["A","A","A","B"]}
                ]}""";
        ResponseEntity<String> resp = post(
                "/api/experiments/EXT-RATIO/block-extensions",
                "c1", "COORDINATOR", "ext-ratio-req", badBody);
        assertEquals(422, resp.getStatusCode().value());
        // 整次失败：无新区组席位、无扩容记录、版本仍为 1
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-RATIO'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE experiment_id = 'EXT-RATIO'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-RATIO'", Integer.class));
        // 失败不占用 requestId：同一 X-Request-Id 以合法参数重试应成功
        ResponseEntity<String> retry = post(
                "/api/experiments/EXT-RATIO/block-extensions",
                "c1", "COORDINATOR", "ext-ratio-req",
                "{\"extensionKey\":\"EXT-RATIO-1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"B\",\"B\",\"A\",\"A\"]}]}");
        assertEquals(200, retry.getStatusCode().value(), retry.getBody());
        // extensionKey 在失败时同样未落库，可被成功重试使用
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE extension_key = 'EXT-RATIO-1'",
                Integer.class));
    }

    @Test
    void illegalTreatmentAndBlockShape_fail() {
        createExperiment("EXT-SHAPE", 2, "ext-shape-create");
        // 出现 A/B 之外的处理代码：配比校验 422
        ResponseEntity<String> badCode = post(
                "/api/experiments/EXT-SHAPE/block-extensions", "c1", "COORDINATOR",
                "ext-shape-req-1",
                "{\"extensionKey\":\"EXT-SHAPE-1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"C\",\"B\",\"B\"]}]}");
        assertEquals(422, badCode.getStatusCode().value());
        // 区组席位数量不为 4：Bean 校验 400
        ResponseEntity<String> threeSeats = post(
                "/api/experiments/EXT-SHAPE/block-extensions", "c1", "COORDINATOR",
                "ext-shape-req-2",
                "{\"extensionKey\":\"EXT-SHAPE-2\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\"]}]}");
        assertEquals(400, threeSeats.getStatusCode().value());
        // 超过 4 个追加区组：Bean 校验 400
        ResponseEntity<String> fiveBlocks = post(
                "/api/experiments/EXT-SHAPE/block-extensions", "c1", "COORDINATOR",
                "ext-shape-req-3",
                "{\"extensionKey\":\"EXT-SHAPE-3\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}");
        assertEquals(400, fiveBlocks.getStatusCode().value());
        // 空区组列表：400
        ResponseEntity<String> empty = post(
                "/api/experiments/EXT-SHAPE/block-extensions", "c1", "COORDINATOR",
                "ext-shape-req-4",
                "{\"extensionKey\":\"EXT-SHAPE-4\",\"expectedVersion\":1,\"blocks\":[]}");
        assertEquals(400, empty.getStatusCode().value());
    }

    @Test
    void exceedingSixteenBlocks_returns422_clean() {
        createExperiment("EXT-CAP2", 8, "ext-cap2-create");
        assertEquals(200, post("/api/experiments/EXT-CAP2/block-extensions",
                "c1", "COORDINATOR", "ext-cap2-req-1", fourBlocksBody("EXT-CAP2-1", 1))
                .getStatusCode().value());
        assertEquals(12, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-CAP2'", Integer.class));
        assertEquals(200, post("/api/experiments/EXT-CAP2/block-extensions",
                "c1", "COORDINATOR", "ext-cap2-req-2", fourBlocksBody("EXT-CAP2-2", 2))
                .getStatusCode().value());
        assertEquals(16, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-CAP2'", Integer.class));
        // 16 个区组再追加 1 个：422，失败不占键、版本不变
        ResponseEntity<String> overflow = post(
                "/api/experiments/EXT-CAP2/block-extensions", "c1", "COORDINATOR",
                "ext-cap2-req-3",
                "{\"extensionKey\":\"EXT-CAP2-3\",\"expectedVersion\":3,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}");
        assertEquals(422, overflow.getStatusCode().value());
        assertEquals(16, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXT-CAP2'", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXT-CAP2'", Integer.class));
        assertEquals(64, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-CAP2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE extension_key = 'EXT-CAP2-3'",
                Integer.class));
    }

    /** 4 个合法追加区组的请求体；extensionKey 与期望版本可参数化。 */
    private static String fourBlocksBody(String extensionKey, int expectedVersion) {
        return "{\"extensionKey\":\"" + extensionKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"blocks\":["
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"B\",\"A\",\"B\"]},"
                + "{\"treatments\":[\"B\",\"A\",\"B\",\"A\"]},"
                + "{\"treatments\":[\"B\",\"B\",\"A\",\"A\"]}]}";
    }

    @Test
    void fullParticipantRegistersAfterExtension_intoFirstNewSeat_notBackfillWithdrawn()
            throws Exception {
        createExperiment("EXT-SEAT", 2, "ext-seat-create");
        registerUntilFull("EXT-SEAT", 8, "ext-seat-alloc-");
        // 第九个参与者扩容前满额 422
        ResponseEntity<String> full = post(
                "/api/experiments/EXT-SEAT/participants/P9/allocations",
                "c1", "COORDINATOR", "ext-seat-p9-first", null);
        assertEquals(422, full.getStatusCode().value());
        // P1（位于 1 区组 1 席）退组，席位不释放
        assertEquals(200, post("/api/experiments/EXT-SEAT/participants/P1/withdrawal",
                "c1", "COORDINATOR", "ext-seat-withdraw-p1", null).getStatusCode().value());

        // 追加 1 个区组
        ResponseEntity<String> ext = post(
                "/api/experiments/EXT-SEAT/block-extensions", "c1", "COORDINATOR",
                "ext-seat-ext",
                "{\"extensionKey\":\"EXT-SEAT-E1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"B\",\"B\",\"A\",\"A\"]}]}");
        assertEquals(200, ext.getStatusCode().value());

        // 此前满额 422 的 P9 扩容后登记成功
        ResponseEntity<String> again = post(
                "/api/experiments/EXT-SEAT/participants/P9/allocations",
                "c1", "COORDINATOR", "ext-seat-p9-second", null);
        assertEquals(201, again.getStatusCode().value());
        JsonNode allocation = json(again);
        assertEquals(3, allocation.path("blockNo").asInt(), "必须先填新区组，不得回填旧区组");
        assertFalse(allocation.has("seatNo"));
        assertFalse(allocation.has("treatment"));
        // 库内断言：P9 落在新区组首个空位 (3,1)，而非退组空出的 (1,1)
        assertEquals("3-1", jdbc.queryForObject(
                "SELECT CONCAT(block_no, '-', seat_no) FROM allocation "
                        + "WHERE experiment_id = 'EXT-SEAT' AND participant_id = 'P9'",
                String.class));
        // 退组席位 (1,1) 仍只有 P1 的 WITHDRAWN 记录，未被回填
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXT-SEAT' "
                        + "AND block_no = 1 AND seat_no = 1", Integer.class));
        assertEquals("WITHDRAWN", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'EXT-SEAT' "
                        + "AND participant_id = 'P1'", String.class));
        // 已有区组顺序与席位内容不变
        assertEquals(List.of("A", "A", "B", "B"), jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXT-SEAT' AND block_no = 1 "
                        + "ORDER BY seat_no", String.class));
    }

    @Test
    void sameParticipant_registeringTwice_afterExtension_stillOneSeat() {
        createExperiment("EXT-DUP", 2, "ext-dup-create");
        registerUntilFull("EXT-DUP", 8, "ext-dup-alloc-");
        assertEquals(200, post("/api/experiments/EXT-DUP/block-extensions",
                "c1", "COORDINATOR", "ext-dup-ext",
                "{\"extensionKey\":\"EXT-DUP-E1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}").getStatusCode().value());
        // P1 已在组（占第一席），扩容后再次登记仍 409，只占一席
        ResponseEntity<String> duplicate = post(
                "/api/experiments/EXT-DUP/participants/P1/allocations",
                "c1", "COORDINATOR", "ext-dup-p1-again", null);
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXT-DUP' "
                        + "AND participant_id = 'P1'", Integer.class));
    }

    @Test
    void reviewerExtension_isForbidden_beforeIdempotencyReplay() {
        createExperiment("EXT-AUTH", 2, "ext-auth-create");
        // 协调员先用 requestId 成功扩容
        assertEquals(200, post("/api/experiments/EXT-AUTH/block-extensions",
                "c1", "COORDINATOR", "ext-auth-req",
                "{\"extensionKey\":\"EXT-AUTH-E1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}").getStatusCode().value());
        // REVIEWER 用同一 X-Request-Id 同参请求：必须 403（权限先于幂等回放），而非回放 200
        ResponseEntity<String> reviewer = post(
                "/api/experiments/EXT-AUTH/block-extensions", "r1", "REVIEWER",
                "ext-auth-req",
                "{\"extensionKey\":\"EXT-AUTH-E1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}");
        assertEquals(403, reviewer.getStatusCode().value());
        // 未携带身份头：401，也不是回放
        assertEquals(401, rest.exchange(
                "/api/experiments/EXT-AUTH/block-extensions", HttpMethod.POST,
                new HttpEntity<>("{\"extensionKey\":\"EXT-AUTH-E1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}",
                        headersNoActor("ext-auth-req")), String.class).getStatusCode().value());
    }

    private HttpHeaders headersNoActor(String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Request-Id", requestId);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void replaySameRequestId_returnsOriginalResult_andReorderIsDifferentParam409()
            throws Exception {
        createExperiment("EXT-IDEM", 2, "ext-idem-create");
        String body = "{\"extensionKey\":\"EXT-IDEM-E1\",\"expectedVersion\":1,\"blocks\":["
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"B\",\"A\",\"B\"]}]}";
        ResponseEntity<String> first = post(
                "/api/experiments/EXT-IDEM/block-extensions",
                "c1", "COORDINATOR", "ext-idem-req", body);
        assertEquals(200, first.getStatusCode().value());
        // 同键同参同操作者重放：原 200 与相同响应体，不产生第二组席位/记录
        ResponseEntity<String> replay = post(
                "/api/experiments/EXT-IDEM/block-extensions",
                "c1", "COORDINATOR", "ext-idem-req", body);
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(16, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXT-IDEM'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE experiment_id = 'EXT-IDEM'",
                Integer.class));

        // 区组换序视为异参：同 requestId 返回 409
        String reordered = "{\"extensionKey\":\"EXT-IDEM-E1\",\"expectedVersion\":1,\"blocks\":["
                + "{\"treatments\":[\"A\",\"B\",\"A\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}";
        ResponseEntity<String> conflict = post(
                "/api/experiments/EXT-IDEM/block-extensions",
                "c1", "COORDINATOR", "ext-idem-req", reordered);
        assertEquals(409, conflict.getStatusCode().value());

        // 异操作者同键同参：409
        assertEquals(409, post("/api/experiments/EXT-IDEM/block-extensions",
                "c2", "COORDINATOR", "ext-idem-req", body).getStatusCode().value());

        // expectedVersion 不同也是异参：409
        String otherVersion = "{\"extensionKey\":\"EXT-IDEM-E1\",\"expectedVersion\":2,\"blocks\":["
                + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]},"
                + "{\"treatments\":[\"A\",\"B\",\"A\",\"B\"]}]}";
        assertEquals(409, post("/api/experiments/EXT-IDEM/block-extensions",
                "c1", "COORDINATOR", "ext-idem-req", otherVersion).getStatusCode().value());
    }

    @Test
    void failedConflict_doesNotConsumeRequestId() {
        createExperiment("EXT-NC1", 2, "ext-nc1-create");
        createExperiment("EXT-NC2", 2, "ext-nc2-create");
        // extensionKey 全局唯一：先在 EXT-NC1 成功占用
        assertEquals(200, post("/api/experiments/EXT-NC1/block-extensions",
                "c1", "COORDINATOR", "ext-nc-req-1",
                "{\"extensionKey\":\"GLOBAL-KEY\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}").getStatusCode().value());
        // 同 extensionKey 给另一个实验扩容：409，失败不占 requestId
        ResponseEntity<String> dup = post(
                "/api/experiments/EXT-NC2/block-extensions", "c1", "COORDINATOR",
                "ext-nc-req-2",
                "{\"extensionKey\":\"GLOBAL-KEY\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}");
        assertEquals(409, dup.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'ext-nc-req-2'",
                Integer.class));
        // 该 requestId 随后以另一组合法参数（新 extensionKey）扩容 EXT-NC2 成功
        ResponseEntity<String> reuse = post(
                "/api/experiments/EXT-NC2/block-extensions", "c1", "COORDINATOR",
                "ext-nc-req-2",
                "{\"extensionKey\":\"GLOBAL-KEY-2\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"B\",\"A\",\"B\"]}]}");
        assertEquals(200, reuse.getStatusCode().value());
    }

    @Test
    void staleExpectedVersion_isConflict() {
        createExperiment("EXT-VER", 2, "ext-ver-create");
        assertEquals(200, post("/api/experiments/EXT-VER/block-extensions",
                "c1", "COORDINATOR", "ext-ver-req-1",
                "{\"extensionKey\":\"EXT-VER-E1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}").getStatusCode().value());
        // 版本已到 2，仍传 expectedVersion=1：409
        ResponseEntity<String> stale = post(
                "/api/experiments/EXT-VER/block-extensions", "c1", "COORDINATOR",
                "ext-ver-req-2",
                "{\"extensionKey\":\"EXT-VER-E2\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}");
        assertEquals(409, stale.getStatusCode().value());
        // 失败不占键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE extension_key = 'EXT-VER-E2'",
                Integer.class));
        // 正确版本 2：成功
        assertEquals(200, post("/api/experiments/EXT-VER/block-extensions",
                "c1", "COORDINATOR", "ext-ver-req-3",
                "{\"extensionKey\":\"EXT-VER-E3\",\"expectedVersion\":2,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}").getStatusCode().value());
    }

    @Test
    void capacityAndHistory_reflectExtensionsAndWithdrawals_andHideMapping() throws Exception {
        createExperiment("EXT-STAT", 2, "ext-stat-create");
        registerUntilFull("EXT-STAT", 8, "ext-stat-alloc-");
        assertEquals(200, post("/api/experiments/EXT-STAT/participants/P1/withdrawal",
                "c1", "COORDINATOR", "ext-stat-wd", null).getStatusCode().value());
        // 满额：已用 8（含退组），可用 0
        JsonNode fullCap = json(get("/api/experiments/EXT-STAT/capacity", "r1", "REVIEWER"));
        assertEquals(2, fullCap.path("blockCount").asInt());
        assertEquals(8, fullCap.path("totalSeats").asInt());
        assertEquals(8, fullCap.path("occupiedSeats").asInt(), "退组席位仍计入已用");
        assertEquals(0, fullCap.path("availableSeats").asInt());
        assertEquals(1, fullCap.path("version").asInt());
        assertFalse(fullCap.has("treatment"));
        assertFalse(fullCap.has("seatNo"));

        // 第一次扩容 1 个区组
        clock.advance(1_000L);
        assertEquals(200, post("/api/experiments/EXT-STAT/block-extensions",
                "c1", "COORDINATOR", "ext-stat-ext-1",
                "{\"extensionKey\":\"EXT-STAT-E1\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}").getStatusCode().value());
        // 第二次扩容 2 个区组
        clock.advance(1_000L);
        assertEquals(200, post("/api/experiments/EXT-STAT/block-extensions",
                "c1", "COORDINATOR", "ext-stat-ext-2",
                "{\"extensionKey\":\"EXT-STAT-E2\",\"expectedVersion\":2,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"B\",\"A\",\"B\"]},"
                        + "{\"treatments\":[\"B\",\"A\",\"B\",\"A\"]}]}").getStatusCode().value());

        JsonNode cap = json(get("/api/experiments/EXT-STAT/capacity", "c1", "COORDINATOR"));
        assertEquals(5, cap.path("blockCount").asInt());
        assertEquals(20, cap.path("totalSeats").asInt());
        assertEquals(8, cap.path("occupiedSeats").asInt());
        assertEquals(12, cap.path("availableSeats").asInt());
        assertEquals(3, cap.path("version").asInt());
        assertEquals("OPEN", cap.path("status").asText());

        // 扩容历史：按先后顺序，含区组号区间与版本，不含处理映射
        JsonNode history = json(get("/api/experiments/EXT-STAT/block-extensions",
                "r1", "REVIEWER"));
        assertEquals("EXT-STAT", history.path("experimentId").asText());
        assertEquals(2, history.path("extensions").size());
        JsonNode e1 = history.path("extensions").get(0);
        assertEquals("EXT-STAT-E1", e1.path("extensionKey").asText());
        assertEquals(1, e1.path("expectedVersion").asInt());
        assertEquals(2, e1.path("version").asInt());
        assertEquals(2, e1.path("fromBlockCount").asInt());
        assertEquals(1, e1.path("addedBlockCount").asInt());
        assertEquals(3, e1.path("fromBlockNo").asInt());
        assertEquals(3, e1.path("toBlockNo").asInt());
        assertEquals("c1", e1.path("operatorActor").asText());
        JsonNode e2 = history.path("extensions").get(1);
        assertEquals(4, e2.path("fromBlockNo").asInt());
        assertEquals(5, e2.path("toBlockNo").asInt());
        assertEquals(3, e2.path("version").asInt());
        assertFalse(history.has("treatment"));
        assertFalse(history.toString().contains("\"seatNo\""),
                "历史视图不得出现席位序号字段");
        assertFalse(history.toString().contains("\"treatment\""),
                "历史视图不得出现处理代码字段");

        // 关闭实验：扩容被拒 409，但历史仍可查
        assertEquals(200, post("/api/experiments/EXT-STAT/close",
                "c1", "COORDINATOR", "ext-stat-close", null).getStatusCode().value());
        assertEquals(409, post("/api/experiments/EXT-STAT/block-extensions",
                "c1", "COORDINATOR", "ext-stat-ext-3",
                "{\"extensionKey\":\"EXT-STAT-E4\",\"expectedVersion\":3,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}").getStatusCode().value());
        JsonNode afterClose = json(get("/api/experiments/EXT-STAT/block-extensions",
                "c1", "COORDINATOR"));
        assertEquals(2, afterClose.path("extensions").size(), "关闭后扩容记录仍保留可查");
        JsonNode closedCap = json(get("/api/experiments/EXT-STAT/capacity", "c1", "COORDINATOR"));
        assertEquals("CLOSED", closedCap.path("status").asText());
        assertEquals(5, closedCap.path("blockCount").asInt());
    }

    @Test
    void extensionOnMissingExperiment_is404_andReviewerCanReadStats() {
        ResponseEntity<String> missing = post(
                "/api/experiments/NOPE/block-extensions", "c1", "COORDINATOR", "ext-missing",
                "{\"extensionKey\":\"EXT-NOPE\",\"expectedVersion\":1,\"blocks\":["
                        + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}");
        assertEquals(404, missing.getStatusCode().value());
        assertEquals(404, get("/api/experiments/NOPE/capacity", "r1", "REVIEWER")
                .getStatusCode().value());
        assertEquals(404, get("/api/experiments/NOPE/block-extensions", "r1", "REVIEWER")
                .getStatusCode().value());
    }
}
