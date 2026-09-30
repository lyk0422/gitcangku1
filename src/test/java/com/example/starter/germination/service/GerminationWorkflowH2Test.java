package com.example.starter.germination.service;

import com.example.starter.germination.AbstractGerminationH2Test;
import com.example.starter.germination.FixedClockTestConfiguration;
import com.example.starter.germination.web.BatchChainView;
import com.example.starter.germination.web.CertificateView;
import com.example.starter.germination.web.IssueCertificateRequest;
import com.example.starter.germination.web.ReleaseBatchRequest;
import com.example.starter.germination.web.ReleaseView;
import com.example.starter.germination.web.RegisterTestRequest;
import com.example.starter.germination.web.TestView;
import com.example.starter.germination.web.VoidTestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 H2（MODE=MySQL）上的业务主链与拒绝分支测试。
 */
@Import(FixedClockTestConfiguration.class)
class GerminationWorkflowH2Test extends AbstractGerminationH2Test {

    private static final String TAG = "ITWF-";

    @Autowired
    private GerminationService service;

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected void setupScenario() {
        createBatch(TAG + "B1", "合成水稻");
        createBatch(TAG + "B2", "合成玉米");
    }

    private RegisterTestRequest reg(String reqId, String testNo, String batchNo, int sample, int germinated) {
        return new RegisterTestRequest(testNo, batchNo, sample, germinated, reqId);
    }

    @Test
    void twoPassingTests_thenIssueAndRelease_happyChain() {
        TestView t1 = service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B1", 100, 86));
        TestView t2 = service.registerTest(reg(TAG + "R2", TAG + "T2", TAG + "B1", 100, 90));
        assertThat(t1.ratioPercent()).isEqualByComparingTo("86.00");
        assertThat(t2.ratioPercent()).isEqualByComparingTo("90.00");

        CertificateView cert = service.issueCertificate(
                new IssueCertificateRequest(TAG + "B1", TAG + "R3"));
        assertThat(cert.firstTestNo()).isEqualTo(TAG + "T1");
        assertThat(cert.firstRatio()).isEqualByComparingTo("86.00");
        assertThat(cert.secondTestNo()).isEqualTo(TAG + "T2");
        assertThat(cert.secondRatio()).isEqualByComparingTo("90.00");
        assertThat(cert.status()).isEqualTo("VALID");
        assertThat(cert.issuedAt()).isEqualTo(FixedClockTestConfiguration.FIXED);

        ReleaseView release = service.releaseBatch(new ReleaseBatchRequest(TAG + "B1", TAG + "R4"));
        assertThat(release.released()).isTrue();
        assertThat(release.certNo()).isEqualTo(cert.certNo());
        assertThat(release.releasedAt()).isEqualTo(FixedClockTestConfiguration.FIXED);

        BatchChainView chain = service.getBatchChain(TAG + "B1");
        assertThat(chain.tests()).extracting(TestView::testNo)
                .containsExactly(TAG + "T1", TAG + "T2");
        assertThat(chain.certificate()).isNotNull();
        // 证书依赖的检测编号在批次查询中可见
        assertThat(chain.certificate().firstTestNo()).isEqualTo(TAG + "T1");
        assertThat(chain.certificate().secondTestNo()).isEqualTo(TAG + "T2");
        assertThat(chain.release().released()).isTrue();
        assertThat(chain.release().certNo()).isEqualTo(cert.certNo());
    }

