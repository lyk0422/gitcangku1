package com.example.starter.deacid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱酸业务链主流程与拒绝分支测试，真实 H2（MODE=MySQL）写入验证。
 */
@SpringBootTest
class DeacidServiceFlowTest {

    @Autowired
    private DeacidService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : List.of("seal_request", "enroll_request", "qc_rejection",
                "treatment_record", "page_occupancy", "batch_member", "deacid_batch", "archive_page")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private long page(String collectionNo) {
        return service.registerPage(collectionNo);
    }

    @Test
    void normalFlowEnrollTreatSeal() {
        long p1 = page("P-001");
        long p2 = page("P-002");
        long batch = service.createBatch("B-001");
        assertEquals("ENROLLED", service.enroll(batch, "enr-1", "P-001").outcome());
        assertEquals("ENROLLED", service.enroll(batch, "enr-2", "P-002").outcome());

        service.addTreatment(batch, p1, new BigDecimal("6.1"), new BigDecimal("7.0"), "RG-100");
        service.addTreatment(batch, p2, new BigDecimal("6.3"), new BigDecimal("7.4"), "RG-100");

        SealResult result = service.seal(batch, "seal-1");
        assertEquals("SEALED", result.outcome());
        assertEquals("SEALED", service.batchView(batch).status());

        Integer occupancy = jdbc.queryForObject("SELECT COUNT(*) FROM page_occupancy", Integer.class);
        assertEquals(0, occupancy, "封存后处理占用必须释放");

        List<TreatmentRoundView> rounds = service.pageTreatments("P-001");
        assertEquals(1, rounds.size());
        assertEquals("B-001", rounds.get(0).batchNo());
        assertEquals("SEALED", rounds.get(0).batchStatus());
        assertEquals(0, rounds.get(0).phAfter().compareTo(new BigDecimal("7.0")));
        assertEquals("RG-100", rounds.get(0).reagentBatchNo());
    }

    @Test
    void sealRejectsOutOfRangeThenFixedByAppendingRound() {
        long p1 = page("P-100");
        long p2 = page("P-101");
        long batch = service.createBatch("B-100");
        service.enroll(batch, "enr-a", "P-100");
        service.enroll(batch, "enr-b", "P-101");
        service.addTreatment(batch, p1, new BigDecimal("6.0"), new BigDecimal("6.4"), "RG-200");
        service.addTreatment(batch, p2, new BigDecimal("6.2"), new BigDecimal("7.1"), "RG-200");

        SealResult rejected = service.seal(batch, "seal-a");
        assertEquals("REJECTED", rejected.outcome());
        assertEquals(1, rejected.problems().size());
        assertEquals("P-100", rejected.problems().get(0).collectionNo());
        assertTrue(rejected.problems().get(0).reason().contains("超范围"));
        assertEquals("IN_PROGRESS", service.batchView(batch).status(), "整批退回，不可只封存合格子集");

        Integer rejections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qc_rejection WHERE batch_id = ?", Integer.class, batch);
        assertEquals(1, rejections, "退回理由必须落库保留");

        // 修正方式：追加合规复处理轮次，不改写原值
        service.addTreatment(batch, p1, new BigDecimal("6.4"), new BigDecimal("7.2"), "RG-201");
        SealResult sealed = service.seal(batch, "seal-b");
        assertEquals("SEALED", sealed.outcome());

        List<TreatmentRoundView> rounds = service.pageTreatments("P-100");
        assertEquals(2, rounds.size());
        assertEquals(0, rounds.get(0).phAfter().compareTo(new BigDecimal("6.4")), "旧轮次读数不得被覆盖");
        assertEquals(0, rounds.get(1).phAfter().compareTo(new BigDecimal("7.2")));
        assertEquals("RG-201", rounds.get(1).reagentBatchNo());
    }

