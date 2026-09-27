package com.example.starter.api;

import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.DeprecationEventResponse;
import com.example.starter.api.dto.DeprecationRequest;
import com.example.starter.api.dto.DeprecationResponse;
import com.example.starter.api.dto.DeprecationWarningView;
import com.example.starter.api.dto.DeprecatedBlockedItem;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * 弃用窗口 H2（MODE=MySQL）业务测试：使用可控 UTC 时钟覆盖
 * 主流程、左闭右开时刻边界、失败分支、批量事务原子性、历史固化、
 * 幂等与真实并发裁决。
 */
@SpringBootTest
class DeprecationWindowH2Test {

    /** 固定 UTC 基准时刻（微秒对齐，避免 TIMESTAMP(6) 舍入干扰断言）。 */
    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    /** 可在测试中推进的固定 UTC 时钟，替代系统时钟。 */
    static class MutableClock extends Clock {
        private volatile Instant instant = T0;

        void setInstant(Instant value) {
            this.instant = value;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM lock_deprecation_warning");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_deprecation_event");
        jdbcTemplate.update("DELETE FROM artifact_deprecation");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        clock.setInstant(T0);
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static DeprecationRequest deprecation(String name, int version, Instant effectiveAt,
                                                  int replacementVersion, String note) {
        return new DeprecationRequest(name, version, effectiveAt, replacementVersion, note);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    /** 登记 app:1 -> lib[1,1]，lib1 与 lib2；仓库版本停在 3。 */
    private void seedAppLibChain() {
        service.registerArtifact(rid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1));
        service.registerArtifact(rid(), artifact("lib", 2));
        assertThat(repositoryVersion()).isEqualTo(3L);
    }

    // ------------------------------------------------------------------
    // 主流程：登记、待生效警告、生效后 422
    // ------------------------------------------------------------------

    @Test
    void registerDeprecationPersistsWindowAndAdvancesVersion() {
        seedAppLibChain();
        Instant effective = T0.plus(Duration.ofDays(10));

        DeprecationResponse response = service.registerDeprecation(rid(),
                deprecation("lib", 1, effective, 2, "请升级 lib2"));

        assertThat(response.id()).isPositive();
        assertThat(response.name()).isEqualTo("lib");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.replacementName()).isEqualTo("lib");
        assertThat(response.replacementVersion()).isEqualTo(2);
        assertThat(response.effectiveAt()).isEqualTo(effective);
        assertThat(response.note()).isEqualTo("请升级 lib2");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.revokedAt()).isNull();
        assertThat(response.createdAt()).isEqualTo(T0);
        assertThat(repositoryVersion()).isEqualTo(4L);

