package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.MediaRegisterRequest;
import com.example.starter.evidence.dto.MigrationCreateRequest;
import com.example.starter.evidence.dto.MigrationVerifyRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 介质迁移并发与幂等边界测试：切换与取消、切换与封条异常并发时按事务提交顺序生效；
 * 不允许出现已取消迁移仍切换、或封条已异常仍完成切换的结果；任何时刻恰好一个 ACTIVE。
 */
@SpringBootTest
class MediaMigrationConcurrencyTest {

    private static final String HASH_H = "a".repeat(64);
    private static final String HASH_K = "b".repeat(64);

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private MediaMigrationController migrationController;

    @Autowired
    private MediaMigrationService migrationService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void intakeWithMedia(String actor, String evidenceKey) {
        ResponseEntity<String> intake = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DIGITAL", "SEAL-1"));
        assertThat(intake.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> media = migrationController.registerMedia(actor, evidenceKey,
                new MediaRegisterRequest(uniqueKey("CMD"), "DISK-1", HASH_H));
        assertThat(media.getStatusCode().value()).isEqualTo(201);
    }

    private void createAndVerifyMigration(String actor, String witness, String evidenceKey) {
        ResponseEntity<String> created = migrationController.createMigration(actor, evidenceKey,
                new MigrationCreateRequest(uniqueKey("CMD"), "DISK-2", HASH_K));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> verified = migrationController.verifyMigration(witness, evidenceKey,
                new MigrationVerifyRequest(uniqueKey("CMD"), HASH_H));
        assertThat(verified.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
    private static final class StatusCall implements Callable<Integer> {
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final Callable<ResponseEntity<String>> call;

        StatusCall(CountDownLatch ready, CountDownLatch start, Callable<ResponseEntity<String>> call) {
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
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private long activeMediaCount(String evidenceKey) {
        return migrationService.listMedia(evidenceKey).stream()
                .filter(m -> m.status() == MediaStatus.ACTIVE).count();
    }

    @Test
    void concurrentSwitchAndCancelExactlyOneWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey);
        createAndVerifyMigration("alice", "bob", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> migrationController.switchMigration("alice", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> migrationController.cancelMigration("alice", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var migrations = migrationService.listMigrations(evidenceKey);
        assertThat(migrations).hasSize(1);
        var migration = migrations.get(0);
        // 任何结果下恰好一个 ACTIVE 介质
        assertThat(activeMediaCount(evidenceKey)).isEqualTo(1);
        if (migration.status() == MigrationStatus.COMPLETED) {
            // 切换先提交：新介质 ACTIVE、旧介质 RETIRED，摘要与见证人已冻结
            assertThat(migration.sourceSha256()).isEqualTo(HASH_H);
            assertThat(migration.targetSha256()).isEqualTo(HASH_H);
            assertThat(migration.witnessId()).isEqualTo("bob");
            assertThat(migrationService.currentMedia(evidenceKey).mediaNo()).isEqualTo("DISK-2");
        } else {
            // 取消先提交：源副本不变，新介质不得成为当前读取来源
            assertThat(migration.status()).isEqualTo(MigrationStatus.CANCELLED);
            assertThat(migrationService.currentMedia(evidenceKey).mediaNo()).isEqualTo("DISK-1");
        }
    }

    @Test
    void concurrentSwitchAndFailedInspectionNeverSwitchOnBrokenSeal() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey);
        createAndVerifyMigration("alice", "bob", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> migrationController.switchMigration("alice", evidenceKey,
                        new CommandRequest(uniqueKey("CMD"))),
                () -> evidenceController.inspectSeal("alice", evidenceKey,
                        new SealInspectionRequest(uniqueKey("CMD"), false, "cracked")));

        // 核验登记总是允许（200）；核验先提交则切换得到 422，切换先提交则切换 200
        assertThat(statuses).allMatch(s -> s == 200 || s == 422);

        var migrations = migrationService.listMigrations(evidenceKey);
        assertThat(migrations).hasSize(1);
        assertThat(activeMediaCount(evidenceKey)).isEqualTo(1);
        if (migrations.get(0).status() == MigrationStatus.COMPLETED) {
            // 切换先提交：切换时封条尚未异常，随后异常登记生效
            assertThat(statuses).containsOnly(200);
            assertThat(migrationService.currentMedia(evidenceKey).mediaNo()).isEqualTo("DISK-2");
        } else {
            // 失败核验先提交：封条异常，迁移不得完成，源副本不变
            assertThat(statuses).contains(422);
            assertThat(migrations.get(0).status()).isEqualTo(MigrationStatus.PENDING);
            assertThat(migrationService.currentMedia(evidenceKey).mediaNo()).isEqualTo("DISK-1");
        }
    }

    @Test
    void concurrentDuplicateCreateMigrationWithSameCommandKeyReplaysSingleResult()
            throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey);
        String commandKey = uniqueKey("CMD");
        MigrationCreateRequest request = new MigrationCreateRequest(commandKey, "DISK-2", HASH_K);

        List<Integer> statuses = runConcurrently(
                () -> migrationController.createMigration("alice", evidenceKey, request),
                () -> migrationController.createMigration("alice", evidenceKey, request));

        assertThat(statuses).containsOnly(201);
        assertThat(migrationService.listMigrations(evidenceKey)).hasSize(1);
        assertThat(migrationService.listMedia(evidenceKey)).hasSize(2);
    }

    @Test
    void concurrentCreateMigrationWithDifferentTargetsExactlyOnePending() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> migrationController.createMigration("alice", evidenceKey,
                        new MigrationCreateRequest(uniqueKey("CMD"), "DISK-2", HASH_K)),
                () -> migrationController.createMigration("alice", evidenceKey,
                        new MigrationCreateRequest(uniqueKey("CMD"), "DISK-3", HASH_K)));

        // 每件证物至多一条待完成迁移：一个成功，另一个 409
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(migrationService.listMigrations(evidenceKey)).hasSize(1);
        assertThat(migrationService.listMigrations(evidenceKey).get(0).status())
                .isEqualTo(MigrationStatus.PENDING);
    }

    @Test
    void concurrentDuplicateVerifyWithSameCommandKeyVerifiesOnce() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey);
        ResponseEntity<String> created = migrationController.createMigration("alice", evidenceKey,
                new MigrationCreateRequest(uniqueKey("CMD"), "DISK-2", HASH_K));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        String verifyKey = uniqueKey("CMD");
        MigrationVerifyRequest request = new MigrationVerifyRequest(verifyKey, HASH_H);
        List<Integer> statuses = runConcurrently(
                () -> migrationController.verifyMigration("bob", evidenceKey, request),
                () -> migrationController.verifyMigration("bob", evidenceKey, request));

        assertThat(statuses).containsOnly(200);
        var migrations = migrationService.listMigrations(evidenceKey);
        assertThat(migrations).hasSize(1);
        assertThat(migrations.get(0).witnessId()).isEqualTo("bob");
        // 验证只生效一次：目标介质 VERIFIED，源介质仍 ACTIVE
        assertThat(migrationService.listMedia(evidenceKey).stream()
                .filter(m -> m.status() == MediaStatus.VERIFIED).count()).isEqualTo(1);
        assertThat(activeMediaCount(evidenceKey)).isEqualTo(1);
    }
}