    @Test
    void voidFirstTestBeforeRelease_invalidatesCertAndReleaseReturns409_thenRetestReissues() {
        service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B1", 100, 86));
        service.registerTest(reg(TAG + "R2", TAG + "T2", TAG + "B1", 100, 90));
        CertificateView cert = service.issueCertificate(
                new IssueCertificateRequest(TAG + "B1", TAG + "R3"));

        TestView voided = service.voidTest(new VoidTestRequest(TAG + "T1", TAG + "B1", TAG + "R5"));
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(voided.voidedAt()).isEqualTo(FixedClockTestConfiguration.FIXED);

        // 放行请求返回 409
        assertThatThrownBy(() -> service.releaseBatch(new ReleaseBatchRequest(TAG + "B1", TAG + "R6")))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(RejectReason.CERTIFICATE_INVALID);
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                });

        BatchChainView chainAfterVoid = service.getBatchChain(TAG + "B1");
        assertThat(chainAfterVoid.certificate().status()).isEqualTo("INVALID");
        assertThat(chainAfterVoid.release().released()).isFalse();
        // 历史检测内容未被修改，仅状态变化
        assertThat(chainAfterVoid.tests()).hasSize(2);
        assertThat(chainAfterVoid.tests().get(0).status()).isEqualTo("VOID");
        assertThat(chainAfterVoid.tests().get(0).germinatedCount()).isEqualTo(86);

        // 复检追加新记录后可凭最近两次有效检测重新签发，旧失效证书留档
        service.registerTest(reg(TAG + "R7", TAG + "T3", TAG + "B1", 100, 95));
        CertificateView cert2 = service.issueCertificate(
                new IssueCertificateRequest(TAG + "B1", TAG + "R8"));
        assertThat(cert2.certNo()).isNotEqualTo(cert.certNo());
        assertThat(cert2.firstTestNo()).isEqualTo(TAG + "T2");
        assertThat(cert2.secondTestNo()).isEqualTo(TAG + "T3");
        assertThat(cert2.status()).isEqualTo("VALID");

        Integer certCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM germination_certificate WHERE batch_no = ?",
                Integer.class, TAG + "B1");
        assertThat(certCount).isEqualTo(2);
    }

    @Test
    void releaseCommitted_thenVoidKeepsCertificateSnapshotAndReleaseFact() {
        service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B2", 100, 86));
        service.registerTest(reg(TAG + "R2", TAG + "T2", TAG + "B2", 100, 90));
        CertificateView cert = service.issueCertificate(
                new IssueCertificateRequest(TAG + "B2", TAG + "R3"));
        service.releaseBatch(new ReleaseBatchRequest(TAG + "B2", TAG + "R4"));

        // 已放行后作废检测：事实不回滚
        service.voidTest(new VoidTestRequest(TAG + "T1", TAG + "B2", TAG + "R5"));

        BatchChainView chain = service.getBatchChain(TAG + "B2");
        assertThat(chain.tests().get(0).status()).isEqualTo("VOID");
        assertThat(chain.certificate().certNo()).isEqualTo(cert.certNo());
        assertThat(chain.certificate().status()).isEqualTo("VALID");
        assertThat(chain.release().released()).isTrue();

        // 重复放行 409
        assertThatThrownBy(() -> service.releaseBatch(new ReleaseBatchRequest(TAG + "B2", TAG + "R6")))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.BATCH_ALREADY_RELEASED));
    }

    @Test
    void rejectsInvalidSampleAndGerminatedCounts() {
        assertThatThrownBy(() -> service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B1", 99, 99)))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.SAMPLE_OUT_OF_RANGE));
        assertThatThrownBy(() -> service.registerTest(reg(TAG + "R2", TAG + "T2", TAG + "B1", 401, 400)))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.SAMPLE_OUT_OF_RANGE));
        assertThatThrownBy(() -> service.registerTest(reg(TAG + "R3", TAG + "T3", TAG + "B1", 100, 101)))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.GERMINATED_EXCEEDS_SAMPLE));

        // 拒绝不产生任何部分数据
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM germination_test WHERE test_no IN (?, ?, ?)",
                Integer.class, TAG + "T1", TAG + "T2", TAG + "T3");
        assertThat(rows).isZero();
    }

    @Test
    void rejectsDuplicateAndCrossBatchTestReuse() {
        service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B1", 100, 90));

        // 同批次重复使用检测编号
        assertThatThrownBy(() -> service.registerTest(reg(TAG + "R2", TAG + "T1", TAG + "B1", 100, 90)))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.TEST_NO_DUPLICATED));

        // 跨批次引用同一检测编号
        assertThatThrownBy(() -> service.registerTest(reg(TAG + "R3", TAG + "T1", TAG + "B2", 100, 90)))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.TEST_BELONGS_TO_OTHER_BATCH));

        Integer b2Tests = jdbc.queryForObject(
                "SELECT COUNT(*) FROM germination_test WHERE batch_no = ?", Integer.class, TAG + "B2");
        assertThat(b2Tests).isZero();
    }

    @Test
    void rejectsIssueWhenTestsMissingOrBelowThreshold() {
        // 无证书直接放行 → 409
        assertThatThrownBy(() -> service.releaseBatch(new ReleaseBatchRequest(TAG + "B1", TAG + "R0")))
                .isInstanceOfSatisfying(ConflictException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.CERTIFICATE_NOT_FOUND));

        // 仅一次有效检测
        service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B1", 100, 99));
        assertThatThrownBy(() -> service.issueCertificate(new IssueCertificateRequest(TAG + "B1", TAG + "R2")))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.NOT_ENOUGH_VALID_TESTS));

        // 最近两次中存在低于 85% 的检测（86 与 84，最近两次即这两条）
        service.registerTest(reg(TAG + "R3", TAG + "T2", TAG + "B1", 100, 84));
        assertThatThrownBy(() -> service.issueCertificate(new IssueCertificateRequest(TAG + "B1", TAG + "R4")))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.RATIO_BELOW_THRESHOLD));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM germination_certificate WHERE batch_no = ?",
                Integer.class, TAG + "B1")).isZero();
    }

    @Test
    void issuePassesAtExactly85PercentIncludingRoundedRatio() {
        // 170/200 = 85.00，85/100 = 85.00，恰好达标
        service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B2", 200, 170));
        service.registerTest(reg(TAG + "R2", TAG + "T2", TAG + "B2", 100, 85));
        CertificateView cert = service.issueCertificate(
                new IssueCertificateRequest(TAG + "B2", TAG + "R3"));
        assertThat(cert.firstRatio()).isEqualByComparingTo("85.00");
        assertThat(cert.secondRatio()).isEqualByComparingTo("85.00");
        assertThat(cert.status()).isEqualTo("VALID");
    }

    @Test
    void rejectsIssueWhenLatestTwoIncludeFailingOne() {
        // T1=99、T2=84、T3=90：最近两次为 T2、T3，其中 T2 不足 85，拒绝
        service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B1", 100, 99));
        service.registerTest(reg(TAG + "R2", TAG + "T2", TAG + "B1", 100, 84));
        service.registerTest(reg(TAG + "R3", TAG + "T3", TAG + "B1", 100, 90));
        assertThatThrownBy(() -> service.issueCertificate(new IssueCertificateRequest(TAG + "B1", TAG + "R4")))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.RATIO_BELOW_THRESHOLD));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM germination_certificate WHERE batch_no = ?",
                Integer.class, TAG + "B1")).isZero();
    }

    @Test
    void rejectsUnknownBatchAndTestAndDoubleVoid() {
        assertThatThrownBy(() -> service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "NOPE", 100, 90)))
                .isInstanceOfSatisfying(NotFoundException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(RejectReason.BATCH_NOT_FOUND);
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
        assertThatThrownBy(() -> service.getBatchChain(TAG + "NOPE"))
                .isInstanceOf(NotFoundException.class);

        service.registerTest(reg(TAG + "R2", TAG + "T2", TAG + "B1", 100, 90));
        assertThatThrownBy(() -> service.voidTest(new VoidTestRequest(TAG + "NOPE", TAG + "B1", TAG + "R3")))
                .isInstanceOfSatisfying(NotFoundException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.TEST_NOT_FOUND));

        service.voidTest(new VoidTestRequest(TAG + "T2", TAG + "B1", TAG + "R4"));
        assertThatThrownBy(() -> service.voidTest(new VoidTestRequest(TAG + "T2", TAG + "B1", TAG + "R5")))
                .isInstanceOfSatisfying(BusinessRejectedException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(RejectReason.TEST_ALREADY_VOID));
    }

    @Test
    void queryChainShowsTestsInRecordOrderAndNullCertificateBeforeIssue() {
        service.registerTest(reg(TAG + "R1", TAG + "T1", TAG + "B1", 300, 270));
        BatchChainView chain = service.getBatchChain(TAG + "B1");
        assertThat(chain.cropName()).isEqualTo("合成水稻");
        assertThat(chain.tests()).extracting(TestView::testNo).isEqualTo(List.of(TAG + "T1"));
        assertThat(chain.certificate()).isNull();
        assertThat(chain.release()).isEqualTo(new ReleaseView(false, null, null));
    }
}
