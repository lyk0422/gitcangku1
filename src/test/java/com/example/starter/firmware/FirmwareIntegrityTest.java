package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件分片完整性 API 集成测试（真实 H2 内存库，MODE=MySQL）：
 * 登记 422 分支、逐片/批量接收与裁决、失败禁止安装且不计失败率、重拉新代次与旧证据保留、
 * 任务撤销、幂等边界、诊断与 UTC 左闭右开区间查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareIntegrityTest {

    private static final String D0 = FirmwareFixtures.DIGEST_0;
    private static final String D1 = FirmwareFixtures.DIGEST_1;
    private static final String BAD = "c".repeat(64);
    private static final String PKG = FirmwareFixtures.PACKAGE_DIGEST;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        FirmwareFixtures.cleanAll(jdbc);
    }

    private long registerAndPull(String deviceId, String releaseRequestId) throws Exception {
        return registerAndPull(deviceId, "m-" + deviceId, releaseRequestId, true);
    }

    /**
     * 登记设备并拉取任务；createRelease 为 true 时同时为该型号创建发布单，
     * 为 false 时复用该型号已存在的未终结发布单（同发布单多设备场景）。
     */
    private long registerAndPull(String deviceId, String model, String releaseRequestId,
                                 boolean createRelease) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s-dev","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(deviceId, deviceId, model))).andExpect(status().isOk());
        if (createRelease) {
            mockMvc.perform(post("/api/releases").contentType("application/json")
                    .content(FirmwareFixtures.createBody(releaseRequestId, model, "1.0.0", "2.0.0", 100)))
                    .andExpect(status().isOk());
        }
        MvcResult pull = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s-pull\"}".formatted(deviceId)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.taskId")).longValue();
    }

    private String fragmentsBody(String requestId, String... seqDigest) {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"").append(requestId)
                .append("\",\"fragments\":[");
        for (int i = 0; i < seqDigest.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"seqNo\":").append(seqDigest[i]).append(",\"digest\":\"").append(seqDigest[i + 1])
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String receive(long taskId, String body) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/fragments")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /**
     * 以 Object 目标类型读取 JSON 路径，避免 JsonPath 泛型推断与 AssertJ 重载歧义。
     */
    private static Object json(String body, String path) {
        return com.jayway.jsonpath.JsonPath.read(body, path);
    }

    @Test
    void 登记发布单_返回规范排序分片与完整包摘要() throws Exception {
        // 故意乱序提交，响应必须按序号 0、1 规范排序
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-d","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """)).andExpect(status().isOk());
        String body = """
                {"requestId":"r-r","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "fragments":[{"seqNo":1,"digest":"%s"},{"seqNo":0,"digest":"%s"}],
                "packageDigest":"%s"}
                """.formatted(D1, D0, PKG);
        mockMvc.perform(post("/api/releases").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fragmentCount").value(2))
                .andExpect(jsonPath("$.packageDigest").value(PKG))
                .andExpect(jsonPath("$.fragments.length()").value(2))
                .andExpect(jsonPath("$.fragments[0].seqNo").value(0))
                .andExpect(jsonPath("$.fragments[0].digest").value(D0))
                .andExpect(jsonPath("$.fragments[1].seqNo").value(1))
                .andExpect(jsonPath("$.fragments[1].digest").value(D1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_fragment", Long.class)).isEqualTo(2);
    }

    @Test
    void 登记_重复序号422() throws Exception {
        String body = """
                {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "fragments":[{"seqNo":0,"digest":"%s"},{"seqNo":0,"digest":"%s"}],
                "packageDigest":"%s"}
                """.formatted(D0, D1, PKG);
        mockMvc.perform(post("/api/releases").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SEQ"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
    }

    @Test
    void 登记_序号缺口422() throws Exception {
        String body = """
                {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "fragments":[{"seqNo":0,"digest":"%s"},{"seqNo":2,"digest":"%s"}],
                "packageDigest":"%s"}
                """.formatted(D0, D1, PKG);
        mockMvc.perform(post("/api/releases").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEQ_GAP"));
    }

    @Test
    void 登记_聚合摘要不匹配422() throws Exception {
        String body = """
                {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "fragments":[{"seqNo":0,"digest":"%s"},{"seqNo":1,"digest":"%s"}],
                "packageDigest":"%s"}
                """.formatted(D0, D1, "f".repeat(64));
        mockMvc.perform(post("/api/releases").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PACKAGE_DIGEST_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_fragment", Long.class)).isZero();
    }

    @Test
    void 逐分片接收_缺失停留PENDING给出差额_收齐聚合匹配才可安装() throws Exception {
        long taskId = registerAndPull("d1", "r1");

        String first = receive(taskId, fragmentsBody("rf0", "0", D0));
        assertThat(first).contains("\"status\":\"PENDING\"");
        assertThat(json(first, "$.receivedCount")).isEqualTo(1);
        assertThat(json(first, "$.requiredCount")).isEqualTo(2);
        assertThat(json(first, "$.missingCount")).isEqualTo(1);
        assertThat((net.minidev.json.JSONArray) json(first, "$.missingSeqs"))
                .containsExactly(1);
        // 未收齐不固化任何裁决事件
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_integrity_event WHERE task_id = ? AND event_type IN"
                        + " ('INSTALLABLE','INTEGRITY_FAILED')", Long.class, taskId)).isZero();

        String second = receive(taskId, fragmentsBody("rf1", "1", D1));
        assertThat(json(second, "$.task.status")).isEqualTo("INSTALLABLE");
        assertThat(json(second, "$.aggregateActual")).isEqualTo(PKG);
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo("INSTALLABLE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_integrity_event WHERE task_id = ? AND event_type = 'INSTALLABLE'",
                Long.class, taskId)).isEqualTo(1);
    }

    @Test
    void 批量接收_完整最终集合正确_一次裁决可安装() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        String response = receive(taskId, fragmentsBody("rball", "1", D1, "0", D0));
        assertThat(json(response, "$.task.status")).isEqualTo("INSTALLABLE");
        assertThat(json(response, "$.receivedCount")).isEqualTo(2);
        assertThat(json(response, "$.attempt")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(2);
    }

    @Test
    void 接收摘要不匹配_任务完整性失败_禁止安装回执_不计失败率() throws Exception {
        long taskId = registerAndPull("d1", "r1");

        String response = receive(taskId, fragmentsBody("rbad", "0", D0, "1", BAD));
        assertThat(json(response, "$.task.status"))
                .isEqualTo("INTEGRITY_FAILED");
        assertThat(json(response, "$.integrityFailureCode"))
                .isEqualTo("MISMATCH_DIGEST");
        // 问题分片的错误摘要也固化为证据
        Map<String, Object> badEvidence = jdbc.queryForMap(
                "SELECT * FROM task_fragment_receipt WHERE task_id = ? AND seq_no = 1", taskId);
        assertThat(badEvidence.get("received_digest")).isEqualTo(BAD);
        assertThat(badEvidence.get("expected_digest")).isEqualTo(D1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_integrity_event WHERE task_id = ?"
                        + " AND event_type = 'INTEGRITY_FAILED'", Long.class, taskId)).isEqualTo(1);

        // 禁止成功回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTEGRITY_INSTALL_BLOCKED"));
        // 也禁止失败回执：完整性失败不能被记成设备执行失败
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTEGRITY_INSTALL_BLOCKED"));
        // 失败率统计未被污染
        long releaseId = jdbc.queryForObject("SELECT release_id FROM rollout_task WHERE id = ?",
                Long.class, taskId);
        assertThat(jdbc.queryForObject("SELECT round_failed FROM release_order WHERE id = ?",
                Integer.class, releaseId)).isZero();
        assertThat(jdbc.queryForObject("SELECT first_result FROM rollout_task WHERE id = ?",
                String.class, taskId)).isNull();
    }

    @Test
    void 同批重复序号异摘要_完整性失败且不留半成品() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        String response = receive(taskId, fragmentsBody("rdup", "0", D0, "1", D1, "0", BAD));
        assertThat(json(response, "$.task.status"))
                .isEqualTo("INTEGRITY_FAILED");
        assertThat(json(response, "$.integrityFailureCode"))
                .isEqualTo("DUPLICATE_DIGEST");
        // 绝无部分可安装状态：无 INSTALLABLE 事件，任务终态 INTEGRITY_FAILED
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_integrity_event WHERE task_id = ? AND event_type = 'INSTALLABLE'",
                Long.class, taskId)).isZero();
        // 同序号只有一条证据
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND seq_no = 0",
                Long.class, taskId)).isEqualTo(1);
    }

    @Test
    void 已收序号再提交异摘要_当前代次完整性失败() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        receive(taskId, fragmentsBody("r0", "0", D0));
        String response = receive(taskId, fragmentsBody("r0bad", "0", BAD));
        assertThat(json(response, "$.task.status"))
                .isEqualTo("INTEGRITY_FAILED");
        assertThat(json(response, "$.integrityFailureCode"))
                .isEqualTo("DUPLICATE_DIGEST");
    }

    @Test
    void 序号越界400_分片接收幂等同参重放_异参409_异常不占键() throws Exception {
        long taskId = registerAndPull("d1", "r1");

        // 越界序号：400（业务异常回滚，不占 requestId）
        mockMvc.perform(post("/api/tasks/" + taskId + "/fragments").contentType("application/json")
                        .content(fragmentsBody("rf", "5", D0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FRAGMENT_SEQ_OUT_OF_RANGE"));
        // 同一 requestId 随后可正常使用，证明异常不占键
        String first = receive(taskId, fragmentsBody("rf", "0", D0));
        assertThat(first).contains("\"status\":\"PENDING\"");

        // 同参重放首次响应（PENDING，已收1片，证据不重复落库）
        String replay = receive(taskId, fragmentsBody("rf", "0", D0));
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/fragments").contentType("application/json")
                        .content(fragmentsBody("rf", "1", D1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 错误摘要导致完整性失败：裁决是 200 成功响应并占键，同参重放原裁决，异参 409
        String failed = receive(taskId, fragmentsBody("rbad", "1", BAD));
        assertThat(json(failed, "$.task.status"))
                .isEqualTo("INTEGRITY_FAILED");
        String failedReplay = receive(taskId, fragmentsBody("rbad", "1", BAD));
        assertThat(failedReplay).isEqualTo(failed);
        mockMvc.perform(post("/api/tasks/" + taskId + "/fragments").contentType("application/json")
                        .content(fragmentsBody("rbad", "0", D0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 重新拉取_失败任务建立新代次_旧证据保留_新代次收齐可安装() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        receive(taskId, fragmentsBody("rbad", "0", D0, "1", BAD));
        assertThat(jdbc.queryForObject("SELECT attempt FROM rollout_task WHERE id = ?",
                Integer.class, taskId)).isEqualTo(1);

        MvcResult repull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"d1-repull\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.attempt").value(2))
                .andExpect(jsonPath("$.manifest.attempt").value(2))
                .andReturn();
        assertThat(json(repull.getResponse().getContentAsString(),
                "$.task.taskId")).isEqualTo((int) taskId);

        // 旧代次证据与失败事件保留
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND attempt = 1",
                Long.class, taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_integrity_event WHERE task_id = ? AND attempt = 1"
                        + " AND event_type = 'INTEGRITY_FAILED'", Long.class, taskId)).isEqualTo(1);

        // 新代次从0证据开始，收齐后可安装
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND attempt = 2",
                Long.class, taskId)).isZero();
        String response = receive(taskId, fragmentsBody("rok", "0", D0, "1", D1));
        assertThat(json(response, "$.task.status")).isEqualTo("INSTALLABLE");
        assertThat(json(response, "$.attempt")).isEqualTo(2);
        // 旧失败记录仍在，安装成功后事件流水两个代次都可查
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rcok\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void 已安装任务重新拉取_不新建代次_撤回后重拉不改写() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        receive(taskId, fragmentsBody("rok", "0", D0, "1", D1));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"d1-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.attempt").value(1))
                .andExpect(jsonPath("$.task.status").value("SUCCESS"));
        assertThat(jdbc.queryForObject("SELECT attempt FROM rollout_task WHERE id = ?",
                Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    void 撤销任务_未安装可撤销_已安装不可撤销_分片受理停止() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        receive(taskId, fragmentsBody("r0", "0", D0));

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rcan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 已收证据保留
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);
        // 撤销后分片与回执均不再受理
        mockMvc.perform(post("/api/tasks/" + taskId + "/fragments").contentType("application/json")
                        .content(fragmentsBody("rf", "1", D1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rr\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));

        // 重复撤销幂等
        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rcan2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 已安装任务不可撤销
        long taskId2 = registerAndPull("d2", "r2");
        receive(taskId2, fragmentsBody("rok2", "0", D0, "1", D1));
        mockMvc.perform(post("/api/tasks/" + taskId2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + taskId2 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rcan3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_FINISHED"));
    }

    @Test
    void 发布单撤回_未安装任务全部撤销_安装终态保留_重拉不改写() throws Exception {
        long t1 = registerAndPull("d1", "r1");
        long t2 = registerAndPull("d2", "m-d1", "r2", false);
        receive(t1, fragmentsBody("rok1", "0", D0, "1", D1));
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        // t2 处于 PENDING
        long releaseId = jdbc.queryForObject("SELECT id FROM release_order", Long.class);

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rx\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, t2))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, t1))
                .isEqualTo("SUCCESS");

        // 撤回后重拉不改写已撤销任务，也不新建代次
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"d2-repull\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("CANCELLED"))
                .andExpect(jsonPath("$.task.attempt").value(1));
    }

    @Test
    void 拉取同requestId重放_返回首次快照且不重复建任务() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"rd1","deviceId":"d1","model":"m-pull","currentVersion":"1.0.0","bucketNo":1}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/releases").contentType("application/json")
                .content(FirmwareFixtures.createBody("rr1", "m-pull", "1.0.0", "2.0.0", 100)))
                .andExpect(status().isOk());

        String body = "{\"requestId\":\"rpull\"}";
        MvcResult first = mockMvc.perform(post("/api/devices/d1/pull")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.attempt").value(1))
                .andExpect(jsonPath("$.manifest.fragments.length()").value(2))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/devices/d1/pull")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE device_id = 'd1'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void 诊断查询_含登记清单各代次证据与事件_只读不改状态() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        receive(taskId, fragmentsBody("rbad", "0", D0, "1", BAD));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                .content("{\"requestId\":\"d1-repull\"}")).andExpect(status().isOk());
        receive(taskId, fragmentsBody("rok", "0", D0, "1", D1));

        MvcResult diag = mockMvc.perform(get("/api/tasks/" + taskId + "/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredCount").value(2))
                .andExpect(jsonPath("$.packageDigest").value(PKG))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].attempt").value(1))
                .andExpect(jsonPath("$.attempts[0].fragments[1].match").value(false))
                .andExpect(jsonPath("$.attempts[1].attempt").value(2))
                .andExpect(jsonPath("$.attempts[1].receivedCount").value(2))
                .andReturn();
        String body = diag.getResponse().getContentAsString();
        // 事件流水同时保留旧代次失败与新代次可安装
        assertThat(body).contains("\"eventType\":\"INTEGRITY_FAILED\"");
        assertThat(body).contains("\"eventType\":\"INSTALLABLE\"");
        assertThat(body).contains("\"releaseVersion\":1");

        // 只读：连续查询状态不变
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo("INSTALLABLE");
    }

    @Test
    void 发布单事件诊断_UTC区间左闭右开_实际数量正确() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        receive(taskId, fragmentsBody("rok", "0", D0, "1", D1));
        long releaseId = jdbc.queryForObject(
                "SELECT release_id FROM rollout_task WHERE id = ?", Long.class, taskId);
        String eventTime = jdbc.queryForObject(
                "SELECT created_at_utc FROM task_integrity_event WHERE task_id = ? ORDER BY id LIMIT 1",
                String.class, taskId);
        String next = java.time.Instant.parse(eventTime).plusSeconds(1).toString();
        String farFuture = "2099-01-01T00:00:00Z";
        String farPast = "2000-01-01T00:00:00Z";

        // 全区间：3 个事件（2 RECEIVED + 1 INSTALLABLE）
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
        // 左闭：[eventTime, next) 包含该秒事件
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity-events")
                        .param("fromUtc", eventTime).param("toUtc", next))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
        // 右开：toUtc = eventTime 不包含该秒事件
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity-events")
                        .param("fromUtc", farPast).param("toUtc", eventTime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        // 下界之后无事件
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity-events")
                        .param("fromUtc", next).param("toUtc", farFuture))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        // 非法区间与非法时间格式
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity-events")
                        .param("fromUtc", farFuture).param("toUtc", farPast))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/integrity-events")
                        .param("fromUtc", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME"));
    }

    @Test
    void 发布单摘要_有任务拉取后不提供修改途径_撤回不改写已接收证据() throws Exception {
        long taskId = registerAndPull("d1", "r1");
        receive(taskId, fragmentsBody("r0", "0", D0));
        long releaseId = jdbc.queryForObject(
                "SELECT release_id FROM rollout_task WHERE id = ?", Long.class, taskId);

        // 撤回发布单
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rx\"}"))
                .andExpect(status().isOk());
        // 登记分片摘要仍在，且已接收证据的期望摘要快照不被改写
        assertThat(jdbc.queryForObject("SELECT package_digest FROM release_order WHERE id = ?",
                String.class, releaseId)).isEqualTo(PKG);
        assertThat(jdbc.queryForObject(
                "SELECT expected_digest FROM task_fragment_receipt WHERE task_id = ? AND seq_no = 0",
                String.class, taskId)).isEqualTo(D0);
    }
}
