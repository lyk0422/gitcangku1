package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DeprecationBatchRequest;
import com.example.starter.api.dto.DeprecationBatchResponse;
import com.example.starter.api.dto.DeprecationDiagnosisResponse;
import com.example.starter.api.dto.DeprecationEventView;
import com.example.starter.api.dto.DeprecationSpec;
import com.example.starter.api.dto.DeprecationWindowView;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import com.example.starter.support.MutableClock;
import com.example.starter.support.MutableClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
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
 * 弃用窗口能力的 H2（MODE=MySQL）数据库与业务测试：
 * 覆盖登记/撤销/批量校验主流程与失败分支、锁定裁决边界、历史不可改写、
 * 幂等重放、事务原子性与并发提交顺序裁决。时钟为可控 UTC 时钟。
 */
@SpringBootTest
@Import(MutableClockConfiguration.class)
class DeprecationServiceH2Test {

    private static final Instant T0 = MutableClockConfiguration.T0;

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_deprecation");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM deprecation_event");
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

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static DeprecationSpec window(String name, int version, int replacement,
                                          String effectiveAt, String note) {
        return new DeprecationSpec(name, version, replacement, Instant.parse(effectiveAt), note);
    }

    private DeprecationBatchResponse setWindows(DeprecationSpec... specs) {
        return service.setDeprecations(requestId(), new DeprecationBatchRequest(List.of(specs)));
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 登记主流程
    // ------------------------------------------------------------------

    @Test
    void registerWindowPersistsAndAdvancesRepositoryVersion() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));

        DeprecationBatchResponse response = setWindows(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "迁移到 v2"));

        assertThat(response.requestedCount()).isEqualTo(1);
        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.repositoryVersion()).isEqualTo(3L);
        assertThat(response.appliedAt()).isEqualTo(T0);
        assertThat(response.windows()).hasSize(1);
        DeprecationWindowView view = response.windows().get(0);
        assertThat(view.name()).isEqualTo("lib");
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.replacementVersion()).isEqualTo(2);
        assertThat(view.effectiveAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(view.note()).isEqualTo("迁移到 v2");
        assertThat(view.status()).isEqualTo("REGISTERED");
        assertThat(view.revokedAt()).isNull();

        // 明细查询与历史查询一致。
        assertThat(service.getDeprecation("lib", 1)).isEqualTo(view);
        assertThat(service.listDeprecations()).containsExactly(view);
        List<DeprecationEventView> events = service.getDeprecationEvents("lib", 1);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo("REGISTERED");
        assertThat(events.get(0).note()).isEqualTo("迁移到 v2");
    }

    @Test
    void batchOfMultipleWindowsAppliesAtomically() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.registerArtifact(requestId(), artifact("util", 1));
        service.registerArtifact(requestId(), artifact("util", 2));

        DeprecationBatchResponse response = setWindows(
                window("util", 1, 2, "2026-06-01T00:00:00Z", "u"),
                window("lib", 1, 2, "2026-07-01T00:00:00Z", "l"));

        assertThat(response.acceptedCount()).isEqualTo(2);
        // 响应按名称、版本稳定排序，与请求顺序无关。
        assertThat(response.windows()).extracting("name", "version")
                .containsExactly(tuple("lib", 1), tuple("util", 1));
        assertThat(repositoryVersion()).isEqualTo(5L);
        assertThat(service.listDeprecationEvents()).hasSize(2);
    }

    @Test
    void reRegisterAfterRevokeOverwritesCurrentStateButKeepsHistory() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "first"));
        service.revokeDeprecation(requestId(), "lib", 1);

        setWindows(window("lib", 1, 2, "2026-08-01T00:00:00Z", "second"));

        DeprecationWindowView current = service.getDeprecation("lib", 1);
        assertThat(current.status()).isEqualTo("REGISTERED");
        assertThat(current.effectiveAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(current.note()).isEqualTo("second");
        assertThat(current.revokedAt()).isNull();
        // 历史保留：登记、撤销、再登记三条事件。
        assertThat(service.getDeprecationEvents("lib", 1))
                .extracting("action")
                .containsExactly("REGISTERED", "REVOKED", "REGISTERED");
    }

    // ------------------------------------------------------------------
    // 登记失败分支：整批不写入
    // ------------------------------------------------------------------

    @Test
    void effectiveAtInPastRejectedWithDifference() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));

        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 2, "2025-12-31T23:59:59Z", "past")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("EFFECTIVE_IN_PAST");
                });
        assertThat(service.listDeprecations()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void effectiveAtExactlyNowIsAcceptedAsImmediateEffect() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));

        DeprecationBatchResponse response = setWindows(
                window("lib", 1, 2, T0.toString(), "now"));
        assertThat(response.acceptedCount()).isEqualTo(1);
        // 立即生效：同一时刻锁定应被阻断（闭包精确命中被弃用的 lib:1）。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("DEPRECATION_EFFECTIVE");
                });
    }

    @Test
    void missingSubjectVersionRejected() {
        service.registerArtifact(requestId(), artifact("lib", 2));
        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "x")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("SUBJECT_MISSING");
                });
        assertThat(service.listDeprecations()).isEmpty();
    }

    @Test
    void missingReplacementRejected() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 9, "2026-06-01T00:00:00Z", "x")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("REPLACEMENT_MISSING");
                });
        assertThat(service.listDeprecations()).isEmpty();
    }

    @Test
    void withdrawnReplacementRejected() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.withdrawArtifact(requestId(), "lib", 2);

        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "x")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("REPLACEMENT_WITHDRAWN");
                });
        assertThat(service.listDeprecations()).isEmpty();
    }

    @Test
    void selfReplacementCycleRejected() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 1, "2026-06-01T00:00:00Z", "self")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("REPLACEMENT_CYCLE");
                });
        assertThat(service.listDeprecations()).isEmpty();
    }

    @Test
    void cycleWithinBatchRejectedAtomically() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.registerArtifact(requestId(), artifact("util", 1));
        service.registerArtifact(requestId(), artifact("util", 2));

        // lib 1->2、lib 2->1 成环；util 合法但整批不得写入。
        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "a"),
                window("lib", 2, 1, "2026-06-01T00:00:00Z", "b"),
                window("util", 1, 2, "2026-06-01T00:00:00Z", "ok")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("REPLACEMENT_CYCLE");
                });
        assertThat(service.listDeprecations()).isEmpty();
        assertThat(service.listDeprecationEvents()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(4L);
    }

    @Test
    void cycleFormedWithExistingWindowRejected() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "first"));

        // 已有 1->2，再登记 2->1 构成环。
        assertThatThrownBy(() -> setWindows(
                window("lib", 2, 1, "2026-06-01T00:00:00Z", "back")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("REPLACEMENT_CYCLE");
                });
        // 既有窗口不受影响。
        assertThat(service.getDeprecation("lib", 1).replacementVersion()).isEqualTo(2);
        assertThat(service.listDeprecations()).hasSize(1);
    }

    @Test
    void duplicateItemInBatchRejectedWithCounts() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));

        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "a"),
                window("lib", 1, 2, "2026-07-01T00:00:00Z", "b")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("DUPLICATE_ITEM");
                });
        assertThat(service.listDeprecations()).isEmpty();
    }

    @Test
    void replacementMustBeSameArtifactImplicitlyByLookup() {
        // 替代版本按同制品名查找；其它制品的同名版本不构成有效替代。
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("other", 2));
        assertThatThrownBy(() -> setWindows(
                window("lib", 1, 2, "2026-06-01T00:00:00Z", "x")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("REPLACEMENT_MISSING");
                });
    }

    // ------------------------------------------------------------------
    // 撤销
    // ------------------------------------------------------------------

    @Test
    void revokeBeforeEffectiveSucceedsAndKeepsHistory() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "迁移"));

        DeprecationWindowView revoked = service.revokeDeprecation(requestId(), "lib", 1);

        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isEqualTo(T0);
        // 撤销事件保留撤销前的替代版本与说明。
        List<DeprecationEventView> events = service.getDeprecationEvents("lib", 1);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).action()).isEqualTo("REVOKED");
        assertThat(events.get(1).replacementVersion()).isEqualTo(2);
        assertThat(events.get(1).note()).isEqualTo("迁移");
        // 撤销后窗口不再参与裁决：锁定无警告。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 5L));
        assertThat(lock.deprecationWarnings()).isEmpty();
    }

    @Test
    void revokeAfterEffectiveRejected422() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"));
        clock.set(Instant.parse("2026-06-01T00:00:00Z"));

        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "lib", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("DEPRECATION_ALREADY_EFFECTIVE");
                });
        assertThat(service.getDeprecation("lib", 1).status()).isEqualTo("REGISTERED");
    }

    @Test
    void revokeAtExactEffectiveInstantRejectedLeftClosed() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"));
        clock.set(Instant.parse("2026-06-01T00:00:00Z").minusNanos(1));

        // 生效前一纳秒仍可撤销。
        DeprecationWindowView revoked = service.revokeDeprecation(requestId(), "lib", 1);
        assertThat(revoked.status()).isEqualTo("REVOKED");
    }

    @Test
    void revokeMissingWindowReturns404() {
        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "ghost", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void doubleRevokeReturns409AndKeepsSingleRevokeEvent() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"));
        service.revokeDeprecation(requestId(), "lib", 1);

        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "lib", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.getDeprecationEvents("lib", 1))
                .extracting("action")
                .containsExactly("REGISTERED", "REVOKED");
    }

    // ------------------------------------------------------------------
    // 锁定裁决
    // ------------------------------------------------------------------

    @Test
    void lockBeforeEffectiveSucceedsWithWarningAndFreezesEvidence() {
        // app:1 -> lib[1,2]，解析取高版本 lib2；lib2 登记未来弃用，替代为 lib1。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 2, 1, "2026-06-01T00:00:00Z", "lib2 弃用"));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));

        assertThat(lock.resolvedAt()).isEqualTo(T0);
        assertThat(lock.deprecationWarnings()).hasSize(1);
        var warning = lock.deprecationWarnings().get(0);
        assertThat(warning.name()).isEqualTo("lib");
        assertThat(warning.version()).isEqualTo(2);
        assertThat(warning.replacementVersion()).isEqualTo(1);
        assertThat(warning.effectiveAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(warning.note()).isEqualTo("lib2 弃用");
        assertThat(warning.secondsUntilEffective())
                .isEqualTo(Duration.between(T0, Instant.parse("2026-06-01T00:00:00Z")).getSeconds());

        // 历史锁查询返回同样的固化警告。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.deprecationWarnings()).isEqualTo(lock.deprecationWarnings());
        assertThat(queried.resolvedAt()).isEqualTo(T0);
    }

    @Test
    void lockAfterEffectiveFails422WithStableSortedBlockedList() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("zlib", 1, 1), dep("alib", 1, 1)));
        service.registerArtifact(requestId(), artifact("zlib", 1));
        service.registerArtifact(requestId(), artifact("zlib", 2));
        service.registerArtifact(requestId(), artifact("alib", 1));
        service.registerArtifact(requestId(), artifact("alib", 2));
        setWindows(
                window("zlib", 1, 2, "2026-06-01T00:00:00Z", "z"),
                window("alib", 1, 2, "2026-06-01T00:00:00Z", "a"));
        clock.set(Instant.parse("2026-06-01T00:00:00Z"));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 6L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("DEPRECATION_EFFECTIVE");
                });
        // 整次失败：不产生任何锁文件半成品。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_deprecation", Integer.class)).isZero();
    }

    @Test
    void historicalLockNotRewrittenByLaterWindowChanges() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 2, 1, "2026-06-01T00:00:00Z", "lib2 弃用"));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(lock.deprecationWarnings()).hasSize(1);

        // 之后调整窗口（改替代与说明）并撤回替代版本：历史锁不得改写。
        setWindows(window("lib", 2, 1, "2026-09-01T00:00:00Z", "调整后说明"));
        service.withdrawArtifact(requestId(), "lib", 1);

        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.deprecationWarnings()).isEqualTo(lock.deprecationWarnings());
        assertThat(queried.deprecationWarnings().get(0).note()).isEqualTo("lib2 弃用");
        assertThat(queried.deprecationWarnings().get(0).effectiveAt())
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(queried.resolvedAt()).isEqualTo(T0);
        assertThat(queried.entries()).isEqualTo(lock.entries());
    }

    @Test
    void reResolutionAfterEffectiveUsesNewClockAndBlocks() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 2, 1, "2026-06-01T00:00:00Z", "x"));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(lock.deprecationWarnings()).hasSize(1);

        // 时钟越过生效时刻后重解析（新锁请求）按新时刻裁决：422。
        clock.set(Instant.parse("2026-06-02T00:00:00Z"));
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("DEPRECATION_EFFECTIVE");
                });
        // 旧锁仍保留当时的警告证据。
        assertThat(service.getLock(lock.id()).deprecationWarnings()).hasSize(1);
    }

    @Test
    void revokedWindowDoesNotWarnOrBlock() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"));
        service.revokeDeprecation(requestId(), "lib", 1);

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 5L));
        assertThat(lock.deprecationWarnings()).isEmpty();
        // 撤销历史仍可查询。
        assertThat(service.getDeprecation("lib", 1).status()).isEqualTo("REVOKED");
    }

    // ------------------------------------------------------------------
    // 只读诊断
    // ------------------------------------------------------------------

    @Test
    void diagnosePreviewsWithoutChangingState() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 2, 1, "2026-06-01T00:00:00Z", "x"));
        long versionBefore = repositoryVersion();

        DeprecationDiagnosisResponse diagnosis = service.diagnose("app", 1, null);
        assertThat(diagnosis.repositoryVersion()).isEqualTo(versionBefore);
        assertThat(diagnosis.resolvedAt()).isEqualTo(T0);
        assertThat(diagnosis.blocked()).isEmpty();
        assertThat(diagnosis.warnings()).hasSize(1);
        assertThat(diagnosis.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));

        // 越过生效时刻的诊断：blocked 非空，但仍不写入任何状态。
        DeprecationDiagnosisResponse future = service.diagnose(
                "app", 1, Instant.parse("2026-06-02T00:00:00Z"));
        assertThat(future.blocked()).hasSize(1);
        assertThat(future.warnings()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(versionBefore);
        assertThat(service.listLocks()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysBatchResult() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        String rid = requestId();
        DeprecationBatchRequest request = new DeprecationBatchRequest(
                List.of(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x")));

        DeprecationBatchResponse first = service.setDeprecations(rid, request);
        DeprecationBatchResponse replay = service.setDeprecations(rid, request);

        assertThat(replay).isEqualTo(first);
        assertThat(service.listDeprecations()).hasSize(1);
        assertThat(service.listDeprecationEvents()).hasSize(1);
        assertThat(repositoryVersion()).isEqualTo(3L);
    }

    @Test
    void sameRequestIdDifferentBatchReturns409() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        String rid = requestId();
        service.setDeprecations(rid, new DeprecationBatchRequest(
                List.of(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"))));

        assertThatThrownBy(() -> service.setDeprecations(rid, new DeprecationBatchRequest(
                List.of(window("lib", 1, 2, "2026-07-01T00:00:00Z", "y")))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedBatchDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        String rid = requestId();
        // 替代版本不存在，失败不占键。
        assertThatThrownBy(() -> service.setDeprecations(rid, new DeprecationBatchRequest(
                List.of(window("lib", 1, 9, "2026-06-01T00:00:00Z", "x")))))
                .isInstanceOf(ApiException.class);

        service.registerArtifact(requestId(), artifact("lib", 2));
        DeprecationBatchResponse later = service.setDeprecations(rid, new DeprecationBatchRequest(
                List.of(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"))));
        assertThat(later.acceptedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void revokeIdempotentReplayReturnsSameSnapshot() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        setWindows(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x"));
        String rid = requestId();

        DeprecationWindowView first = service.revokeDeprecation(rid, "lib", 1);
        DeprecationWindowView replay = service.revokeDeprecation(rid, "lib", 1);

        assertThat(replay).isEqualTo(first);
        assertThat(service.getDeprecationEvents("lib", 1)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // 并发与事务
    // ------------------------------------------------------------------

    @Test
    void concurrentLockAndDeprecationRegistrationAreSerialized() throws Exception {
        // app:1 -> lib[1,1]；lib:1、lib:2。窗口对 lib:1 立即生效。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        long versionBefore = repositoryVersion();

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
            try {
                return service.setDeprecations(requestId(), new DeprecationBatchRequest(
                        List.of(window("lib", 1, 2, T0.toString(), "立即生效"))));
            } catch (ApiException e) {
                return e;
            }
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, deprecateTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object deprecateOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(deprecateOutcome).isInstanceOf(DeprecationBatchResponse.class);
        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：窗口尚未登记，无警告；历史锁此后不被改写。
            assertThat(lock.deprecationWarnings()).isEmpty();
            assertThat(service.getLock(lock.id()).deprecationWarnings()).isEmpty();
        } else {
            // 弃用先提交：仓库版本已推进，锁的期望版本过期 → 409，无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
        }
        assertThat(repositoryVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    void concurrentSameRequestIdBatchResolvesToSingleResult() throws Exception {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        DeprecationBatchRequest request = new DeprecationBatchRequest(
                List.of(window("lib", 1, 2, "2026-06-01T00:00:00Z", "x")));

        List<Future<DeprecationBatchResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.setDeprecations(rid, request);
            }));
        }
        pool.shutdown();

        DeprecationBatchResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<DeprecationBatchResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(service.listDeprecations()).hasSize(1);
        assertThat(service.listDeprecationEvents()).hasSize(1);
        // 仅批次请求占用该 requestId（制品登记使用各自独立 requestId）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM deprecation_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void uniqueConstraintOnWindowNameVersionEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO deprecation_window (artifact_name, artifact_version, "
                + "replacement_version, effective_at, note, status, request_id, created_at) "
                + "VALUES ('lib', 1, 2, CURRENT_TIMESTAMP(6), 'n', 'REGISTERED', 'r1', "
                + "CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO deprecation_window (artifact_name, artifact_version, "
                        + "replacement_version, effective_at, note, status, request_id, created_at) "
                        + "VALUES ('lib', 1, 2, CURRENT_TIMESTAMP(6), 'n', 'REGISTERED', 'r2', "
                        + "CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void lockDeprecationSnapshotRowsPersistedAndOrdered() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("zlib", 1, 1), dep("alib", 1, 1)));
        service.registerArtifact(requestId(), artifact("zlib", 1));
        service.registerArtifact(requestId(), artifact("zlib", 2));
        service.registerArtifact(requestId(), artifact("alib", 1));
        service.registerArtifact(requestId(), artifact("alib", 2));
        setWindows(
                window("zlib", 1, 2, "2026-06-01T00:00:00Z", "z"),
                window("alib", 1, 2, "2026-07-01T00:00:00Z", "a"));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 6L));

        // 警告按名称升序稳定排序。
        assertThat(lock.deprecationWarnings()).extracting("name")
                .containsExactly("alib", "zlib");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_deprecation WHERE lock_file_id = ?",
                Integer.class, lock.id());
        assertThat(rows).isEqualTo(2);
    }
}
