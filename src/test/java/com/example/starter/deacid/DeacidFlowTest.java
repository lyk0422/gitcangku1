package com.example.starter.deacid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正常流程、拒绝原因、幂等与只读约束测试，真实写入 H2（MODE=MySQL）。
 */
@SpringBootTest
class DeacidFlowTest {

    @Autowired
    private DeacidService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM seal_request");
        jdbc.update("DELETE FROM treatment_record");
        jdbc.update("DELETE FROM page_occupancy");
        jdbc.update("DELETE FROM batch_page");
        jdbc.update("DELETE FROM deacid_batch");
        jdbc.update("DELETE FROM archive_page");
    }

    @Test
    void 正常流程_登记入组读数封存并可查询轮次() {
        Contracts.PageResponse page = service.registerPage("CN-001");
        Contracts.BatchResponse batch = service.createBatch("B-001");

        Contracts.EnrollResponse enrolled = service.enroll(batch.batchId(), "enroll-1", "CN-001");
        assertFalse(enrolled.replayed());
        assertEquals("ACTIVE", enrolled.status());

        Contracts.TreatmentResponse t1 = service.appendTreatment(batch.batchId(), page.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("6.8"), new BigDecimal("7.2"), "REAGENT-2026A"));
        assertEquals(1, t1.roundNo());

        DeacidService.SealOutcome outcome = service.seal(batch.batchId(), "seal-1");
        Contracts.SealResponse sealed = assertInstanceOf(DeacidService.SealOutcome.Sealed.class, outcome).response();
        assertEquals("SEALED", sealed.status());

        Contracts.PageTreatmentsResponse view = service.pageTreatments("CN-001");
        assertEquals(1, view.rounds().size());
        Contracts.TreatmentRoundView round = view.rounds().get(0);
        assertEquals("SEALED", round.batchStatus());
        assertEquals(new BigDecimal("6.80"), round.phBefore());
        assertEquals(new BigDecimal("7.20"), round.phAfter());
        assertEquals("REAGENT-2026A", round.reagentBatchNo());
    }

    @Test
    void 缺读数_整批封存被拒并列出问题页() {
        service.registerPage("CN-010");
        service.registerPage("CN-011");
        Contracts.BatchResponse batch = service.createBatch("B-010");
        service.enroll(batch.batchId(), "e-1", "CN-010");
        service.enroll(batch.batchId(), "e-2", "CN-011");
        long pageId = jdbc.queryForObject(
                "SELECT id FROM archive_page WHERE collection_no = 'CN-010'", Long.class);
        service.appendTreatment(batch.batchId(), pageId,
                new Contracts.TreatmentRequest(new BigDecimal("6.8"), new BigDecimal("7.0"), "RG-1"));

        DeacidService.SealOutcome outcome = service.seal(batch.batchId(), "s-1");
        DeacidService.SealOutcome.Rejected rejected =
                assertInstanceOf(DeacidService.SealOutcome.Rejected.class, outcome);
        assertEquals(1, rejected.problems().size());
        assertEquals("CN-011", rejected.problems().get(0).collectionNo());
        assertEquals(List.of("MISSING_READINGS"), rejected.problems().get(0).reasons());

        String status = jdbc.queryForObject(
                "SELECT status FROM deacid_batch WHERE id = ?", String.class, batch.batchId());
        assertEquals("OPEN", status, "拒绝后批次保持进行中，可追加处理轮次");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seal_request WHERE outcome = 'REJECTED'", Integer.class),
                "退回理由必须留痕");
    }

    @Test
    void 合成页P后读数6点4_整批封存失败_追加合规复处理后成功且原值保留() {
        Contracts.PageResponse ok = service.registerPage("CN-OK");
        Contracts.PageResponse p = service.registerPage("P");
        Contracts.BatchResponse batch = service.createBatch("B-P");
        service.enroll(batch.batchId(), "e-ok", "CN-OK");
        service.enroll(batch.batchId(), "e-p", "P");
        service.appendTreatment(batch.batchId(), ok.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("6.8"), new BigDecimal("7.1"), "RG-9"));
        service.appendTreatment(batch.batchId(), p.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("7.0"), new BigDecimal("6.4"), "RG-9"));

        DeacidService.SealOutcome.Rejected rejected =
                assertInstanceOf(DeacidService.SealOutcome.Rejected.class, service.seal(batch.batchId(), "s-p"));
        assertEquals(1, rejected.problems().size(), "整批拒绝，不只封存合格子集");
        assertEquals("P", rejected.problems().get(0).collectionNo());
        assertTrue(rejected.problems().get(0).reasons().contains("PH_OUT_OF_RANGE"));
        assertTrue(rejected.problems().get(0).reasons().contains("PH_DECREASED"));

        Contracts.TreatmentResponse fix = service.appendTreatment(batch.batchId(), p.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("6.6"), new BigDecimal("7.2"), "RG-10"));
        assertEquals(2, fix.roundNo(), "修正方式是追加复处理轮次");

        assertInstanceOf(DeacidService.SealOutcome.Sealed.class, service.seal(batch.batchId(), "s-p2"));

        Contracts.PageTreatmentsResponse view = service.pageTreatments("P");
        assertEquals(2, view.rounds().size());
        assertEquals(new BigDecimal("6.40"), view.rounds().get(0).phAfter(),
                "旧轮次读数不得被改写");
        assertEquals(new BigDecimal("7.20"), view.rounds().get(1).phAfter());
        assertEquals("RG-10", view.rounds().get(1).reagentBatchNo());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seal_request WHERE outcome = 'REJECTED'", Integer.class),
                "退回理由记录保留不被覆盖");
    }
    @Test
    void pH边界_6点5与8点5通过_越界拒绝() {
        Contracts.PageResponse low = service.registerPage("CN-L");
        Contracts.PageResponse high = service.registerPage("CN-H");
        Contracts.BatchResponse batch = service.createBatch("B-B");
        service.enroll(batch.batchId(), "e-l", "CN-L");
        service.enroll(batch.batchId(), "e-h", "CN-H");
        service.appendTreatment(batch.batchId(), low.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("6.5"), new BigDecimal("6.5"), "RG-1"));
        service.appendTreatment(batch.batchId(), high.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("8.5"), new BigDecimal("8.5"), "RG-1"));
        assertInstanceOf(DeacidService.SealOutcome.Sealed.class, service.seal(batch.batchId(), "s-b"));

        Contracts.PageResponse over = service.registerPage("CN-O");
        Contracts.BatchResponse batch2 = service.createBatch("B-B2");
        service.enroll(batch2.batchId(), "e-o", "CN-O");
        service.appendTreatment(batch2.batchId(), over.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("7.0"), new BigDecimal("8.51"), "RG-1"));
        DeacidService.SealOutcome.Rejected rejected =
                assertInstanceOf(DeacidService.SealOutcome.Rejected.class, service.seal(batch2.batchId(), "s-b2"));
        assertEquals(List.of("PH_OUT_OF_RANGE"), rejected.problems().get(0).reasons());
    }

    @Test
    void 已封存批次只读_入组读数归还均拒绝() {
        Contracts.PageResponse page = service.registerPage("CN-S");
        Contracts.BatchResponse batch = service.createBatch("B-S");
        service.enroll(batch.batchId(), "e-s", "CN-S");
        service.appendTreatment(batch.batchId(), page.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("6.8"), new BigDecimal("7.0"), "RG-1"));
        assertInstanceOf(DeacidService.SealOutcome.Sealed.class, service.seal(batch.batchId(), "s-s"));

        ApiException enroll = assertThrows(ApiException.class,
                () -> service.enroll(batch.batchId(), "e-s2", "CN-S"));
        assertEquals(HttpStatus.CONFLICT, enroll.status());
        ApiException treat = assertThrows(ApiException.class, () -> service.appendTreatment(
                batch.batchId(), page.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("7.0"), new BigDecimal("7.5"), "RG-2")));
        assertEquals(HttpStatus.CONFLICT, treat.status());
        ApiException release = assertThrows(ApiException.class,
                () -> service.release(batch.batchId(), page.pageId()));
        assertEquals(HttpStatus.CONFLICT, release.status());
    }

    @Test
    void 入组幂等_同键同参重放_同键异参409() {
        service.registerPage("CN-I");
        service.registerPage("CN-J");
        Contracts.BatchResponse batch = service.createBatch("B-I");
        Contracts.EnrollResponse first = service.enroll(batch.batchId(), "req-1", "CN-I");

        Contracts.EnrollResponse replay = service.enroll(batch.batchId(), "req-1", "CN-I");
        assertTrue(replay.replayed());
        assertEquals(first.batchPageId(), replay.batchPageId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_page", Integer.class));

        ApiException conflict = assertThrows(ApiException.class,
                () -> service.enroll(batch.batchId(), "req-1", "CN-J"));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("REQUEST_ID_CONFLICT", conflict.code());
    }

    @Test
    void 封存幂等_同键重放原结果_异键409_退回也可重放() {
        Contracts.PageResponse page = service.registerPage("CN-M");
        Contracts.BatchResponse batch = service.createBatch("B-M");
        service.enroll(batch.batchId(), "e-m", "CN-M");

        DeacidService.SealOutcome.Rejected first =
                assertInstanceOf(DeacidService.SealOutcome.Rejected.class, service.seal(batch.batchId(), "seal-m"));
        DeacidService.SealOutcome.Rejected replay =
                assertInstanceOf(DeacidService.SealOutcome.Rejected.class, service.seal(batch.batchId(), "seal-m"));
        assertTrue(replay.replayed());
        assertEquals(first.problems(), replay.problems());

        service.appendTreatment(batch.batchId(), page.pageId(),
                new Contracts.TreatmentRequest(new BigDecimal("6.9"), new BigDecimal("7.3"), "RG-1"));
        assertInstanceOf(DeacidService.SealOutcome.Sealed.class, service.seal(batch.batchId(), "seal-m2"));
        Contracts.SealResponse sealedReplay = assertInstanceOf(DeacidService.SealOutcome.Sealed.class,
                service.seal(batch.batchId(), "seal-m2")).response();
        assertTrue(sealedReplay.replayed());

        ApiException conflict = assertThrows(ApiException.class,
                () -> service.seal(batch.batchId(), "seal-m3"));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("BATCH_NOT_OPEN", conflict.code());
    }

    @Test
    void 占用冲突后释放_失败重试可使用原键() {
        service.registerPage("CN-R");
        Contracts.BatchResponse batchA = service.createBatch("B-A");
        Contracts.BatchResponse batchB = service.createBatch("B-B");
        service.enroll(batchA.batchId(), "req-a", "CN-R");

        ApiException occupied = assertThrows(ApiException.class,
                () -> service.enroll(batchB.batchId(), "req-b", "CN-R"));
        assertEquals(HttpStatus.CONFLICT, occupied.status());
        assertEquals("PAGE_OCCUPIED", occupied.code());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_page WHERE request_id = 'req-b'", Integer.class),
                "冲突入组整体回滚，不留半成品记录");

        long pageId = jdbc.queryForObject(
                "SELECT id FROM archive_page WHERE collection_no = 'CN-R'", Long.class);
        service.release(batchA.batchId(), pageId);
        Contracts.EnrollResponse retried = service.enroll(batchB.batchId(), "req-b", "CN-R");
        assertFalse(retried.replayed(), "释放占用后可用原 requestId 重试成功");
    }

    @Test
    void 占用唯一约束在数据库层生效() {
        service.registerPage("CN-U");
        Contracts.BatchResponse batch = service.createBatch("B-U");
        service.enroll(batch.batchId(), "e-u", "CN-U");
        long pageId = jdbc.queryForObject(
                "SELECT id FROM archive_page WHERE collection_no = 'CN-U'", Long.class);
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO page_occupancy(page_id, batch_id, batch_page_id) VALUES (?,?,?)",
                pageId, batch.batchId(), 999999L), "page_occupancy 主键必须阻止并发双占用");
    }
}