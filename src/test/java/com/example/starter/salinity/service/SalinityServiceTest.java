package com.example.starter.salinity.service;

import com.example.starter.salinity.dto.AdoptRequest;
import com.example.starter.salinity.dto.CorrectionRequest;
import com.example.starter.salinity.dto.SubmitSampleRequest;
import com.example.starter.salinity.exception.ConflictException;
import com.example.starter.salinity.exception.UnprocessableException;
import com.example.starter.salinity.model.Sample;
import com.example.starter.salinity.model.Voyage;
import com.example.starter.salinity.model.VoyageStatus;
import com.example.starter.salinity.repository.SalinityRepository;
import com.example.starter.salinity.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务层单元测试：范围校验、双重幂等、跨航次/封存后拒绝、缺深度封存等分支，
 * 仓储以 Mockito 替身隔离；数据库约束与并发结论由 H2 集成测试负责。
 */
class SalinityServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    private SalinityRepository repository;
    private MutableClock clock;
    private SalinityService service;

    @BeforeEach
    void setUp() {
        repository = mock(SalinityRepository.class);
        clock = new MutableClock(T0);
        service = new SalinityService(repository, clock);
    }

    private SubmitSampleRequest submit(String voyage, String station, int depth, String salinity,
                                       String sampleNo, String requestId) {
        return new SubmitSampleRequest(voyage, station, depth, new BigDecimal(salinity),
                T0, sampleNo, requestId);
    }

    private Sample sample(long id, String voyage, String station, int depth, String salinity,
                          String sampleNo, String requestId, boolean adopted, int version) {
        return new Sample((long) id, voyage, station, depth, new BigDecimal(salinity), T0,
                sampleNo, requestId, adopted, version, T0);
    }

    @Nested
    @DisplayName("样本提交")
    class Submit {

        @Test
        @DisplayName("深度越界返回 422 且不落库")
        void depthOutOfRange() {
            assertThatThrownBy(() -> service.submitSample(
                    submit("V1", "S", 501, "35.000", "N1", "R1")))
                    .isInstanceOf(UnprocessableException.class)
                    .hasMessageContaining("深度越界");
            verify(repository, never()).insertSample(any());
        }

        @Test
        @DisplayName("盐度越界返回 422 且不落库")
        void salinityOutOfRange() {
            assertThatThrownBy(() -> service.submitSample(
                    submit("V1", "S", 100, "45.001", "N1", "R1")))
                    .isInstanceOf(UnprocessableException.class)
                    .hasMessageContaining("盐度越界");
            verify(repository, never()).insertSample(any());
        }

        @Test
        @DisplayName("边界值 0 米与 0 PSU 合法")
        void lowerBoundariesValid() {
            when(repository.findVoyage("V1")).thenReturn(Optional.of(
                    new Voyage("V1", VoyageStatus.OPEN, null, T0)));
            when(repository.lockVoyage("V1")).thenReturn(Optional.of(
                    new Voyage("V1", VoyageStatus.OPEN, null, T0)));
            when(repository.findSampleByRequestId("R1")).thenReturn(Optional.empty());
            when(repository.findSampleBySampleNo("N1")).thenReturn(Optional.empty());
            when(repository.nextVersionNo(anyString(), anyString(), anyInt())).thenReturn(1);
            when(repository.insertSample(any())).thenAnswer(inv -> {
                Sample s = inv.getArgument(0);
                return s.withId(1L);
            });

            SalinityService.SubmitOutcome outcome =
                    service.submitSample(submit("V1", "S", 0, "0.000", "N1", "R1"));

            assertThat(outcome.replayed()).isFalse();
            assertThat(outcome.sample().depthM()).isZero();
        }

        @Test
        @DisplayName("同 requestId 同参重放返回原样本")
        void sameRequestIdReplay() {
            Sample existing = sample(1, "V1", "S", 0, "35.000", "N1", "R1", false, 1);
            when(repository.findSampleByRequestId("R1")).thenReturn(Optional.of(existing));

            SalinityService.SubmitOutcome outcome =
                    service.submitSample(submit("V1", "S", 0, "35.000", "N1", "R1"));

            assertThat(outcome.replayed()).isTrue();
            assertThat(outcome.sample().id()).isEqualTo(1L);
            verify(repository, never()).insertSample(any());
        }

        @Test
        @DisplayName("同 requestId 内容不同返回 409")
        void sameRequestIdDifferentContent() {
            Sample existing = sample(1, "V1", "S", 0, "35.000", "N1", "R1", false, 1);
            when(repository.findSampleByRequestId("R1")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.submitSample(
                    submit("V1", "S", 0, "36.000", "N1", "R1")))
                    .isInstanceOf(ConflictException.class);
            verify(repository, never()).insertSample(any());
        }

        @Test
        @DisplayName("同采样编号不同盐度返回 409")
        void sameSampleNoDifferentContent() {
            when(repository.findSampleByRequestId("R2")).thenReturn(Optional.empty());
            when(repository.findSampleBySampleNo("N1")).thenReturn(Optional.of(
                    sample(1, "V1", "S", 0, "35.000", "N1", "R1", false, 1)));

            assertThatThrownBy(() -> service.submitSample(
                    submit("V1", "S", 0, "36.000", "N1", "R2")))
                    .isInstanceOf(ConflictException.class);
            verify(repository, never()).insertSample(any());
        }
    }

    @Nested
    @DisplayName("质控采纳")
    class Adopt {

        @Test
        @DisplayName("跨航次质控返回 422")
        void crossVoyageAdoption() {
            when(repository.findAdoptionByRequestId("R9")).thenReturn(Optional.empty());
            when(repository.findSampleBySampleNo("N1")).thenReturn(Optional.of(
                    sample(1, "V-OTHER", "S", 0, "35.000", "N1", "R1", false, 1)));

            assertThatThrownBy(() -> service.adopt(new AdoptRequest("V1", "N1", "R9")))
                    .isInstanceOf(UnprocessableException.class)
                    .hasMessageContaining("跨航次质控");
            verify(repository, never()).markAdopted(anyLong());
        }

        @Test
        @DisplayName("航次已封存时采纳返回 422")
        void adoptAfterSeal() {
            when(repository.findAdoptionByRequestId("R9")).thenReturn(Optional.empty());
            when(repository.findSampleBySampleNo("N1")).thenReturn(Optional.of(
                    sample(1, "V1", "S", 0, "35.000", "N1", "R1", false, 1)));
            when(repository.lockVoyage("V1")).thenReturn(Optional.of(
                    new Voyage("V1", VoyageStatus.SEALED, T0, T0)));

            assertThatThrownBy(() -> service.adopt(new AdoptRequest("V1", "N1", "R9")))
                    .isInstanceOf(UnprocessableException.class)
                    .hasMessageContaining("已封存");
            verify(repository, never()).markAdopted(anyLong());
        }

        @Test
        @DisplayName("采纳请求幂等键复用于不同样本返回 409")
        void adoptionRequestIdConflict() {
            when(repository.findAdoptionByRequestId("R9")).thenReturn(Optional.of(
                    new com.example.starter.salinity.model.AdoptionRecord(
                            1L, "R9", "V1", "S", 0, 7L, T0)));
            when(repository.findSampleBySampleNo("N2")).thenReturn(Optional.of(
                    sample(2, "V1", "S", 0, "35.000", "N2", "R2", false, 2)));

            assertThatThrownBy(() -> service.adopt(new AdoptRequest("V1", "N2", "R9")))
                    .isInstanceOf(ConflictException.class);
            verify(repository, never()).markAdopted(anyLong());
        }
    }

    @Nested
    @DisplayName("封存")
    class Seal {

        @Test
        @DisplayName("缺 50 米采纳样本返回 422 且不写封存状态")
        void missingDepth() {
            when(repository.lockVoyage("V1")).thenReturn(Optional.of(
                    new Voyage("V1", VoyageStatus.OPEN, null, T0)));
            when(repository.findSamplesByVoyage("V1")).thenReturn(List.of(
                    sample(1, "V1", "S", 0, "35.000", "N0", "R0", false, 1),
                    sample(2, "V1", "S", 100, "34.000", "N100", "R100", false, 1)));
            when(repository.findAllAdopted("V1")).thenReturn(List.of());

            assertThatThrownBy(() -> service.seal("V1"))
                    .isInstanceOf(UnprocessableException.class)
                    .hasMessageContaining("缺 50 米采纳样本");
            verify(repository, never()).markSealedIfOpen(anyString(), any());
            verify(repository, never()).insertSealedProfile(any());
        }
    }

    @Nested
    @DisplayName("勘误")
    class Correction {

        @Test
        @DisplayName("未封存航次追加勘误返回 422")
        void correctionBeforeSeal() {
            when(repository.findCorrectionByRequestId("C1")).thenReturn(Optional.empty());
            when(repository.lockVoyage("V1")).thenReturn(Optional.of(
                    new Voyage("V1", VoyageStatus.OPEN, null, T0)));

            assertThatThrownBy(() -> service.addCorrection("V1",
                    new CorrectionRequest("S", 0, null, "说明", "C1")))
                    .isInstanceOf(UnprocessableException.class)
                    .hasMessageContaining("尚未封存");
            verify(repository, never()).insertCorrection(any());
        }
    }
}
