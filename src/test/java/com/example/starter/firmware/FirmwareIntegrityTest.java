package com.example.starter.firmware;

import com.example.starter.firmware.domain.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件分片完整性核验与安装前门禁区 API 测试（H2 内存库，MODE=MySQL）：
 * 清单登记校验、逐分片/批量接收、完整性失败、重新拉取代次、证据固化与只读查询。
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
        jdbc.update("DELETE FROM shard_receipt");
        jdbc.update("DELETE FROM integrity_event");
        jdbc.update("DELETE FROM release_shard");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    /**
     * 第 i 个分片的合成摘要（与生产聚合规则一致的小写十六进制 SHA-256）。
     */
    private static String digestOf(int i) {
        return Digests.aggregateHex(List.of("shard-seed-" + i));
    }

    private static String fullDigestOf(int shardCount) {
        return Digests.aggregateHex(IntStream.range(0, shardCount).mapToObj(FirmwareIntegrityTest::digestOf).toList());
    }

    private static String shardsJson(int shardCount) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < shardCount; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"shardNo\":").append(i).append(",\"digest\":\"").append(digestOf(i)).append("\"}");
        }
        return sb.append("]").toString();
    }

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
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

    private void registerShards(String requestId, long releaseId, int shardCount) throws Exception {
        mockMvc.perform(post("/api/releases/" + releaseId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"%s","fullDigest":"%s","shards":%s}
                                """.formatted(requestId, fullDigestOf(shardCount), shardsJson(shardCount))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shardCount").value(shardCount))
                .andExpect(jsonPath("$.fullDigest").value(fullDigestOf(shardCount)));
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void submitShard(String requestId, long taskId, int shardNo) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"%s","shards":[{"shardNo":%d,"digest":"%s"}]}
                                """.formatted(requestId, shardNo, digestOf(shardNo))))
                .andExpect(status().isOk());
    }

    @Test
    void 登记校验_重复序号缺口聚合不匹配均422且不写入() throws Exception {
        long releaseId = createRelease("r-create", "m1", 100);

        // 重复序号：422，响应携带重复序号
        mockMvc.perform(post("/api/releases/" + releaseId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-dup","fullDigest":"%s","shards":[
                                {"shardNo":0,"digest":"%s"},{"shardNo":0,"digest":"%s"}]}
                                """.formatted(fullDigestOf(1), digestOf(0), digestOf(0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARD_DUPLICATE"));

        // 序号缺口（0,2 缺 1）：422，响应携带缺失与要求
        MvcResult gap = mockMvc.perform(post("/api/releases/" + releaseId + "/shards")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"r-gap","fullDigest":"%s","shards":[
                                {"shardNo":0,"digest":"%s"},{"shardNo":2,"digest":"%s"}]}
                                """.formatted(fullDigestOf(2), digestOf(0), digestOf(2))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARD_GAP"))
                .andReturn();
        assertThat(gap.getResponse().getContentAsString()).contains("缺失 [1]");

        // 聚合摘要不匹配：422，响应携带登记值与实际聚合值
        String wrongFull = Digests.aggregateHex(List.of("not-the-full-package"));
        MvcResult mismatch = mockMvc.perform(post("/api/releases/" + releaseId + "/shards")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"r-agg","fullDigest":"%s","shards":%s}
                                """.formatted(wrongFull, shardsJson(2))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FULL_DIGEST_MISMATCH"))
                .andReturn();
        assertThat(mismatch.getResponse().getContentAsString())
                .contains(wrongFull).contains(fullDigestOf(2));

        // 摘要格式非法（非64位小写十六进制）：400
        mockMvc.perform(post("/api/releases/" + releaseId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-fmt","fullDigest":"%s","shards":[
                                {"shardNo":0,"digest":"XYZ"}]}
                                """.formatted(fullDigestOf(1))))
                .andExpect(status().isBadRequest());

        // 全部失败不写入：清单仍为空
        mockMvc.perform(get("/api/releases/" + releaseId + "/shards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shardCount").value(0))
                .andExpect(jsonPath("$.shards.length()").value(0))
                .andExpect(jsonPath("$.fullDigest").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_shard", Long.class)).isZero();
    }

    @Test
    void 登记_无任务时可替换重登_有任务拉取后锁定409() throws Exception {
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh1", releaseId, 2);

        // 尚无任务拉取：允许整体替换重登
        mockMvc.perform(post("/api/releases/" + releaseId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-sh2","fullDigest":"%s","shards":%s}
                                """.formatted(fullDigestOf(3), shardsJson(3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shardCount").value(3));
        mockMvc.perform(get("/api/releases/" + releaseId + "/shards"))
                .andExpect(jsonPath("$.shardCount").value(3))
                .andExpect(jsonPath("$.shards[2].shardNo").value(2))
                .andExpect(jsonPath("$.shards[2].digest").value(digestOf(2)));

        // 有任务拉取后：清单锁定，重登 409
        registerDevice("r-dev", "d1", "m1", 1);
        pullTask("r-pull", "d1");
        mockMvc.perform(post("/api/releases/" + releaseId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-sh3","fullDigest":"%s","shards":%s}
                                """.formatted(fullDigestOf(2), shardsJson(2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHARD_MANIFEST_LOCKED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/shards"))
                .andExpect(jsonPath("$.shardCount").value(3));
    }

    @Test
    void 主流程_逐分片提交到可安装_回执成功_证据与事件固化() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);
        long taskId = pullTask("r-pull", "d1");

        // 第0分片：继续接收，响应携带实际/要求/缺失数量与缺失序号
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-s0","shards":[{"shardNo":0,"digest":"%s"}]}
                                """.formatted(digestOf(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.requiredShardCount").value(2))
                .andExpect(jsonPath("$.receivedShardCount").value(1))
                .andExpect(jsonPath("$.missingShardCount").value(1))
                .andExpect(jsonPath("$.missingShards[0]").value(1))
                .andExpect(jsonPath("$.decidedAtUtc").doesNotExist());

        // 第1分片：集齐且聚合匹配，原子转为 INSTALLABLE 并固化聚合摘要
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-s1","shards":[{"shardNo":1,"digest":"%s"}]}
                                """.formatted(digestOf(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.receivedShardCount").value(2))
                .andExpect(jsonPath("$.aggregateDigest").value(fullDigestOf(2)))
                .andExpect(jsonPath("$.decidedAtUtc").isString());

        // 安装回执成功并更新设备版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 任务完整性明细：两条接收证据（含UTC时刻与请求ID）+ 一条 INSTALLABLE 判定事件
        MvcResult integrity = mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.aggregateDigest").value(fullDigestOf(2)))
                .andExpect(jsonPath("$.receipts.length()").value(2))
                .andExpect(jsonPath("$.receipts[0].shardNo").value(0))
                .andExpect(jsonPath("$.receipts[0].shardDigest").value(digestOf(0)))
                .andExpect(jsonPath("$.receipts[0].requestId").value("r-s0"))
                .andExpect(jsonPath("$.receipts[1].shardNo").value(1))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].result").value("INSTALLABLE"))
                .andExpect(jsonPath("$.events[0].reason").value("OK"))
                .andExpect(jsonPath("$.events[0].requiredShardCount").value(2))
                .andExpect(jsonPath("$.events[0].receivedShardCount").value(2))
                .andExpect(jsonPath("$.events[0].expectedFullDigest").value(fullDigestOf(2)))
                .andExpect(jsonPath("$.events[0].actualFullDigest").value(fullDigestOf(2)))
                .andReturn();
        String decidedAt = com.jayway.jsonpath.JsonPath.read(
                integrity.getResponse().getContentAsString(), "$.events[0].decidedAtUtc");
        assertThat(decidedAt).endsWith("Z");
        String receivedAt = com.jayway.jsonpath.JsonPath.read(
                integrity.getResponse().getContentAsString(), "$.receipts[0].receivedAtUtc");
        assertThat(receivedAt).endsWith("Z");

        // 发布单诊断：清单与判定事件可查
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shardCount").value(2))
                .andExpect(jsonPath("$.fullDigest").value(fullDigestOf(2)))
                .andExpect(jsonPath("$.shards.length()").value(2))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].result").value("INSTALLABLE"));
    }

    @Test
    void 批量一次提交完整集合_直接可安装() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 3);
        long taskId = pullTask("r-pull", "d1");

        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-batch","shards":%s}
                                """.formatted(shardsJson(3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.receivedShardCount").value(3))
                .andExpect(jsonPath("$.aggregateDigest").value(fullDigestOf(3)));

        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.receipts.length()").value(3))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].result").value("INSTALLABLE"));
    }

    @Test
    void 摘要不匹配_整批完整性失败_禁止回执且不计失败率() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);
        long taskId = pullTask("r-pull", "d1");

        String wrongDigest = Digests.aggregateHex(List.of("tampered-shard-1"));
        // 同一批含一个正确分片与一个篡改分片：整批判定失败，不留下部分可安装状态
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-bad","shards":[
                                {"shardNo":0,"digest":"%s"},{"shardNo":1,"digest":"%s"}]}
                                """.formatted(digestOf(0), wrongDigest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.reason").value("SHARD_DIGEST_MISMATCH"))
                .andExpect(jsonPath("$.mismatchedShards[0]").value(1))
                .andExpect(jsonPath("$.receivedShardCount").value(2))
                .andExpect(jsonPath("$.requiredShardCount").value(2));

        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.tasks.length()").value(1));

        // 禁止安装与成功回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));

        // 不计入设备执行失败率
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 证据固化：实际提交的篡改摘要原样保留，判定事件含期望聚合摘要
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.receipts.length()").value(2))
                .andExpect(jsonPath("$.receipts[1].shardDigest").value(wrongDigest))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].result").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.events[0].reason").value("SHARD_DIGEST_MISMATCH"))
                .andExpect(jsonPath("$.events[0].mismatchedShards").value("1"))
                .andExpect(jsonPath("$.events[0].expectedFullDigest").value(fullDigestOf(2)));

        // 失败后继续提交分片：409，本代次已关闭
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-more","shards":[{"shardNo":1,"digest":"%s"}]}
                                """.formatted(digestOf(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INTEGRITY_FAILED"));
    }

    @Test
    void 序号越界与跨批重复_均整批完整性失败() throws Exception {
        registerDevice("r-dev1", "d1", "m1", 1);
        registerDevice("r-dev2", "d2", "m1", 2);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);

        // 序号越界（清单只有0/1，提交2）
        long t1 = pullTask("r-pull1", "d1");
        mockMvc.perform(post("/api/tasks/" + t1 + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-oob","shards":[{"shardNo":2,"digest":"%s"}]}
                                """.formatted(digestOf(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.reason").value("SHARD_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.outOfRangeShards[0]").value(2));

        // 跨批重复：第0分片已接收，再次提交（即使摘要一致）判定重复失败
        long t2 = pullTask("r-pull2", "d2");
        submitShard("r-s0", t2, 0);
        mockMvc.perform(post("/api/tasks/" + t2 + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-dup","shards":[{"shardNo":0,"digest":"%s"}]}
                                """.formatted(digestOf(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.reason").value("DUPLICATE_SHARD"))
                .andExpect(jsonPath("$.duplicateShards[0]").value(0));
        // 重复证据不重复落库：仍只有一条第0分片证据
        mockMvc.perform(get("/api/tasks/" + t2 + "/integrity"))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.events[0].reason").value("DUPLICATE_SHARD"));
    }

    @Test
    void 批内重复序号_422且不留下任何写入() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);
        long taskId = pullTask("r-pull", "d1");

        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-baddup","shards":[
                                {"shardNo":0,"digest":"%s"},{"shardNo":0,"digest":"%s"}]}
                                """.formatted(digestOf(0), digestOf(0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARD_DUPLICATE"));

        // 查询不到半成品：无证据、无事件，任务仍 PENDING
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.receipts.length()").value(0))
                .andExpect(jsonPath("$.events.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shard_receipt", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integrity_event", Long.class)).isZero();
    }

    @Test
    void 未集齐禁止安装回执_响应携带实际与要求数量() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);
        long taskId = pullTask("r-pull", "d1");
        submitShard("r-s0", taskId, 0);

        MvcResult result = mockMvc.perform(post("/api/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-rc\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_INSTALLABLE"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("已接收分片 1").contains("要求 2");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
    }

    @Test
    void 重新拉取建立新代次_旧证据保留_新代次可成功() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);
        long taskId1 = pullTask("r-pull1", "d1");

        // 第一代次：篡改分片导致完整性失败
        String wrongDigest = Digests.aggregateHex(List.of("tampered"));
        mockMvc.perform(post("/api/tasks/" + taskId1 + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-bad","shards":[{"shardNo":0,"digest":"%s"}]}
                                """.formatted(wrongDigest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));

        // 重新拉取：建立第2代次，旧代次保留
        MvcResult pull2 = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.attemptNo").value(2))
                .andReturn();
        long taskId2 = idOf(pull2, "$.task.taskId");
        assertThat(taskId2).isNotEqualTo(taskId1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(2);

        // 旧代次证据与失败事件不改写
        mockMvc.perform(get("/api/tasks/" + taskId1 + "/integrity"))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].shardDigest").value(wrongDigest))
                .andExpect(jsonPath("$.events[0].result").value("INTEGRITY_FAILED"));

        // 新代次从零接收：正确分片集齐后可安装并回执成功
        submitShard("r-n0", taskId2, 0);
        submitShard("r-n1", taskId2, 1);
        mockMvc.perform(post("/api/tasks/" + taskId2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 任务列表包含两个代次，状态分别为 INTEGRITY_FAILED 与 SUCCESS
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.tasks[1].status").value("SUCCESS"));
    }

    @Test
    void 取消后提交分片409_已接收证据不改写() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);
        long taskId = pullTask("r-pull", "d1");
        submitShard("r-s0", taskId, 0);

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-s1","shards":[{"shardNo":1,"digest":"%s"}]}
                                """.formatted(digestOf(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));

        // 版本撤回不改写已接收证据
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].shardDigest").value(digestOf(0)));
        // 发布单诊断仍保留清单
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity"))
                .andExpect(jsonPath("$.shardCount").value(2));
    }

    @Test
    void 分片提交与登记_同键重放首次响应_异参409() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);

        // 登记幂等：同键同参重放一致，异参409
        String registerBody = """
                {"requestId":"r-reg","fullDigest":"%s","shards":%s}
                """.formatted(fullDigestOf(2), shardsJson(2));
        MvcResult first = mockMvc.perform(post("/api/releases/" + releaseId + "/shards")
                        .contentType("application/json").content(registerBody))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(post("/api/releases/" + releaseId + "/shards")
                        .contentType("application/json").content(registerBody))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        mockMvc.perform(post("/api/releases/" + releaseId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-reg","fullDigest":"%s","shards":%s}
                                """.formatted(fullDigestOf(3), shardsJson(3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        long taskId = pullTask("r-pull", "d1");

        // 分片提交幂等：同键同参重放一致（不重复落证据），异参409
        String submitBody = """
                {"requestId":"r-sub","shards":[{"shardNo":0,"digest":"%s"}]}
                """.formatted(digestOf(0));
        MvcResult submitFirst = mockMvc.perform(post("/api/tasks/" + taskId + "/shards")
                        .contentType("application/json").content(submitBody))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards")
                        .contentType("application/json").content(submitBody))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(submitFirst.getResponse().getContentAsString()));
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-sub","shards":[{"shardNo":1,"digest":"%s"}]}
                                """.formatted(digestOf(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.receipts.length()").value(1));
    }

    @Test
    void 未登记清单_旧流程可直接回执_提交分片409() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        long taskId = pullTask("r-pull", "d1");

        // 未登记清单的发布单：分片接收无依据，409
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-s0","shards":[{"shardNo":0,"digest":"%s"}]}
                                """.formatted(digestOf(0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHARDS_NOT_REGISTERED"));

        // 旧流程：未登记清单时允许直接安装回执（向后兼容）
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }

    @Test
    void 查询只读_重复查询不改变状态() throws Exception {
        registerDevice("r-dev", "d1", "m1", 1);
        long releaseId = createRelease("r-create", "m1", 100);
        registerShards("r-sh", releaseId, 2);
        long taskId = pullTask("r-pull", "d1");
        submitShard("r-s0", taskId, 0);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/releases/" + releaseId + "/shards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shardCount").value(2));
            mockMvc.perform(get("/api/releases/" + releaseId + "/integrity"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events.length()").value(0));
            mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.receipts.length()").value(1));
        }
        // 查询后任务仍可继续接收分片
        submitShard("r-s1", taskId, 1);
        mockMvc.perform(get("/api/tasks/" + taskId + "/integrity"))
                .andExpect(jsonPath("$.status").value("INSTALLABLE"));

        // 不存在的任务/发布单：404
        mockMvc.perform(get("/api/tasks/9999/integrity")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/shards")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/integrity")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tasks/9999/shards").contentType("application/json")
                        .content("""
                                {"requestId":"r-404","shards":[{"shardNo":0,"digest":"%s"}]}
                                """.formatted(digestOf(0))))
                .andExpect(status().isNotFound());
    }
}
