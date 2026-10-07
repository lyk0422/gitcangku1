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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.starter.consent.dto.JointRecordConfirmRequest;
import com.example.starter.consent.dto.JointRecordCreateRequest;
import com.example.starter.consent.dto.JointRecordResponse;

/**
 * 共同拥有记录 API 集成测试：双主体提案、确认、修订、撤回与并发重试边界。
 * 场景数据（合成主体 A/B 及其研究授权）由用例自行建立并在每个用例前清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointRecordApiTest {

    private static final String SUBJECT_A = "subj-a";
    private static final String SUBJECT_B = "subj-b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JointRecordService jointRecordService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM joint_record");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
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

    private ResultActions createJoint(String actor, String requestId, String a, String b,
                                      int epochA, int epochB, String recordKey, String payload) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/joint-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKeyA":"%s","subjectKeyB":"%s","epochA":%d,"epochB":%d,"recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, a, b, epochA, epochB, recordKey, payload));
        if (actor != null) {
            builder.header("X-Actor-Id", actor);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions confirm(String actor, String requestId, String recordKey,
                                  String subjectKey, int expectedVersion) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/joint-records/{recordKey}/confirmations", recordKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","expectedVersion":%d}
                        """.formatted(requestId, subjectKey, expectedVersion));
        if (actor != null) {
            builder.header("X-Actor-Id", actor);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions revise(String actor, String requestId, String recordKey,
                                 int expectedVersion, String payload) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/v1/joint-records/{recordKey}/revisions", recordKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","expectedVersion":%d,"payload":"%s"}
                        """.formatted(requestId, expectedVersion, payload));
        if (actor != null) {
            builder.header("X-Actor-Id", actor);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions readJoint(String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/joint-records/{recordKey}", recordKey));
    }

    private void grantBothActive() throws Exception {
        grant("g-a-1", SUBJECT_A).andExpect(status().isOk());
        grant("g-b-1", SUBJECT_B).andExpect(status().isOk());
    }

    private void createConfirmedJoint() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        confirm(SUBJECT_A, "jf-a-1", "joint-1", SUBJECT_A, 1).andExpect(status().isOk());
        confirm(SUBJECT_B, "jf-b-1", "joint-1", SUBJECT_B, 1).andExpect(status().isOk());
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：双确认后可读 ----------

    @Test
    void doubleConfirmThenReadReturnsFrozenPayload() throws Exception {
        grantBothActive();
        // 写响应只返回提案元数据，不含内容
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revisionCount").value(0))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.epochA").value(1))
                .andExpect(jsonPath("$.epochB").value(1))
                .andExpect(jsonPath("$.payload").doesNotExist());

        // 未双确认：读取 409
        readJoint("joint-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_CONFIRMED"));

        confirm(SUBJECT_A, "jf-a-1", "joint-1", SUBJECT_A, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.subjectAConfirmed").value(true))
                .andExpect(jsonPath("$.subjectBConfirmed").value(false))
                .andExpect(jsonPath("$.payload").doesNotExist());
        readJoint("joint-1").andExpect(status().isConflict());

        confirm(SUBJECT_B, "jf-b-1", "joint-1", SUBJECT_B, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        readJoint("joint-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-v1"))
                .andExpect(jsonPath("$.epochA").value(1))
                .andExpect(jsonPath("$.epochB").value(1))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 新增校验分支 ----------

    @Test
    void createWithSameSubjectReturns400() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_A, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUBJECT_PAIR_INVALID"));
    }

    @Test
    void createWithoutGrantReturns404() throws Exception {
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void createWithRevokedGrantReturns410() throws Exception {
        grantBothActive();
        revoke("r-b-1", SUBJECT_B, 1).andExpect(status().isOk());
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void createWithStaleEpochReturns409() throws Exception {
        grantBothActive();
        revoke("r-b-1", SUBJECT_B, 1).andExpect(status().isOk());
        grant("g-b-2", SUBJECT_B).andExpect(status().isOk());
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_EPOCH_MISMATCH"));
    }

    @Test
    void createWithReversedSubjectOrderIsEquivalent() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_B, SUBJECT_A, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectA").value(SUBJECT_A))
                .andExpect(jsonPath("$.subjectB").value(SUBJECT_B));
    }

    // ---------- 确认越权与版本分支 ----------

    @Test
    void confirmActorMismatchReturns403() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        // X-Actor-Id 与声明主体不一致
        confirm(SUBJECT_B, "jf-1", "joint-1", SUBJECT_A, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_FORBIDDEN"));
        // 缺少 X-Actor-Id
        confirm(null, "jf-2", "joint-1", SUBJECT_A, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_FORBIDDEN"));
        // 声明主体不属于共同记录两方
        confirm("subj-c", "jf-3", "joint-1", "subj-c", 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_FORBIDDEN"));
        assertThat(count("SELECT COUNT(*) FROM joint_record WHERE confirm_a OR confirm_b")).isZero();
    }

    @Test
    void confirmWithWrongVersionReturns409() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        confirm(SUBJECT_A, "jf-1", "joint-1", SUBJECT_A, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_VERSION_CONFLICT"));
    }

    @Test
    void sameSubjectConfirmTwiceDoesNotCompleteProposal() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        confirm(SUBJECT_A, "jf-1", "joint-1", SUBJECT_A, 1).andExpect(status().isOk());
        // 同一方重复确认不产生第二票，提案仍需另一方确认
        confirm(SUBJECT_A, "jf-2", "joint-1", SUBJECT_A, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        readJoint("joint-1").andExpect(status().isConflict());
    }

    // ---------- 修订分支 ----------

    @Test
    void revisionClearsConfirmationsAndIncrementsVersion() throws Exception {
        createConfirmedJoint();
        revise(SUBJECT_A, "jr-1", "joint-1", 1, "payload-v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.revisionCount").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.subjectAConfirmed").value(false))
                .andExpect(jsonPath("$.subjectBConfirmed").value(false))
                .andExpect(jsonPath("$.payload").doesNotExist());

        // 修订使两票失效：读取 409，旧版本确认 409
        readJoint("joint-1").andExpect(status().isConflict());
        confirm(SUBJECT_A, "jf-a-2", "joint-1", SUBJECT_A, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_VERSION_CONFLICT"));

        confirm(SUBJECT_A, "jf-a-3", "joint-1", SUBJECT_A, 2).andExpect(status().isOk());
        confirm(SUBJECT_B, "jf-b-3", "joint-1", SUBJECT_B, 2).andExpect(status().isOk());
        readJoint("joint-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-v2"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void revisionLimitExceededReturns422() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        revise(SUBJECT_A, "jr-1", "joint-1", 1, "payload-v2").andExpect(status().isOk());
        revise(SUBJECT_A, "jr-2", "joint-1", 2, "payload-v3").andExpect(status().isOk());
        revise(SUBJECT_A, "jr-3", "joint-1", 3, "payload-v4")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.revisionCount").value(3));
        revise(SUBJECT_A, "jr-4", "joint-1", 4, "payload-v5")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVISION_LIMIT_EXCEEDED"));
        // 超限失败后数据保持第 4 版
        assertThat(count("SELECT COUNT(*) FROM joint_record WHERE version = 4 AND revision_count = 3")).isEqualTo(1);
    }

    // ---------- 撤回与 410 语义（题干场景） ----------

    @Test
    void revokeAfterRevisionMakesJointRecordGoneEvenAfterRegrant() throws Exception {
        // 合成 A 代 1 和 B 代 1 共同稿 v1，两方确认后可读
        createConfirmedJoint();
        readJoint("joint-1").andExpect(status().isOk());

        // 修订 v2 使两票失效
        revise(SUBJECT_A, "jr-1", "joint-1", 1, "payload-v2").andExpect(status().isOk());

        // B 撤回：读取、确认、修订均 410，即使 A 仍有效
        revoke("r-b-1", SUBJECT_B, 1).andExpect(status().isOk());
        readJoint("joint-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        confirm(SUBJECT_A, "jf-a-2", "joint-1", SUBJECT_A, 2)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        revise(SUBJECT_A, "jr-2", "joint-1", 2, "payload-v3")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));

        // B 新授权代 2 不能替换旧记录中的代次，原稿仍 410
        grant("g-b-2", SUBJECT_B).andExpect(status().isOk());
        readJoint("joint-1").andExpect(status().isGone());
        revise(SUBJECT_A, "jr-3", "joint-1", 2, "payload-v3").andExpect(status().isGone());
    }

    // ---------- 幂等边界 ----------

    @Test
    void replaySameParamsReturnsOriginalResult() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'jc-1'")).isEqualTo(1);

        confirm(SUBJECT_A, "jf-1", "joint-1", SUBJECT_A, 1).andExpect(status().isOk());
        confirm(SUBJECT_A, "jf-1", "joint-1", SUBJECT_A, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectAConfirmed").value(true));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'jf-1'")).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-2", "payload-v1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        grantBothActive();
        revoke("r-b-1", SUBJECT_B, 1).andExpect(status().isOk());
        grant("g-b-2", SUBJECT_B).andExpect(status().isOk());
        // 代次不匹配失败，requestId 不应被占用
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isConflict());
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 2, "joint-1", "payload-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epochB").value(2));
    }

    @Test
    void replayAfterRevokeReturns410() throws Exception {
        createConfirmedJoint();
        revoke("r-a-1", SUBJECT_A, 1).andExpect(status().isOk());
        // 授权有效性先于含值重放：命中幂等快照也返回 410
        confirm(SUBJECT_A, "jf-a-1", "joint-1", SUBJECT_A, 1)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentConfirmsByBothSubjectsFreezeOnce() throws Exception {
        grantBothActive();
        createJoint(SUBJECT_A, "jc-1", SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "payload-v1")
                .andExpect(status().isOk());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<JointRecordResponse>> tasks = List.of(
                () -> {
                    ready.countDown();
                    start.await();
                    return jointRecordService.confirm(SUBJECT_A, "joint-1",
                            new JointRecordConfirmRequest("jf-a-1", SUBJECT_A, 1));
                },
                () -> {
                    ready.countDown();
                    start.await();
                    return jointRecordService.confirm(SUBJECT_B, "joint-1",
                            new JointRecordConfirmRequest("jf-b-1", SUBJECT_B, 1));
                });
        List<Future<JointRecordResponse>> futures = new ArrayList<>();
        for (Callable<JointRecordResponse> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        // 行锁使两次确认串行提交：先提交者响应仍为 PENDING，后提交者完成冻结返回 CONFIRMED
        List<JointStatus> statuses = new ArrayList<>();
        for (Future<JointRecordResponse> future : futures) {
            statuses.add(future.get(20, TimeUnit.SECONDS).status());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(JointStatus.PENDING, JointStatus.CONFIRMED);
        assertThat(count("SELECT COUNT(*) FROM joint_record WHERE status = 'CONFIRMED'"
                + " AND confirm_a AND confirm_b")).isEqualTo(1);
        readJoint("joint-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("payload-v1"));
    }

    @Test
    void concurrentCreatesSameRecordKeySameParamsYieldSingleRecord() throws Exception {
        grantBothActive();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "jc-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return jointRecordService.create(SUBJECT_A, new JointRecordCreateRequest(
                        requestId, SUBJECT_A, SUBJECT_B, 1, 1, "joint-1", "same-payload")).version();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM joint_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'JOINT_CREATE'"))
                .isEqualTo(threads);
    }
}
