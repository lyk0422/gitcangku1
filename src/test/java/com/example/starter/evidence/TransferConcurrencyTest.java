package com.example.starter.evidence;

import com.example.starter.evidence.domain.CommandType;
import com.example.starter.evidence.service.CommandExecutor;
import com.example.starter.evidence.service.CommandResult;
import com.example.starter.evidence.service.EvidenceService;
import com.example.starter.evidence.web.ApiException;
import com.example.starter.evidence.web.dto.IntakeRequest;
import com.example.starter.evidence.web.dto.SealCheckRequest;
import com.example.starter.evidence.domain.SealCheckResult;
import com.example.starter.evidence.web.dto.TransferInitiateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交接接受/取消与失败核验的并发边界：依赖证物行锁串行化，按事务提交顺序生效，
 * 不允许出现"已取消交接仍改变保管人"或"封条已异常仍完成交接"。
 */
@SpringBootTest
class TransferConcurrencyTest {

    @Autowired
    private CommandExecutor commandExecutor;

    @Autowired
    private EvidenceService evidenceService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM seal_check");
        jdbc.update("DELETE FROM evidence_transfer");
        jdbc.update("DELETE FROM evidence");
    }

    @Test
    void acceptAndCancelRaceExactlyOneWins() throws Exception {
        intake("EV-C1", "alice");
        initiate("EV-C1", "alice", "bob");

        Outcome accept = new Outcome();
        Outcome cancel = new Outcome();
        runConcurrently(
                () -> accept.capture(() -> commandExecutor.execute("cmd-c1-a", CommandType.TRANSFER_ACCEPT, "bob",
                        CommandExecutor.fingerprintOf("TRANSFER_ACCEPT", "bob", "EV-C1"),
                        () -> new CommandResult(200, evidenceService.acceptTransfer("bob", "EV-C1")))),
                () -> cancel.capture(() -> commandExecutor.execute("cmd-c1-c", CommandType.TRANSFER_CANCEL, "alice",
                        CommandExecutor.fingerprintOf("TRANSFER_CANCEL", "alice", "EV-C1"),
                        () -> new CommandResult(200, evidenceService.cancelTransfer("alice", "EV-C1")))));

        // 恰好一个成功，另一个因状态冲突失败（409）
        assertThat(accept.succeeded()).isNotEqualTo(cancel.succeeded());
        Outcome loser = accept.succeeded() ? cancel : accept;
        assertThat(loser.error).isNotNull();
        assertThat(loser.error.status().value()).isEqualTo(409);

        String status = jdbc.queryForObject("SELECT status FROM evidence WHERE evidence_key = 'EV-C1'", String.class);
        String custodian = jdbc.queryForObject("SELECT custodian_id FROM evidence WHERE evidence_key = 'EV-C1'", String.class);
        String transferStatus = jdbc.queryForObject(
                "SELECT t.status FROM evidence_transfer t JOIN evidence e ON t.evidence_id = e.id WHERE e.evidence_key = 'EV-C1'",
                String.class);
        assertThat(status).isEqualTo("SEALED");
        if (accept.succeeded()) {
            // 接受生效：保管人切换为接收人
            assertThat(custodian).isEqualTo("bob");
            assertThat(transferStatus).isEqualTo("ACCEPTED");
        } else {
            // 取消生效：保管人不变，不存在"已取消交接仍改变保管人"
            assertThat(custodian).isEqualTo("alice");
            assertThat(transferStatus).isEqualTo("CANCELLED");
        }
    }

    @Test
    void failedCheckAndAcceptRaceStayConsistent() throws Exception {
        intake("EV-C2", "alice");
        initiate("EV-C2", "alice", "bob");

        Outcome check = new Outcome();
        Outcome accept = new Outcome();
        runConcurrently(
                () -> check.capture(() -> commandExecutor.execute("cmd-c2-s", CommandType.SEAL_CHECK, "alice",
                        CommandExecutor.fingerprintOf("SEAL_CHECK", "alice", "EV-C2", "FAIL", ""),
                        () -> new CommandResult(201, evidenceService.sealCheck("alice", "EV-C2",
                                new SealCheckRequest("cmd-c2-s", SealCheckResult.FAIL, null))))),
                () -> accept.capture(() -> commandExecutor.execute("cmd-c2-a", CommandType.TRANSFER_ACCEPT, "bob",
                        CommandExecutor.fingerprintOf("TRANSFER_ACCEPT", "bob", "EV-C2"),
                        () -> new CommandResult(200, evidenceService.acceptTransfer("bob", "EV-C2")))));

        // 恰好一个成功
        assertThat(check.succeeded()).isNotEqualTo(accept.succeeded());

        String status = jdbc.queryForObject("SELECT status FROM evidence WHERE evidence_key = 'EV-C2'", String.class);
        String custodian = jdbc.queryForObject("SELECT custodian_id FROM evidence WHERE evidence_key = 'EV-C2'", String.class);
        String transferStatus = jdbc.queryForObject(
                "SELECT t.status FROM evidence_transfer t JOIN evidence e ON t.evidence_id = e.id WHERE e.evidence_key = 'EV-C2'",
                String.class);
        if (check.succeeded()) {
            // 失败核验先生效：封条异常，交接不得完成
            assertThat(status).isEqualTo("SEAL_BROKEN");
            assertThat(custodian).isEqualTo("alice");
            assertThat(transferStatus).isEqualTo("PENDING");
            assertThat(accept.error.status().value()).isEqualTo(422);
        } else {
            // 接受先生效：保管人已切换，核验因操作人不匹配失败，不存在"封条已异常仍完成交接"
            assertThat(status).isEqualTo("SEALED");
            assertThat(custodian).isEqualTo("bob");
            assertThat(transferStatus).isEqualTo("ACCEPTED");
            assertThat(check.error.status().value()).isEqualTo(409);
        }
    }

    private void intake(String evidenceKey, String custodian) {
        evidenceService.intake(custodian, new IntakeRequest(
                "cmd-intake-" + evidenceKey, evidenceKey, "CASE-" + evidenceKey, "DOC", "SEAL-" + evidenceKey, custodian));
    }

    private void initiate(String evidenceKey, String from, String to) {
        evidenceService.initiateTransfer(from, evidenceKey,
                new TransferInitiateRequest("cmd-init-" + evidenceKey, to));
    }

    private void runConcurrently(ThrowingRunnable first, ThrowingRunnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> f1 = pool.submit(() -> {
                ready.countDown();
                await(start);
                runUnchecked(first);
            });
            Future<?> f2 = pool.submit(() -> {
                ready.countDown();
                await(start);
                runUnchecked(second);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void runUnchecked(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * 记录一次并发命令的结果：成功保存状态码，失败保存 ApiException。
     */
    private static final class Outcome {
        private Integer status;
        private ApiException error;

        void capture(ThrowingRunnable action) {
            try {
                action.run();
                this.status = 200;
            } catch (ApiException e) {
                this.error = e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        boolean succeeded() {
            return status != null;
        }
    }
}
