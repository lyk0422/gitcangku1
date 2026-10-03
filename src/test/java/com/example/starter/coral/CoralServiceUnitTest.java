package com.example.starter.coral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.starter.coral.CoralRepository.PlotRow;
import com.example.starter.coral.CoralRepository.TransplantRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 纯单元测试：固定时钟下的幂等重放与死亡终态分支，不依赖数据库。
 */
class CoralServiceUnitTest {

    private static final Instant FIXED = Instant.parse("2026-10-01T00:00:00Z");

    private CoralRepository repository;
    private CoralService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(CoralRepository.class);
        service = new CoralService(repository, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    void replayWithSameParamsReturnsExistingWithoutInsert() {
        TransplantRow existing = new TransplantRow(1L, "req-1", "C-1", "合成苗圃U", 7L,
                FIXED, TransplantStatus.PENDING);
        when(repository.findTransplantByRequestId("req-1")).thenReturn(existing);
        when(repository.findPlotByIdForUpdate(7L))
                .thenReturn(new PlotRow(7L, "PLOT-U", 12, PlotStatus.ACTIVE));
        when(repository.findReviewsByTransplantId(1L)).thenReturn(List.of());
        when(repository.findSealByPlotId(7L)).thenReturn(null);

        TransplantView view = service.registerTransplant(
                new RegisterTransplantRequest("req-1", "C-1", "合成苗圃U", "PLOT-U"));

        assertEquals("C-1", view.plantCode());
        assertEquals(FIXED, view.transplantedAt());
        verify(repository, never()).insertTransplant(anyString(), anyString(), anyString(),
                anyLong(), any());
    }

    @Test
    void replayWithDifferentParamsConflicts() {
        TransplantRow existing = new TransplantRow(1L, "req-1", "C-1", "合成苗圃U", 7L,
                FIXED, TransplantStatus.PENDING);
        when(repository.findTransplantByRequestId("req-1")).thenReturn(existing);
        when(repository.findPlotByIdForUpdate(7L))
                .thenReturn(new PlotRow(7L, "PLOT-U", 12, PlotStatus.ACTIVE));

        BizException e = assertThrows(BizException.class, () -> service.registerTransplant(
                new RegisterTransplantRequest("req-1", "C-2", "合成苗圃U", "PLOT-U")));
        assertEquals(409, e.status());
    }

    @Test
    void deadPlantRejectsAliveWithoutAnyWrite() {
        TransplantRow dead = new TransplantRow(2L, "req-d", "C-D", "合成苗圃U", 7L,
                FIXED, TransplantStatus.DEAD);
        when(repository.findReviewByRequestId("rev-x")).thenReturn(null);
        when(repository.findTransplantByPlantCode("C-D")).thenReturn(dead);
        when(repository.findPlotByIdForUpdate(7L))
                .thenReturn(new PlotRow(7L, "PLOT-U", 12, PlotStatus.ACTIVE));

        BizException e = assertThrows(BizException.class,
                () -> service.review("C-D", new ReviewRequest("rev-x", TransplantStatus.ALIVE)));
        assertEquals(422, e.status());
        verify(repository, never()).insertReview(anyString(), anyLong(), any(), any());
        verify(repository, never()).updateTransplantStatus(anyLong(), any());
    }

    @Test
    void fullPlotRejectsRegistration() {
        when(repository.findTransplantByRequestId("req-f")).thenReturn(null);
        when(repository.findPlotForUpdate("PLOT-F"))
                .thenReturn(new PlotRow(9L, "PLOT-F", 12, PlotStatus.ACTIVE));
        when(repository.findTransplantByPlantCode("C-F")).thenReturn(null);
        when(repository.countActiveTransplants(9L)).thenReturn(12L);

        BizException e = assertThrows(BizException.class, () -> service.registerTransplant(
                new RegisterTransplantRequest("req-f", "C-F", "合成苗圃U", "PLOT-F")));
        assertEquals(409, e.status());
        verify(repository, never()).insertTransplant(anyString(), anyString(), anyString(),
                anyLong(), any());
    }
}