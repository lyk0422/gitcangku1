package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 拉取与回执主流程：投放条件、任务终结、设备版本更新、取消后的后到回执。
 */
class TaskFlowApiTest extends BaseIntegrationTest {

    private long rolloutId;

    @BeforeEach
    void setUpRolloutAndDevices() throws Exception {
        registerDevice("dev-a", "model-a", "1.0.0", 5);
        registerDevice("dev-b", "model-a", "1.0.0", 50);
        registerDevice("dev-c", "model-a", "9.9.9", 1);
        registerDevice("dev-d", "model-b", "1.0.0", 1);
        String response = mockMvc.perform(post("/api/rollouts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"setup-rollout\",\"model\":\"model-a\","
                                + "\"fromVersion\":\"1.0.0\",\"toVersion\":\"2.0.0\",\"ratio\":10}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        rolloutId = Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void registerDevice(String deviceId, String model, String version, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"setup-%s","deviceId":"%s","model":"%s","firmwareVersion":"%s","bucketNo":%d}
                                """.formatted(deviceId, deviceId, model, version, bucket)))
                .andExpect(status().isCreated());
    }

    private String pull(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"deviceId\":\"" + deviceId + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private long pullTaskId(String requestId, String deviceId) throws Exception {
        String body = pull(requestId, deviceId);
        return Long.parseLong(body.replaceAll(".*\"task\":\\{\"id\":(\\d+).*", "$1"));
    }

    private String deviceVersion(String deviceId) {
        return jdbc.queryForObject("SELECT firmware_version FROM device WHERE device_id = ?",
                String.class, deviceId);
    }

    @Test
    void pullCreatesTaskForEligibleDevice() throws Exception {
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-1\",\"deviceId\":\"dev-a\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.task.deviceId").value("dev-a"))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.fromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.task.toVersion").value("2.0.0"))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void pullReturnsExistingTaskWithoutCreatingNew() throws Exception {
        long taskId = pullTaskId("p-1", "dev-a");
        // 重复拉取（新 requestId）返回同一任务，不新建
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-2\",\"deviceId\":\"dev-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value(taskId));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE rollout_id = ? AND device_id = 'dev-a'",
                Integer.class, rolloutId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void pullRejectsIneligibleDevices() throws Exception {
        // 分桶号 50 不小于比例 10
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-b\",\"deviceId\":\"dev-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.reason").value("OUT_OF_BUCKET"));
        // 当前版本不匹配
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-c\",\"deviceId\":\"dev-c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("VERSION_MISMATCH"));
        // 型号不匹配
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-d\",\"deviceId\":\"dev-d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("MODEL_MISMATCH"));
        // 未登记设备 404
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-x\",\"deviceId\":\"ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEVICE_NOT_FOUND"));
        // 均未产生任务
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void successReceiptUpdatesDeviceVersionAndIsIdempotentOnSameResult() throws Exception {
        long taskId = pullTaskId("p-1", "dev-a");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(deviceVersion("dev-a")).isEqualTo("2.0.0");
        // 同结果回执（新 requestId）重复成功
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        // 改结果 409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-3\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_CONFLICT"));
        assertThat(deviceVersion("dev-a")).isEqualTo("2.0.0");
    }

    @Test
    void failedReceiptDoesNotUpdateVersionAndTaskNotReissued() throws Exception {
        long taskId = pullTaskId("p-1", "dev-a");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(deviceVersion("dev-a")).isEqualTo("1.0.0");
        // 已失败任务本轮不重新投放：再次拉取返回原 FAILED 任务
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-2\",\"deviceId\":\"dev-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value(taskId))
                .andExpect(jsonPath("$.task.status").value("FAILED"));
        // 改结果 409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_CONFLICT"));
    }

    @Test
    void cancelledRolloutRejectsPullAndLateReceipt() throws Exception {
        long taskId = pullTaskId("p-1", "dev-a");
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x-1\"}"))
                .andExpect(status().isOk());
        // 未终结任务已置为 CANCELLED
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 取消后拉取不创建新任务
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p-9\",\"deviceId\":\"dev-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("ROLLOUT_NOT_ACTIVE"));
        // 后到回执 409 且设备版本不更新
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TASK_CANCELLED"));
        assertThat(deviceVersion("dev-a")).isEqualTo("1.0.0");
    }

    @Test
    void succeededDeviceNotRolledBackByCancel() throws Exception {
        long taskId = pullTaskId("p-1", "dev-a");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rollouts/" + rolloutId + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"x-1\"}"))
                .andExpect(status().isOk());
        // 已成功任务不回滚
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(deviceVersion("dev-a")).isEqualTo("2.0.0");
    }

    @Test
    void taskDetailQueries() throws Exception {
        long taskId = pullTaskId("p-1", "dev-a");
        mockMvc.perform(get("/api/rollouts/" + rolloutId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(taskId))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("dev-a"));
        mockMvc.perform(get("/api/tasks/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
        mockMvc.perform(get("/api/rollouts/999/tasks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROLLOUT_NOT_FOUND"));
    }

    @Test
    void receiptWithInvalidResultReturns400() throws Exception {
        long taskId = pullTaskId("p-1", "dev-a");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"result\":\"DONE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
