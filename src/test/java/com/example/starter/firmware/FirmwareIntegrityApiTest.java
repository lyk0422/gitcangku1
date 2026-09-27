package com.example.starter.firmware;

import com.example.starter.firmware.domain.DigestRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件分片完整性 API 测试（真实 H2 内存库，MODE=MySQL）：
 * 登记校验422、逐片接收与可安装判定、缺失/重复/越界/摘要不匹配失败、
 * 批量最终集合原子裁决、摘要锁定、重拉新代次、撤销、幂等、不计失败率、只读诊断。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareIntegrityApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM task_shard_receipt");
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_shard");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String d(String content) {
        return DigestRules.sha256Hex(content);
    }

    private static String pkg(String... contents) {
        return DigestRules.aggregateOfOrderedDigests(Arrays.stream(contents)
                .map(DigestRules::sha256Hex).toList());
    }

    private String shardsJson(String... contents) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < contents.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"shardIndex\":").append(i)
                    .append(",\"shardDigest\":\"").append(d(contents[i])).append("\"}");
        }
        return sb.append(']').toString();
    }

    private String manifestPart(String... contents) {
        return "\"packageDigest\":\"" + pkg(contents) + "\",\"shards\":" + shardsJson(contents);
    }

    private void registerDevice(String requestId, String deviceId, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, bucket)))
                .andExpect(status().isOk());
    }

    private long createManifestRelease(String requestId, int ratio, int floor, int threshold,
                                       String... contents) throws Exception {
        String body = "{\"requestId\":\"%s\",\"model\":\"m1\",\"fromVersion\":\"1.0.0\","
                .formatted(requestId)
                + "\"toVersion\":\"2.0.0\",\"ratio\":" + ratio + ",\"sampleFloor\":" + floor
                + ",\"failureThresholdPercent\":" + threshold + "," + manifestPart(contents) + "}";
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.releaseId")).longValue();
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.task.taskId")).longValue();
    }

    private MvcResult sendShard(long taskId, String requestId, int index, String digest) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                .content("{\"requestId\":\"%s\",\"shardIndex\":%d,\"shardDigest\":\"%s\"}"
                        .formatted(requestId, index, digest))).andReturn();
    }

    private void sendCorrectShard(long taskId, String requestId, int index, String content) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"shardIndex\":%d,\"shardDigest\":\"%s\"}"
                                .formatted(requestId, index, d(content))))
                .andExpect(status().isOk());
    }

    @Test
    void 主流程_登记分片拉取逐片接收完整后可安装_成功回执更新版本() throws Exception {
        registerDevice("rd", "d1", 1);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0", "s1", "s2");

        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredShards").value(3))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.shards[0].shardIndex").value(0))
                .andExpect(jsonPath("$.shards[2].shardDigest").value(d("s2")));

        long taskId = pullTask("rp", "d1");

        // 拉取即锁定清单
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lockedAtUtc").isString());

        // 逐片接收：缺口期间保持 PENDING，实际值/要求值/差额随响应给出
        sendCorrectShard(taskId, "rs0", 0, "s0");
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"shardIndex\":1,\"shardDigest\":\"%s\"}".formatted(d("s1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requiredShards").value(3))
                .andExpect(jsonPath("$.receivedShards").value(2))
                .andExpect(jsonPath("$.missingShards").value(1));

        // 未完整禁止安装回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rx\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_INSTALLABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("实际 2")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("要求 3")));

        // 最后一片到位：可安装，时刻固化
        MvcResult installable = mockMvc.perform(post("/api/tasks/" + taskId + "/shards")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rs2\",\"shardIndex\":2,\"shardDigest\":\"%s\"}".formatted(d("s2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"))
                .andExpect(jsonPath("$.receivedShards").value(3))
                .andExpect(jsonPath("$.missingShards").value(0))
                .andReturn();
        String finalizedAt = objectMapper.readTree(installable.getResponse().getContentAsString())
                .get("attemptFinalizedAtUtc").asText();
        assertThat(finalizedAt).endsWith("Z");

        // 可安装后不再接收分片
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rs2b\",\"shardIndex\":0,\"shardDigest\":\"%s\"}".formatted(d("s0"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_INSTALLABLE"));

        // 成功回执：设备版本更新
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rok\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }

    @Test
    void 登记_重复序号缺口坏摘要聚合不匹配均422_不留数据() throws Exception {
        String d0 = d("s0");
        String d1 = d("s1");

        // 重复序号
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "packageDigest":"%s","shards":[{"shardIndex":0,"shardDigest":"%s"},{"shardIndex":0,"shardDigest":"%s"}]}
                """.formatted(pkg("s0"), d0, d1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARD_INDEX_DUPLICATE"));

        // 缺口
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "packageDigest":"%s","shards":[{"shardIndex":0,"shardDigest":"%s"},{"shardIndex":2,"shardDigest":"%s"}]}
                """.formatted(pkg("s0", "x", "s1"), d0, d1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARD_INDEX_GAP"));

        // 坏摘要（大写非法）
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "packageDigest":"%s","shards":[{"shardIndex":0,"shardDigest":"%s"}]}
                """.formatted(d0, d0.toUpperCase())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARD_DIGEST_INVALID"));

        // 聚合不匹配
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "packageDigest":"%s","shards":[{"shardIndex":0,"shardDigest":"%s"}]}
                """.formatted(d("other"), d0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGGREGATE_DIGEST_MISMATCH"));

        // 422 不留半成品
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_shard", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Long.class)).isZero();
    }

    @Test
    void 摘要锁定_拉取后不可修改_拉取前可整体替换() throws Exception {
        registerDevice("rd", "d1", 1);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0", "s1");

        // 拉取前替换清单：2片 -> 3片
        String newManifest = "{\"requestId\":\"rm\",\"packageDigest\":\"" + pkg("a", "b", "c")
                + "\",\"shards\":" + shardsJson("a", "b", "c") + "}";
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest")
                        .contentType("application/json").content(newManifest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredShards").value(3));
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(jsonPath("$.requiredShards").value(3));

        pullTask("rp", "d1");

        // 拉取后锁定：修改 409，旧清单不变
        mockMvc.perform(post("/api/releases/" + releaseId + "/manifest")
                        .contentType("application/json").content(newManifest.replace("\"rm\"", "\"rm2\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_LOCKED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.requiredShards").value(3));
    }

    @Test
    void 单片摘要不匹配_立即完整性失败_禁止回执且不计失败率_证据固化() throws Exception {
        registerDevice("rd1", "d1", 1);
        registerDevice("rd2", "d2", 2);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0", "s1");
        long t1 = pullTask("rp1", "d1");
        long t2 = pullTask("rp2", "d2");

        // d1 收到错误摘要：立即 INTEGRITY_FAILED
        mockMvc.perform(post("/api/tasks/" + t1 + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rb\",\"shardIndex\":0,\"shardDigest\":\"%s\"}".formatted(d("wrong"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.failureReason").value("DIGEST_MISMATCH"))
                .andExpect(jsonPath("$.receivedShards").value(1))
                .andExpect(jsonPath("$.missingShards").value(1));

        // 禁止安装与成功回执
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rx1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTEGRITY_FAILED"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rx2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTEGRITY_FAILED"));
        // 继续提交分片也被拒绝
        MvcResult refused = sendCorrectShardResult(t1, "rx3", 1, "s1");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);

        // 完整性失败不计入设备执行失败率：两台都完整性失败，统计仍为0且发布单 ACTIVE
        mockMvc.perform(post("/api/tasks/" + t2 + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rb2\",\"shardIndex\":1,\"shardDigest\":\"%s\"}".formatted(d("bad"))))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 证据固化：期望摘要、实际摘要、比对结果
        mockMvc.perform(get("/api/tasks/" + t1 + "/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].failureReason").value("DIGEST_MISMATCH"))
                .andExpect(jsonPath("$.shardReceipts.length()").value(1))
                .andExpect(jsonPath("$.shardReceipts[0].shardIndex").value(0))
                .andExpect(jsonPath("$.shardReceipts[0].expectedDigest").value(d("s0")))
                .andExpect(jsonPath("$.shardReceipts[0].receivedDigest").value(d("wrong")))
                .andExpect(jsonPath("$.shardReceipts[0].digestMatched").value(false));
    }

    private MvcResult sendCorrectShardResult(long taskId, String requestId, int index, String content)
            throws Exception {
        return sendShard(taskId, requestId, index, d(content));
    }

    @Test
    void 批量接收_完整集合一次通过_失败整批不残留可安装() throws Exception {
        registerDevice("rd1", "d1", 1);
        registerDevice("rd2", "d2", 2);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0", "s1");
        long t1 = pullTask("rp1", "d1");
        long t2 = pullTask("rp2", "d2");

        // 完整正确批量：一次 INSTALLABLE
        String fullBatch = "{\"requestId\":\"rb1\",\"shards\":["
                + "{\"shardIndex\":0,\"shardDigest\":\"" + d("s0") + "\"},"
                + "{\"shardIndex\":1,\"shardDigest\":\"" + d("s1") + "\"}]}";
        mockMvc.perform(post("/api/tasks/" + t1 + "/shards/batch")
                        .contentType("application/json").content(fullBatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLABLE"));
        assertThat(jdbc.queryForObject(
                "SELECT aggregate_digest FROM task_attempt WHERE task_id = ? AND attempt_no = 1",
                String.class, t1)).isEqualTo(pkg("s0", "s1"));

        // 缺口批量：最终集合不完整，判 MISSING_SHARD，不留可安装
        mockMvc.perform(post("/api/tasks/" + t2 + "/shards/batch").contentType("application/json")
                        .content("{\"requestId\":\"rb2\",\"shards\":["
                                + "{\"shardIndex\":0,\"shardDigest\":\"" + d("s0") + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.failureReason").value("MISSING_SHARD"))
                .andExpect(jsonPath("$.receivedShards").value(1));

        // 批内重复
        registerDevice("rd3", "d3", 3);
        long t3 = pullTask("rp3", "d3");
        mockMvc.perform(post("/api/tasks/" + t3 + "/shards/batch").contentType("application/json")
                        .content("{\"requestId\":\"rb3\",\"shards\":["
                                + "{\"shardIndex\":0,\"shardDigest\":\"" + d("s0") + "\"},"
                                + "{\"shardIndex\":0,\"shardDigest\":\"" + d("s0") + "\"},"
                                + "{\"shardIndex\":1,\"shardDigest\":\"" + d("s1") + "\"}]}"))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.failureReason").value("DUPLICATE_SHARD"));

        // 越界：证据 expected 为 null，原因 OUT_OF_RANGE
        registerDevice("rd4", "d4", 4);
        long t4 = pullTask("rp4", "d4");
        mockMvc.perform(post("/api/tasks/" + t4 + "/shards/batch").contentType("application/json")
                        .content("{\"requestId\":\"rb4\",\"shards\":["
                                + "{\"shardIndex\":0,\"shardDigest\":\"" + d("s0") + "\"},"
                                + "{\"shardIndex\":1,\"shardDigest\":\"" + d("s1") + "\"},"
                                + "{\"shardIndex\":2,\"shardDigest\":\"" + d("xx") + "\"}]}"))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.failureReason").value("OUT_OF_RANGE"));
        mockMvc.perform(get("/api/tasks/" + t4 + "/diagnostics"))
                .andExpect(jsonPath("$.shardReceipts[2].shardIndex").value(2))
                .andExpect(jsonPath("$.shardReceipts[2].expectedDigest").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.shardReceipts[2].digestMatched").value(false));
    }

    @Test
    void 重新拉取_仅完整性失败可开新代次_旧代次证据保留_新代次可安装() throws Exception {
        registerDevice("rd", "d1", 1);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0", "s1");
        long taskId = pullTask("rp", "d1");

        // 先单片错摘要失败
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rb\",\"shardIndex\":0,\"shardDigest\":\"%s\"}".formatted(d("bad"))))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));

        // PENDING 之外的状态不能重拉：完整性失败可以
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"rq\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.receivedShards").value(0));

        // 旧失败代次与证据保留
        mockMvc.perform(get("/api/tasks/" + taskId + "/diagnostics"))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].status").value("INTEGRITY_FAILED"))
                .andExpect(jsonPath("$.attempts[1].attemptNo").value(2))
                .andExpect(jsonPath("$.attempts[1].status").value("RECEIVING"))
                .andExpect(jsonPath("$.shardReceipts.length()").value(1));

        // 重复重拉：接收中 409
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"rq2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_RECEIVING"));

        // 新代次逐片接收成功
        String batch = "{\"requestId\":\"rb2\",\"shards\":["
                + "{\"shardIndex\":0,\"shardDigest\":\"" + d("s0") + "\"},"
                + "{\"shardIndex\":1,\"shardDigest\":\"" + d("s1") + "\"}]}";
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards/batch")
                        .contentType("application/json").content(batch))
                .andExpect(jsonPath("$.status").value("INSTALLABLE"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rok\",\"result\":\"SUCCESS\"}"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 已终结不能再重拉
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                        .content("{\"requestId\":\"rq3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_TERMINATED"));
    }

    @Test
    void 任务撤销_接收中和可安装可撤销_已安装不可撤销_代次留撤销终态() throws Exception {
        registerDevice("rd1", "d1", 1);
        registerDevice("rd2", "d2", 2);
        registerDevice("rd3", "d3", 3);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0");
        long t1 = pullTask("rp1", "d1");
        long t2 = pullTask("rp2", "d2");
        long t3 = pullTask("rp3", "d3");

        // 接收中撤销
        mockMvc.perform(post("/api/tasks/" + t1 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rc1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/tasks/" + t1 + "/diagnostics"))
                .andExpect(jsonPath("$.attempts[0].status").value("CANCELLED"));
        // 撤销后拒收分片与回执
        mockMvc.perform(post("/api/tasks/" + t1 + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rs\",\"shardIndex\":0,\"shardDigest\":\"%s\"}".formatted(d("s0"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rx\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        // 重复撤销幂等
        mockMvc.perform(post("/api/tasks/" + t1 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rc1b\"}"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 可安装撤销
        sendCorrectShard(t2, "rs2", 0, "s0");
        mockMvc.perform(post("/api/tasks/" + t2 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rc2\"}"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 已安装不可撤销
        sendCorrectShard(t3, "rs3", 0, "s0");
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rx3\",\"result\":\"SUCCESS\"}"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(post("/api/tasks/" + t3 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rc3\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_TERMINATED"));
    }

    @Test
    void 分片接收幂等_同参重放首次响应_异参409_失败不占键_并发唯一() throws Exception {
        registerDevice("rd", "d1", 1);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0", "s1");
        long taskId = pullTask("rp", "d1");

        // 同 requestId 同参重放：响应一致，证据只有一条
        MvcResult first = sendShard(taskId, "same", 0, d("s0"));
        MvcResult replay = sendShard(taskId, "same", 0, d("s0"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_shard_receipt WHERE task_id = ? AND attempt_no = 1 AND shard_index = 0",
                Long.class, taskId)).isEqualTo(1);

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"same\",\"shardIndex\":1,\"shardDigest\":\"%s\"}".formatted(d("s1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：错误 digest 导致完整性失败后，该 requestId 可用于新代次的正确请求
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"bad\",\"shardIndex\":1,\"shardDigest\":\"%s\"}".formatted(d("bad"))))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/repull").contentType("application/json")
                .content("{\"requestId\":\"rq\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"bad\",\"shardIndex\":0,\"shardDigest\":\"%s\"}".formatted(d("s0"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void 遗留发布单_无分片清单走旧回执路径_不支持分片接收() throws Exception {
        registerDevice("rd", "d1", 1);
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk()).andReturn();
        long releaseId = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.releaseId")).longValue();
        long taskId = pullTask("rp", "d1");

        mockMvc.perform(get("/api/releases/" + releaseId + "/manifest"))
                .andExpect(jsonPath("$.packageDigest").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.requiredShards").value(0))
                .andExpect(jsonPath("$.shards.length()").value(0));

        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rs\",\"shardIndex\":0,\"shardDigest\":\"" + d("x") + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SHARD_MANIFEST"));

        // PENDING 直接回执的旧路径仍可用
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rx\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void 设备重复拉取_返回同一任务_完整性失败需显式重拉才开新代次() throws Exception {
        registerDevice("rd", "d1", 1);
        long releaseId = createManifestRelease("rr", 100, 2, 50, "s0");
        long taskId = pullTask("rp1", "d1");
        mockMvc.perform(post("/api/tasks/" + taskId + "/shards").contentType("application/json")
                        .content("{\"requestId\":\"rb\",\"shardIndex\":0,\"shardDigest\":\"%s\"}".formatted(d("bad"))))
                .andExpect(jsonPath("$.status").value("INTEGRITY_FAILED"));

        // 设备再次拉取仍返回同一任务（失败状态），不自动开新代次
        MvcResult pullAgain = mockMvc.perform(post("/api/devices/d1/pull")
                        .contentType("application/json").content("{\"requestId\":\"rp2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.attemptNo").value(1))
                .andExpect(jsonPath("$.task.status").value("INTEGRITY_FAILED"))
                .andReturn();
        JsonNode node = objectMapper.readTree(pullAgain.getResponse().getContentAsString());
        assertThat(node.get("task").get("shardDigests").get(0).asText()).isEqualTo(d("s0"));
    }
}
