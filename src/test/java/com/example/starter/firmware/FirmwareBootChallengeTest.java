package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 启动挑战任务 API 测试（H2 内存库，MODE=MySQL）：题干主场景（b=4 领取、维护到 6、
 * c=6 拒绝、c=7 成功且只安装一次、新键改挑战拒绝）、FAILED 证明、计数维护边界与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareBootChallengeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void register(String requestId, String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(requestId, deviceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootCount").value(0))
                .andExpect(jsonPath("$.profileVersion").value(1));
    }

    private ResultActions maintain(String requestId, String deviceId, String bootCount, int expectedVersion)
            throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/boot-maintenance")
                .contentType("application/json")
                .content("""
                        {"requestId":"%s","operator":"op-t","bootCount":"%s","expectedDeviceVersion":%d}
                        """.formatted(requestId, bootCount, expectedVersion)));
    }

    private long createRelease(String requestId, String toVersion) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"%s","ratio":100}
                """.formatted(requestId, toVersion)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private MvcResult pullChallenge(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","taskType":"BOOT_CHALLENGE","operator":"op-t"}
                                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private ResultActions receipt(long taskId, String requestId, String result, String challenge,
                                  String bootCount, String operator) throws Exception {
        StringBuilder body = new StringBuilder("{\"requestId\":\"").append(requestId)
                .append("\",\"result\":\"").append(result).append("\"");
        if (challenge != null) {
            body.append(",\"challenge\":\"").append(challenge).append("\"");
        }
        if (bootCount != null) {
            body.append(",\"bootCount\":\"").append(bootCount).append("\"");
        }
        if (operator != null) {
            body.append(",\"operator\":\"").append(operator).append("\"");
        }
        return mockMvc.perform(post("/api/tasks/" + taskId + "/receipt")
                .contentType("application/json").content(body.append("}").toString()));
    }

    private static String strOf(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void assertDevice(String deviceId, String version, long bootCount, int profileVersion) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT current_version, boot_count, profile_version FROM device WHERE device_id = ?", deviceId);
        assertThat(row.get("current_version")).isEqualTo(version);
        assertThat(((Number) row.get("boot_count")).longValue()).isEqualTo(bootCount);
        assertThat(((Number) row.get("profile_version")).intValue()).isEqualTo(profileVersion);
    }

    private void assertTaskAndStats(long taskId, long releaseId, String taskStatus, int success, int failed) {
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, taskId)).isEqualTo(taskStatus);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT round_success, round_failed FROM release_order WHERE id = ?", releaseId);
        assertThat(((Number) row.get("round_success")).intValue()).isEqualTo(success);
        assertThat(((Number) row.get("round_failed")).intValue()).isEqualTo(failed);
    }

    @Test
    void 题干场景_领取冻结b4_维护到6后c6拒绝_c7成功且只安装一次() throws Exception {
        register("bc-r1", "d1");
        // 合成 b=4：计数维护到 4，档案版本 1→2
        maintain("bc-m1", "d1", "4", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootCount").value(4))
                .andExpect(jsonPath("$.profileVersion").value(2));
        long releaseId = createRelease("bc-r2", "2.0.0");
        // 领取新类型任务：冻结 b=4 并生成唯一挑战
        MvcResult pull = pullChallenge("bc-p1", "d1");
        String challenge = strOf(pull, "$.task.challenge");
        long taskId = idOf(pull, "$.task.taskId");
        assertThat(challenge).isNotBlank();
        assertThat(idOf(pull, "$.task.frozenBootCount")).isEqualTo(4);
        assertThat(strOf(pull, "$.task.taskType")).isEqualTo("BOOT_CHALLENGE");
        // 维护到 6，档案版本 2→3
        maintain("bc-m2", "d1", "6", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootCount").value(6))
                .andExpect(jsonPath("$.profileVersion").value(3));
        // 提交 c=6：不大于维护后的实际计数 6 → 409，设备、任务、失败率均不变
        receipt(taskId, "bc-c1", "SUCCESS", challenge, "6", "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_BOOT_COUNT"));
        assertDevice("d1", "1.0.0", 6, 3);
        assertTaskAndStats(taskId, releaseId, "PENDING", 0, 0);
        // c=7 成功：设备计数、当前版本、任务与本轮监控样本原子更新
        MvcResult ok = receipt(taskId, "bc-c2", "SUCCESS", challenge, "7", "op-t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();
        assertDevice("d1", "2.0.0", 7, 3);
        assertTaskAndStats(taskId, releaseId, "SUCCESS", 1, 0);
        // 旧成功键只重放首次完整响应，不回写设备计数与样本
        String firstBody = ok.getResponse().getContentAsString();
        receipt(taskId, "bc-c2", "SUCCESS", challenge, "7", "op-t")
                .andExpect(status().isOk())
                .andExpect(content().string(firstBody));
        assertDevice("d1", "2.0.0", 7, 3);
        assertTaskAndStats(taskId, releaseId, "SUCCESS", 1, 0);
        // 终态新键改 challenge：409
        receipt(taskId, "bc-c3", "SUCCESS", "tampered-challenge", "8", "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_MISMATCH"));
        // 终态新键挑战一致：返回当前任务视图但不回写设备计数与样本
        receipt(taskId, "bc-c4", "SUCCESS", challenge, "9", "op-t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertDevice("d1", "2.0.0", 7, 3);
        assertTaskAndStats(taskId, releaseId, "SUCCESS", 1, 0);
    }

    @Test
    void 挑战任务FAILED_携带正确挑战不提升计数_错误挑战与缺证明拒绝() throws Exception {
        register("bf-r1", "d1");
        maintain("bf-m1", "d1", "4", 1).andExpect(status().isOk());
        long releaseId = createRelease("bf-r2", "2.0.0");
        MvcResult pull = pullChallenge("bf-p1", "d1");
        String challenge = strOf(pull, "$.task.challenge");
        long taskId = idOf(pull, "$.task.taskId");

        // 错误挑战：409，任务与统计不变
        receipt(taskId, "bf-c1", "FAILED", "wrong-challenge", null, "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_MISMATCH"));
        // 缺挑战：409
        receipt(taskId, "bf-c2", "FAILED", null, null, "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_MISMATCH"));
        // 缺操作者：400
        receipt(taskId, "bf-c3", "FAILED", challenge, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_REQUIRED"));
        assertTaskAndStats(taskId, releaseId, "PENDING", 0, 0);

        // 正确挑战 FAILED：终结任务但不提升计数、不改设备版本，失败样本加一
        receipt(taskId, "bf-c4", "FAILED", challenge, null, "op-t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertDevice("d1", "1.0.0", 4, 2);
        assertTaskAndStats(taskId, releaseId, "FAILED", 0, 1);

        // 终态新键改结果：409
        receipt(taskId, "bf-c5", "SUCCESS", challenge, "9", "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));
        assertDevice("d1", "1.0.0", 4, 2);
    }

    @Test
    void 计数维护_版本校验_不可递减_超范围400_幂等与失败不占键() throws Exception {
        register("bm-r1", "d1");

        // 期望版本不匹配：409
        maintain("bm-m1", "d1", "4", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 成功维护到 4，版本 1→2
        MvcResult ok = maintain("bm-m2", "d1", "4", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersion").value(2))
                .andReturn();
        // 同键同参重放：完整响应一致，版本不再推进
        maintain("bm-m2", "d1", "4", 1)
                .andExpect(status().isOk())
                .andExpect(content().string(ok.getResponse().getContentAsString()));
        assertDevice("d1", "1.0.0", 4, 2);
        // 同键异参：409
        maintain("bm-m2", "d1", "5", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 计数递减（含负数）：409
        maintain("bm-m3", "d1", "2", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_REGRESS"));
        maintain("bm-m4", "d1", "-1", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_REGRESS"));
        // 等于当前计数允许（不可递减），版本 2→3
        maintain("bm-m5", "d1", "4", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootCount").value(4))
                .andExpect(jsonPath("$.profileVersion").value(3));
        // 超出 long 范围或非数字：400
        maintain("bm-m6", "d1", "99999999999999999999", 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_OUT_OF_RANGE"));
        maintain("bm-m7", "d1", "abc", 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_OUT_OF_RANGE"));
        // 缺操作者：400
        mockMvc.perform(post("/api/devices/d1/boot-maintenance").contentType("application/json")
                        .content("{\"requestId\":\"bm-m8\",\"bootCount\":\"5\",\"expectedDeviceVersion\":3}"))
                .andExpect(status().isBadRequest());
        // 设备不存在：404
        maintain("bm-m9", "ghost", "5", 1)
                .andExpect(status().isNotFound());
        // 失败不占键：版本冲突后用同 requestId 正确参数成功
        maintain("bm-mx", "d1", "5", 99)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        maintain("bm-mx", "d1", "5", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootCount").value(5))
                .andExpect(jsonPath("$.profileVersion").value(4));
        assertDevice("d1", "1.0.0", 5, 4);
    }

    @Test
    void 领取_类型与操作者校验_挑战唯一且历史不被下一任务覆盖() throws Exception {
        register("bp-r1", "d1");
        register("bp-r2", "d2");
        long releaseId = createRelease("bp-r3", "2.0.0");

        // 缺操作者：400
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"bp-p0\",\"taskType\":\"BOOT_CHALLENGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_REQUIRED"));
        // 非法任务类型：400
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"bp-p1\",\"taskType\":\"BOGUS\",\"operator\":\"op-t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TASK_TYPE"));

        // 两台设备领取：挑战全局唯一
        MvcResult pull1 = pullChallenge("bp-p2", "d1");
        MvcResult pull2 = pullChallenge("bp-p3", "d2");
        String challenge1 = strOf(pull1, "$.task.challenge");
        String challenge2 = strOf(pull2, "$.task.challenge");
        long taskId1 = idOf(pull1, "$.task.taskId");
        assertThat(challenge1).isNotEqualTo(challenge2);

        // 重复领取返回同一任务同一挑战
        MvcResult again = pullChallenge("bp-p4", "d1");
        assertThat(idOf(again, "$.task.taskId")).isEqualTo(taskId1);
        assertThat(strOf(again, "$.task.challenge")).isEqualTo(challenge1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 取消发布单后旧挑战任务的后到回执：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"bp-x1\"}"))
                .andExpect(status().isOk());
        receipt(taskId1, "bp-c1", "SUCCESS", challenge1, "5", "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));

        // 下一发布单生成新任务新挑战，旧任务的挑战历史不被覆盖
        long releaseId2 = createRelease("bp-r4", "3.0.0");
        MvcResult pull3 = pullChallenge("bp-p5", "d1");
        String challenge3 = strOf(pull3, "$.task.challenge");
        assertThat(challenge3).isNotEqualTo(challenge1);
        assertThat(jdbc.queryForObject("SELECT challenge FROM rollout_task WHERE id = ?",
                String.class, taskId1)).isEqualTo(challenge1);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT challenge) FROM rollout_task",
                Long.class)).isEqualTo(3);
    }

    @Test
    void 回执证明边界_等于基线或实际计数409_超范围400_失败不占键() throws Exception {
        register("be-r1", "d1");
        maintain("be-m1", "d1", "4", 1).andExpect(status().isOk());
        long releaseId = createRelease("be-r2", "2.0.0");
        MvcResult pull = pullChallenge("be-p1", "d1");
        String challenge = strOf(pull, "$.task.challenge");
        long taskId = idOf(pull, "$.task.taskId");

        // c 等于冻结基线 b=4：409
        receipt(taskId, "be-c1", "SUCCESS", challenge, "4", "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_BOOT_COUNT"));
        // c 为负数（不大于实际计数）：409
        receipt(taskId, "be-c2", "SUCCESS", challenge, "-1", "op-t")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_BOOT_COUNT"));
        // 超出 long 范围或非数字：400
        receipt(taskId, "be-c3", "SUCCESS", challenge, "99999999999999999999", "op-t")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_OUT_OF_RANGE"));
        receipt(taskId, "be-c4", "SUCCESS", challenge, "abc", "op-t")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_OUT_OF_RANGE"));
        // SUCCESS 缺计数：400
        receipt(taskId, "be-c5", "SUCCESS", challenge, null, "op-t")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_REQUIRED"));
        assertTaskAndStats(taskId, releaseId, "PENDING", 0, 0);
        assertDevice("d1", "1.0.0", 4, 2);

        // 失败不占键：同一 requestId 先 409 后成功
        receipt(taskId, "be-c6", "SUCCESS", challenge, "4", "op-t")
                .andExpect(status().isConflict());
        receipt(taskId, "be-c6", "SUCCESS", challenge, "5", "op-t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertDevice("d1", "2.0.0", 5, 2);
        assertTaskAndStats(taskId, releaseId, "SUCCESS", 1, 0);
    }

    @Test
    void 普通任务_新字段为空且原流程不需要证明() throws Exception {
        register("bn-r1", "d1");
        long releaseId = createRelease("bn-r2", "2.0.0");
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"bn-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskType").value("NORMAL"))
                .andExpect(jsonPath("$.task.challenge").doesNotExist())
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"bn-c1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertDevice("d1", "2.0.0", 0, 1);
        assertTaskAndStats(taskId, releaseId, "SUCCESS", 1, 0);
    }
}
