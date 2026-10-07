package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.JointConfirmRequest;
import com.example.starter.consent.dto.JointCreateRequest;
import com.example.starter.consent.dto.JointProposalResponse;

/**
 * 共同研究记录 API 集成测试：覆盖双确认主流程、越权 403、版本冲突、修订上限 422、
 * 撤回 410、幂等重放与并发边界。每个用例前重建本题合成数据（subj-a/subj-b 各持研究授权代次 1）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointRecordApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JointRecordService jointRecordService;

    @BeforeEach
    void resetScenario() {
        jdbc.update("DELETE FROM joint_record");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM consent_grant");
        // 本题合成数据：主体 subj-a 与 subj-b 各持有效研究授权代次 1
        jdbc.update("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                + " VALUES ('subj-a', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-subj-a-1')");
        jdbc.update("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                + " VALUES ('subj-b', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-subj-b-1')");
    }

    private ResultActions createJoint(String requestId, String recordKey, String subjectA, String subjectB,
                                      String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/joint-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","recordKey":"%s","subjectA":"%s","subjectB":"%s","payload":"%s"}
                        """.formatted(requestId, recordKey, subjectA, subjectB, payload)));
    }

    private ResultActions confirm(String requestId, String recordKey, String subject, int version,
                                  String actorId) throws Exception {
        return mockMvc.perform(post("/api/v1/joint-records/{recordKey}/confirmations", recordKey)
                .header("X-Actor-Id", actorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subject":"%s","version":%d}
                        """.formatted(requestId, subject, version)));
    }

    private ResultActions revise(String requestId, String recordKey, int expectedVersion,
                                 String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/joint-records/{recordKey}/revisions", recordKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","payload":"%s","expectedVersion":%d}
                        """.formatted(requestId, payload, expectedVersion)));
    }

    private ResultActions readJoint(String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/joint-records/{recordKey}", recordKey));
    }

    private ResultActions grant(String requestId, String subjectKey) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH"}
                        """.formatted(requestId, subjectKey)));
    }

    private ResultActions revoke(String requestId, String subjectKey, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d}
                        """.formatted(requestId, subjectKey, epoch)));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 创建主流程与失败分支 ----------

    @Test
    void createReturnsProposalMetadataWithoutPayload() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "joint-payload-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("joint-1"))
                .andExpect(jsonPath("$.epochA").value(1))
                .andExpect(jsonPath("$.epochB").value(1))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revisionCount").value(0))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.confirmedA").value(false))
                .andExpect(jsonPath("$.confirmedB").value(false))
                // 写响应只返回提案元数据，不含内容
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    @Test
    void createWithSameSubjectReturns400() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-a", "payload")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("JOINT_SUBJECTS_EQUAL"));
    }

    @Test
    void createWithoutGrantReturns404() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-none", "payload")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void createWithRevokedGrantReturns410() throws Exception {
        revoke("rvk-1", "subj-b", 1).andExpect(status().isOk());
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void createDuplicateRecordKeyWithDifferentPayloadReturns409() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-1").andExpect(status().isOk());
        createJoint("jc-2", "joint-1", "subj-a", "subj-b", "payload-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOINT_RECORD_KEY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
    }

    @Test
    void createSameKeySameParamsDeduplicates() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-1").andExpect(status().isOk());
        createJoint("jc-2", "joint-1", "subj-a", "subj-b", "payload-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
    }

    // ---------- 确认与越权 ----------

    @Test
    void confirmWithMismatchedActorReturns403() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload").andExpect(status().isOk());
        // 声明主体 subj-a，但本地 X-Actor-Id 为 subj-b：越权
        confirm("cf-1", "joint-1", "subj-a", 1, "subj-b")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("JOINT_ACTOR_FORBIDDEN"));
    }

    @Test
    void confirmWithOutsiderSubjectReturns403() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload").andExpect(status().isOk());
        confirm("cf-1", "joint-1", "subj-c", 1, "subj-c")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("JOINT_ACTOR_FORBIDDEN"));
    }

    @Test
    void confirmWithoutActorHeaderReturns400() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/joint-records/joint-1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"cf-1\",\"subject\":\"subj-a\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void confirmWithStaleVersionReturns409() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload").andExpect(status().isOk());
        confirm("cf-1", "joint-1", "subj-a", 9, "subj-a")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOINT_VERSION_CONFLICT"));
    }

    @Test
    void confirmNonexistentRecordReturns404() throws Exception {
        confirm("cf-1", "joint-none", "subj-a", 1, "subj-a")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOINT_RECORD_NOT_FOUND"));
    }

    // ---------- 题干要求的全生命周期场景 ----------

    @Test
    void jointLifecycleMatchesRequiredScenario() throws Exception {
        // 合成 A 代1 和 B 代1 共同稿 v1，两方确认后可读
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "joint-payload-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("PROPOSED"));
        confirm("cf-1", "joint-1", "subj-a", 1, "subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedA").value(true))
                .andExpect(jsonPath("$.confirmedB").value(false));
        // 当前提案未双确认：读取 409
        readJoint("joint-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOINT_NOT_CONFIRMED"));
        confirm("cf-2", "joint-1", "subj-b", 1, "subj-b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        readJoint("joint-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("joint-payload-v1"))
                .andExpect(jsonPath("$.epochA").value(1))
                .andExpect(jsonPath("$.epochB").value(1));

        // 修订 v2 使两票失效
        revise("rv-1", "joint-1", 1, "joint-payload-v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.revisionCount").value(1))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.confirmedA").value(false))
                .andExpect(jsonPath("$.confirmedB").value(false));
        readJoint("joint-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOINT_NOT_CONFIRMED"));

        // B 撤回代1：即使 A 有效，读取/确认/修订均 410
        revoke("rvk-b", "subj-b", 1).andExpect(status().isOk());
        readJoint("joint-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        confirm("cf-3", "joint-1", "subj-a", 2, "subj-a")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        revise("rv-2", "joint-1", 2, "joint-payload-v3")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));

        // B 新授权代2 不能替换旧记录中的代次，原稿仍 410
        grant("g-b2", "subj-b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2));
        readJoint("joint-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 修订边界 ----------

    @Test
    void reviseWithStaleExpectedVersionReturns409() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        revise("rv-1", "joint-1", 2, "payload-v2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOINT_VERSION_CONFLICT"));
    }

    @Test
    void reviseBeyondThreeTimesReturns422() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        revise("rv-1", "joint-1", 1, "payload-v2").andExpect(status().isOk());
        revise("rv-2", "joint-1", 2, "payload-v3").andExpect(status().isOk());
        revise("rv-3", "joint-1", 3, "payload-v4")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.revisionCount").value(3));
        // 第 4 次修订超限：422
        revise("rv-4", "joint-1", 4, "payload-v5")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("JOINT_REVISION_LIMIT"));
        assertThat(count("SELECT COUNT(*) FROM joint_record WHERE record_key = 'joint-1'"
                + " AND version = 4 AND revision_count = 3")).isEqualTo(1);
    }

    @Test
    void reviseAfterRevokeReturns410() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        revoke("rvk-a", "subj-a", 1).andExpect(status().isOk());
        revise("rv-1", "joint-1", 1, "payload-v2")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void confirmAfterRevokeReturns410() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        revoke("rvk-b", "subj-b", 1).andExpect(status().isOk());
        confirm("cf-1", "joint-1", "subj-a", 1, "subj-a")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 幂等与重放 ----------

    @Test
    void createReplaySameRequestIdReturnsOriginalResult() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'jc-1'")).isEqualTo(1);
    }

    @Test
    void createSameRequestIdWithDifferentParamsReturns409() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        createJoint("jc-1", "joint-2", "subj-a", "subj-b", "payload-v1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void confirmReplaySameRequestIdReturnsOriginalResult() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        confirm("cf-1", "joint-1", "subj-a", 1, "subj-a").andExpect(status().isOk());
        confirm("cf-1", "joint-1", "subj-a", 1, "subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedA").value(true));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'cf-1'")).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        // 无授权主体导致创建失败，requestId 不应被占用
        createJoint("jc-1", "joint-1", "subj-a", "subj-none", "payload-v1")
                .andExpect(status().isNotFound());
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1")
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
    }

    @Test
    void replayAfterRevokeCannotBypassConsentState() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        revoke("rvk-b", "subj-b", 1).andExpect(status().isOk());
        // 授权有效性先于含值重放：同 requestId 同参数重放也必须 410
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentConfirmsByBothSidesFreezeExactlyOnce() throws Exception {
        createJoint("jc-1", "joint-1", "subj-a", "subj-b", "payload-v1").andExpect(status().isOk());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<JointProposalResponse> confirmA = () -> {
            ready.countDown();
            start.await();
            return jointRecordService.confirm("joint-1", "subj-a",
                    new JointConfirmRequest("cf-a", "subj-a", 1));
        };
        Callable<JointProposalResponse> confirmB = () -> {
            ready.countDown();
            start.await();
            return jointRecordService.confirm("joint-1", "subj-b",
                    new JointConfirmRequest("cf-b", "subj-b", 1));
        };
        Future<JointProposalResponse> futureA = pool.submit(confirmA);
        Future<JointProposalResponse> futureB = pool.submit(confirmB);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        futureA.get(20, TimeUnit.SECONDS);
        futureB.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        // 两票齐后最终状态：恰好一次冻结，内容可读
        assertThat(count("SELECT COUNT(*) FROM joint_record WHERE record_key = 'joint-1'"
                + " AND status = 'FROZEN' AND confirm_a = 'subj-a' AND confirm_b = 'subj-b'")).isEqualTo(1);
        readJoint("joint-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-v1"));
    }

    @Test
    void concurrentCreatesSameRequestIdReplaySingleResult() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<JointProposalResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return jointRecordService.create(
                        new JointCreateRequest("jc-same", "joint-c", "subj-a", "subj-b", "payload-v1"));
            });
        }
        List<Future<JointProposalResponse>> futures = new ArrayList<>();
        for (Callable<JointProposalResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<JointProposalResponse> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS).version()).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'jc-same'")).isEqualTo(1);
    }

    @Test
    void concurrentCreatesDifferentRequestIdsSameKeyProduceSingleRecord() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<JointProposalResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "jc-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return jointRecordService.create(
                        new JointCreateRequest(requestId, "joint-c", "subj-a", "subj-b", "payload-v1"));
            });
        }
        List<Future<JointProposalResponse>> futures = new ArrayList<>();
        for (Callable<JointProposalResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<JointProposalResponse> future : futures) {
            // 同 key 同参数去重：全部成功且不报唯一键 500
            assertThat(future.get(20, TimeUnit.SECONDS).recordKey()).isEqualTo("joint-c");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'JOINT_CREATE'"))
                .isEqualTo(threads);
    }
}
