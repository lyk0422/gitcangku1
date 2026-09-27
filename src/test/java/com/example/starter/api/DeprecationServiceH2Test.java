package com.example.starter.api;

import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.BatchDeprecationResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DeprecatedClosureItem;
import com.example.starter.api.dto.DeprecationDiagnosticsResponse;
import com.example.starter.api.dto.DeprecationResponse;
import com.example.starter.api.dto.DeprecationSpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RevokeDeprecationResponse;
import com.example.starter.support.ApiException;
import com.example.starter.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 弃用窗口能力的服务层 H2 测试：使用可注入可变时钟精确控制生效边界，
 * 覆盖登记/撤销/锁定裁决主流程、失败分支、批量原子性、幂等与并发边界。
 */
@SpringBootTest
class DeprecationServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return MutableClock.at(T0.toString());
        }
    }

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_deprecation_warning");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM deprecation_window");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        clock.set(T0);
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private void register(String name, int version, DependencySpec... deps) {
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest(name, version, List.of(deps)));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static DeprecationSpec window(String name, int version, Instant effectiveAt,
                                          int replacementVersion, String reason) {
        return new DeprecationSpec(name, version, effectiveAt, replacementVersion, reason);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    private int deprecationRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(1) FROM deprecation_window", Integer.class);
    }

    // ------------------------------------------------------------------
    // 主流程：登记、历史、明细、诊断
    // ------------------------------------------------------------------

    @Test
    void batchRegisterPersistsWindowsAndAdvancesRepositoryVersion() {
        register("lib", 1);
        register("lib", 2);

        BatchDeprecationResponse response = service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(3600), 2, "旧版下线"))));

        assertThat(response.repositoryVersion()).isEqualTo(3L);
        assertThat(response.windows()).hasSize(1);
        DeprecationResponse window = response.windows().get(0);
        assertThat(window.name()).isEqualTo("lib");
        assertThat(window.version()).isEqualTo(1);
        assertThat(window.effectiveAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(window.replacementVersion()).isEqualTo(2);
        assertThat(window.reason()).isEqualTo("旧版下线");
        assertThat(window.revoked()).isFalse();
        assertThat(window.revokedAt()).isNull();
        assertThat(window.createdAt()).isEqualTo(T0);

        // 历史与明细查询一致，且读取不推进仓库版本。
        assertThat(service.listDeprecations()).hasSize(1);
        DeprecationResponse detail = service.getDeprecation("lib", 1);
        assertThat(detail).isEqualTo(window);
        assertThat(repositoryVersion()).isEqualTo(3L);
        assertThat(deprecationRowCount()).isEqualTo(1);
    }

    @Test
    void diagnosticsReflectClockAndDoNotChangeState() {
        register("lib", 1);
        register("lib", 2);
        register("util", 1);
        register("util", 2);
        register("tool", 1);
        register("tool", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2, null),
                window("util", 1, T0, 2, "立即生效"))));
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("tool", 1, T0.plusSeconds(3600), 2, null))));
        service.revokeDeprecation(requestId(), "tool", 1);
        assertThat(repositoryVersion()).isEqualTo(9L);

        DeprecationDiagnosticsResponse diagnostics = service.getDeprecationDiagnostics();
        assertThat(diagnostics.now()).isEqualTo(T0);
        assertThat(diagnostics.total()).isEqualTo(3);
        assertThat(diagnostics.pending()).isEqualTo(1);
        assertThat(diagnostics.effective()).isEqualTo(1);
        assertThat(diagnostics.revoked()).isEqualTo(1);
        assertThat(diagnostics.effectiveWindows())
                .extracting("name", "version").containsExactly(tuple("util", 1));

        // 诊断是只读查询：重复调用不推进仓库版本、不改变计数。
        DeprecationDiagnosticsResponse again = service.getDeprecationDiagnostics();
        assertThat(again.total()).isEqualTo(3);
        assertThat(repositoryVersion()).isEqualTo(9L);

        // 时钟推进到 lib 生效后，诊断口径随之时钟变化。
        clock.set(T0.plusSeconds(3600));
        DeprecationDiagnosticsResponse later = service.getDeprecationDiagnostics();
        assertThat(later.pending()).isEqualTo(0);
        assertThat(later.effective()).isEqualTo(2);
        assertThat(later.effectiveWindows())
                .extracting("name", "version")
                .containsExactly(tuple("lib", 1), tuple("util", 1));
    }

    // ------------------------------------------------------------------
    // 锁定裁决：警告、生效 422、边界
    // ------------------------------------------------------------------

    @Test
    void lockBeforeEffectiveEmitsWarningAndPersistsSnapshot() {
        register("app", 1, dep("lib", 1, 1));
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2, "旧版下线"))));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(lock.createdAt()).isEqualTo(T0);
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
        assertThat(lock.deprecationWarnings()).hasSize(1);
        assertThat(lock.deprecationWarnings().get(0).name()).isEqualTo("lib");
        assertThat(lock.deprecationWarnings().get(0).version()).isEqualTo(1);
        assertThat(lock.deprecationWarnings().get(0).replacementVersion()).isEqualTo(2);
        assertThat(lock.deprecationWarnings().get(0).effectiveAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(lock.deprecationWarnings().get(0).reason()).isEqualTo("旧版下线");

        // 历史查询返回固化的警告快照。
        assertThat(service.getLock(lock.id()).deprecationWarnings())
                .isEqualTo(lock.deprecationWarnings());

        // 之后撤回替代版本、撤销窗口都不改写历史锁的警告快照。
        service.withdrawArtifact(requestId(), "lib", 2);
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.deprecationWarnings()).isEqualTo(lock.deprecationWarnings());
        assertThat(queried.createdAt()).isEqualTo(T0);
    }

    @Test
    void lockAfterEffectiveFails422WithSortedDetailsAndConsumesNoKey() {
        register("app", 1, dep("lib", 1, 1), dep("util", 1, 1));
        register("lib", 1);
        register("lib", 2);
        register("util", 1);
        register("util", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("util", 1, T0.plusSeconds(3600), 2, null),
                window("lib", 1, T0.plusSeconds(3600), 2, "旧版下线"))));
        clock.set(T0.plusSeconds(3600));

        String rid = requestId();
        assertThatThrownBy(() -> service.createLock(rid, new LockRequest("app", 1, 6L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getDetails()).isInstanceOf(List.class);
                    @SuppressWarnings("unchecked")
                    List<DeprecatedClosureItem> items = (List<DeprecatedClosureItem>) e.getDetails();
                    // 稳定排序：按名称升序返回制品、版本、替代与生效时刻。
                    assertThat(items).extracting("name", "version", "replacementVersion")
                            .containsExactly(tuple("lib", 1, 2), tuple("util", 1, 2));
                    assertThat(items.get(0).effectiveAt()).isEqualTo(T0.plusSeconds(3600));
                });

        // 整次失败：无半成品，失败不占 requestId。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_deprecation_warning", Integer.class)).isZero();
        service.registerArtifact(rid, new RegisterArtifactRequest("other", 1, List.of()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void lockAdjudicationBoundaryIsLeftClosed() {
        register("app", 1, dep("lib", 1, 1));
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2, null))));

        // 生效前 1 秒：仍可解析，产生警告。
        clock.set(T0.plusSeconds(3599));
        LockFileResponse before = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(before.deprecationWarnings()).hasSize(1);

        // 恰好到达生效时刻（左闭）：整次 422。
        clock.set(T0.plusSeconds(3600));
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).hasSize(1);
    }

    @Test
    void lockedFileUnaffectedByLaterDeprecation() {
        register("app", 1, dep("lib", 1, 1));
        register("lib", 1);
        register("lib", 2);
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 3L));
        assertThat(lock.deprecationWarnings()).isEmpty();

        // 锁定之后登记立即生效的弃用：历史锁不改写，新锁定按新时刻裁决。
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0, 2, "立即生效"))));
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.deprecationWarnings()).isEmpty();
        assertThat(queried.repositoryVersion()).isEqualTo(3L);

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    @SuppressWarnings("unchecked")
                    List<DeprecatedClosureItem> items = (List<DeprecatedClosureItem>) e.getDetails();
                    assertThat(items).extracting("name", "version", "replacementVersion")
                            .containsExactly(tuple("lib", 1, 2));
                });
    }

    // ------------------------------------------------------------------
    // 登记失败分支
    // ------------------------------------------------------------------

    @Test
    void effectiveAtInPastReturns422WithActualAndRequired() {
        register("lib", 1);
        register("lib", 2);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.minusSeconds(1), 2, null)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains(T0.minusSeconds(1).toString());
                    assertThat(e.getMessage()).contains(T0.toString());
                });
        assertThat(deprecationRowCount()).isZero();
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void deprecatedVersionMissingReturns404() {
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("ghost", 1, T0.plusSeconds(60), 2, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThat(deprecationRowCount()).isZero();
    }

    @Test
    void replacementMissingReturns422() {
        register("lib", 1);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(60), 9, null)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("lib:9");
                });
        assertThat(deprecationRowCount()).isZero();
    }

    @Test
    void replacementWithdrawnReturns422() {
        register("lib", 1);
        register("lib", 2);
        service.withdrawArtifact(requestId(), "lib", 2);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(60), 2, null)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("已撤回");
                });
        assertThat(deprecationRowCount()).isZero();
    }

    @Test
    void replacementCycleReturns422AndWritesNothing() {
        register("lib", 1);
        register("lib", 2);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(60), 2, null),
                        window("lib", 2, T0.plusSeconds(60), 1, null)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("环");
                });
        assertThat(deprecationRowCount()).isZero();
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void selfReplacementReturns422() {
        register("lib", 1);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(60), 1, null)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("环");
                });
        assertThat(deprecationRowCount()).isZero();
    }

    @Test
    void batchIsAtomicWhenAnyItemInvalid() {
        register("lib", 1);
        register("lib", 2);
        register("util", 1);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(60), 2, null),
                        window("util", 1, T0.plusSeconds(60), 9, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 任一失败整次不写入：查询不到半成品，仓库版本不推进。
        assertThat(deprecationRowCount()).isZero();
        assertThat(service.listDeprecations()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(3L);
    }

    @Test
    void duplicateWindowReturns409() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(60), 2, null))));

        // 已有窗口的版本再次登记 → 409。
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(120), 2, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 批量内重复 → 409。
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new BatchDeprecationRequest(List.of(
                        window("lib", 2, T0.plusSeconds(120), 2, null),
                        window("lib", 2, T0.plusSeconds(120), 2, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(deprecationRowCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 撤销
    // ------------------------------------------------------------------

    @Test
    void revokePendingWindowKeepsRecordAndStopsWarning() {
        register("app", 1, dep("lib", 1, 1));
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2, "旧版下线"))));

        RevokeDeprecationResponse revoked = service.revokeDeprecation(requestId(), "lib", 1);
        assertThat(revoked.repositoryVersion()).isEqualTo(5L);
        assertThat(revoked.window().revoked()).isTrue();
        assertThat(revoked.window().revokedAt()).isEqualTo(T0);
        assertThat(revoked.window().effectiveAt()).isEqualTo(T0.plusSeconds(3600));

        // 撤销记录保留：历史仍可见，不能隐藏曾经的窗口。
        List<DeprecationResponse> history = service.listDeprecations();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).revoked()).isTrue();
        assertThat(history.get(0).revokedAt()).isEqualTo(T0);

        // 已撤销窗口不再参与锁定裁决。
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 5L));
        assertThat(lock.deprecationWarnings()).isEmpty();
    }

    @Test
    void revokeEffectiveWindowReturns409() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2, null))));
        clock.set(T0.plusSeconds(3600));

        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "lib", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("已生效");
                });
        // 失败撤销不改变窗口状态。
        assertThat(service.getDeprecation("lib", 1).revoked()).isFalse();
    }

    @Test
    void doubleRevokeReturns409AndMissingWindowReturns404() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2, null))));
        service.revokeDeprecation(requestId(), "lib", 1);

        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "lib", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "lib", 2))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.getDeprecation("lib", 2))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysBatchAndDifferentParamsConflict() {
        register("lib", 1);
        register("lib", 2);
        String rid = requestId();
        BatchDeprecationRequest request = new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(60), 2, null)));

        BatchDeprecationResponse first = service.registerDeprecations(rid, request);
        BatchDeprecationResponse replay = service.registerDeprecations(rid, request);
        assertThat(replay).isEqualTo(first);
        assertThat(deprecationRowCount()).isEqualTo(1);
        assertThat(repositoryVersion()).isEqualTo(3L);

        // 同 requestId 异参 → 409。
        String finalRid = rid;
        assertThatThrownBy(() -> service.registerDeprecations(finalRid,
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(120), 2, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 撤销幂等：同 requestId 同参重放首次快照。
        String revokeRid = requestId();
        RevokeDeprecationResponse revokeFirst = service.revokeDeprecation(revokeRid, "lib", 1);
        RevokeDeprecationResponse revokeReplay = service.revokeDeprecation(revokeRid, "lib", 1);
        assertThat(revokeReplay).isEqualTo(revokeFirst);
        assertThat(service.getDeprecation("lib", 1).revoked()).isTrue();
    }

    @Test
    void failedBatchDoesNotConsumeRequestId() {
        register("lib", 1);
        register("lib", 2);
        String rid = requestId();
        assertThatThrownBy(() -> service.registerDeprecations(rid,
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(60), 9, null)))))
                .isInstanceOf(ApiException.class);

        BatchDeprecationResponse later = service.registerDeprecations(rid,
                new BatchDeprecationRequest(List.of(
                        window("lib", 1, T0.plusSeconds(60), 2, null))));
        assertThat(later.windows()).hasSize(1);
        assertThat(deprecationRowCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentSameRequestIdBatchResolvesToSingleResult() throws Exception {
        register("lib", 1);
        register("lib", 2);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        BatchDeprecationRequest request = new BatchDeprecationRequest(List.of(
                window("lib", 1, T0.plusSeconds(60), 2, null)));

        List<Future<BatchDeprecationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerDeprecations(rid, request);
            }));
        }
        pool.shutdown();

        BatchDeprecationResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<BatchDeprecationResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(deprecationRowCount()).isEqualTo(1);
        assertThat(repositoryVersion()).isEqualTo(3L);
        // 两条登记 + 一次批量登记各留一条幂等记录；同一 requestId 仅一条。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void concurrentLockAndDeprecationRegistrationAreSerialized() throws Exception {
        register("app", 1, dep("lib", 1, 1));
        register("lib", 1);
        register("lib", 2);
        long versionBefore = 3L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(requestId(), new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> deprecateTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.registerDeprecations(requestId(), new BatchDeprecationRequest(List.of(
                    window("lib", 1, T0, 2, "立即生效"))));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, deprecateTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object deprecateOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        // 按提交顺序裁决：弃用登记总是成功并把仓库版本推进到 4。
        assertThat(deprecateOutcome).isInstanceOf(BatchDeprecationResponse.class);
        assertThat(repositoryVersion()).isEqualTo(versionBefore + 1);
        assertThat(deprecationRowCount()).isEqualTo(1);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：裁决时窗口尚不存在，无警告且内容固化。
            assertThat(lock.deprecationWarnings()).isEmpty();
            assertThat(service.getLock(lock.id()).deprecationWarnings()).isEmpty();
        } else {
            // 弃用先提交：锁的期望版本过期，409 且无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
        }
    }
}
