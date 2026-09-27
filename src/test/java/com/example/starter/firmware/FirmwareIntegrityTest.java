package com.example.starter.firmware;

import com.example.starter.firmware.service.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件分片完整性核验 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、登记校验 422、完整性失败与重拉代次、撤销、幂等与只读查询边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareIntegrityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM integrity_event");
        jdbc.update("DELETE FROM chunk_receipt");
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM firmware_chunk");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String dg(String seed) {
        return Digests.sha256Hex(seed);
    }

    private static List<String> digests(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> dg("chunk-" + i)).toList();
    }

    private static String chunksJson(List<String> digests) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < digests.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"chunkIndex\":").append(i).append(",\"digest\":\"").append(digests.get(i))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createReleaseWithManifest(String requestId, String model, int ratio, List<String> digests)
            throws Exception {
        String packageDigest = Digests.aggregateOf(digests);
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                 "chunks":%s,"packageDigest":"%s"}
                """.formatted(requestId, model, ratio, chunksJson(digests), packageDigest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(digests.size()))
                .andExpect(jsonPath("$.packageDigest").value(packageDigest))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private long pullTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return id.longValue();
    }

    private static String chunkBody(int index, String digest) {
        return "{\"chunkIndex\":%d,\"digest\":\"%s\"}".formatted(index, digest);
    }

    private org.springframework.test.web.servlet.ResultActions submitChunks(
            long taskId, String requestId, String... chunkEntries) throws Exception {
        String chunks = "[" + String.join(",", chunkEntries) + "]";
        return mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                .content("{\"requestId\":\"%s\",\"chunks\":%s}".formatted(requestId, chunks)));
    }

    @Test
    void 主流程_登记清单_逐批接收_可安装_回执成功() throws Exception {
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        List<String> digests = digests(3);
        long releaseId = createReleaseWithManifest("r-r", "m1", 100, digests);

        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.attemptNo").value(1))
                .andExpect(jsonPath("$.task.receivedChunks").value(0))
                .andExpect(jsonPath("$.task.requiredChunks").value(3))
                .andReturn();
        long taskId = ((Number) com.jayway.jsonpath.JsonPath.read(
                pull.getResponse().getContentAsString(), "$.task.taskId")).longValue();

        // 未收齐分片时禁止安装回执，响应给出实际/要求数量
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-0\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_INSTALLABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("0/3")));

        // 第一批：分片0、1，任务保持 PENDING
        submitChunks(taskId, "c-1", chunkBody(0, digests.get(0)), chunkBody(1, digests.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.receivedChunks").value(2))
                .andExpect(jsonPath("$.requiredChunks").value(3));

        // 第二批：分片2，集合齐备且聚合匹配，进入 INSTALLABLE
        submitChunks(taskId, "c-2", chunkBody(2, digests.get(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.receivedChunks").value(3));

        // 可安装后回执成功，设备版本更新
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 明细：逐分片期望/实际一致，缺失差额为0
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredChunks").value(3))
                .andExpect(jsonPath("$.receivedChunks").value(3))
                .andExpect(jsonPath("$.missingChunkCount").value(0))
                .andExpect(jsonPath("$.chunks.length()").value(3))
                .andExpect(jsonPath("$.chunks[0].expectedDigest").value(digests.get(0)))
                .andExpect(jsonPath("$.chunks[0].receivedDigest").value(digests.get(0)));

        // 诊断：3 条接收事件 + 1 条可安装判定，聚合结果等于完整包摘要
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedEventCount").value(3))
                .andExpect(jsonPath("$.installableEventCount").value(1))
                .andExpect(jsonPath("$.failureEventCount").value(0))
                .andExpect(jsonPath("$.aggregateDigest").value(Digests.aggregateOf(digests)))
                .andExpect(jsonPath("$.packageDigest").value(Digests.aggregateOf(digests)));

        // 历史：1 个代次、3 条接收证据、4 条事件，读取不改变状态
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.receipts.length()").value(3))
                .andExpect(jsonPath("$.events.length()").value(4));
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void 登记清单_重复序号_缺口_聚合不匹配_格式非法_均422() throws Exception {
        // 重复序号
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"m-1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "chunks":[{"chunkIndex":0,"digest":"%s"},{"chunkIndex":0,"digest":"%s"}],
                         "packageDigest":"%s"}
                        """.formatted(dg("a"), dg("b"), dg("p"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CHUNK_INDEX"));

        // 序号缺口（0、2 缺 1）
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"m-2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "chunks":[{"chunkIndex":0,"digest":"%s"},{"chunkIndex":2,"digest":"%s"}],
                         "packageDigest":"%s"}
                        """.formatted(dg("a"), dg("c"), dg("p"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_INDEX_GAP"));

        // 聚合摘要不匹配：响应给出实际计算值与登记值
        List<String> digests = digests(2);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"m-3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "chunks":%s,"packageDigest":"%s"}
                        """.formatted(chunksJson(digests), dg("wrong-package"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGGREGATE_MISMATCH"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(Digests.aggregateOf(digests))));

        // 摘要格式非法
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"m-4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "chunks":[{"chunkIndex":0,"digest":"not-a-digest"}],
                         "packageDigest":"%s"}
                        """.formatted(dg("p"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DIGEST_FORMAT"));

        // 只提供 chunks 不提供 packageDigest：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"m-5","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "chunks":[{"chunkIndex":0,"digest":"%s"}]}
                        """.formatted(dg("a"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MANIFEST"));

        // 422 校验失败不占键：同 requestId 修正参数后可成功
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"m-3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "chunks":%s,"packageDigest":"%s"}
                        """.formatted(chunksJson(digests), Digests.aggregateOf(digests))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(2));
    }

    @Test
    void 清单补登与锁定_有任务拉取后禁止登记或修改() throws Exception {
        // 未登记清单的发布单：GET manifest 404
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r-1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk()).andReturn();
        long releaseId = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.releaseId")).longValue();
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MANIFEST_NOT_REGISTERED"));

        // 补登成功，清单按序号规范排序返回
        List<String> digests = digests(2);
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("""
                                {"requestId":"mf-1","chunks":%s,"packageDigest":"%s"}
                                """.formatted(chunksJson(digests), Digests.aggregateOf(digests))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(2))
                .andExpect(jsonPath("$.chunks[0].digest").value(digests.get(0)));

        // 重复登记：409，清单不可修改
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("""
                                {"requestId":"mf-2","chunks":%s,"packageDigest":"%s"}
                                """.formatted(chunksJson(digests), Digests.aggregateOf(digests))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_ALREADY_REGISTERED"));

        // 另一发布单：有任务拉取后禁止补登
        registerDevice("d-r2", "d2", "m2", "1.0.0", 1);
        MvcResult created2 = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r-2","model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk()).andReturn();
        long releaseId2 = ((Number) com.jayway.jsonpath.JsonPath.read(
                created2.getResponse().getContentAsString(), "$.releaseId")).longValue();
        pullTask("d2", "p-2");
        mockMvc.perform(post("/api/releases/" + releaseId2 + "/manifest").contentType("application/json")
                        .content("""
                                {"requestId":"mf-3","chunks":%s,"packageDigest":"%s"}
                                """.formatted(chunksJson(digests), Digests.aggregateOf(digests))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_LOCKED"));

        // 登记后清单只读可查
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageDigest").value(Digests.aggregateOf(digests)));
    }

    @Test
    void 摘要不匹配_完整性失败_禁止回执_不计失败率_重拉新代次_旧证据保留() throws Exception {
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        List<String> digests = digests(2);
        long releaseId = createReleaseWithManifest("r-r", "m1", 100, digests);
        long taskId = pullTask("d1", "p-1");

        // 分片0合法接收
        submitChunks(taskId, "c-1", chunkBody(0, digests.get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 分片1摘要不匹配：409 DIGEST_MISMATCH，任务转 INTEGRITY_FAILED
        submitChunks(taskId, "c-2", chunkBody(1, dg("tampered")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));

        // 禁止安装回执，且不计入设备执行失败率
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 失败事件固化：期望/实际摘要与时刻可查
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/diagnostics"))
                .andExpect(jsonPath("$.failureEventCount").value(1))
                .andExpect(jsonPath("$.events[1].eventType").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.events[1].reason").value("DIGEST_MISMATCH"))
                .andExpect(jsonPath("$.events[1].expectedDigest").value(digests.get(1)))
                .andExpect(jsonPath("$.events[1].receivedDigest").value(dg("tampered")));

        // 失败不占键：重拉建立新代次后，同一 requestId 可携带正确分片成功
        MvcResult repull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.attemptNo").value(2))
                .andExpect(jsonPath("$.task.receivedChunks").value(0))
                .andReturn();
        long repulledTaskId = ((Number) com.jayway.jsonpath.JsonPath.read(
                repull.getResponse().getContentAsString(), "$.task.taskId")).longValue();
        org.assertj.core.api.Assertions.assertThat(repulledTaskId).isEqualTo(taskId);

        submitChunks(taskId, "c-2", chunkBody(0, digests.get(0)), chunkBody(1, digests.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.attemptNo").value(2));

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 旧代次失败证据保留：2 个代次、3 条接收证据（旧1+新2）、失败事件仍在
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/history"))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.attempts[1].status").value("SUCCESS"))
                .andExpect(jsonPath("$.receipts.length()").value(3));
        Long failedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INTEGRITY_FAILED'",
                Long.class, taskId);
        assertThat(failedEvents).isEqualTo(1);
    }

    @Test
    void 序号越界_批内重复_重复提交_均完整性失败且不留部分证据() throws Exception {
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 2);
        registerDevice("d-r3", "d3", "m1", "1.0.0", 3);
        List<String> digests = digests(2);
        createReleaseWithManifest("r-r", "m1", 100, digests);

        // 序号越界
        long t1 = pullTask("d1", "p-1");
        submitChunks(t1, "c-1", chunkBody(5, dg("x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INDEX_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("0..1")));
        mockMvc.perform(get("/api/tasks/" + t1))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_receipt WHERE task_id = ?", Long.class, t1)).isZero();

        // 批内重复序号：整批失败，不写入任何接收证据
        long t2 = pullTask("d2", "p-2");
        submitChunks(t2, "c-2", chunkBody(0, digests.get(0)), chunkBody(0, digests.get(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INDEX"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_receipt WHERE task_id = ?", Long.class, t2)).isZero();

        // 与已接收证据重复：第一批合法，第二批含已接收序号，整批失败且第一批证据保留
        long t3 = pullTask("d3", "p-3");
        submitChunks(t3, "c-3", chunkBody(0, digests.get(0)))
                .andExpect(status().isOk());
        submitChunks(t3, "c-4", chunkBody(0, digests.get(0)), chunkBody(1, digests.get(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INDEX"));
        mockMvc.perform(get("/api/tasks/" + t3))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_receipt WHERE task_id = ?", Long.class, t3)).isEqualTo(1);
    }

    @Test
    void 撤销_未终结任务可撤销_终结后409_撤销后拒绝接收与回执() throws Exception {
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 2);
        List<String> digests = digests(2);
        long releaseId = createReleaseWithManifest("r-r", "m1", 100, digests);

        // PENDING 撤销
        long t1 = pullTask("d1", "p-1");
        mockMvc.perform(post("/api/tasks/" + t1 + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 撤销后拒绝接收与回执
        submitChunks(t1, "c-x", chunkBody(0, digests.get(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-x\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        // 重复撤销（新 requestId）幂等返回当前状态
        mockMvc.perform(post("/api/tasks/" + t1 + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // INSTALLABLE 撤销
        long t2 = pullTask("d2", "p-2");
        submitChunks(t2, "c-1", chunkBody(0, digests.get(0)), chunkBody(1, digests.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"));
        mockMvc.perform(post("/api/tasks/" + t2 + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 已终结任务撤销 409
        registerDevice("d-r3", "d3", "m1", "1.0.0", 3);
        long t3 = pullTask("d3", "p-3");
        submitChunks(t3, "c-2", chunkBody(0, digests.get(0)), chunkBody(1, digests.get(1)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-3\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t3 + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv-4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_FINAL"));

        // 取消发布单：不影响已终结任务
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cx-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks/" + t3))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void 取消发布单_可安装与完整性失败任务一并取消() throws Exception {
        registerDevice("d-r1", "d1", "m1", "1.0.0", 1);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 2);
        registerDevice("d-r3", "d3", "m1", "1.0.0", 3);
        List<String> digests = digests(2);
        long releaseId = createReleaseWithManifest("r-r", "m1", 100, digests);

        long t1 = pullTask("d1", "p-1"); // 将转 INSTALLABLE
        long t2 = pullTask("d2", "p-2"); // 将转 INTEGRITY_FAILED
        long t3 = pullTask("d3", "p-3"); // 保持 PENDING

        submitChunks(t1, "c-1", chunkBody(0, digests.get(0)), chunkBody(1, digests.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"));
        submitChunks(t2, "c-2", chunkBody(0, dg("bad")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cx-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/tasks/" + t1)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/tasks/" + t2)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/tasks/" + t3)).andExpect(jsonPath("$.status").value("CANCELLED"));

        // 尝试代次一并终结：历史只读可查，状态与终结时刻稳定
        mockMvc.perform(get("/api/tasks/" + t1 + "/integrity/history"))
                .andExpect(jsonPath("$.attempts[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.attempts[0].finalizedAtUtc").isNotEmpty());
        mockMvc.perform(get("/api/tasks/" + t2 + "/integrity/history"))
                .andExpect(jsonPath("$.attempts[0].status").value("CANCELLED"));
    }

    @Test
    void 完整性失败_同requestId同参重放首次失败响应_不重复落事件() throws Exception {
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        List<String> digests = digests(2);
        createReleaseWithManifest("r-r", "m1", 100, digests);
        long taskId = pullTask("d1", "p-1");

        // 首次失败：409 DIGEST_MISMATCH
        submitChunks(taskId, "c-1", chunkBody(0, dg("bad")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));
        // 同 requestId 同参重放：返回首次失败原因，不重复固化失败事件（失败不占键但重放确定）
        submitChunks(taskId, "c-1", chunkBody(0, dg("bad")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));
        Long failureEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INTEGRITY_FAILED'",
                Long.class, taskId);
        assertThat(failureEvents).isEqualTo(1);
        // 失败未占键：重拉后同 requestId 携带正确参数可成功
        pullTask("d1", "p-2");
        submitChunks(taskId, "c-1", chunkBody(0, digests.get(0)), chunkBody(1, digests.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"));
    }

    @Test
    void 幂等_同键同参重放_异参409_接收证据不重复() throws Exception {
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        List<String> digests = digests(2);
        createReleaseWithManifest("r-r", "m1", 100, digests);
        long taskId = pullTask("d1", "p-1");

        MvcResult first = submitChunks(taskId, "c-1", chunkBody(0, digests.get(0)))
                .andExpect(status().isOk())
                .andReturn();
        // 同键同参重放：响应一致，证据不重复
        MvcResult replay = submitChunks(taskId, "c-1", chunkBody(0, digests.get(0)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_receipt WHERE task_id = ?", Long.class, taskId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ?", Long.class, taskId)).isEqualTo(1);

        // 同键异参：409
        submitChunks(taskId, "c-1", chunkBody(1, digests.get(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 查询边界_旧式任务无完整性数据_任务不存在404_时间区间左闭右开() throws Exception {
        // 旧式发布单任务：无清单，完整性查询 409，回执不受门禁影响
        registerDevice("d-r", "d1", "m1", "1.0.0", 1);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r-1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk());
        long legacyTask = pullTask("d1", "p-1");
        mockMvc.perform(get("/api/tasks/" + legacyTask + "/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_NOT_REGISTERED"));
        submitChunks(legacyTask, "c-1", chunkBody(0, dg("x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_NOT_REGISTERED"));
        mockMvc.perform(post("/api/tasks/" + legacyTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 任务不存在：404
        mockMvc.perform(get("/api/tasks/9999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/9999/integrity")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/9999/integrity/history")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/9999/integrity/diagnostics")).andExpect(status().isNotFound());

        // 时间区间左闭右开：取第一条事件时刻作为边界
        registerDevice("d-r2", "d2", "m2", "1.0.0", 1);
        List<String> digests = digests(1);
        createReleaseWithManifest("r-2", "m2", 100, digests);
        long taskId = pullTask("d2", "p-2");
        submitChunks(taskId, "c-2", chunkBody(0, digests.get(0))).andExpect(status().isOk());

        MvcResult history = mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andReturn();
        String firstAt = com.jayway.jsonpath.JsonPath.read(
                history.getResponse().getContentAsString(), "$.events[0].occurredAtUtc");
        // from = 事件时刻（左闭，包含）
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/history").param("from", firstAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2));
        // to = 事件时刻（右开，排除该时刻及之后）
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/history").param("to", firstAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0));
        // 非法时间格式：400
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity/history").param("from", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }
}
