package com.example.starter.custody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.custody.CustodyDtos.BatchView;
import com.example.starter.custody.CustodyDtos.ChildSpec;
import com.example.starter.custody.CustodyDtos.SignoffRequest;
import com.example.starter.custody.CustodyDtos.SplitRequest;
import com.example.starter.custody.CustodyDtos.SplitResponse;
import com.example.starter.custody.CustodyDtos.SourceRequest;
import com.example.starter.custody.CustodyDtos.TraceResponse;
import com.example.starter.custody.CustodyDtos.TransferRequest;
import com.example.starter.custody.CustodyDtos.TransferView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实H2库上的业务链测试:正常流程、422/409分支、幂等重放与失败回滚。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:timber-service-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa"})
class CustodyServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-04T01:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    private CustodyService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_key");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM batch");
    }

    private BatchView source(String code, int weightKg) {
        return service.registerSource(
                new SourceRequest("VCH-001", "松木", weightKg, code, "加工点A"));
    }

    @Test
    void splitConservingWeightSucceedsAndParentBecomesSourceNode() {
        source("SRC-1", 100);
        // 100公斤来源拆为60与50公斤不守恒,整单422
        ApiException rejected = assertThrows(ApiException.class, () -> service.split("SRC-1",
                new SplitRequest("req-split-1",
                        List.of(new ChildSpec("C-60B", 60, null), new ChildSpec("C-50", 50, null)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.status());
        // 失败事务不残留子批次与请求键,原批次仍是叶子批次仍可用
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE parent_id IS NOT NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key", Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_code = 'SRC-1'", String.class));
        // 同一requestId在失败回滚后可携带修正参数重试,拆为60与40公斤成功
        SplitResponse ok = service.split("SRC-1", new SplitRequest("req-split-1",
                List.of(new ChildSpec("C-60", 60, null), new ChildSpec("C-40", 40, null))));
        assertEquals(2, ok.children().size());
        assertEquals("VCH-001", ok.children().get(0).voucherNo());
        assertEquals("松木", ok.children().get(0).species());
        assertEquals("加工点A", ok.children().get(0).holder());
        // 已拆分父批次只保留来源节点,不得再移交
        ApiException transferOnSplit = assertThrows(ApiException.class, () ->
                service.createTransfer("SRC-1", new TransferRequest("加工点A", "下游B")));
        assertEquals(HttpStatus.CONFLICT, transferOnSplit.status());
    }

    @Test
    void duplicateChildCodesAndCrossSourceAndUnknownHolderAreRejected() {
        source("SRC-2", 100);
        ApiException dup = assertThrows(ApiException.class, () -> service.split("SRC-2",
                new SplitRequest("req-dup", List.of(new ChildSpec("C-X", 50, null),
                        new ChildSpec("C-X", 50, null)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, dup.status());

        ApiException cross = assertThrows(ApiException.class, () -> service.split("SRC-2",
                new SplitRequest("req-cross", List.of(new ChildSpec("C-Y", 50, "VCH-OTHER"),
                        new ChildSpec("C-Z", 50, null)))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, cross.status());

        ApiException noRight = assertThrows(ApiException.class, () ->
                service.createTransfer("SRC-2", new TransferRequest("无关人员", "下游B")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noRight.status());
    }

    @Test
    void existingChildCodeConflictsAndFailedSplitConsumesNothing() {
        source("SRC-3", 100);
        source("SRC-EXISTING", 10);
        ApiException conflict = assertThrows(ApiException.class, () -> service.split("SRC-3",
                new SplitRequest("req-exist", List.of(new ChildSpec("SRC-EXISTING", 60, null),
                        new ChildSpec("C-40C", 40, null)))));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_code = 'SRC-3'", String.class));
    }

    @Test
    void splitRequestIdReplaysSameResultAndRejectsDifferentParams() {
        source("SRC-4", 100);
        SplitRequest request = new SplitRequest("req-idem",
                List.of(new ChildSpec("C-1", 60, null), new ChildSpec("C-2", 40, null)));
        SplitResponse first = service.split("SRC-4", request);
        SplitResponse replay = service.split("SRC-4", request);
        assertEquals(first, replay);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class));

        ApiException changed = assertThrows(ApiException.class, () -> service.split("SRC-4",
                new SplitRequest("req-idem",
                        List.of(new ChildSpec("C-1", 70, null), new ChildSpec("C-2", 30, null)))));
        assertEquals(HttpStatus.CONFLICT, changed.status());
    }

    @Test
    void transferAcceptMovesHolderAndRejectKeepsHolderWithReason() {
        source("SRC-5", 80);
        TransferView pending = service.createTransfer("SRC-5",
                new TransferRequest("加工点A", "下游B"));
        assertEquals("PENDING", pending.status());

        TransferView accepted = service.signoff(pending.id(),
                new SignoffRequest("req-sign-1", "ACCEPT", null));
        assertEquals("ACCEPTED", accepted.status());
        assertEquals("下游B", service.trace("SRC-5").currentHolder());
        assertEquals(FIXED_NOW, jdbc.queryForObject(
                "SELECT decided_at FROM transfer WHERE id = " + pending.id(), java.sql.Timestamp.class)
                .toInstant());
        // 签收requestId同参重放原结果
        TransferView replay = service.signoff(pending.id(),
                new SignoffRequest("req-sign-1", "ACCEPT", null));
        assertEquals(accepted, replay);
        // 已签收批次不可再次移交
        ApiException again = assertThrows(ApiException.class, () ->
                service.createTransfer("SRC-5", new TransferRequest("下游B", "下游C")));
        assertEquals(HttpStatus.CONFLICT, again.status());

        source("SRC-6", 20);
        TransferView pending2 = service.createTransfer("SRC-6",
                new TransferRequest("加工点A", "下游B"));
        ApiException noReason = assertThrows(ApiException.class, () -> service.signoff(
                pending2.id(), new SignoffRequest("req-sign-2", "REJECT", " ")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, noReason.status());
        TransferView rejected = service.signoff(pending2.id(),
                new SignoffRequest("req-sign-2", "REJECT", "含水率超标"));
        assertEquals("REJECTED", rejected.status());
        assertEquals("含水率超标", rejected.rejectReason());
        assertEquals("加工点A", service.trace("SRC-6").currentHolder());
        // 拒绝后移交申请保留,批次仍为叶子可再次发起移交
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer WHERE batch_id IN "
                        + "(SELECT id FROM batch WHERE batch_code = 'SRC-6')", Integer.class));
        TransferView retry = service.createTransfer("SRC-6",
                new TransferRequest("加工点A", "下游C"));
        assertNotNull(retry);
    }

    @Test
    void traceReturnsFullAncestorChainWeightAndHolder() {
        source("SRC-7", 100);
        service.split("SRC-7", new SplitRequest("req-t1",
                List.of(new ChildSpec("C-70", 70, null), new ChildSpec("C-30", 30, null))));
        service.split("C-70", new SplitRequest("req-t2",
                List.of(new ChildSpec("G-40", 40, null), new ChildSpec("G-30", 30, null))));
        service.createTransfer("G-40", new TransferRequest("加工点A", "下游B"));
        TransferView pending = service.createTransfer("G-30", new TransferRequest("加工点A", "下游C"));
        service.signoff(pending.id(), new SignoffRequest("req-t3", "ACCEPT", null));

        TraceResponse trace = service.trace("G-30");
        assertEquals(30, trace.weightKg());
        assertEquals("下游C", trace.currentHolder());
        assertEquals(List.of("SRC-7", "C-70", "G-30"),
                trace.chain().stream().map(CustodyDtos.TraceNode::batchCode).toList());
        assertTrue(trace.chain().stream().allMatch(n -> n.voucherNo().equals("VCH-001")));
        assertEquals("SPLIT", trace.chain().get(0).status());
    }
}