package com.example.coral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.coral.domain.PlantStatus;
import com.example.coral.service.BusinessException;
import com.example.coral.service.QuadratService;
import com.example.coral.service.TransplantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 移植登记主流程与拒绝分支：容量12、重复植株、幂等重放，真实H2验证。
 */
@SpringBootTest
class TransplantFlowTest {

    @Autowired
    private TransplantService transplantService;
    @Autowired
    private QuadratService quadratService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM seal_snapshot");
        jdbc.update("DELETE FROM transplant");
        jdbc.update("DELETE FROM quadrat");
    }

    @Test
    void twelfthAcceptedThirteenthRejected() {
        quadratService.create("Q-CAP", "容量样方");
        for (int i = 1; i <= 12; i++) {
            transplantService.register("req-cap-" + i, "P-CAP-" + i, "苗圃A", "Q-CAP");
        }
        var q = quadratService.findByCode("Q-CAP");
        assertEquals(12, quadratService.plantsOf(q.id()).size());

        BusinessException e = assertThrows(BusinessException.class, () ->
                transplantService.register("req-cap-13", "P-CAP-13", "苗圃A", "Q-CAP"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("容量已满"));
        assertEquals(12, quadratService.plantsOf(q.id()).size());
    }

    @Test
    void duplicatePlantCodeRejectedAcrossQuadrats() {
        quadratService.create("Q-A", "样方A");
        quadratService.create("Q-B", "样方B");
        transplantService.register("req-dup-1", "P-DUP-1", "苗圃A", "Q-A");

        // 同一植株不得转到另一活跃样方
        BusinessException e = assertThrows(BusinessException.class, () ->
                transplantService.register("req-dup-2", "P-DUP-1", "苗圃B", "Q-B"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("植株编号已存在"));
    }

    @Test
    void sameRequestIdSameParamsReplaysSameParamsDifferConflicts() {
        quadratService.create("Q-IDEM", "幂等样方");
        var first = transplantService.register("req-idem-1", "P-IDEM-1", "苗圃A", "Q-IDEM");

        var replay = transplantService.register("req-idem-1", "P-IDEM-1", "苗圃A", "Q-IDEM");
        assertEquals(first.id(), replay.id());
        assertEquals(1, quadratService.plantsOf(
                quadratService.findByCode("Q-IDEM").id()).size());

        BusinessException e = assertThrows(BusinessException.class, () ->
                transplantService.register("req-idem-1", "P-IDEM-OTHER", "苗圃A", "Q-IDEM"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("requestId已使用"));
    }

    @Test
    void transplantFixesNurseryQuadratAndUtcInstant() {
        quadratService.create("Q-FIX", "固化样方");
        var before = java.time.Instant.now().minusSeconds(5);
        var t = transplantService.register("req-fix-1", "P-FIX-1", "东山苗圃", "Q-FIX");
        var after = java.time.Instant.now().plusSeconds(5);

        assertEquals("东山苗圃", t.nursery());
        assertEquals(PlantStatus.PENDING, t.status());
        assertTrue(!t.transplantedAt().isBefore(before) && !t.transplantedAt().isAfter(after));
        var q = quadratService.findByCode("Q-FIX");
        assertEquals(q.id(), t.quadratId());
    }

    @Test
    void unknownQuadratReturns422() {
        BusinessException e = assertThrows(BusinessException.class, () ->
                transplantService.register("req-none-1", "P-NONE-1", "苗圃A", "Q-MISSING"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
    }

    @Test
    void duplicateQuadratCodeRejected() {
        quadratService.create("Q-DUP", "样方");
        BusinessException e = assertThrows(BusinessException.class, () ->
                quadratService.create("Q-DUP", "另一样方"));
        assertEquals(HttpStatus.CONFLICT, e.status());
    }
}