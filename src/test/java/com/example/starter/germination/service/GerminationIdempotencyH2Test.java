package com.example.starter.germination.service;

import com.example.starter.germination.AbstractGerminationH2Test;
import com.example.starter.germination.FixedClockTestConfiguration;
import com.example.starter.germination.repository.GerminationRepository;
import com.example.starter.germination.web.CertificateView;
import com.example.starter.germination.web.IssueCertificateRequest;
import com.example.starter.germination.web.RegisterTestRequest;
import com.example.starter.germination.web.TestView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 幂等语义 H2 测试：同参重放首次响应（含失败）、异参 409、事务失败后原键可重试。
 */
@Import(FixedClockTestConfiguration.class)
class GerminationIdempotencyH2Test extends AbstractGerminationH2Test {

    private static final String TAG = "ITID-";

    @Autowired
    private GerminationService service;

    @SpyBean
    private GerminationRepository repositorySpy;

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected void setupScenario() {
        createBatch(TAG + "B1", "合成水稻");
    }

    @Test
    void sameRequestSameParamsReplaysFirstResponse() {
        var request = new RegisterTestRequest(TAG + "T1", TAG + "B1", 100, 86, TAG + "R1");

        TestView first = service.registerTest(request);
        TestView replay = service.registerTest(request);

        // 重放返回首次响应内容（比例以数值比较，避免 BigDecimal 反序列化后的标度差异）
        assertThat(replay.testNo()).isEqualTo(first.testNo());
        assertThat(replay.batchNo()).isEqualTo(first.batchNo());
        assertThat(replay.sampleCount()).isEqualTo(first.sampleCount());
        assertThat(replay.germinatedCount()).isEqualTo(first.germinatedCount());
        assertThat(replay.ratioPercent()).isEqualByComparingTo(first.ratioPercent());
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.recordedAt()).isEqualTo(first.recordedAt());
        assertThat(replay.voidedAt()).isEqualTo(first.voidedAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM germination_test WHERE test_no = ?",
                Integer.class, TAG + "T1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request WHERE request_id = ?",
                Integer.class, TAG + "R1")).isEqualTo(1);
    }

    @Test
    void sameRequestDifferentParamsReturns409AndKeepsFirstResult() {
        service.registerTest(new RegisterTestRequest(TAG + "T1", TAG + "B1", 100, 86, TAG + "R1"));

        var changed = new RegisterTestRequest(TAG + "T2", TAG + "B1", 100, 90, TAG + "R1");
        assertThatThrownBy(() -> service.registerTest(changed))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo(RejectReason.IDEMPOTENT_PARAM_MISMATCH);
                });

        // 异参请求未产生部分数据
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM germination_test WHERE test_no = ?",
                Integer.class, TAG + "T2")).isZero();
    }

    @Test
    void replayReturnsFirstFailureResponseToo() {
        // 首次：仅一次检测时签发，明确失败（422）
        service.registerTest(new RegisterTestRequest(TAG + "T1", TAG + "B1", 100, 90, TAG + "R1"));
        var issueRequest = new IssueCertificateRequest(TAG + "B1", TAG + "R2");
        assertThatThrownBy(() -> service.issueCertificate(issueRequest))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.NOT_ENOUGH_VALID_TESTS));

        // 即使补齐第二次检测，同一 requestId 重放仍返回首次的失败结果
        service.registerTest(new RegisterTestRequest(TAG + "T2", TAG + "B1", 100, 90, TAG + "R3"));
        assertThatThrownBy(() -> service.issueCertificate(issueRequest))
                .isInstanceOfSatisfying(CachedResponseException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(422);
                    assertThat(ex.getReason()).isEqualTo(RejectReason.NOT_ENOUGH_VALID_TESTS);
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM germination_certificate WHERE batch_no = ?",
                Integer.class, TAG + "B1")).isZero();

        // 新 requestId 正常签发
        CertificateView certificate = service.issueCertificate(
                new IssueCertificateRequest(TAG + "B1", TAG + "R4"));
        assertThat(certificate.status()).isEqualTo("VALID");
    }

    @Test
    void transactionFailureRollsBackIdempotentRowAndSameKeyCanRetry() {
        // 首次执行在业务校验通过后、落库时模拟非预期故障，整事务回滚
        doAnswer(invocation -> {
            throw new IllegalStateException("模拟数据库瞬态故障");
        }).doCallRealMethod().when(repositorySpy).insertTest(any());

        var request = new RegisterTestRequest(TAG + "T1", TAG + "B1", 100, 86, TAG + "R9");
        assertThatThrownBy(() -> service.registerTest(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("瞬态故障");

        // 幂等占位记录随事务回滚，未残留 PROCESSING
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request WHERE request_id = ?",
                Integer.class, TAG + "R9")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM germination_test WHERE test_no = ?",
                Integer.class, TAG + "T1")).isZero();

        Mockito.reset(repositorySpy);

        // 允许原 requestId 重试并成功
        TestView retried = service.registerTest(request);
        assertThat(retried.testNo()).isEqualTo(TAG + "T1");
        assertThat(retried.ratioPercent()).isEqualByComparingTo("86.00");
    }
}
