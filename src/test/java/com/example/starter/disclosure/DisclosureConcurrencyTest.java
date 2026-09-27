package com.example.starter.disclosure;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.disclosure.dto.PackageAmendRequest;
import com.example.starter.disclosure.dto.PackageCreateRequest;
import com.example.starter.disclosure.dto.PackageRevokeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 披露包并发与幂等边界测试（真实 H2 内存库）：
 * 同键并发重放只产生一个包；建包与借出、建包与建包、修订与撤销、交付与撤销
 * 均按事务提交顺序裁决，不允许出现版本空洞、状态并存或半成品。
 */
@SpringBootTest
class DisclosureConcurrencyTest {

    private static final LocalDateTime FUTURE = LocalDateTime.of(2026, 12, 31, 0, 0, 0);

    @Autowired
    private DisclosureController controller;

    @Autowired
    private com.example.starter.evidence.EvidenceController evidenceController;

    @Autowired
    private DisclosurePackageRepository packageRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intake(String custodian, String evidenceKey, String caseKey) {
        ResponseEntity<String> response = evidenceController.intake(custodian,
                new com.example.starter.evidence.dto.IntakeRequest(
                        uniqueKey("CMD"), evidenceKey, caseKey, "DOCUMENT", "SEAL-1"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    /**
     * 并发执行两个调用，返回各自 HTTP 状态码（业务异常取其携带状态码）。
     */
    private List<Integer> runConcurrently(Callable<ResponseEntity<String>> first,
                                          Callable<ResponseEntity<String>> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> f1 = executor.submit(new StatusCall(ready, start, first));
            Future<Integer> f2 = executor.submit(new StatusCall(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start,
                   Callable<ResponseEntity<String>> call) {
            this.ready = ready;
            this.start = start;
            this.call = call;
        }

        @Override
        public Integer call() throws Exception {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return call.call().getStatusCode().value();
            } catch (ApiException e) {
                return e.status().value();
            }
        }
    }

    @Test
    void concurrentCreateWithSameCommandKeyReplaysSinglePackage() throws Exception {
        String caseKey = uniqueKey("CASE");
        String actor = uniqueKey("actor");
        String ev = uniqueKey("EV");
        intake(actor, ev, caseKey);
        String commandKey = uniqueKey("PKGCMD");
        PackageCreateRequest request = new PackageCreateRequest(
                commandKey, caseKey, FUTURE, List.of(ev));

        List<Integer> statuses = runConcurrently(
                () -> controller.createPackage(actor, request),
                () -> controller.createPackage(actor, request));

        assertThat(statuses).containsOnly(201);
        List<DisclosurePackage> packages = packageRepository.findByCase(caseKey);
        assertThat(packages).hasSize(1);
        assertThat(packages.get(0).packageVersion()).isEqualTo(1);
    }

    @Test
    void concurrentCreateAndBorrowArbitratedByCommitOrderWithoutHalfFinishedPackage() throws Exception {
        String caseKey = uniqueKey("CASE");
        String actor = uniqueKey("actor");
        String borrower = uniqueKey("borrower");
        String ev = uniqueKey("EV");
        intake(actor, ev, caseKey);

        LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        com.example.starter.evidence.dto.LoanCreateRequest loanRequest =
                new com.example.starter.evidence.dto.LoanCreateRequest(
                        uniqueKey("CMD"), uniqueKey("LOAN"), borrower, "鉴定用", dueAt);

        List<Integer> statuses = runConcurrently(
                () -> controller.createPackage(actor,
                        new PackageCreateRequest(uniqueKey("PKGCMD"), caseKey, FUTURE, List.of(ev))),
                () -> evidenceController.borrow(actor, ev, loanRequest));

        // 借出始终成功；建包取决于提交顺序：先建包则 201，先借出则截至时刻借出未归还 422。
        assertThat(statuses).contains(200);
        assertThat(statuses).allMatch(s -> s == 200 || s == 201 || s == 422);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);

        List<DisclosurePackage> packages = packageRepository.findByCase(caseKey);
        String evidenceStatus = jdbc.queryForObject(
                "SELECT status FROM evidence WHERE evidence_key = ?", String.class, ev);
        if (packages.isEmpty()) {
            // 借出先提交：建包必须 422，证物 BORROWED，不存在任何半成品包或快照。
            assertThat(statuses).contains(422);
            assertThat(evidenceStatus).isEqualTo("BORROWED");
            assertThat(jdbc.queryForList(
                    "SELECT i.id FROM disclosure_package_item i "
                            + "JOIN disclosure_package p ON i.package_key = p.package_key "
                            + "WHERE p.case_key = ?", caseKey)).isEmpty();
        } else {
            // 建包先提交：恰好一个包，冻结快照必须为封签正常，之后借出成功。
            assertThat(statuses).contains(201);
            assertThat(packages).hasSize(1);
            assertThat(packages.get(0).status()).isEqualTo(DisclosurePackageStatus.GENERATED);
            assertThat(evidenceStatus).isEqualTo("BORROWED");
        }
    }

    @Test
    void concurrentCreatesForSameCaseGetSequentialVersionsByCommitOrder() throws Exception {
        String caseKey = uniqueKey("CASE");
        String actor = uniqueKey("actor");
        String ev = uniqueKey("EV");
        intake(actor, ev, caseKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.createPackage(actor, new PackageCreateRequest(
                        uniqueKey("PKGCMD"), caseKey, FUTURE, List.of(ev))),
                () -> controller.createPackage(actor, new PackageCreateRequest(
                        uniqueKey("PKGCMD"), caseKey, FUTURE, List.of(ev))));

        assertThat(statuses).containsOnly(201);
        List<DisclosurePackage> packages = packageRepository.findByCase(caseKey);
        assertThat(packages).hasSize(2);
        assertThat(packages.stream().map(DisclosurePackage::packageVersion))
                .containsExactly(1, 2);
        // 指纹含版本，两个包指纹必须不同。
        assertThat(packages.get(0).packageKey()).isNotEqualTo(packages.get(1).packageKey());
    }

    @Test
    void concurrentAmendAndRevokeAreSerializedWithConsistentOutcome() throws Exception {
        String caseKey = uniqueKey("CASE");
        String actor = uniqueKey("actor");
        String ev = uniqueKey("EV");
        intake(actor, ev, caseKey);

        ResponseEntity<String> created = controller.createPackage(actor,
                new PackageCreateRequest(uniqueKey("PKGCMD"), caseKey, FUTURE, List.of(ev)));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String packageKey = jdbc.queryForObject(
                "SELECT package_key FROM disclosure_package WHERE case_key = ? AND package_version = 1",
                String.class, caseKey);

        PackageAmendRequest amendRequest = new PackageAmendRequest(
                uniqueKey("PKGCMD"), FUTURE, List.of(), List.of());
        PackageRevokeRequest revokeRequest = new PackageRevokeRequest(
                uniqueKey("PKGCMD"), "信息需要更正");

        List<Integer> statuses = runConcurrently(
                () -> controller.amendPackage(actor, packageKey, amendRequest),
                () -> controller.revokePackage(actor, packageKey, revokeRequest));

        List<DisclosurePackage> packages = packageRepository.findByCase(caseKey);
        DisclosurePackage v1 = packages.stream()
                .filter(p -> p.packageVersion() == 1).findFirst().orElseThrow();
        // 撤销与修订由案件行锁串行，v1 最终必须是 REVOKED（撤销赢）或仍 GENERATED 但存在修订后继。
        if (packages.size() == 1) {
            // 撤销先提交：修订 409，v1 REVOKED。
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(v1.status()).isEqualTo(DisclosurePackageStatus.REVOKED);
        } else {
            // 修订先提交：修订 201，撤销随后作用于仍 GENERATED 的 v1 返回 200。
            assertThat(packages).hasSize(2);
            assertThat(statuses).containsExactlyInAnyOrder(201, 200);
            assertThat(v1.status()).isEqualTo(DisclosurePackageStatus.REVOKED);
            DisclosurePackage v2 = packages.stream()
                    .filter(p -> p.packageVersion() == 2).findFirst().orElseThrow();
            assertThat(v2.basedOnVersion()).isEqualTo(1);
            assertThat(v2.status()).isEqualTo(DisclosurePackageStatus.GENERATED);
        }
    }

    @Test
    void concurrentDeliverAndRevokeExactlyOneTerminalWins() throws Exception {
        String caseKey = uniqueKey("CASE");
        String actor = uniqueKey("actor");
        String ev = uniqueKey("EV");
        intake(actor, ev, caseKey);
        controller.createPackage(actor, new PackageCreateRequest(
                uniqueKey("PKGCMD"), caseKey, FUTURE, List.of(ev)));
        String packageKey = jdbc.queryForObject(
                "SELECT package_key FROM disclosure_package WHERE case_key = ? AND package_version = 1",
                String.class, caseKey);

        List<Integer> statuses = runConcurrently(
                () -> controller.deliverPackage(actor, packageKey,
                        new CommandRequest(uniqueKey("PKGCMD"))),
                () -> controller.revokePackage(actor, packageKey,
                        new PackageRevokeRequest(uniqueKey("PKGCMD"), "并发撤销")));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, delivered_at, revoked_at, revoke_reason FROM disclosure_package WHERE package_key = ?",
                packageKey);
        String status = (String) row.get("status");
        // 交付与撤销绝不能同时生效。
        assertThat(status).isIn("DELIVERED", "REVOKED");
        if (status.equals("DELIVERED")) {
            assertThat(row.get("revoked_at")).isNull();
            assertThat(row.get("revoke_reason")).isNull();
        } else {
            assertThat(row.get("delivered_at")).isNull();
        }
    }
}
