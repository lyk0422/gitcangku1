package com.example.starter.archival.service;

import com.example.starter.archival.domain.ArchivePage;
import com.example.starter.archival.domain.BatchMember;
import com.example.starter.archival.domain.BatchStatus;
import com.example.starter.archival.domain.DeacidificationBatch;
import com.example.starter.archival.domain.IdempotentRequest;
import com.example.starter.archival.domain.MemberStatus;
import com.example.starter.archival.domain.TreatmentRound;
import com.example.starter.archival.repo.BatchRepository;
import com.example.starter.archival.repo.IdempotentRequestRepository;
import com.example.starter.archival.repo.MemberRepository;
import com.example.starter.archival.repo.OccupationRepository;
import com.example.starter.archival.repo.PageRepository;
import com.example.starter.archival.repo.RejectionRepository;
import com.example.starter.archival.repo.RoundRepository;
import com.example.starter.archival.support.MutableTestClock;
import com.example.starter.archival.web.dto.SealProblem;
import com.example.starter.archival.web.dto.SealRequest;
import com.example.starter.archival.web.dto.SealResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 封存校验规则纯单元测试：缺读数、超范围、后值低于前值必须整批拒绝并列出全部问题页，
 * 不得只封存合格子集；修正靠追加合规轮次而非改旧值。
 */
class SealValidationUnitTest {

    private PageRepository pageRepository;
    private BatchRepository batchRepository;
    private MemberRepository memberRepository;
    private OccupationRepository occupationRepository;
    private RoundRepository roundRepository;
    private RejectionRepository rejectionRepository;
    private IdempotentRequestRepository idempotentRequestRepository;
    private ArchivalService service;
    private MutableTestClock clock;

    private static final DeacidificationBatch OPEN_BATCH =
            new DeacidificationBatch(1L, "B1", BatchStatus.OPEN, null,
                    LocalDateTime.of(2026, 10, 1, 9, 0));

    @BeforeEach
    void setUp() {
        pageRepository = mock(PageRepository.class);
        batchRepository = mock(BatchRepository.class);
        memberRepository = mock(MemberRepository.class);
        occupationRepository = mock(OccupationRepository.class);
        roundRepository = mock(RoundRepository.class);
        rejectionRepository = mock(RejectionRepository.class);
        idempotentRequestRepository = mock(IdempotentRequestRepository.class);
        clock = MutableTestClock.startAt(2026, 10, 1, 10, 0);
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        service = new ArchivalService(pageRepository, batchRepository, memberRepository,
                occupationRepository, roundRepository, rejectionRepository,
                idempotentRequestRepository, clock, objectMapper);

        when(batchRepository.findByBatchNoForUpdate("B1")).thenReturn(Optional.of(OPEN_BATCH));
        when(idempotentRequestRepository.find(any())).thenReturn(Optional.empty());
    }

    private BatchMember activeMember(long pageId) {
        return new BatchMember(pageId, 1L, pageId, MemberStatus.ACTIVE,
                clock.now(), null, "req-" + pageId);
    }

    private ArchivePage page(long id, String accessionNo) {
        return new ArchivePage(id, accessionNo, clock.now());
    }

    private TreatmentRound round(long pageId, int roundNo, String before, String after) {
        return new TreatmentRound(roundNo * 100L + pageId, 1L, pageId, roundNo,
                before == null ? null : new BigDecimal(before),
                after == null ? null : new BigDecimal(after),
                "LOT-" + roundNo, clock.now());
    }

