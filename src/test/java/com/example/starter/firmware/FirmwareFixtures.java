package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.FragmentInput;
import com.example.starter.firmware.api.FragmentReceiptRequest;
import com.example.starter.firmware.service.DigestSupport;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试夹具：固定两片规范分片（序号0/1，64位小写十六进制摘要）与按规则算出的完整包摘要。
 */
final class FirmwareFixtures {

    static final String DIGEST_0 = "a".repeat(64);
    static final String DIGEST_1 = "b".repeat(64);
    static final String PACKAGE_DIGEST = DigestSupport.aggregate(List.of(DIGEST_0, DIGEST_1));
    static final int FRAGMENT_COUNT = 2;

    private FirmwareFixtures() {
    }

    static List<FragmentInput> fragments() {
        return List.of(new FragmentInput(0, DIGEST_0), new FragmentInput(1, DIGEST_1));
    }

    static CreateReleaseRequest createRequest(String requestId, String model, String from, String to,
                                              int ratio) {
        return new CreateReleaseRequest(requestId, model, from, to, ratio, fragments(), PACKAGE_DIGEST);
    }

    static CreateReleaseRequest createRequest(String requestId, String model, String from, String to,
                                              int ratio, int sampleFloor, int threshold) {
        return new CreateReleaseRequest(requestId, model, from, to, ratio, sampleFloor, threshold,
                fragments(), PACKAGE_DIGEST);
    }

    static String createBody(String requestId, String model, String from, String to, int ratio) {
        return """
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d,
                "fragments":[{"seqNo":0,"digest":"%s"},{"seqNo":1,"digest":"%s"}],
                "packageDigest":"%s"}
                """.formatted(requestId, model, from, to, ratio, DIGEST_0, DIGEST_1, PACKAGE_DIGEST);
    }

    static String createBody(String requestId, String model, int ratio, int sampleFloor, int threshold) {
        return """
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                "sampleFloor":%d,"failureThresholdPercent":%d,
                "fragments":[{"seqNo":0,"digest":"%s"},{"seqNo":1,"digest":"%s"}],
                "packageDigest":"%s"}
                """.formatted(requestId, model, ratio, sampleFloor, threshold,
                DIGEST_0, DIGEST_1, PACKAGE_DIGEST);
    }

    /**
     * 不带监控参数的创建体（用于验证缺省值 floor=2 / threshold=100）。
     */
    static String createBodyWithoutMonitorParams(String requestId, String model, int ratio) {
        return """
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                "fragments":[{"seqNo":0,"digest":"%s"},{"seqNo":1,"digest":"%s"}],
                "packageDigest":"%s"}
                """.formatted(requestId, model, ratio, DIGEST_0, DIGEST_1, PACKAGE_DIGEST);
    }

    /**
     * 批量提交全部分片（摘要正确），任务应进入 INSTALLABLE。
     */
    static void submitAllFragments(MockMvc mockMvc, long taskId, String requestId) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/fragments")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","fragments":[
                                {"seqNo":0,"digest":"%s"},{"seqNo":1,"digest":"%s"}]}
                                """.formatted(requestId, DIGEST_0, DIGEST_1)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.task.status").value("INSTALLABLE"));
    }

    static FragmentReceiptRequest allFragmentsRequest(String requestId) {
        return new FragmentReceiptRequest(requestId, List.of(
                new FragmentReceiptRequest.ReceivedFragment(0, DIGEST_0),
                new FragmentReceiptRequest.ReceivedFragment(1, DIGEST_1)));
    }

    /**
     * 清理全部业务表（含分片与完整性证据），测试间数据隔离。
     */
    static void cleanAll(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM task_fragment_receipt");
        jdbc.update("DELETE FROM task_integrity_event");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_fragment");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }
}