    @Test
    void sealRejectsMissingReadingsAndDecreasingPh() {
        long p1 = page("P-200");
        long p2 = page("P-201");
        long batch = service.createBatch("B-200");
        service.enroll(batch, "enr-c", "P-200");
        service.enroll(batch, "enr-d", "P-201");
        service.addTreatment(batch, p2, new BigDecimal("7.5"), new BigDecimal("7.0"), "RG-300");

        SealResult rejected = service.seal(batch, "seal-c");
        assertEquals("REJECTED", rejected.outcome());
        assertEquals(2, rejected.problems().size());
        assertTrue(rejected.problems().stream()
                .anyMatch(p -> p.collectionNo().equals("P-200") && p.reason().contains("缺少处理读数")));
        assertTrue(rejected.problems().stream()
                .anyMatch(p -> p.collectionNo().equals("P-201") && p.reason().contains("低于处理前")));
    }

    @Test
    void sealRejectsCrossBatchOccupancy() {
        long p1 = page("P-300");
        long batch = service.createBatch("B-300");
        long other = service.createBatch("B-301");
        service.enroll(batch, "enr-e", "P-300");
        service.addTreatment(batch, p1, new BigDecimal("6.2"), new BigDecimal("7.0"), "RG-400");
        // 模拟异常数据：占用指向另一个进行中批次
        jdbc.update("UPDATE page_occupancy SET batch_id = ? WHERE page_id = ?", other, p1);

        SealResult rejected = service.seal(batch, "seal-d");
        assertEquals("REJECTED", rejected.outcome());
        assertTrue(rejected.problems().stream()
                .anyMatch(p -> p.collectionNo().equals("P-300") && p.reason().contains("跨批次占用")));
    }

    @Test
    void enrollIdempotentReplayAndConflict() {
        page("P-400");
        page("P-401");
        long batch = service.createBatch("B-400");
        EnrollResult first = service.enroll(batch, "enr-f", "P-400");
        EnrollResult replay = service.enroll(batch, "enr-f", "P-400");
        assertEquals(first, replay, "同参重放必须返回原状态");
        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_member WHERE batch_id = ?", Integer.class, batch);
        assertEquals(1, members, "重放不得产生重复成员");

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.enroll(batch, "enr-f", "P-401"));
        assertEquals(409, conflict.status().value(), "同 requestId 异参必须 409");
    }

    @Test
    void sealIdempotentReplayAndReadOnly() {
        long p1 = page("P-500");
        long batch = service.createBatch("B-500");
        service.enroll(batch, "enr-g", "P-500");
        service.addTreatment(batch, p1, new BigDecimal("6.2"), new BigDecimal("7.0"), "RG-500");

        assertEquals("SEALED", service.seal(batch, "seal-e").outcome());
        assertEquals("SEALED", service.seal(batch, "seal-e").outcome(), "同参重放返回原状态");

        long other = service.createBatch("B-501");
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.seal(other, "seal-e"));
        assertEquals(409, conflict.status().value());

        assertThrows(BusinessException.class,
                () -> service.addTreatment(batch, p1, new BigDecimal("7.0"), new BigDecimal("7.5"), "RG-501"));
        assertThrows(BusinessException.class, () -> service.enroll(batch, "enr-h", "P-500"));
        assertThrows(BusinessException.class, () -> service.releasePage(batch, p1));
    }

    @Test
    void rejectedSealReplayKeepsSingleRejectionRecord() {
        long p1 = page("P-600");
        long batch = service.createBatch("B-600");
        service.enroll(batch, "enr-i", "P-600");
        service.addTreatment(batch, p1, new BigDecimal("6.0"), new BigDecimal("6.4"), "RG-600");

        SealResult first = service.seal(batch, "seal-f");
        SealResult replay = service.seal(batch, "seal-f");
        assertEquals("REJECTED", first.outcome());
        assertEquals(first, replay, "退回重放必须返回原问题页列表");
        Integer rejections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qc_rejection WHERE batch_id = ?", Integer.class, batch);
        assertEquals(1, rejections, "重放不得重复记录退回理由");
    }

    @Test
    void releasePageFreesOccupancyForReenroll() {
        long pageId = page("P-700");
        long batch = service.createBatch("B-700");
        long other = service.createBatch("B-701");
        service.enroll(batch, "enr-j", "P-700");

        service.releasePage(batch, pageId);
        assertEquals("AVAILABLE", jdbc.queryForObject(
                "SELECT status FROM archive_page WHERE id = ?", String.class, pageId));
        assertEquals("ENROLLED", service.enroll(other, "enr-k", "P-700").outcome(),
                "归还释放占用后必须能入组新批次");
    }
}