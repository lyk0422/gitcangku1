package com.example.starter.archival;

import com.example.starter.archival.domain.BatchStatus;
import com.example.starter.archival.service.ArchivalService;
import com.example.starter.archival.web.ApiConflictException;
import com.example.starter.archival.web.dto.BatchResponse;
import com.example.starter.archival.web.dto.CreateBatchRequest;
import com.example.starter.archival.web.dto.EnrollRequest;
import com.example.starter.archival.web.dto.EnrollResult;
import com.example.starter.archival.web.dto.PageHistoryResponse;
import com.example.starter.archival.web.dto.ReadingRequest;
import com.example.starter.archival.web.dto.ReadingResponse;
import com.example.starter.archival.web.dto.RegisterPageRequest;
import com.example.starter.archival.web.dto.RejectRequest;
import com.example.starter.archival.web.dto.RejectResult;
import com.example.starter.archival.web.dto.ReleaseRequest;
import com.example.starter.archival.web.dto.RoundView;
import com.example.starter.archival.web.dto.SealRequest;
import com.example.starter.archival.web.dto.SealResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 H2 内存库（MODE=MySQL）端到端业务链测试：
 * 登记→建批→入组→读数→封存拒绝（后读数6.40）→质检退回→追加合规轮次→封存成功→查询。
 * 验证旧读数/旧理由不覆盖、已封存只读、占用释放、幂等与归还释放。
 */
class ArchivalWorkflowH2Test extends AbstractH2IntegrationTest {

    @Autowired
    private ArchivalService service;

