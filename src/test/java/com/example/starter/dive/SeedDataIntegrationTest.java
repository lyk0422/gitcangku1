package com.example.starter.dive;

import com.example.starter.dive.domain.AnomalyType;
import com.example.starter.dive.domain.DiveReview;
import com.example.starter.dive.service.DiveLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题干合成数据场景：独立命名内存库加载data.sql种子，
 * 作业A在10:15达31米必须拒绝（不得仅因末点0米而通过），作业B全程合规必须通过。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:divelog_seed;MODE=MySQL")
class SeedDataIntegrationTest {

    @Autowired
    private DiveLogService service;

    @Test
    void seededSessionARejectedAt31Meters() {
        DiveLogService.OverviewResult before = service.overview(1L);
        assertEquals(9, before.points().size());
        // 末点为0米，但10:15的31米点必须导致拒绝
        assertEquals(0, BigDecimal.ZERO.compareTo(before.points().get(8).depthM()));

        DiveLogService.ReviewResult review = service.requestReview(1L, "seed-review-a");
        assertEquals(DiveReview.ReviewDecision.REJECTED, review.decision());
        assertEquals(1, review.anomalies().size());
        assertEquals(AnomalyType.EXCEED_DEPTH, review.anomalies().get(0).type());
        assertEquals(4, review.anomalies().get(0).fromSeq());
        assertEquals(Instant.parse("2026-10-04T10:15:00Z"), review.anomalies().get(0).fromTimeUtc());
        assertEquals(0, new BigDecimal("31.00").compareTo(review.anomalies().get(0).depthM()));
    }

    @Test
    void seededSessionBPasses() {
        DiveLogService.ReviewResult review = service.requestReview(2L, "seed-review-b");
        assertEquals(DiveReview.ReviewDecision.PASSED, review.decision());
        assertTrue(review.anomalies().isEmpty());
        assertEquals(1, review.pointVersion());
    }
}