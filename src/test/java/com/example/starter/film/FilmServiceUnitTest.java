package com.example.starter.film;

import com.example.starter.film.api.ApiException;
import com.example.starter.film.api.ReviewRequest;
import com.example.starter.film.api.SegmentSubmitRequest;
import com.example.starter.film.api.SegmentSubmitResponse;
import com.example.starter.film.domain.Film;
import com.example.starter.film.domain.SegmentVersion;
import com.example.starter.film.repo.FilmRepository;
import com.example.starter.film.service.FilmServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不依赖数据库的服务规则单元测试：用 mock 仓储驱动幂等与缺段分支。
 */
class FilmServiceUnitTest {

    private FilmRepository repository;
    private FilmServiceImpl service;

    private final Film film = new Film(7L, "F-UNIT", 2, Instant.parse("2026-10-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        repository = mock(FilmRepository.class);
        when(repository.findFilmByCode("F-UNIT")).thenReturn(Optional.of(film));
        service = new FilmServiceImpl(repository, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneOffset.UTC));
    }

    private SegmentSubmitRequest request(int segmentNo, String digest, Integer basedOn, String requestId) {
        return new SegmentSubmitRequest(segmentNo, digest, basedOn, requestId);
    }

    @Test
    void 同requestId同参重放原记录_不写入新版本() {
        SegmentVersion existing = new SegmentVersion(
                1L, 7L, 1, 1, TestData.digest("a"), null, "rid", Instant.parse("2026-10-01T01:00:00Z"));
        when(repository.findSegmentRequest(7L, "rid")).thenReturn(Optional.of(existing));

        SegmentSubmitResponse response = service.submitSegment("F-UNIT",
                request(1, TestData.digest("a"), null, "rid"));

        assertTrue(response.replayed());
        assertEquals(1, response.versionNo());
        verify(repository, never()).insertSegmentVersion(
                anyLong(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void 同requestId异参抛409() {
        SegmentVersion existing = new SegmentVersion(
                1L, 7L, 1, 1, TestData.digest("a"), null, "rid", Instant.parse("2026-10-01T01:00:00Z"));
        when(repository.findSegmentRequest(7L, "rid")).thenReturn(Optional.of(existing));

        ApiException ex = assertThrows(ApiException.class, () -> service.submitSegment("F-UNIT",
                request(1, TestData.digest("b"), null, "rid")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
    }

    @Test
    void 缺段审核422_不插入母版() {
        when(repository.findMasterByReviewRequest(anyLong(), any())).thenReturn(Optional.empty());
        when(repository.findLatestVersions(7L)).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.review("F-UNIT", new ReviewRequest("rv")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("MISSING_SEGMENTS", ex.getCode());
        verify(repository, never()).insertMaster(
                anyLong(), any(), any(), any());
    }

    @Test
    void 基于旧版次409() {
        SegmentVersion latest = new SegmentVersion(
                2L, 7L, 1, 2, TestData.digest("v2"), 1, "rid2", Instant.parse("2026-10-01T02:00:00Z"));
        when(repository.findLatestSegment(7L, 1)).thenReturn(Optional.of(latest));

        ApiException ex = assertThrows(ApiException.class, () -> service.submitSegment("F-UNIT",
                request(1, TestData.digest("v3"), 1, "rid3")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("VERSION_CONFLICT", ex.getCode());
        verify(repository, never()).insertSegmentVersion(
                anyLong(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void 首版省略basedOn成功写入版次1() {
        SegmentVersion saved = new SegmentVersion(
                3L, 7L, 1, 1, TestData.digest("v1"), null, "new-rid",
                Instant.parse("2026-10-01T08:00:00Z"));
        when(repository.findSegmentRequest(7L, "new-rid")).thenReturn(Optional.empty());
        // 同一方法先查不到（写入前），插入后再查返回新记录。
        when(repository.findLatestSegment(7L, 1))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(saved));

        SegmentSubmitResponse response = service.submitSegment("F-UNIT",
                request(1, TestData.digest("v1"), null, "new-rid"));
        assertEquals(1, response.versionNo());
        verify(repository).insertSegmentVersion(eq(7L), eq(1), eq(1),
                eq(TestData.digest("v1")), eq(null), eq("new-rid"), any(Instant.class));
    }
}