    @Test
    @DisplayName("完整业务链：6.40整批封存失败，追加合规复处理后封存成功，历史可查且旧值保留")
    void fullWorkflowFailedSealThenReworkThenSealed() {
        service.registerPage("P");
        service.registerPage("Q");
        service.createBatch("B-2026");
        service.enroll(new EnrollRequest("B-2026", "P", "enroll-p"));
        service.enroll(new EnrollRequest("B-2026", "Q", "enroll-q"));

        // 第一轮：合成页 P 后读数 6.40（低于 6.50）；Q 合格
        ReadingResponse pRound1 = service.recordReading(
                new ReadingRequest("B-2026", "P", new BigDecimal("7.00"), new BigDecimal("6.40"), "LOT-A"));
        service.recordReading(
                new ReadingRequest("B-2026", "Q", new BigDecimal("7.10"), new BigDecimal("7.30"), "LOT-A"));
        assertThat(pRound1.roundNo()).isEqualTo(1);

        // 整批封存失败：必须列出问题页 P（6.40 同时触发“超范围”和“后低于前”两条原因），不能只封存合格页 Q
        SealResult failed = service.seal(new SealRequest("B-2026", "seal-1"));
        assertThat(failed.sealed()).isFalse();
        assertThat(failed.problems()).hasSize(2);
        assertThat(failed.problems()).allSatisfy(p -> assertThat(p.accessionNo()).isEqualTo("P"));
        assertThat(failed.problems()).extracting("reason")
                .anyMatch(r -> r.toString().contains("6.40") && r.toString().contains("6.50-8.50"))
                .anyMatch(r -> r.toString().contains("低于处理前"));
        assertThat(service.getBatch("B-2026").status()).isEqualTo(BatchStatus.REJECTED);
        // 拒绝后占用仍保留（页面仍在组，等待复处理）
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isEqualTo(2L);

        // 质检退回：追加理由
        RejectResult rejected = service.reject(new RejectRequest("B-2026", "P页处理后pH低于允许下限"));
        assertThat(rejected.reasons()).containsExactly("P页处理后pH低于允许下限");

        // 同 requestId 同参重放失败结果；修复前用“新 requestId”重试属于新请求，仍被整批拒绝
        SealResult replayedFail = service.seal(new SealRequest("B-2026", "seal-1"));
        assertThat(replayedFail.sealed()).isFalse();
        assertThat(replayedFail.replayed()).isTrue();
        assertThat(replayedFail.problems()).hasSize(2);
        SealResult newAttemptBeforeFix = service.seal(new SealRequest("B-2026", "seal-1b"));
        assertThat(newAttemptBeforeFix.sealed()).isFalse();
        assertThat(newAttemptBeforeFix.replayed()).isFalse();

        // 同一 requestId 异参（绑定另一批次）→ 409
        service.createBatch("B-OTHER");
        assertThatThrownBy(() -> service.seal(new SealRequest("B-OTHER", "seal-1")))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不同参数");

        // 追加第二轮合规复处理（前 6.50、后 7.00 均在窗口内且不下降），旧轮次不改写
        ReadingResponse pRound2 = service.recordReading(
                new ReadingRequest("B-2026", "P", new BigDecimal("6.50"), new BigDecimal("7.00"), "LOT-B"));
        assertThat(pRound2.roundNo()).isEqualTo(2);

        // 再次退回也只能追加理由
        RejectResult rejectedAgain = service.reject(new RejectRequest("B-2026", "复测观察一轮"));
        assertThat(rejectedAgain.reasons()).hasSize(2);

        // 用新 requestId 封存成功
        SealResult sealed = service.seal(new SealRequest("B-2026", "seal-2"));
        assertThat(sealed.sealed()).isTrue();
        assertThat(sealed.problems()).isEmpty();
        BatchResponse batch = service.getBatch("B-2026");
        assertThat(batch.status()).isEqualTo(BatchStatus.SEALED);
        assertThat(batch.sealedAt()).isNotNull();

        // 封存后处理占用全部释放
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isZero();

        // 已封存只读：登记读数、入组、退回、归还均被拒
        assertThatThrownBy(() -> service.recordReading(
                new ReadingRequest("B-2026", "P", new BigDecimal("7.00"), new BigDecimal("7.10"), "LOT-C")))
                .isInstanceOf(ApiConflictException.class);
        assertThatThrownBy(() -> service.enroll(new EnrollRequest("B-2026", "P", "enroll-p-again")))
                .isInstanceOf(ApiConflictException.class);
        assertThatThrownBy(() -> service.reject(new RejectRequest("B-2026", "封存后不能退回")))
                .isInstanceOf(ApiConflictException.class);
        assertThatThrownBy(() -> service.release(new ReleaseRequest("B-2026", "P")))
                .isInstanceOf(ApiConflictException.class);
        assertThatThrownBy(() -> service.seal(new SealRequest("B-2026", "seal-3")))
                .isInstanceOf(ApiConflictException.class);

        // 历史查询：P 有两轮，第一轮 6.40 原样保留，批次状态 SEALED，药剂来源可见
        PageHistoryResponse historyP = service.getHistory("P");
        assertThat(historyP.rounds()).hasSize(2);
        RoundView r1 = historyP.rounds().get(0);
        RoundView r2 = historyP.rounds().get(1);
        assertThat(r1.roundNo()).isEqualTo(1);
        assertThat(r1.phBefore()).isEqualByComparingTo("7.00");
        assertThat(r1.phAfter()).isEqualByComparingTo("6.40");
        assertThat(r1.reagentLot()).isEqualTo("LOT-A");
        assertThat(r1.batchStatus()).isEqualTo("SEALED");
        assertThat(r2.roundNo()).isEqualTo(2);
        assertThat(r2.phAfter()).isEqualByComparingTo("7.00");
        assertThat(r2.reagentLot()).isEqualTo("LOT-B");
    }