        List<DeprecationResponse> active = service.listActiveDeprecations();
        assertThat(active).hasSize(1);
        assertThat(service.getDeprecation(response.id()).note()).isEqualTo("请升级 lib2");
    }

    @Test
    void lockBeforeEffectiveSucceedsWithFrozenDeprecationWarning() {
        seedAppLibChain();
        Instant effective = T0.plus(Duration.ofDays(10));
        service.registerDeprecation(rid(), deprecation("lib", 1, effective, 2, "请升级"));
        // 弃用登记推进版本到 4。
        assertThat(repositoryVersion()).isEqualTo(4L);

        LockFileResponse lock = service.createLock(rid(), new LockRequest("app", 1, 4L));

        assertThat(lock.resolvedAt()).isEqualTo(T0);
        assertThat(lock.deprecationWarnings()).hasSize(1);
        DeprecationWarningView warning = lock.deprecationWarnings().get(0);
        assertThat(warning.name()).isEqualTo("lib");
        assertThat(warning.version()).isEqualTo(1);
        assertThat(warning.replacementName()).isEqualTo("lib");
        assertThat(warning.replacementVersion()).isEqualTo(2);
        assertThat(warning.effectiveAt()).isEqualTo(effective);
        assertThat(warning.note()).isEqualTo("请升级");
        assertThat(warning.resolvedAt()).isEqualTo(T0);
        // 警告真实落库并随锁查询返回。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_deprecation_warning", Integer.class)).isEqualTo(1);
        assertThat(service.getLock(lock.id()).deprecationWarnings())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(warning);
    }

    @Test
    void lockAfterEffectiveReturns422WithStableSortedDetails() {
        // 闭包含两个已生效弃用版本：zlib 与 lib，验证按制品名称稳定排序与全字段返回。
        service.registerArtifact(rid(), artifact("app", 1,
                dep("lib", 1, 1), dep("zlib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1));
        service.registerArtifact(rid(), artifact("lib", 2));
        service.registerArtifact(rid(), artifact("zlib", 1));
        service.registerArtifact(rid(), artifact("zlib", 2));
        Instant effectiveLib = T0.plus(Duration.ofDays(2));
        Instant effectiveZlib = T0.plus(Duration.ofDays(1));
        service.registerDeprecation(rid(), deprecation("lib", 1, effectiveLib, 2, null));
        service.registerDeprecation(rid(), deprecation("zlib", 1, effectiveZlib, 2, null));
        long versionAfterDeprecations = repositoryVersion();

        clock.advance(Duration.ofDays(3));

        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, versionAfterDeprecations)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("DEPRECATED_ARTIFACT_IN_CLOSURE");
                    @SuppressWarnings("unchecked")
                    List<DeprecatedBlockedItem> items = (List<DeprecatedBlockedItem>) e.getDetails();
                    assertThat(items).extracting("name", "version", "replacementName",
                                    "replacementVersion", "effectiveAt")
                            .containsExactly(
                                    tuple("lib", 1, "lib", 2, effectiveLib),
                                    tuple("zlib", 1, "zlib", 2, effectiveZlib));
                });
        // 422 不留任何锁、条目、警告或幂等占位。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_deprecation_warning", Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(versionAfterDeprecations);
    }

    @Test
    void lockExactlyAtEffectiveInstantIsBlockedLeftClosed() {
        seedAppLibChain();
        Instant effective = T0.plus(Duration.ofDays(10));
        service.registerDeprecation(rid(), deprecation("lib", 1, effective, 2, null));

        // 左闭：解析时刻恰好等于生效时刻也必须 422。
        clock.setInstant(effective);
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 右开边界的内侧：早 1 微秒仍可锁定并产生警告。
        clock.setInstant(effective.minus(Duration.ofNanos(1_000)));
        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.deprecationWarnings()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void effectiveAtInThePastReturns422AndWritesNothing() {
        seedAppLibChain();
        long before = repositoryVersion();
        assertThatThrownBy(() -> service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.minus(Duration.ofSeconds(1)), 2, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listActiveDeprecations()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation_event", Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(before);
    }

    @Test
    void missingReplacementReturns422() {
        seedAppLibChain();
        assertThatThrownBy(() -> service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 9, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void withdrawnReplacementReturns422() {
        seedAppLibChain();
        service.withdrawArtifact(rid(), "lib", 2);
        assertThatThrownBy(() -> service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 2, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void deprecatedVersionMissingReturns404() {
        seedAppLibChain();
        assertThatThrownBy(() -> service.registerDeprecation(rid(),
                deprecation("ghost", 1, T0.plus(Duration.ofDays(1)), 2, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void selfReplacementFormsCycleReturns422() {
        seedAppLibChain();
        assertThatThrownBy(() -> service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 1, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("环");
                });
        assertThat(service.listActiveDeprecations()).isEmpty();
    }

    @Test
    void duplicateActiveWindowReturns409() {
        seedAppLibChain();
        service.registerDeprecation(rid(), deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 2, null));
        assertThatThrownBy(() -> service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(2)), 2, "再次登记")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 批量：完整后态先校验，任一失败整次不写入
    // ------------------------------------------------------------------

    @Test
    void batchAppliesAllWindowsAndReturnsActualAndRequestedCounts() {
        seedAppLibChain();
        service.registerArtifact(rid(), artifact("tool", 1));
        service.registerArtifact(rid(), artifact("tool", 2));
        long before = repositoryVersion();

        BatchDeprecationRequest request = new BatchDeprecationRequest(List.of(
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 2, "lib 升级"),
                deprecation("tool", 1, T0.plus(Duration.ofDays(2)), 2, null)));
        var response = service.batchDeprecations(rid(), request);

        assertThat(response.requestedCount()).isEqualTo(2);
        assertThat(response.appliedCount()).isEqualTo(2);
        assertThat(response.windows()).extracting("name", "version")
                .containsExactly(tuple("lib", 1), tuple("tool", 1));
        assertThat(service.listActiveDeprecations()).hasSize(2);
        assertThat(repositoryVersion()).isEqualTo(before + 1);
        // 每个窗口各留一条 REGISTERED 事件，历史按时间、ID 排序。
        assertThat(service.listDeprecationHistory())
                .extracting("eventType")
                .containsExactly("REGISTERED", "REGISTERED");
    }

    @Test
    void batchCycleWithExistingWindowsRejectsEntireBatch() {
        // 已存在 lib1 -> lib2；批量把 lib2 -> lib1 形成二连环，整批回滚。
        seedAppLibChain();
        service.registerDeprecation(rid(), deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, null));
        long versionAfterRegister = repositoryVersion();

        BatchDeprecationRequest request = new BatchDeprecationRequest(List.of(
                deprecation("lib", 2, T0.plus(Duration.ofDays(1)), 1, null)));
        assertThatThrownBy(() -> service.batchDeprecations(rid(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        assertThat(service.listActiveDeprecations()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);
        // 不追加事件、不推进版本：无部分结果。
        assertThat(service.listDeprecationHistory()).hasSize(1);
        assertThat(repositoryVersion()).isEqualTo(versionAfterRegister);
    }

    @Test
    void batchCycleWithinItselfRollsBackAllItems() {
        // 同一批内 lib1->lib2 与 lib2->lib1 成环：两项都不得写入。
        seedAppLibChain();
        long before = repositoryVersion();

        BatchDeprecationRequest request = new BatchDeprecationRequest(List.of(
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 2, null),
                deprecation("lib", 2, T0.plus(Duration.ofDays(2)), 1, null)));
        assertThatThrownBy(() -> service.batchDeprecations(rid(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        assertThat(service.listActiveDeprecations()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation_event", Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(before);
    }

    @Test
    void batchWithOneInvalidItemRollsBackValidSibling() {
        // 第二项替代版本已撤回 → 422；第一项即使本身合法也不得写入。
        seedAppLibChain();
        service.registerArtifact(rid(), artifact("tool", 1));
        service.withdrawArtifact(rid(), "lib", 2);
        long before = repositoryVersion();

        BatchDeprecationRequest request = new BatchDeprecationRequest(List.of(
                deprecation("tool", 1, T0.plus(Duration.ofDays(1)), 1, null),
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 2, null)));
        assertThatThrownBy(() -> service.batchDeprecations(rid(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        assertThat(service.listActiveDeprecations()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(before);
    }

    @Test
    void batchDuplicateVersionInSameRequestReturns422() {
        seedAppLibChain();
        BatchDeprecationRequest request = new BatchDeprecationRequest(List.of(
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 2, "一"),
                deprecation("lib", 1, T0.plus(Duration.ofDays(2)), 2, "二")));
        assertThatThrownBy(() -> service.batchDeprecations(rid(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void batchAdjustsExistingWindowAndKeepsPriorHistory() {
        seedAppLibChain();
        DeprecationResponse first = service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, "旧说明"));
        long versionAfterRegister = repositoryVersion();

        clock.advance(Duration.ofHours(1));
        Instant newEffective = T0.plus(Duration.ofDays(8));
        service.batchDeprecations(rid(), new BatchDeprecationRequest(List.of(
                deprecation("lib", 1, newEffective, 2, "新说明"))));

        DeprecationResponse adjusted = service.getDeprecation(first.id());
        assertThat(adjusted.effectiveAt()).isEqualTo(newEffective);
        assertThat(adjusted.note()).isEqualTo("新说明");
        // 同一窗口 ID 被调整而非新增；历史同时保留 REGISTERED 与 ADJUSTED。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);
        assertThat(service.listDeprecationHistory())
                .extracting("eventType", "note")
                .containsExactly(tuple("REGISTERED", "旧说明"), tuple("ADJUSTED", "新说明"));
        assertThat(repositoryVersion()).isEqualTo(versionAfterRegister + 1);
    }

    // ------------------------------------------------------------------
    // 撤销：仅未生效窗口，撤销留档，警告历史不隐藏
    // ------------------------------------------------------------------

    @Test
    void revokePendingWindowSucceedsAndKeepsRecords() {
        seedAppLibChain();
        DeprecationResponse window = service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, "说明"));
        clock.advance(Duration.ofHours(1));
        Instant revokeAt = clock.instant();

        DeprecationResponse revoked = service.revokeDeprecation(rid(), window.id());

        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isEqualTo(revokeAt);
        // ACTIVE 查询不再包含，但按 ID 仍可查，记录未删除。
        assertThat(service.listActiveDeprecations()).isEmpty();
        assertThat(service.getDeprecation(window.id()).status()).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);

        List<DeprecationEventResponse> history = service.listDeprecationHistory();
        assertThat(history).extracting("eventType")
                .containsExactly("REGISTERED", "REVOKED");
        // 撤销事件保留撤销前的生效时刻与说明快照。
        DeprecationEventResponse revokeEvent = history.get(1);
        assertThat(revokeEvent.effectiveAt()).isEqualTo(window.effectiveAt());
        assertThat(revokeEvent.note()).isEqualTo("说明");
        assertThat(revokeEvent.eventAt()).isEqualTo(revokeAt);

        // 撤销后锁定既不警告也不拦截。
        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.deprecationWarnings()).isEmpty();
    }

    @Test
    void revokeAfterEffectiveReturns422AndKeepsWindowActive() {
        seedAppLibChain();
        DeprecationResponse window = service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, null));
        clock.setInstant(window.effectiveAt());

        assertThatThrownBy(() -> service.revokeDeprecation(rid(), window.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.getDeprecation(window.id()).status()).isEqualTo("ACTIVE");
        assertThat(service.listDeprecationHistory()).hasSize(1);
    }

    @Test
    void revokeMissingReturns404AndDoubleRevokeReturns409() {
        seedAppLibChain();
        assertThatThrownBy(() -> service.revokeDeprecation(rid(), 999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        DeprecationResponse window = service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, null));
        service.revokeDeprecation(rid(), window.id());
        assertThatThrownBy(() -> service.revokeDeprecation(rid(), window.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 历史固化：窗口调整/撤销/替代撤回不改写已提交锁
    // ------------------------------------------------------------------

    @Test
    void laterWindowAdjustmentAndReplacementWithdrawalDoNotRewriteHistoricalLock() {
        seedAppLibChain();
        Instant firstEffective = T0.plus(Duration.ofDays(5));
        DeprecationResponse window = service.registerDeprecation(rid(),
                deprecation("lib", 1, firstEffective, 2, "旧说明"));
        long versionAtFirstLock = repositoryVersion();

        LockFileResponse historical = service.createLock(rid(),
                new LockRequest("app", 1, versionAtFirstLock));
        assertThat(historical.deprecationWarnings()).hasSize(1);

        // 事后：批量调整窗口的生效时刻与说明、撤回替代版本 lib2、再撤销窗口。
        service.batchDeprecations(rid(), new BatchDeprecationRequest(List.of(
                deprecation("lib", 1, T0.plus(Duration.ofDays(20)), 2, "新说明"))));
        service.withdrawArtifact(rid(), "lib", 2);
        service.revokeDeprecation(rid(), window.id());

        LockFileResponse queried = service.getLock(historical.id());
        assertThat(queried.resolvedAt()).isEqualTo(T0);
        assertThat(queried.deprecationWarnings()).hasSize(1);
        DeprecationWarningView frozen = queried.deprecationWarnings().get(0);
        assertThat(frozen.effectiveAt()).isEqualTo(firstEffective);
        assertThat(frozen.note()).isEqualTo("旧说明");
        assertThat(frozen.replacementVersion()).isEqualTo(2);
        assertThat(frozen.resolvedAt()).isEqualTo(T0);
        assertThat(queried.entries()).isEqualTo(historical.entries());
    }

    @Test
    void reparsingLockAtLaterInstantReAdjudicatesWhileOldLockStaysIntact() {
        seedAppLibChain();
        Instant effective = T0.plus(Duration.ofDays(5));
        service.registerDeprecation(rid(), deprecation("lib", 1, effective, 2, null));

        LockFileResponse before = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(before.deprecationWarnings()).hasSize(1);

        // 重解析时刻（新锁请求）到达生效点：闭包裁决为 422；旧锁仍为警告态。
        clock.setInstant(effective);
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        LockFileResponse oldLock = service.getLock(before.id());
        assertThat(oldLock.deprecationWarnings()).hasSize(1);
        assertThat(oldLock.deprecationWarnings().get(0).effectiveAt()).isEqualTo(effective);
    }

    // ------------------------------------------------------------------
    // 幂等：重放快照、异参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void deprecationRequestIdReplayReturnsSameSnapshot() {
        seedAppLibChain();
        String requestId = rid();
        DeprecationRequest request = deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, "x");
        DeprecationResponse first = service.registerDeprecation(requestId, request);
        DeprecationResponse replay = service.registerDeprecation(requestId, request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);
        // 重放不追加事件、不推进版本。
        assertThat(service.listDeprecationHistory()).hasSize(1);
    }

    @Test
    void deprecationSameRequestIdDifferentParamsReturns409() {
        seedAppLibChain();
        String requestId = rid();
        service.registerDeprecation(requestId,
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, null));
        assertThatThrownBy(() -> service.registerDeprecation(requestId,
                deprecation("lib", 1, T0.plus(Duration.ofDays(6)), 2, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void requestIdReusedAcrossDeprecationAndLockReturns409() {
        seedAppLibChain();
        String requestId = rid();
        service.registerDeprecation(requestId,
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, null));
        assertThatThrownBy(() -> service.createLock(requestId,
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedDeprecationDoesNotConsumeRequestId() {
        seedAppLibChain();
        String requestId = rid();
        // 替代版本不存在 → 422 失败，不占键。
        assertThatThrownBy(() -> service.registerDeprecation(requestId,
                deprecation("lib", 1, T0.plus(Duration.ofDays(1)), 9, null)))
                .isInstanceOf(ApiException.class);

        DeprecationResponse later = service.registerDeprecation(requestId,
                deprecation("lib", 1, T0.plus(Duration.ofDays(2)), 2, null));
        assertThat(later.version()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void revokeRequestIdReplayDoesNotDuplicateEvents() {
        seedAppLibChain();
        DeprecationResponse window = service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, null));
        String requestId = rid();
        DeprecationResponse first = service.revokeDeprecation(requestId, window.id());
        DeprecationResponse replay = service.revokeDeprecation(requestId, window.id());

        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.revokedAt()).isEqualTo(first.revokedAt());
        assertThat(service.listDeprecationHistory())
                .extracting("eventType")
                .containsExactly("REGISTERED", "REVOKED");
    }

    @Test
    void concurrentSameDeprecationRequestIdResolvesToSingleWindow() throws Exception {
        seedAppLibChain();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String requestId = rid();
        DeprecationRequest request = deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, null);

        List<Future<DeprecationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerDeprecation(requestId, request);
            }));
        }
        pool.shutdown();

        DeprecationResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<DeprecationResponse> future : futures) {
            DeprecationResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.id()).isEqualTo(first.id());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation_event", Integer.class)).isEqualTo(1);
        // 三个种子制品登记各占一条幂等记录；同参并发只额外产生一条弃用记录。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // 真实并发：弃用登记与锁定按提交顺序裁决，闭包与版本口径一致
    // ------------------------------------------------------------------

    @Test
    void concurrentDeprecationAndLockAdjudicateByCommitOrder() throws Exception {
        seedAppLibChain();
        long versionBefore = 3L;
        Instant effective = T0.plus(Duration.ofDays(10));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = rid();
        String deprecationRequestId = rid();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                // 锁定持有的乐观版本是并发开始前的 3。
                return service.createLock(lockRequestId, new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> deprecationTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.registerDeprecation(deprecationRequestId,
                    deprecation("lib", 1, effective, 2, null));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, deprecationTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object deprecationOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(deprecationOutcome).isInstanceOf(DeprecationResponse.class);
        assertThat(repositoryVersion()).isEqualTo(versionBefore + 1);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：登记后到，窗口待生效，锁必须固化一条警告且内容不改写。
            assertThat(lock.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(lock.deprecationWarnings()).hasSize(1);
            assertThat(lock.deprecationWarnings().get(0).effectiveAt()).isEqualTo(effective);
            assertThat(service.getLock(lock.id()).deprecationWarnings()).hasSize(1);
        } else {
            // 登记先提交：仓库版本推进，锁的乐观版本过期，必须 409，无半成品。
            assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(409));
            assertThat(service.listLocks()).isEmpty();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM lock_deprecation_warning", Integer.class)).isZero();
        }
    }

    // ------------------------------------------------------------------
    // 查询只读
    // ------------------------------------------------------------------

    @Test
    void queriesDoNotMutateState() {
        seedAppLibChain();
        DeprecationResponse window = service.registerDeprecation(rid(),
                deprecation("lib", 1, T0.plus(Duration.ofDays(5)), 2, "说明"));
        long version = repositoryVersion();

        service.listActiveDeprecations();
        service.getDeprecation(window.id());
        service.listDeprecationHistory();
        service.listLocks();

        assertThat(repositoryVersion()).isEqualTo(version);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void getUnknownDeprecationReturns404() {
        seedAppLibChain();
        assertThatThrownBy(() -> service.getDeprecation(404L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }
}
