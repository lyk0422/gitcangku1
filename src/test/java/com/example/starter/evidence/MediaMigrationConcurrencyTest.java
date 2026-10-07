package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.MediaRegisterRequest;
import com.example.starter.evidence.dto.MigrationCreateRequest;
import com.example.starter.evidence.dto.MigrationDecideRequest;
import com.example.starter.evidence.dto.MigrationVerifyRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * 介质迁移并发与幂等边界测试：切换与取消、切换与封条异常按事务提交顺序生效；
 * 任何竞争结果下每件证物恰好一个 ACTIVE 介质，已取消迁移不得被重放复活。
 */
@SpringBootTest
class MediaMigrationConcurrencyTest {

    @Autowired
    private EvidenceController evidenceController;

    @Autowired
    private MediaMigrationController migrationController;

    @Autowired
    private MediaMigrationService migrationService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String sha256Of(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造 SEALED 证物并登记 ACTIVE 源介质，返回源摘要。 */
    private String sealedWithMedia(String actor, String evidenceKey) {
        ResponseEntity<String> intake = evidenceController.intake(actor,
                new IntakeRequest(uniqueKey("CMD"), evidenceKey, "CASE-1", "DIGITAL", "SEAL-1"));
        assertThat(intake.getStatusCode().value()).isEqualTo(201);
        String sourceSha = sha256Of("content-" + evidenceKey);
        ResponseEntity<String> media = migrationController.registerMedia(actor, evidenceKey,
                new MediaRegisterRequest(uniqueKey("CMD"), "DISK-1", sourceSha));
        assertThat(media.getStatusCode().value()).isEqualTo(201);
        return sourceSha;
    }

    /** 创建迁移并由见证者验证通过，进入 PENDING_SWITCH（版本 2）。 */
    private String verifiedMigration(String actor, String evidenceKey, String sourceSha) {
        String migrationKey = uniqueKey("MIG");
        ResponseEntity<String> created = migrationController.createMigration(actor, evidenceKey,
                new MigrationCreateRequest(uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<String> verified = migrationController.verifyMigration("wendy", evidenceKey,
                migrationKey, new MigrationVerifyRequest(uniqueKey("CMD"), sourceSha, 1L));
        assertThat(verified.getStatusCode().value()).isEqualTo(200);
        return migrationKey;
    }

    /**
     * 记录一次并发调用的 HTTP 状态码（业务异常取其携带状态码）。
     */
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

    private List<Integer> runConcurrently(Callable<ResponseEntity<String>> first,
                                          Callable<ResponseEntity<String>> second)
            throws Exception {
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

    @Test
    void concurrentSwitchAndCancelExactlyOneWins() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey);
        String migrationKey = verifiedMigration("alice", evidenceKey, sourceSha);

        List<Integer> statuses = runConcurrently(
                () -> migrationController.switchMigration("alice", evidenceKey, migrationKey,
                        new MigrationDecideRequest(uniqueKey("CMD"), 2L)),
                () -> migrationController.cancelMigration("alice", evidenceKey, migrationKey,
                        new MigrationDecideRequest(uniqueKey("CMD"), 2L)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var migration = migrationService.listMigrations(evidenceKey).get(0);
        var media = migrationService.listMedia(evidenceKey);
        long activeCount = media.stream()
                .filter(m -> m.status() == MediaStatus.ACTIVE).count();
        assertThat(activeCount).isEqualTo(1);
        if (migration.status() == MigrationStatus.SWITCHED) {
            // 切换先提交：新介质 ACTIVE，源介质 RETIRED
            assertThat(media.get(0).status()).isEqualTo(MediaStatus.RETIRED);
            assertThat(media.get(1).status()).isEqualTo(MediaStatus.ACTIVE);
        } else {
            // 取消先提交：源副本不变仍 ACTIVE，新介质 RETIRED
            assertThat(migration.status()).isEqualTo(MigrationStatus.CANCELLED);
            assertThat(media.get(0).status()).isEqualTo(MediaStatus.ACTIVE);
            assertThat(media.get(1).status()).isEqualTo(MediaStatus.RETIRED);
        }
    }

    @Test
    void concurrentSwitchAndSealFailureResolveByCommitOrder() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey);
        String migrationKey = verifiedMigration("alice", evidenceKey, sourceSha);

        List<Integer> statuses = runConcurrently(
                () -> migrationController.switchMigration("alice", evidenceKey, migrationKey,
                        new MigrationDecideRequest(uniqueKey("CMD"), 2L)),
                () -> evidenceController.inspectSeal("alice", evidenceKey,
                        new SealInspectionRequest(uniqueKey("CMD"), false, "cracked")));

        // 封条异常登记始终成功；切换是否成功取决于提交顺序
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(200, 422);
        var migration = migrationService.listMigrations(evidenceKey).get(0);
        var media = migrationService.listMedia(evidenceKey);
        long activeCount = media.stream()
                .filter(m -> m.status() == MediaStatus.ACTIVE).count();
        assertThat(activeCount).isEqualTo(1);
        if (statuses.get(0) == 422) {
            // 异常先提交：切换被阻止，迁移仍待切换
            assertThat(migration.status()).isEqualTo(MigrationStatus.PENDING_SWITCH);
            assertThat(media.get(0).status()).isEqualTo(MediaStatus.ACTIVE);
        } else {
            // 切换先提交：异常仍登记，迁移已切换
            assertThat(migration.status()).isEqualTo(MigrationStatus.SWITCHED);
            assertThat(media.get(1).status()).isEqualTo(MediaStatus.ACTIVE);
        }
    }

    @Test
    void concurrentVerifyWithSameCommandKeyVerifiesOnce() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey);
        String migrationKey = uniqueKey("MIG");
        ResponseEntity<String> created = migrationController.createMigration("alice", evidenceKey,
                new MigrationCreateRequest(uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        String commandKey = uniqueKey("CMD");
        List<Integer> statuses = runConcurrently(
                () -> migrationController.verifyMigration("wendy", evidenceKey, migrationKey,
                        new MigrationVerifyRequest(commandKey, sourceSha, 1L)),
                () -> migrationController.verifyMigration("wendy", evidenceKey, migrationKey,
                        new MigrationVerifyRequest(commandKey, sourceSha, 1L)));

        assertThat(statuses).containsOnly(200);
        var migration = migrationService.listMigrations(evidenceKey).get(0);
        assertThat(migration.status()).isEqualTo(MigrationStatus.PENDING_SWITCH);
        assertThat(migration.witnessId()).isEqualTo("wendy");
        // 只验证一次：迁移版本与新介质版本各递增一次
        assertThat(migration.version()).isEqualTo(2);
        var media = migrationService.listMedia(evidenceKey);
        assertThat(media.get(1).status()).isEqualTo(MediaStatus.VERIFIED);
        assertThat(media.get(1).version()).isEqualTo(2);
    }

    @Test
    void concurrentCreateMigrationExactlyOnePending() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey);

        List<Integer> statuses = runConcurrently(
                () -> migrationController.createMigration("alice", evidenceKey,
                        new MigrationCreateRequest(uniqueKey("CMD"), uniqueKey("MIG"),
                                "DISK-2", sourceSha)),
                () -> migrationController.createMigration("alice", evidenceKey,
                        new MigrationCreateRequest(uniqueKey("CMD"), uniqueKey("MIG"),
                                "DISK-3", sourceSha)));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        // 每件证物至多一条待完成迁移
        assertThat(migrationService.listMigrations(evidenceKey)).hasSize(1);
        assertThat(migrationService.listMigrations(evidenceKey).get(0).status())
                .isEqualTo(MigrationStatus.PENDING_VERIFY);
    }
}
