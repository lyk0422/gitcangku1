package com.example.starter.firmware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件灰度投放 API 主流程与失败分支测试。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:firmware_test_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
class FirmwareApiTest extends AbstractIntegrationTest {

    private static final String DB_URL =
            "jdbc:h2:mem:firmware_test_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @AfterAll
    static void releaseDatabase() throws Exception {
        shutdownDatabase(DB_URL);
    }

    private MvcResult postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andReturn();
    }

    private String registerDevice(String requestId, String deviceId, String model,
                                  String version, int bucket) throws Exception {
        MvcResult result = postJson("/api/devices", Map.of(
                "requestId", requestId, "deviceId", deviceId, "model", model,
                "firmwareVersion", version, "bucket", bucket));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = postJson("/api/releases", Map.of(
                "requestId", requestId, "model", model, "fromVersion", from,
                "toVersion", to, "ratio", ratio));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return om.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long pull(String requestId, String deviceId) throws Exception {
        MvcResult result = postJson("/api/pull", Map.of("requestId", requestId, "deviceId", deviceId));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return om.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void registerDevice_idempotentReplayAndConflicts() throws Exception {
        String body = registerDevice("r1", "dev-1", "model-A", "1.0", 10);
        assertThat(om.readTree(body).get("currentVersion").asText()).isEqualTo("1.0");

        // 同键同参重放原成功结果
        MvcResult replay = postJson("/api/devices", Map.of(
                "requestId", "r1", "deviceId", "dev-1", "model", "model-A",
                "firmwareVersion", "1.0", "bucket", 10));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(body);

        // 同键异参返回 409
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "r1", "deviceId", "dev-1", "model", "model-A",
                                "firmwareVersion", "1.0", "bucket", 11))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 新请求号重复登记同一设备返回 409
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "r2", "deviceId", "dev-1", "model", "model-A",
                                "firmwareVersion", "1.0", "bucket", 10))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_EXISTS"));

        // 分桶号越界返回 400
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "r3", "deviceId", "dev-2", "model", "model-A",
                                "firmwareVersion", "1.0", "bucket", 100))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRelease_validationAndSingleActivePerModel() throws Exception {
        // 目标与来源版本相同返回 400
        mvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "rc0", "model", "model-A", "fromVersion", "1.0",
                                "toVersion", "1.0", "ratio", 10))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_DISTINCT"));

        long id = createRelease("rc1", "model-A", "1.0", "2.0", 10);
        mvc.perform(get("/api/releases/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 同型号第二张 ACTIVE 发布单返回 409
        mvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "rc2", "model", "model-A", "fromVersion", "1.0",
                                "toVersion", "3.0", "ratio", 20))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RELEASE_EXISTS"));

        // 取消后同型号可再次创建
        mvc.perform(post("/api/releases/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "rc3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        createRelease("rc4", "model-A", "1.0", "3.0", 20);
    }

    @Test
    void updateRatio_optimisticVersionAndMonotonicIncrease() throws Exception {
        long id = createRelease("ru1", "model-A", "1.0", "2.0", 10);

        // 版本不符返回 409
        mvc.perform(put("/api/releases/{id}/ratio", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "ru2", "ratio", 30, "expectedVersion", 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 比例不增返回 409
        mvc.perform(put("/api/releases/{id}/ratio", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "ru3", "ratio", 10, "expectedVersion", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATIO_NOT_INCREASING"));

        // 失败不占键：同一 requestId 修正参数后可成功
        mvc.perform(put("/api/releases/{id}/ratio", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "ru2", "ratio", 30, "expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratio").value(30))
                .andExpect(jsonPath("$.version").value(2));

        // 取消后扩量返回 409
        mvc.perform(post("/api/releases/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "ru4"))))
                .andExpect(status().isOk());
        mvc.perform(put("/api/releases/{id}/ratio", id).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "requestId", "ru5", "ratio", 50, "expectedVersion", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
    }

    @Test
    void pull_eligibilityAndExistingTask() throws Exception {
        registerDevice("p1", "dev-hit", "model-A", "1.0", 30);
        registerDevice("p2", "dev-bucket-miss", "model-A", "1.0", 70);
        registerDevice("p3", "dev-version-miss", "model-A", "1.1", 10);

        // 无 ACTIVE 发布单返回 404
        mvc.perform(post("/api/pull").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "p4", "deviceId", "dev-hit"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_TASK_AVAILABLE"));

        createRelease("p5", "model-A", "1.0", "2.0", 50);

        // 分桶号不小于比例、当前版本不匹配均返回 404
        mvc.perform(post("/api/pull").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "p6", "deviceId", "dev-bucket-miss"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/pull").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "p7", "deviceId", "dev-version-miss"))))
                .andExpect(status().isNotFound());

        // 未登记设备返回 404
        mvc.perform(post("/api/pull").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "p8", "deviceId", "ghost"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        // 满足条件创建任务；重复拉取返回同一任务
        long taskId = pull("p9", "dev-hit");
        long again = pull("p10", "dev-hit");
        assertThat(again).isEqualTo(taskId);

        mvc.perform(get("/api/tasks").param("deviceId", "dev-hit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void receipt_finalizeAndVersionUpdate() throws Exception {
        registerDevice("t1", "dev-ok", "model-A", "1.0", 10);
        registerDevice("t2", "dev-bad", "model-A", "1.0", 20);
        long releaseId = createRelease("t3", "model-A", "1.0", "2.0", 50);
        long okTask = pull("t4", "dev-ok");
        long badTask = pull("t5", "dev-bad");

        // SUCCESS 回执更新设备当前版本
        mvc.perform(post("/api/tasks/{id}/receipt", okTask).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "t6", "result", "SUCCESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(get("/api/devices/{id}", "dev-ok"))
                .andExpect(jsonPath("$.currentVersion").value("2.0"));

        // 同结果重复回执成功；改结果返回 409
        mvc.perform(post("/api/tasks/{id}/receipt", okTask).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "t7", "result", "SUCCESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(post("/api/tasks/{id}/receipt", okTask).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "t8", "result", "FAILED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_CONFLICT"));

        // FAILED 回执不更新设备版本；已失败任务本轮不重新投放
        mvc.perform(post("/api/tasks/{id}/receipt", badTask).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "t9", "result", "FAILED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mvc.perform(get("/api/devices/{id}", "dev-bad"))
                .andExpect(jsonPath("$.currentVersion").value("1.0"));
        MvcResult repull = postJson("/api/pull", Map.of("requestId", "t10", "deviceId", "dev-bad"));
        JsonNode repulled = om.readTree(repull.getResponse().getContentAsString());
        assertThat(repulled.get("id").asLong()).isEqualTo(badTask);
        assertThat(repulled.get("status").asText()).isEqualTo("FAILED");

        // 任务明细查询按发布单过滤
        mvc.perform(get("/api/tasks").param("releaseId", String.valueOf(releaseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void cancel_pendingTasksCancelledAndLateReceiptRejected() throws Exception {
        registerDevice("c1", "dev-pending", "model-A", "1.0", 10);
        registerDevice("c2", "dev-done", "model-A", "1.0", 20);
        long releaseId = createRelease("c3", "model-A", "1.0", "2.0", 50);
        long pendingTask = pull("c4", "dev-pending");
        long doneTask = pull("c5", "dev-done");
        mvc.perform(post("/api/tasks/{id}/receipt", doneTask).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "c6", "result", "SUCCESS"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/releases/{id}/cancel", releaseId).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "c7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 未终结任务变为 CANCELLED，已成功任务不回滚
        mvc.perform(get("/api/tasks").param("releaseId", String.valueOf(releaseId))
                        .param("deviceId", "dev-pending"))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
        mvc.perform(get("/api/tasks").param("releaseId", String.valueOf(releaseId))
                        .param("deviceId", "dev-done"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));

        // 已取消任务的迟到回执返回 409 且不更新设备版本
        mvc.perform(post("/api/tasks/{id}/receipt", pendingTask).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "c8", "result", "SUCCESS"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mvc.perform(get("/api/devices/{id}", "dev-pending"))
                .andExpect(jsonPath("$.currentVersion").value("1.0"));

        // 取消后不再投放
        registerDevice("c9", "dev-late", "model-A", "1.0", 5);
        mvc.perform(post("/api/pull").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "c10", "deviceId", "dev-late"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_TASK_AVAILABLE"));
    }
}
