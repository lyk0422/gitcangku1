package com.example.starter.firmware;

import com.example.starter.firmware.service.IntegrityService;
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
 * 固件分片完整性核验与安装前门禁区：主流程、失败分支、证据固化与幂等边界（H2 内存库，MODE=MySQL）。
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
        jdbc.update("DELETE FROM task_chunk_receipt");
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM release_manifest_chunk");
        jdbc.update("DELETE FROM release_manifest");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String digestOf(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d}
                """.formatted(requestId, model, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long createRelease(String requestId, String model, int ratio, int sampleFloor, int threshold)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                "sampleFloor":%d,"failureThresholdPercent":%d}
                """.formatted(requestId, model, ratio, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private static String manifestJson(String requestId, List<String> digests, String aggregate) {
        StringBuilder chunks = new StringBuilder();
        for (int i = 0; i < digests.size(); i++) {
            if (i > 0) {
                chunks.append(',');
            }
            chunks.append("{\"index\":%d,\"digest\":\"%s\"}".formatted(i, digests.get(i)));
        }
        return "{\"requestId\":\"%s\",\"chunks\":[%s],\"aggregateDigest\":\"%s\"}"
                .formatted(requestId, chunks, aggregate);
    }

    private void registerManifest(long releaseId, String requestId, List<String> digests) throws Exception {
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestJson(requestId, digests, IntegrityService.aggregateOf(digests))))
                .andExpect(status().isOk());
    }

    private long pullTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private static String batchJson(String requestId, List<String> digests) {
        StringBuilder chunks = new StringBuilder();
        for (int i = 0; i < digests.size(); i++) {
            if (i > 0) {
                chunks.append(',');
            }
            chunks.append("{\"index\":%d,\"digest\":\"%s\"}".formatted(i, digests.get(i)));
        }
        return "{\"requestId\":\"%s\",\"chunks\":[%s]}".formatted(requestId, chunks);
    }

    @Test
    void 主流程_登记清单逐片接收可安装并成功回执() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", 100);
        List<String> digests = List.of(digestOf('a'), digestOf('b'), digestOf('c'));
        registerManifest(releaseId, "r3", digests);

        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(3))
                .andExpect(jsonPath("$.aggregateDigest").value(IntegrityService.aggregateOf(digests)))
                .andExpect(jsonPath("$.chunks[0].digest").value(digestOf('a')))
                .andExpect(jsonPath("$.registeredAtUtc").isString());

        long taskId = pullTask("d1", "r4");

        // 逐片接收：第1片后仍 PENDING，诊断返回实际/要求/差额
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(batchJson("r5", List.of(digestOf('a')))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.receivedCount").value(1))
                .andExpect(jsonPath("$.requiredCount").value(3))
                .andExpect(jsonPath("$.missingCount").value(2))
                .andExpect(jsonPath("$.missingIndices[0]").value(1))
                .andExpect(jsonPath("$.missingIndices[1]").value(2))
                .andExpect(jsonPath("$.verdict").doesNotExist());

        // 剩余分片到齐且聚合匹配：INSTALLABLE
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content("""
                                {"requestId":"r6","chunks":[{"index":1,"digest":"%s"},{"index":2,"digest":"%s"}]}
                                """.formatted(digestOf('b'), digestOf('c'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.verdict").value("INSTALLABLE"))
                .andExpect(jsonPath("$.receivedCount").value(3))
                .andExpect(jsonPath("$.missingCount").value(0))
                .andExpect(jsonPath("$.actualAggregate").value(IntegrityService.aggregateOf(digests)))
                .andExpect(jsonPath("$.expectedAggregate").value(IntegrityService.aggregateOf(digests)))
                .andExpect(jsonPath("$.decidedAtUtc").isString());

        // 可安装任务允许成功回执并更新设备版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 历史：单代次、3笔接收证据，判定字段已固化
        mockMvc.perform(get("/api/tasks/" + taskId + "/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].verdict").value("INSTALLABLE"))
                .andExpect(jsonPath("$.attempts[0].toVersion").value("2.0.0"))
                .andExpect(jsonPath("$.attempts[0].receipts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].receipts[0].matched").value(true));
    }

    @Test
    void 登记_重复序号缺口聚合不匹配422_格式错误400() throws Exception {
        long releaseId = createRelease("r1", "m1", 100);

        // 重复序号
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("""
                                {"requestId":"r2","chunks":[{"index":0,"digest":"%s"},{"index":0,"digest":"%s"}],
                                "aggregateDigest":"%s"}
                                """.formatted(digestOf('a'), digestOf('b'), digestOf('f'))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_INDEX_DUPLICATE"));

        // 缺口：0 之后直接 2
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("""
                                {"requestId":"r3","chunks":[{"index":0,"digest":"%s"},{"index":2,"digest":"%s"}],
                                "aggregateDigest":"%s"}
                                """.formatted(digestOf('a'), digestOf('b'), digestOf('f'))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_INDEX_GAP"));

        // 聚合摘要不匹配
        List<String> digests = List.of(digestOf('a'), digestOf('b'));
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestJson("r4", digests, digestOf('f'))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGGREGATE_MISMATCH"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(IntegrityService.aggregateOf(digests))));

        // 摘要格式错误：400
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("""
                                {"requestId":"r5","chunks":[{"index":0,"digest":"abc"}],
                                "aggregateDigest":"%s"}
                                """.formatted(digestOf('f'))))
                .andExpect(status().isBadRequest());

        // 失败不占键：同 requestId 修正参数后成功
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestJson("r4", digests, IntegrityService.aggregateOf(digests))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(2));
    }

    @Test
    void 清单_有任务拉取后不可修改_无任务可覆盖重登记() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", 100);
        registerManifest(releaseId, "r3", List.of(digestOf('a')));

        // 无任务时允许覆盖重登记
        registerManifest(releaseId, "r4", List.of(digestOf('a'), digestOf('b')));
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(jsonPath("$.chunkCount").value(2));

        // 有任务拉取后不可修改
        pullTask("d1", "r5");
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestJson("r6", List.of(digestOf('c')),
                                IntegrityService.aggregateOf(List.of(digestOf('c'))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_LOCKED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(jsonPath("$.chunkCount").value(2));

        // 未登记清单的发布单：404
        long bareRelease = createRelease("r7", "m2", 100);
        mockMvc.perform(get("/api/releases/" + bareRelease + "/manifest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MANIFEST_NOT_FOUND"));
    }

    @Test
    void 门禁_未核验任务禁止回执_无清单任务不能接收分片() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m2", "1.0.0", 1);
        long releaseId = createRelease("r3", "m1", 100);
        registerManifest(releaseId, "r4", List.of(digestOf('a')));
        long taskId = pullTask("d1", "r5");

        // 登记了清单的 PENDING 任务禁止安装回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_INSTALLABLE"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_INSTALLABLE"));

        // 未登记清单的发布单任务不能接收分片
        long bareRelease = createRelease("r8", "m2", 100);
        long bareTask = pullTask("d2", "r9");
        mockMvc.perform(post("/api/tasks/" + bareTask + "/chunks").contentType("application/json")
                        .content(batchJson("r10", List.of(digestOf('a')))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_NOT_REGISTERED"));

        // 无清单任务沿用旧行为：可直接回执
        mockMvc.perform(post("/api/tasks/" + bareTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r11\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void 接收_摘要不匹配转INTEGRITY_FAILED_禁止回执且不计失败率() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r3", "m1", 100, 2, 50);
        registerManifest(releaseId, "r4", List.of(digestOf('a'), digestOf('b')));
        long taskId = pullTask("d1", "r5");

        // 第1片正确，第2片摘要不匹配：整体判定 INTEGRITY_FAILED
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(batchJson("r6", List.of(digestOf('a')))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content("""
                                {"requestId":"r7","chunks":[{"index":1,"digest":"%s"}]}
                                """.formatted(digestOf('f'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.verdict").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.verdictReason").value(
                        org.hamcrest.Matchers.containsString("摘要不匹配")))
                .andExpect(jsonPath("$.decidedAtUtc").isString());

        // 失败证据固化：第2片 matched=false
        mockMvc.perform(get("/api/tasks/" + taskId + "/attempts"))
                .andExpect(jsonPath("$.attempts[0].receipts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].receipts[1].matched").value(false))
                .andExpect(jsonPath("$.attempts[0].receipts[1].expectedDigest").value(digestOf('b')));

        // 禁止安装和成功回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));

        // 第二台设备同样完整性失败：不计入设备执行失败率，发布单不暂停
        long task2 = pullTask("d2", "r10");
        mockMvc.perform(post("/api/tasks/" + task2 + "/chunks").contentType("application/json")
                        .content("""
                                {"requestId":"r11","chunks":[{"index":0,"digest":"%s"}]}
                                """.formatted(digestOf('f'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0));
    }

    @Test
    void 接收_重复序号与越界序号转INTEGRITY_FAILED() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        registerDevice("r3", "d3", "m1", "1.0.0", 3);
        long releaseId = createRelease("r4", "m1", 100);
        registerManifest(releaseId, "r5", List.of(digestOf('a'), digestOf('b')));

        // 批内重复序号
        long t1 = pullTask("d1", "r6");
        mockMvc.perform(post("/api/tasks/" + t1 + "/chunks").contentType("application/json")
                        .content("""
                                {"requestId":"r7","chunks":[{"index":0,"digest":"%s"},{"index":0,"digest":"%s"}]}
                                """.formatted(digestOf('a'), digestOf('a'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.verdictReason").value(
                        org.hamcrest.Matchers.containsString("重复序号")));

        // 序号越界
        long t2 = pullTask("d2", "r8");
        mockMvc.perform(post("/api/tasks/" + t2 + "/chunks").contentType("application/json")
                        .content("""
                                {"requestId":"r9","chunks":[{"index":2,"digest":"%s"}]}
                                """.formatted(digestOf('a'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.verdictReason").value(
                        org.hamcrest.Matchers.containsString("越界")));

        // 与已接收序号重复
        long t3 = pullTask("d3", "r10");
        mockMvc.perform(post("/api/tasks/" + t3 + "/chunks").contentType("application/json")
                        .content(batchJson("r11", List.of(digestOf('a')))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/tasks/" + t3 + "/chunks").contentType("application/json")
                        .content(batchJson("r12", List.of(digestOf('a')))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.verdictReason").value(
                        org.hamcrest.Matchers.containsString("重复接收序号")));
        // 重复接收不新增证据行
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ?", Long.class, t3)).isEqualTo(1);
    }

    @Test
    void 重拉_建立新代次旧证据保留_新代次重新核验后可安装() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", 100);
        List<String> digests = List.of(digestOf('a'), digestOf('b'));
        registerManifest(releaseId, "r3", digests);
        long taskId = pullTask("d1", "r4");

        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content("""
                                {"requestId":"r5","chunks":[{"index":0,"digest":"%s"}]}
                                """.formatted(digestOf('f'))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));

        // 重新拉取：新代次，任务回到 PENDING，计数清零
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.receivedCount").value(0))
                .andExpect(jsonPath("$.requiredCount").value(2))
                .andExpect(jsonPath("$.missingCount").value(2));

        // 旧代次失败记录保留，证据不被改写
        mockMvc.perform(get("/api/tasks/" + taskId + "/attempts"))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].verdict").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.attempts[0].receipts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].receipts[0].matched").value(false))
                .andExpect(jsonPath("$.attempts[1].attemptNo").value(2))
                .andExpect(jsonPath("$.attempts[1].verdict").doesNotExist());

        // 新代次重新接收全部正确分片：INSTALLABLE 并可成功回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(batchJson("r7", digests)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.attemptNo").value(2));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 已终结任务不能重新拉取
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"r9\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_FINISHED"));
    }

    @Test
    void 取消_禁止再接收与重拉_已接收证据不改写() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", 100);
        registerManifest(releaseId, "r3", List.of(digestOf('a'), digestOf('b')));
        long taskId = pullTask("d1", "r4");
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(batchJson("r5", List.of(digestOf('a')))))
                .andExpect(status().isOk());

        String before = mockMvc.perform(get("/api/tasks/" + taskId + "/attempts"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.receivedCount").value(1));

        // 版本撤回后禁止再接收与重拉
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content("""
                                {"requestId":"r7","chunks":[{"index":1,"digest":"%s"}]}
                                """.formatted(digestOf('b'))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_RECEIVING"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));

        // 已接收证据不改写
        String after = mockMvc.perform(get("/api/tasks/" + taskId + "/attempts"))
                .andReturn().getResponse().getContentAsString();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void 幂等_接收同键重放响应一致_异参409_重放不新增证据() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", 100);
        registerManifest(releaseId, "r3", List.of(digestOf('a'), digestOf('b')));
        long taskId = pullTask("d1", "r4");

        String body = batchJson("rc-1", List.of(digestOf('a')));
        String first = mockMvc.perform(post("/api/tasks/" + taskId + "/chunks")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(post("/api/tasks/" + taskId + "/chunks")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ?", Long.class, taskId)).isEqualTo(1);

        // 同键异参：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(batchJson("rc-1", List.of(digestOf('b')))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 重拉同键重放：代次只加一次
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"rp-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2));
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"rp-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_attempt WHERE task_id = ?", Long.class, taskId)).isEqualTo(2);
    }

    @Test
    void 查询_诊断与历史只读不改状态_任务不存在404() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", 100);
        registerManifest(releaseId, "r3", List.of(digestOf('a'), digestOf('b')));
        long taskId = pullTask("d1", "r4");
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(batchJson("r5", List.of(digestOf('a')))))
                .andExpect(status().isOk());

        String first = mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.receivedCount").value(1))
                .andExpect(jsonPath("$.missingCount").value(1))
                .andExpect(jsonPath("$.missingIndices[0]").value(1))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rollout_task WHERE id = ?", String.class, taskId)).isEqualTo("PENDING");

        mockMvc.perform(get("/api/tasks/9999/integrity"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/9999/attempts"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tasks/9999/chunks").contentType("application/json")
                        .content(batchJson("r6", List.of(digestOf('a')))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tasks/9999/repull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isNotFound());
    }
}