    @Test
    @DisplayName("同参入组重放原结果，异参409；失败重试使用原键成功")
    void enrollIdempotencySameRequestReplaysDifferentConflicts() {
        service.registerPage("E1");
        service.createBatch("BE");
        EnrollResult first = service.enroll(new EnrollRequest("BE", "E1", "idem-1"));
        assertThat(first.replayed()).isFalse();

        EnrollResult replay = service.enroll(new EnrollRequest("BE", "E1", "idem-1"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batchNo()).isEqualTo("BE");
        assertThat(replay.accessionNo()).isEqualTo("E1");

        // 异参：同一 requestId 绑定另一页或另一批次
        service.registerPage("E2");
        assertThatThrownBy(() -> service.enroll(new EnrollRequest("BE", "E2", "idem-1")))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不同参数");
        service.createBatch("BE-OTHER");
        assertThatThrownBy(() -> service.enroll(new EnrollRequest("BE-OTHER", "E1", "idem-1")))
                .isInstanceOf(ApiConflictException.class);

        // 数据未被异参污染：E1 仍只属于 BE 一次
        assertThat(count("SELECT COUNT(*) FROM batch_member")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isEqualTo(1L);
    }

    @Test
    @DisplayName("归还释放占用：未封存批次归还后可入组新批次，再入组原批次复用在组行")
    void releaseFreesOccupationAndAllowsNewEnrollment() {
        service.registerPage("M1");
        service.createBatch("B1");
        service.createBatch("B2");
        service.enroll(new EnrollRequest("B1", "M1", "e-b1"));

        // 跨批次占用：未归还不能入组第二个进行中批次
        assertThatThrownBy(() -> service.enroll(new EnrollRequest("B2", "M1", "e-b2-first")))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("其他进行中批次占用");
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isEqualTo(1L);

        // 归还：在组行 RELEASED 与占用删除同一事务
        service.release(new ReleaseRequest("B1", "M1"));
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isZero();
        assertThat(count("SELECT COUNT(*) FROM batch_member WHERE member_status='RELEASED'"))
                .isEqualTo(1L);

        // 重复归还 409
        assertThatThrownBy(() -> service.release(new ReleaseRequest("B1", "M1")))
                .isInstanceOf(ApiConflictException.class);

        // 现在可以入组 B2
        EnrollResult inB2 = service.enroll(new EnrollRequest("B2", "M1", "e-b2"));
        assertThat(inB2.replayed()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM page_occupation WHERE batch_id="
                + "(SELECT id FROM deacidification_batch WHERE batch_no='B2')")).isEqualTo(1L);

        // 从 B2 归还后重新入组 B1：复用原在组行（reactivated=true），不新增行；
        // 终态 B1 行 ACTIVE、B2 行 RELEASED，占用唯一
        service.release(new ReleaseRequest("B2", "M1"));
        EnrollResult backToB1 = service.enroll(new EnrollRequest("B1", "M1", "e-b1-back"));
        assertThat(backToB1.reactivated()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM batch_member")).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM batch_member WHERE member_status='ACTIVE'"))
                .isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isEqualTo(1L);
    }

    @Test
    @DisplayName("登记与建批的重复业务键冲突返回409")
    void duplicateNaturalKeysConflict() {
        service.registerPage("DUP-P");
        service.createBatch("DUP-B");
        assertThatThrownBy(() -> service.registerPage("DUP-P"))
                .isInstanceOf(ApiConflictException.class);
        assertThatThrownBy(() -> service.createBatch("DUP-B"))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("顺序分支一：封存先提交后，归还被拒（已封存只读），成员仍ACTIVE且无占用")
    void sealCommitsFirstThenReleaseRejected() {
        service.registerPage("S1");
        service.createBatch("BS1");
        service.enroll(new EnrollRequest("BS1", "S1", "es1"));
        service.recordReading(new ReadingRequest("BS1", "S1",
                new BigDecimal("7.00"), new BigDecimal("7.20"), "L1"));

        assertThat(service.seal(new SealRequest("BS1", "ss1")).sealed()).isTrue();
        assertThatThrownBy(() -> service.release(new ReleaseRequest("BS1", "S1")))
                .isInstanceOf(ApiConflictException.class);

        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isZero();
        assertThat(count("SELECT COUNT(*) FROM batch_member WHERE member_status='ACTIVE'"))
                .isEqualTo(1L);
        assertThat(service.getBatch("BS1").status()).isEqualTo(BatchStatus.SEALED);
    }

    @Test
    @DisplayName("顺序分支二：归还先提交后，封存被拒（无在组页），成员RELEASED且无占用；重新入组后可封存")
    void releaseCommitsFirstThenSealRejected() {
        service.registerPage("S2");
        service.createBatch("BS2");
        service.enroll(new EnrollRequest("BS2", "S2", "es2"));
        service.recordReading(new ReadingRequest("BS2", "S2",
                new BigDecimal("7.00"), new BigDecimal("7.20"), "L2"));

        service.release(new ReleaseRequest("BS2", "S2"));
        assertThatThrownBy(() -> service.seal(new SealRequest("BS2", "ss2")))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("没有在组页面");

        assertThat(count("SELECT COUNT(*) FROM page_occupation")).isZero();
        assertThat(count("SELECT COUNT(*) FROM batch_member WHERE member_status='RELEASED'"))
                .isEqualTo(1L);
        assertThat(service.getBatch("BS2").status()).isNotEqualTo(BatchStatus.SEALED);

        // 页面重新入组补齐读数后可正常封存
        assertThat(service.enroll(new EnrollRequest("BS2", "S2", "es2-back")).reactivated()).isTrue();
        assertThat(service.seal(new SealRequest("BS2", "ss2b")).sealed()).isTrue();
        assertThat(service.getBatch("BS2").status()).isEqualTo(BatchStatus.SEALED);
    }
}
