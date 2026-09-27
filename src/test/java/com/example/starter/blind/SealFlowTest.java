package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.blind.service.RandomTableDigester;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机表封存主流程与失败分支（真实 H2）：
 * 封存只暴露摘要/容量/代码集合；分配固化版本序号；扩容引用前驱且名额守恒；
 * 关闭余量跳过空位；历史不被改写；诊断与 UTC 左闭右开时间窗；幂等与鉴权。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SealFlowTest extends AbstractBlindIntegrationTest {

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

    private int create(String expId, int blocks, String reqId) {
        return exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("c1", "COORDINATOR", reqId),
                "{\"blockCount\":" + blocks + "}").getStatusCode().value();
    }

    private int allocate(String expId, String pid, String reqId) {
        return exchange("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", reqId), null)
                .getStatusCode().value();
    }

    /** 提取诊断事件类型列表，按响应顺序。 */
    private List<String> eventTypes(JsonNode diagnostic) {
        List<String> types = new java.util.ArrayList<>();
        for (JsonNode event : diagnostic.path("events")) {
            types.add(event.path("type").asText());
        }
        return types;
    }

    /** 区组版本1固定序列 AABB 的服务端摘要。 */
    private String v1Digest(String expId) {
        return RandomTableDigester.digest(expId, 1, 1, 4, "A,B", "AABB");
    }

    @Test
    void seal_beforeFirstAllocation_storesSummaryOnly_andAllocationsFollowVersion() throws Exception {
        assertEquals(201, create("SEAL-1", 2, "s1-create"));

        ResponseEntity<String> resp = exchange(
                "/api/experiments/SEAL-1/blocks/1/seals/S1", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s1-seal"), null);
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("S1", body.path("sealId").asText());
        assertEquals(1, body.path("versionNo").asInt());
        assertEquals(4, body.path("capacity").asInt());
        assertEquals("SEALED", body.path("status").asText());
        assertEquals(0, body.path("allocated").asInt());
        assertEquals(4, body.path("remaining").asInt());
        assertEquals(0, body.path("abandoned").asInt());
        assertEquals(v1Digest("SEAL-1"), body.path("tableDigest").asText());
        assertEquals(1_700_000_000_000L, body.path("sealedAt").asLong());
        assertTrue(body.path("predecessorId").isNull(), "首次封存无前驱");
        assertTrue(body.path("closedAt").isNull());
        assertEquals(List.of("A", "B"),
                mapper.readValue(body.path("treatmentCodes").toString(),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        // 封存视图不得暴露具体序列、序号或逐位处理代码
        assertFalse(body.has("sequence"));
        assertFalse(body.has("seatNo"));
        assertFalse(body.has("treatments"));

        // 库内封存记录无序列列
        List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'RANDOM_TABLE_SEAL'", String.class);
        assertTrue(columns.stream().noneMatch(c -> c.toLowerCase().contains("sequence")),
                "封存表不得保存具体序列");

        // 封存后分配：固化版本1与封存编号，且按剩余序列最小序号进行
        assertEquals(201, allocate("SEAL-1", "P1", "s1-alloc-1"));
        assertEquals(201, allocate("SEAL-1", "P2", "s1-alloc-2"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version_no FROM allocation WHERE participant_id = 'P1'", Integer.class));
        assertEquals("S1", jdbc.queryForObject(
                "SELECT seal_id FROM allocation WHERE participant_id = 'P1'", String.class));
        List<Integer> seatNos = jdbc.queryForList(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'SEAL-1' "
                        + "ORDER BY participant_id", Integer.class);
        assertEquals(List.of(1, 2), seatNos, "按版本剩余序列的最小序号顺序分配");
        // 分配处理代码与封存版本席位一致（库内核对，不经过普通接口）：序号1/2均为 A
        assertEquals(List.of("A", "A"), jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no AND s.version_no = a.version_no "
                        + "WHERE a.experiment_id = 'SEAL-1' ORDER BY a.participant_id",
                String.class));

        // 明细与历史查询
        ResponseEntity<String> detail = exchange(
                "/api/experiments/SEAL-1/blocks/1/seals/S1", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, detail.getStatusCode().value());
        JsonNode detailBody = json(detail);
        assertEquals(2, detailBody.path("seal").path("allocated").asInt());
        assertEquals(2, detailBody.path("seal").path("remaining").asInt());
        assertEquals(2, detailBody.path("allocations").size());
        JsonNode firstItem = detailBody.path("allocations").get(0);
        assertEquals("P1", firstItem.path("participantId").asText());
        assertEquals(1, firstItem.path("versionNo").asInt());
        assertFalse(firstItem.has("seatNo"));
        assertFalse(firstItem.has("treatment"));

        ResponseEntity<String> history = exchange(
                "/api/experiments/SEAL-1/blocks/1/seals", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, history.getStatusCode().value());
        assertEquals(1, json(history).path("seals").size());
    }

    @Test
    void seal_rejectsAfterAllocation_afterUnblind_andTwice() {
        assertEquals(201, create("SEAL-2", 2, "s2-create"));
        assertEquals(201, allocate("SEAL-2", "P1", "s2-alloc"));

        // 区组已有分配：409
        assertEquals(409, exchange("/api/experiments/SEAL-2/blocks/1/seals/S2A",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s2-seal-1"), null)
                .getStatusCode().value());

        // 已揭盲（必然伴随分配）：同样 409
        assertEquals(201, exchange(
                "/api/experiments/SEAL-2/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s2-ub"), "{\"reason\":\"核对\"}")
                .getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/SEAL-2/blocks/1/seals/S2B",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s2-seal-2"), null)
                .getStatusCode().value());

        // 区组2正常封存前先测摘要不匹配：422
        assertEquals(422, exchange("/api/experiments/SEAL-2/blocks/2/seals/S2E",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s2-seal-6"),
                "{\"expectedDigest\":\"deadbeef\"}").getStatusCode().value());

        // 区组2正常封存后重复封存：409
        assertEquals(201, exchange("/api/experiments/SEAL-2/blocks/2/seals/S2C",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s2-seal-3"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/SEAL-2/blocks/2/seals/S2D",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s2-seal-4"), null)
                .getStatusCode().value());
        // 同 sealKey 换区组：409
        assertEquals(409, exchange("/api/experiments/SEAL-2/blocks/1/seals/S2C",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s2-seal-5"), null)
                .getStatusCode().value());

        // 区组号越界：422；REVIEWER 无权封存：403
        assertEquals(422, exchange("/api/experiments/SEAL-2/blocks/9/seals/S2F",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s2-seal-7"), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/SEAL-2/blocks/1/seals/S2G",
                HttpMethod.POST, headers("r1", "REVIEWER", "s2-seal-8"), null)
                .getStatusCode().value());

        // 失败不产生封存记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'SEAL-2'",
                Integer.class));
    }

    @Test
    void seal_acceptsMatchingDigest_andExpansionKeepsHistoryAndConservesQuota() throws Exception {
        assertEquals(201, create("SEAL-3", 2, "s3-create"));
        String digest = v1Digest("SEAL-3");
        ResponseEntity<String> sealed = exchange(
                "/api/experiments/SEAL-3/blocks/1/seals/S3V1", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s3-seal"),
                "{\"expectedDigest\":\"" + digest + "\"}");
        assertEquals(201, sealed.getStatusCode().value());

        // 用满版本1的 4 个序号
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, allocate("SEAL-3", "P" + i, "s3-alloc-" + i));
        }

        // 前驱仍有名额时扩容：422（此处先制造一个仍有名额的区组2封存）
        assertEquals(201, exchange("/api/experiments/SEAL-3/blocks/2/seals/S3B1",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s3-seal-b1"), null)
                .getStatusCode().value());
        ResponseEntity<String> expandWithQuota = exchange(
                "/api/experiments/SEAL-3/blocks/2/seals/S3B2/expand", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s3-expand-bad"),
                "{\"predecessorSealId\":\"S3B1\",\"capacity\":4}");
        assertEquals(422, expandWithQuota.getStatusCode().value());
        assertTrue(expandWithQuota.getBody().contains("4"), "错误应给出剩余名额实际值");

        // 引用非最新/不存在的前驱：422；容量非4的倍数：422；摘要不符：422
        assertEquals(422, exchange("/api/experiments/SEAL-3/blocks/1/seals/S3VX/expand",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s3-expand-missing"),
                "{\"predecessorSealId\":\"NOPE\",\"capacity\":4}").getStatusCode().value());
        assertEquals(422, exchange("/api/experiments/SEAL-3/blocks/1/seals/S3VX/expand",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s3-expand-size"),
                "{\"predecessorSealId\":\"S3V1\",\"capacity\":3}").getStatusCode().value());
        assertEquals(422, exchange("/api/experiments/SEAL-3/blocks/1/seals/S3VX/expand",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s3-expand-digest"),
                "{\"predecessorSealId\":\"S3V1\",\"capacity\":4,\"expectedDigest\":\"bad\"}")
                .getStatusCode().value());
        // 多次失败后不留半成品：区组1仍只有版本1的4个席位、无后继封存
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'SEAL-3' AND block_no = 1",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'SEAL-3' "
                        + "AND block_no = 1", Integer.class));

        // 合法扩容：版本2、显式前驱、序号从5开始
        clock.advance(5_000L);
        ResponseEntity<String> expanded = exchange(
                "/api/experiments/SEAL-3/blocks/1/seals/S3V2/expand", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s3-expand"),
                "{\"predecessorSealId\":\"S3V1\",\"capacity\":4,\"expectedDigest\":\""
                        + digest + "\"}");
        assertEquals(201, expanded.getStatusCode().value());
        JsonNode expansion = json(expanded);
        assertEquals(2, expansion.path("versionNo").asInt());
        assertEquals("S3V1", expansion.path("predecessorId").asText());
        assertEquals(4, expansion.path("capacity").asInt());
        assertEquals(1_700_000_005_000L, expansion.path("sealedAt").asLong());
        assertEquals(List.of(5, 6, 7, 8), jdbc.queryForList(
                "SELECT seat_no FROM seat WHERE experiment_id = 'SEAL-3' AND block_no = 1 "
                        + "AND version_no = 2 ORDER BY seat_no", Integer.class));
        // 新版本每组仍两 A 两 B
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'SEAL-3' AND block_no = 1 "
                        + "AND version_no = 2 AND treatment = 'A'", Long.class));

        // 既有版本1分配历史不被改写
        assertEquals(List.of(1, 1, 1, 1), jdbc.queryForList(
                "SELECT version_no FROM allocation WHERE experiment_id = 'SEAL-3' "
                        + "ORDER BY participant_id", Integer.class));
        assertEquals(4L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SEAL-3' AND seal_id = 'S3V1'",
                Long.class));

        // 扩容后新分配落到版本2序号5
        assertEquals(201, allocate("SEAL-3", "P5", "s3-alloc-5"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version_no FROM allocation WHERE participant_id = 'P5'", Integer.class));
        assertEquals(5, jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE participant_id = 'P5'", Integer.class));
        assertEquals("S3V2", jdbc.queryForObject(
                "SELECT seal_id FROM allocation WHERE participant_id = 'P5'", String.class));

        // 不能在仍有名额的版本2上再次分叉扩容
        assertEquals(422, exchange("/api/experiments/SEAL-3/blocks/1/seals/S3V3/expand",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s3-expand-fork"),
                "{\"predecessorSealId\":\"S3V2\",\"capacity\":4}").getStatusCode().value());

        // 失败扩容不留半成品
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'SEAL-3' "
                        + "AND block_no = 1", Integer.class));
    }

    @Test
    void closeRemainder_skipsVacancies_allowsExpansion_andIsImmutable() throws Exception {
        assertEquals(201, create("SEAL-4", 2, "s4-create"));
        assertEquals(201, exchange("/api/experiments/SEAL-4/blocks/1/seals/S4V1",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s4-seal"), null)
                .getStatusCode().value());
        assertEquals(201, allocate("SEAL-4", "P1", "s4-alloc-1"));
        assertEquals(201, allocate("SEAL-4", "P2", "s4-alloc-2"));

        clock.advance(1_000L);
        ResponseEntity<String> closed = exchange(
                "/api/experiments/SEAL-4/blocks/1/seals/S4V1/closure", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s4-close"), null);
        assertEquals(200, closed.getStatusCode().value());
        JsonNode closedBody = json(closed);
        assertEquals("CLOSED", closedBody.path("status").asText());
        assertEquals(2, closedBody.path("allocated").asInt());
        assertEquals(0, closedBody.path("remaining").asInt());
        assertEquals(2, closedBody.path("abandoned").asInt(), "放弃名额=容量-已分");
        assertEquals(1_700_000_001_000L, closedBody.path("closedAt").asLong());

        // 重复关闭：409
        assertEquals(409, exchange("/api/experiments/SEAL-4/blocks/1/seals/S4V1/closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s4-close-again"), null)
                .getStatusCode().value());

        // 区组1剩余序号3/4被跳过：下一名分配落到区组2
        assertEquals(201, allocate("SEAL-4", "P3", "s4-alloc-3"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE participant_id = 'P3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE participant_id = 'P3'", Integer.class));
        assertNull(jdbc.queryForObject(
                "SELECT seal_id FROM allocation WHERE participant_id = 'P3'", String.class),
                "未封存区组的 seal_id 保持 NULL 语义");

        // 关闭后允许扩容版本2
        assertEquals(201, exchange("/api/experiments/SEAL-4/blocks/1/seals/S4V2/expand",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s4-expand"),
                "{\"predecessorSealId\":\"S4V1\",\"capacity\":4}").getStatusCode().value());

        // 扩容后 block1 版本2排序优先：P4~P7 依次占用序号5~8
        String[] expectedSeats = {"5", "6", "7", "8"};
        for (int i = 4; i <= 7; i++) {
            assertEquals(201, allocate("SEAL-4", "P" + i, "s4-alloc-" + i));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT block_no FROM allocation WHERE participant_id = 'P" + i + "'",
                    Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT version_no FROM allocation WHERE participant_id = 'P" + i + "'",
                    Integer.class));
            assertEquals(expectedSeats[i - 4], jdbc.queryForObject(
                    "SELECT seat_no FROM allocation WHERE participant_id = 'P" + i + "'",
                    String.class));
        }

        // 历史分配 P1/P2 仍为版本1、序号1/2，未被扩容改写
        assertEquals(List.of(1, 2), jdbc.queryForList(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'SEAL-4' "
                        + "AND version_no = 1 ORDER BY participant_id", Integer.class));
    }

    @Test
    void diagnostic_reportsActualQuotaDifference_andUtcHalfOpenWindow() throws Exception {
        assertEquals(201, create("SEAL-5", 2, "s5-create"));
        // 区组1：封存、分配2人、关闭余量（放弃2）、扩容版本2；事件时刻严格递增便于时间窗断言
        assertEquals(201, exchange("/api/experiments/SEAL-5/blocks/1/seals/S5V1",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s5-seal"), null)
                .getStatusCode().value());
        clock.advance(1_000L);
        assertEquals(201, allocate("SEAL-5", "P1", "s5-alloc-1"));
        clock.advance(1_000L);
        assertEquals(201, allocate("SEAL-5", "P2", "s5-alloc-2"));
        clock.advance(1_000L);
        assertEquals(200, exchange("/api/experiments/SEAL-5/blocks/1/seals/S5V1/closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s5-close"), null)
                .getStatusCode().value());
        clock.advance(1_000L);
        assertEquals(201, exchange("/api/experiments/SEAL-5/blocks/1/seals/S5V2/expand",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s5-expand"),
                "{\"predecessorSealId\":\"S5V1\",\"capacity\":4}").getStatusCode().value());

        long t0 = 1_700_000_000_000L;
        ResponseEntity<String> diag = exchange(
                "/api/experiments/SEAL-5/blocks/1/diagnostic?from=" + t0 + "&to=" + (t0 + 10_000L),
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(200, diag.getStatusCode().value());
        JsonNode d = json(diag);
        assertEquals(8, d.path("totalCapacity").asInt(), "容量合计实际值");
        assertEquals(2, d.path("totalAllocated").asInt());
        assertEquals(4, d.path("totalRemaining").asInt(), "版本2四个余量");
        assertEquals(2, d.path("totalAbandoned").asInt(), "版本1关闭放弃2个");
        assertEquals(0, d.path("difference").asInt(), "容量=已分+余量+放弃");
        assertTrue(d.path("quotaConsistent").asBoolean());
        assertEquals(2, d.path("versions").size());
        // 时间窗左闭右开：窗内有 SEAL/两次ALLOCATE/CLOSE/EXPAND
        List<String> types = eventTypes(d);
        assertTrue(types.contains("SEAL"));
        assertTrue(types.contains("EXPAND"));
        assertTrue(types.contains("CLOSE"));
        assertEquals(2, types.stream().filter("ALLOCATE"::equals).count());

        // 右端点开区间：to=首个分配时刻(t0+1000) 应排除该 ALLOCATE 与之后事件
        ResponseEntity<String> edge = exchange(
                "/api/experiments/SEAL-5/blocks/1/diagnostic?from=" + t0 + "&to=" + (t0 + 1_000L),
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        JsonNode edgeBody = json(edge);
        assertEquals(List.of("SEAL"), eventTypes(edgeBody), "右端点时刻的事件必须被排除");

        // 未封存区组：历史分配 version_no 为 NULL，仍计入实际名额。
        // 先关闭区组1版本2余量，使新分配跳过区组1落入未封存的区组2。
        clock.advance(1_000L);
        assertEquals(200, exchange("/api/experiments/SEAL-5/blocks/1/seals/S5V2/closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s5-close-v2"), null)
                .getStatusCode().value());
        assertEquals(201, allocate("SEAL-5", "P9", "s5-alloc-9"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE participant_id = 'P9'", Integer.class));
        JsonNode diag2 = json(exchange(
                "/api/experiments/SEAL-5/blocks/2/diagnostic", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null));
        assertEquals(4, diag2.path("totalCapacity").asInt());
        assertEquals(1, diag2.path("totalAllocated").asInt());
        assertEquals(3, diag2.path("totalRemaining").asInt());
        assertEquals(0, diag2.path("difference").asInt());
        assertFalse(diag2.path("versions").get(0).path("sealed").asBoolean());
    }

    @Test
    void sealWrites_areIdempotent_failuresDoNotConsumeKey() throws Exception {
        assertEquals(201, create("SEAL-6", 2, "s6-create"));

        // 封存同键同参重放：原 201 与同一响应体，仅一条记录
        ResponseEntity<String> first = exchange(
                "/api/experiments/SEAL-6/blocks/1/seals/S6", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s6-key"), null);
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = exchange(
                "/api/experiments/SEAL-6/blocks/1/seals/S6", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s6-key"), null);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE id = 'S6'", Integer.class));

        // 同键异参（不同区组）：409
        assertEquals(409, exchange("/api/experiments/SEAL-6/blocks/2/seals/S6",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s6-key"), null)
                .getStatusCode().value());

        // 失败（摘要不符）不占键：换合法请求同键成功
        assertEquals(422, exchange("/api/experiments/SEAL-6/blocks/2/seals/S6B",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s6-key-2"),
                "{\"expectedDigest\":\"bad\"}").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 's6-key-2'",
                Integer.class));
        assertEquals(201, exchange("/api/experiments/SEAL-6/blocks/2/seals/S6B",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s6-key-2"), null)
                .getStatusCode().value());

        // 扩容与关闭同键重放原结果：先关闭区组1余量，使分配进入区组2并将其用满
        ResponseEntity<String> close1 = exchange(
                "/api/experiments/SEAL-6/blocks/1/seals/S6/closure", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s6-close"), null);
        assertEquals(200, close1.getStatusCode().value());
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, allocate("SEAL-6", "B" + i, "s6-b-" + i));
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE participant_id = 'B1'", Integer.class));
        ResponseEntity<String> expand1 = exchange(
                "/api/experiments/SEAL-6/blocks/2/seals/S6C/expand", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s6-expand"),
                "{\"predecessorSealId\":\"S6B\",\"capacity\":4}");
        assertEquals(201, expand1.getStatusCode().value());
        ResponseEntity<String> expandReplay = exchange(
                "/api/experiments/SEAL-6/blocks/2/seals/S6C/expand", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s6-expand"),
                "{\"predecessorSealId\":\"S6B\",\"capacity\":4}");
        assertEquals(201, expandReplay.getStatusCode().value());
        assertEquals(expand1.getBody(), expandReplay.getBody());

        // 关闭同键重放原 200 与 CLOSED（而非重复关闭 409）
        ResponseEntity<String> closeReplay = exchange(
                "/api/experiments/SEAL-6/blocks/1/seals/S6/closure", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s6-close"), null);
        assertEquals(200, closeReplay.getStatusCode().value());
        assertEquals(close1.getBody(), closeReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE id = 'S6' AND status = 'CLOSED'",
                Integer.class));
    }

    @Test
    void unblindingAfterExpansion_readsFixedHistoricalTreatment() throws Exception {
        assertEquals(201, create("SEAL-7", 2, "s7-create"));
        assertEquals(201, exchange("/api/experiments/SEAL-7/blocks/1/seals/S7V1",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s7-seal"), null)
                .getStatusCode().value());
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, allocate("SEAL-7", "P" + i, "s7-" + i));
        }
        assertEquals(201, exchange("/api/experiments/SEAL-7/blocks/1/seals/S7V2/expand",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s7-expand"),
                "{\"predecessorSealId\":\"S7V1\",\"capacity\":4}").getStatusCode().value());
        assertEquals(201, allocate("SEAL-7", "P5", "s7-5"));

        // P5 位于版本2；揭盲读出的处理代码必须等于其固化席位
        String expected = jdbc.queryForObject(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no AND s.version_no = a.version_no "
                        + "WHERE a.participant_id = 'P5'", String.class);
        ResponseEntity<String> apply = exchange(
                "/api/experiments/SEAL-7/participants/P5/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s7-ub"), "{\"reason\":\"扩容后核对\"}");
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("r1", "REVIEWER", "s7-approve"), null)
                .getStatusCode().value());
        JsonNode result = json(exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals(expected, result.path("treatment").asText());

        // 版本1参与者 P1 的历史处理代码同样可揭盲且不被扩容改写
        String expectedP1 = jdbc.queryForObject(
                "SELECT treatment FROM seat WHERE experiment_id = 'SEAL-7' AND block_no = 1 "
                        + "AND version_no = 1 AND seat_no = 1", String.class);
        ResponseEntity<String> apply1 = exchange(
                "/api/experiments/SEAL-7/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "s7-ub1"), "{\"reason\":\"历史核对\"}");
        String ub1 = json(apply1).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ub1 + "/approval",
                HttpMethod.POST, headers("r2", "REVIEWER", "s7-approve1"), null)
                .getStatusCode().value());
        assertEquals(expectedP1, json(exchange("/api/unblind-requests/" + ub1 + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null))
                .path("treatment").asText());
    }
}
