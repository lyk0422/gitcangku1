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
 * 固件分片完整性核验与安装前门禁测试（H2 内存库，MODE=MySQL）：
 * 清单登记 422 校验、锁定、分片接收主流程、缺失/重复/不匹配判定、
 * 重新拉取代次、证据保留、幂等与诊断查询。
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
        jdbc.update("DELETE FROM task_integrity_record");
        jdbc.update("DELETE FROM release_manifest_chunk");
        jdbc.update("DELETE FROM release_manifest");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String digest(int seed) {
        return String.format("%064x", seed);
    }

    private static String chunkJson(int index, String digest) {
        return "{\"index\":%d,\"digest\":\"%s\"}".formatted(index, digest);
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, from, to, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private String manifestBody(String requestId, List<String> digests, String packageDigest) {
        StringBuilder chunks = new StringBuilder();
        for (int i = 0; i < digests.size(); i++) {
            if (i > 0) {
                chunks.append(',');
            }
            chunks.append(chunkJson(i, digests.get(i)));
        }
        return "{\"requestId\":\"%s\",\"packageDigest\":\"%s\",\"chunks\":[%s]}"
                .formatted(requestId, packageDigest, chunks);
    }

    private void registerManifest(String requestId, long releaseId, List<String> digests) throws Exception {
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestBody(requestId, digests, Digests.aggregate(digests))))
                .andExpect(status().isOk());
    }

    private long pull(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return id.longValue();
    }

    private String submitBody(String requestId, boolean complete, String... chunks) {
        return "{\"requestId\":\"%s\",\"complete\":%s,\"chunks\":[%s]}"
                .formatted(requestId, complete, String.join(",", chunks));
    }

    @Test
    void 清单登记_主流程_查询_拉取前可重登记() throws Exception {
        long releaseId = createRelease("r1", "m1", "1.0.0", "2.0.0", 100);
        List<String> digests = List.of(digest(1), digest(2), digest(3));

        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestBody("r2", digests, Digests.aggregate(digests))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId))
                .andExpect(jsonPath("$.firmwareVersion").value("2.0.0"))
                .andExpect(jsonPath("$.chunkCount").value(3))
                .andExpect(jsonPath("$.packageDigest").value(Digests.aggregate(digests)))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.registeredAtUtc").isString())
                .andExpect(jsonPath("$.chunks[0].index").value(0))
                .andExpect(jsonPath("$.chunks[0].digest").value(digest(1)))
                .andExpect(jsonPath("$.chunks[2].digest").value(digest(3)));

        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(3))
                .andExpect(jsonPath("$.locked").value(false));

        // 拉取前可整体重登记
        List<String> updated = List.of(digest(7), digest(8));
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestBody("r3", updated, Digests.aggregate(updated))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(jsonPath("$.chunkCount").value(2))
                .andExpect(jsonPath("$.chunks[1].digest").value(digest(8)));

        // 未登记清单的发布单查询 404
        long other = createRelease("r4", "m2", "1.0.0", "2.0.0", 100);
        mockMvc.perform(get("/api/releases/" + other + "/manifest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MANIFEST_NOT_FOUND"));
    }

    @Test
    void 清单登记_重复序号缺口聚合不匹配格式错误均422且无半成品() throws Exception {
        long releaseId = createRelease("r1", "m1", "1.0.0", "2.0.0", 100);

        // 重复序号
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("{\"requestId\":\"r2\",\"packageDigest\":\"%s\",\"chunks\":[%s]}"
                                .formatted(digest(100), chunkJson(0, digest(1)) + "," + chunkJson(0, digest(2)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MANIFEST_DUPLICATE_INDEX"));

        // 序号缺口
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("{\"requestId\":\"r3\",\"packageDigest\":\"%s\",\"chunks\":[%s]}"
                                .formatted(digest(100), chunkJson(0, digest(1)) + "," + chunkJson(2, digest(2)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MANIFEST_INDEX_GAP"));

        // 聚合摘要不匹配：响应含要求值与实际值
        List<String> digests = List.of(digest(1), digest(2));
        MvcResult mismatch = mockMvc.perform(post("/api/releases/" + releaseId + "/manifest")
                        .contentType("application/json")
                        .content(manifestBody("r4", digests, digest(999))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MANIFEST_AGGREGATE_MISMATCH"))
                .andReturn();
        assertThat(mismatch.getResponse().getContentAsString())
                .contains(Digests.aggregate(digests)).contains(digest(999));

        // 分片摘要格式错误
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"packageDigest\":\"%s\",\"chunks\":[%s]}"
                                .formatted(digest(100), chunkJson(0, "XYZ"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MANIFEST_DIGEST_FORMAT"));

        // 完整包摘要格式错误
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"packageDigest\":\"abc\",\"chunks\":[%s]}"
                                .formatted(chunkJson(0, digest(1)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MANIFEST_DIGEST_FORMAT"));

        // 空清单：参数校验 400
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"packageDigest\":\"%s\",\"chunks\":[]}"
                                .formatted(digest(100))))
                .andExpect(status().isBadRequest());

        // 全部失败不留半成品
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_manifest_chunk", Long.class))
                .isZero();
    }

    @Test
    void 清单锁定_任务拉取后修改409() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        List<String> digests = List.of(digest(1), digest(2));
        registerManifest("r3", releaseId, digests);
        pull("r4", "d1");

        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest").contentType("application/json")
                        .content(manifestBody("r5", List.of(digest(5)), Digests.aggregate(List.of(digest(5))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_LOCKED"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.chunkCount").value(2));
    }

    @Test
    void 分片核验主流程_逐片提交到可安装并成功回执() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        List<String> digests = List.of(digest(1), digest(2), digest(3));
        registerManifest("r3", releaseId, digests);
        long taskId = pull("r4", "d1");

        // 逐片提交：第一片后仍 PENDING
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r5", false, chunkJson(0, digest(1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.receivedCount").value(1))
                .andExpect(jsonPath("$.requiredCount").value(3))
                .andExpect(jsonPath("$.missingCount").value(2))
                .andExpect(jsonPath("$.result").doesNotExist());

        // 批量补齐：达到完整集合自动核验为 INSTALLABLE
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r6", false, chunkJson(1, digest(2)), chunkJson(2, digest(3)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.result").value("INSTALLABLE"))
                .andExpect(jsonPath("$.receivedCount").value(3))
                .andExpect(jsonPath("$.missingCount").value(0))
                .andExpect(jsonPath("$.computedPackageDigest").value(Digests.aggregate(digests)))
                .andExpect(jsonPath("$.expectedPackageDigest").value(Digests.aggregate(digests)));

        // 诊断查询：当前代次证据与判定记录
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.receivedCount").value(3))
                .andExpect(jsonPath("$.missingCount").value(0))
                .andExpect(jsonPath("$.chunks.length()").value(3))
                .andExpect(jsonPath("$.chunks[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.decisions.length()").value(1))
                .andExpect(jsonPath("$.decisions[0].result").value("INSTALLABLE"))
                .andExpect(jsonPath("$.decisions[0].reason").doesNotExist())
                .andExpect(jsonPath("$.decisions[0].firmwareVersion").value("2.0.0"));

        // 可安装任务成功回执并更新设备版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 已终结任务不再接收分片
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r8", false, chunkJson(0, digest(1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_COMPLETED"));
    }

    @Test
    void 摘要不匹配_完整性失败_禁止安装回执_不计失败率_重新拉取新代次且证据保留() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        List<String> digests = List.of(digest(1), digest(2));
        registerManifest("r3", releaseId, digests);
        long taskId = pull("r4", "d1");

        // 第二片摘要错误：完整集合核验失败
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r5", false, chunkJson(0, digest(1)), chunkJson(1, digest(999)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.result").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.reason").value("CHUNK_DIGEST_MISMATCH"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(digest(2)),
                        org.hamcrest.Matchers.containsString(digest(999)))));

        // 禁止安装和成功/失败回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));

        // 不计入设备执行失败率
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 重新拉取：新尝试代次，旧证据保留
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.attempt").value(2));
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.receivedCount").value(0))
                .andExpect(jsonPath("$.chunks.length()").value(0))
                .andExpect(jsonPath("$.decisions.length()").value(1))
                .andExpect(jsonPath("$.decisions[0].attempt").value(1))
                .andExpect(jsonPath("$.decisions[0].result").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.decisions[0].reason").value("CHUNK_DIGEST_MISMATCH"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ? AND attempt = 1",
                Long.class, taskId)).isEqualTo(2);

        // 新代次提交正确分片：可安装并成功回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r9", false, chunkJson(0, digest(1)), chunkJson(1, digest(2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.attempt").value(2));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r10\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.decisions.length()").value(2));

        // 已安装任务重新拉取不再建立新代次
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r11\"}"))
                .andExpect(jsonPath("$.task.status").value("SUCCESS"))
                .andExpect(jsonPath("$.task.attempt").value(2));
    }

    @Test
    void 缺失与重复分片_完整性失败且原因可区分() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r3", "m1", "1.0.0", "2.0.0", 100);
        List<String> digests = List.of(digest(1), digest(2));
        registerManifest("r4", releaseId, digests);

        // 缺失：complete=true 时集合不完整
        long task1 = pull("r5", "d1");
        mockMvc.perform(post("/api/tasks/" + task1 + "/chunks").contentType("application/json")
                        .content(submitBody("r6", true, chunkJson(0, digest(1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.reason").value("CHUNK_MISSING"))
                .andExpect(jsonPath("$.receivedCount").value(1))
                .andExpect(jsonPath("$.requiredCount").value(2))
                .andExpect(jsonPath("$.missingCount").value(1));

        // 重复：同序号再次接收，任务判负且证据不重复落库
        long task2 = pull("r7", "d2");
        mockMvc.perform(post("/api/tasks/" + task2 + "/chunks").contentType("application/json")
                        .content(submitBody("r8", false, chunkJson(0, digest(1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/tasks/" + task2 + "/chunks").contentType("application/json")
                        .content(submitBody("r9", false, chunkJson(0, digest(1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.reason").value("CHUNK_DUPLICATE"))
                .andExpect(jsonPath("$.acceptedCount").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ? AND chunk_index = 0",
                Long.class, task2)).isEqualTo(1);
    }

    @Test
    void 分片接收幂等_同键同参重放_异参409() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        registerManifest("r3", releaseId, List.of(digest(1), digest(2)));
        long taskId = pull("r4", "d1");

        MvcResult first = mockMvc.perform(post("/api/tasks/" + taskId + "/chunks")
                        .contentType("application/json")
                        .content(submitBody("rid-c", false, chunkJson(0, digest(1)))))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/tasks/" + taskId + "/chunks")
                        .contentType("application/json")
                        .content(submitBody("rid-c", false, chunkJson(0, digest(1)))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);

        // 同键异参：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("rid-c", false, chunkJson(1, digest(2)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 接收校验_越界序号格式错误请求内重复422_无清单409_未核验成功回执409() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m2", "1.0.0", 1);
        long releaseId = createRelease("r3", "m1", "1.0.0", "2.0.0", 100);
        registerManifest("r4", releaseId, List.of(digest(1), digest(2)));
        long taskId = pull("r5", "d1");

        // 未核验的 PENDING 任务禁止成功回执；失败回执仍允许（设备执行失败）
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_INSTALLABLE"));

        // 序号越界：响应含要求范围与实际值
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r7", false, chunkJson(5, digest(1)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_INDEX_OUT_OF_RANGE"));

        // 摘要格式错误
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r8", false, chunkJson(0, "ZZ"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_DIGEST_FORMAT"));

        // 请求内序号重复
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r9", false, chunkJson(0, digest(1)), chunkJson(0, digest(1)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_DUPLICATE_INDEX"));

        // 校验失败不留下任何接收记录
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ?", Long.class, taskId))
                .isZero();

        // 无清单发布单的任务不能接收分片
        createRelease("r10", "m2", "1.0.0", "2.0.0", 100);
        long legacyTask = pull("r11", "d2");
        mockMvc.perform(post("/api/tasks/" + legacyTask + "/chunks").contentType("application/json")
                        .content(submitBody("r12", false, chunkJson(0, digest(1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_NOT_FOUND"));

        // 任务不存在：404
        mockMvc.perform(post("/api/tasks/9999/chunks").contentType("application/json")
                        .content(submitBody("r13", false, chunkJson(0, digest(1)))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/9999/integrity"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 取消后分片接收409_证据与清单保留() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        List<String> digests = List.of(digest(1), digest(2));
        registerManifest("r3", releaseId, digests);
        long taskId = pull("r4", "d1");
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r5", false, chunkJson(0, digest(1)))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r7", false, chunkJson(1, digest(2)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));

        // 版本撤回不改写已接收证据与清单
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.receivedCount").value(1))
                .andExpect(jsonPath("$.chunks.length()").value(1))
                .andExpect(jsonPath("$.chunks[0].digest").value(digest(1)));
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(2));
    }

    @Test
    void 诊断查询_判定历史时间区间左闭右开() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        registerManifest("r3", releaseId, List.of(digest(1)));
        long taskId = pull("r4", "d1");
        mockMvc.perform(post("/api/tasks/" + taskId + "/chunks").contentType("application/json")
                        .content(submitBody("r5", false, chunkJson(0, digest(1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("INSTALLABLE"));

        MvcResult all = mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions.length()").value(1))
                .andReturn();
        String decidedAt = com.jayway.jsonpath.JsonPath.read(
                all.getResponse().getContentAsString(), "$.decisions[0].decidedAtUtc");

        // 左闭：fromUtc 等于判定时刻时包含
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity").param("fromUtc", decidedAt))
                .andExpect(jsonPath("$.decisions.length()").value(1));
        // 右开：toUtc 等于判定时刻时不包含
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity").param("toUtc", decidedAt))
                .andExpect(jsonPath("$.decisions.length()").value(0));
        // 区间外
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity")
                        .param("fromUtc", "2099-01-01T00:00:00.000Z"))
                .andExpect(jsonPath("$.decisions.length()").value(0));
        // 非法端点与倒置区间：400
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity").param("fromUtc", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity")
                        .param("fromUtc", "2026-09-26T08:00:00.000Z")
                        .param("toUtc", "2026-09-26T07:00:00.000Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }
}