    @Test
    @DisplayName("全部页读数合格才封存：置 SEALED 并释放全部占用")
    void sealsWhenAllPagesValid() {
        ArchivePage p1 = page(1L, "P1");
        ArchivePage p2 = page(2L, "P2");
        when(memberRepository.findActiveByBatch(1L))
                .thenReturn(List.of(activeMember(1L), activeMember(2L)));
        when(pageRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(pageRepository.findById(2L)).thenReturn(Optional.of(p2));
        when(roundRepository.findByBatchAndPage(1L, 1L)).thenReturn(List.of(round(1L, 1, "7.00", "7.20")));
        when(roundRepository.findByBatchAndPage(1L, 2L)).thenReturn(List.of(round(2L, 1, "6.50", "6.50")));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-ok"));

        assertThat(result.sealed()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.sealedAt()).isEqualTo(clock.now());
        verify(batchRepository).updateStatus(eq(1L), eq(BatchStatus.SEALED), any());
        verify(occupationRepository).releaseByBatch(1L);
        verify(batchRepository, never()).updateStatus(eq(1L), eq(BatchStatus.REJECTED), any());
    }

    @Test
    @DisplayName("一页合格一页缺读数：整批拒绝并列出问题页，不封存合格子集，占用不释放")
    void rejectsWholeBatchWhenOnePageMissingReading() {
        ArchivePage good = page(1L, "GOOD");
        ArchivePage bad = page(2L, "BAD");
        when(memberRepository.findActiveByBatch(1L))
                .thenReturn(List.of(activeMember(1L), activeMember(2L)));
        when(pageRepository.findById(1L)).thenReturn(Optional.of(good));
        when(pageRepository.findById(2L)).thenReturn(Optional.of(bad));
        when(roundRepository.findByBatchAndPage(1L, 1L)).thenReturn(List.of(round(1L, 1, "7.00", "7.50")));
        when(roundRepository.findByBatchAndPage(1L, 2L))
                .thenReturn(List.of(round(2L, 1, "7.00", null)));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-missing"));

        assertThat(result.sealed()).isFalse();
        assertThat(result.sealedAt()).isNull();
        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).accessionNo()).isEqualTo("BAD");
        assertThat(result.problems().get(0).reason()).contains("处理后读数缺失");
        verify(batchRepository).updateStatus(1L, BatchStatus.REJECTED, null);
        verify(occupationRepository, never()).releaseByBatch(anyLong());
        verify(batchRepository, never()).updateStatus(eq(1L), eq(BatchStatus.SEALED), any());
    }

    @Test
    @DisplayName("合成页后读数6.40低于下沿：整批拒绝，同时列出超范围与后低于前两条原因")
    void rejectsAfterPhBelowRange() {
        ArchivePage p = page(9L, "P");
        when(memberRepository.findActiveByBatch(1L)).thenReturn(List.of(activeMember(9L)));
        when(pageRepository.findById(9L)).thenReturn(Optional.of(p));
        when(roundRepository.findByBatchAndPage(1L, 9L))
                .thenReturn(List.of(round(9L, 1, "7.00", "6.40")));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-64"));

        assertThat(result.sealed()).isFalse();
        assertThat(result.problems()).hasSize(2);
        assertThat(result.problems()).allSatisfy(problem ->
                assertThat(problem.accessionNo()).isEqualTo("P"));
        assertThat(result.problems()).extracting(SealProblem::reason)
                .anyMatch(r -> r.contains("6.40") && r.contains("6.50-8.50"))
                .anyMatch(r -> r.contains("低于处理前"));
        verify(batchRepository).updateStatus(1L, BatchStatus.REJECTED, null);
    }

    @Test
    @DisplayName("处理后pH低于处理前：拒绝并列出前后值")
    void rejectsAfterLowerThanBefore() {
        ArchivePage p = page(3L, "DOWN");
        when(memberRepository.findActiveByBatch(1L)).thenReturn(List.of(activeMember(3L)));
        when(pageRepository.findById(3L)).thenReturn(Optional.of(p));
        when(roundRepository.findByBatchAndPage(1L, 3L))
                .thenReturn(List.of(round(3L, 1, "8.00", "7.90")));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-down"));

        assertThat(result.sealed()).isFalse();
        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).reason()).contains("低于处理前");
    }

    @Test
    @DisplayName("处理前读数超上沿8.60：拒绝（同时后值低于前值，两条原因都列出）")
    void rejectsBeforePhAboveRange() {
        ArchivePage p = page(4L, "HIGH");
        when(memberRepository.findActiveByBatch(1L)).thenReturn(List.of(activeMember(4L)));
        when(pageRepository.findById(4L)).thenReturn(Optional.of(p));
        when(roundRepository.findByBatchAndPage(1L, 4L))
                .thenReturn(List.of(round(4L, 1, "8.60", "8.50")));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-high"));

        assertThat(result.sealed()).isFalse();
        assertThat(result.problems()).hasSize(2);
        assertThat(result.problems()).extracting(SealProblem::reason)
                .anyMatch(r -> r.contains("处理前pH"))
                .anyMatch(r -> r.contains("低于处理前"));
    }

    @Test
    @DisplayName("无任何轮次：按缺读数拒绝")
    void rejectsWithoutAnyRound() {
        ArchivePage p = page(5L, "EMPTY");
        when(memberRepository.findActiveByBatch(1L)).thenReturn(List.of(activeMember(5L)));
        when(pageRepository.findById(5L)).thenReturn(Optional.of(p));
        when(roundRepository.findByBatchAndPage(1L, 5L)).thenReturn(List.of());

        SealResult result = service.seal(new SealRequest("B1", "req-seal-empty"));

        assertThat(result.sealed()).isFalse();
        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).reason()).contains("缺少处理轮次");
    }

    @Test
    @DisplayName("多页多问题全部列出：P1前后均超范围且下降（3条）、P2缺前读数（1条），不遗漏")
    void listsEveryProblemPageAndReason() {
        ArchivePage p1 = page(1L, "P1");
        ArchivePage p2 = page(2L, "P2");
        when(memberRepository.findActiveByBatch(1L))
                .thenReturn(List.of(activeMember(1L), activeMember(2L)));
        when(pageRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(pageRepository.findById(2L)).thenReturn(Optional.of(p2));
        when(roundRepository.findByBatchAndPage(1L, 1L))
                .thenReturn(List.of(round(1L, 1, "6.40", "6.30")));
        when(roundRepository.findByBatchAndPage(1L, 2L))
                .thenReturn(List.of(round(2L, 1, null, "7.00")));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-multi"));

        assertThat(result.sealed()).isFalse();
        assertThat(result.problems()).hasSize(4);
        assertThat(result.problems()).extracting(SealProblem::accessionNo)
                .containsExactlyInAnyOrder("P1", "P1", "P1", "P2");
    }

    @Test
    @DisplayName("追加合规复处理轮次后封存成功，封存以最新轮次为准且旧轮次未被修改")
    void additionalCompliantRoundAllowsSealWithoutTouchingOldRound() {
        ArchivePage p = page(9L, "P");
        TreatmentRound badFirstRound = round(9L, 1, "7.00", "6.40");
        TreatmentRound goodSecondRound = round(9L, 2, "6.50", "7.10");
        when(memberRepository.findActiveByBatch(1L)).thenReturn(List.of(activeMember(9L)));
        when(pageRepository.findById(9L)).thenReturn(Optional.of(p));
        when(roundRepository.findByBatchAndPage(1L, 9L))
                .thenReturn(List.of(badFirstRound, goodSecondRound));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-reworked"));

        assertThat(result.sealed()).isTrue();
        // 旧轮次对象保持原值，模拟仓储层只追加不改写
        assertThat(badFirstRound.phAfter()).isEqualByComparingTo("6.40");
        assertThat(goodSecondRound.roundNo()).isEqualTo(2);
        verify(occupationRepository).releaseByBatch(1L);
    }

    @Test
    @DisplayName("最新轮次仍不合格时即使曾有合格旧轮次也拒绝：修正必须追加而非依赖旧值")
    void rejectsWhenLatestRoundInvalidEvenIfOlderRoundWasValid() {
        ArchivePage p = page(6L, "LATEST-BAD");
        when(memberRepository.findActiveByBatch(1L)).thenReturn(List.of(activeMember(6L)));
        when(pageRepository.findById(6L)).thenReturn(Optional.of(p));
        when(roundRepository.findByBatchAndPage(1L, 6L))
                .thenReturn(List.of(round(6L, 1, "7.00", "7.50"),
                        round(6L, 2, "7.50", null)));

        SealResult result = service.seal(new SealRequest("B1", "req-seal-latest"));

        assertThat(result.sealed()).isFalse();
        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0).reason()).contains("处理后读数缺失");
    }

    @Test
    @DisplayName("封存幂等：同requestId同参重放首次结果，不再次执行状态变更")
    void sealReplaysFirstResultWithSameRequestId() throws Exception {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        SealResult first = new SealResult("B1", true, List.of(), clock.now(), false);
        when(idempotentRequestRepository.find("req-same"))
                .thenReturn(Optional.of(new IdempotentRequest("req-same", "SEAL",
                        "SEAL|B1", mapper.writeValueAsString(first), 1L, clock.now())));

        SealResult replay = service.seal(new SealRequest("B1", "req-same"));

        assertThat(replay.sealed()).isTrue();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.sealedAt()).isEqualTo(first.sealedAt());
        verify(batchRepository, never()).updateStatus(anyLong(), any(), any());
        verify(occupationRepository, never()).releaseByBatch(anyLong());
    }
}
