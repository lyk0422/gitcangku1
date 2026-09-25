package com.example.starter.api;

import com.example.starter.api.dto.AdvisoryRequest;
import com.example.starter.api.dto.ExceptionRequest;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PublishSnapshotResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.VulnerabilityHitResponse;
import com.example.starter.support.ApiException;
import com.example.starter.support.GateBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
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
 * 制品漏洞豁免与发布门禁的 H2（MODE=MySQL）业务与事务测试：
 * 精确作用域、双人豁免、到期门禁、多漏洞整次拒绝、公告更新裁决以及并发/幂等边界。
 */
@SpringBootTest
class SecurityServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    private static final String CVE_LIB = "CVE-LIB-1";
    private static final String CVE_UTIL = "CVE-UTIL-1";
    private static final String CVE_HIGH = "CVE-HIGH-1";
    private static final String CVE_PAST = "CVE-PAST-1";

    @Autowired
    private SecurityService securityService;
    @Autowired
    private ArtifactService artifactService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;

    @TestConfiguration
    static class FixedClockConfig {
        /** 同时以 MutableClock 与 Clock 类型注册，@Primary 覆盖生产时钟，不触发同名 Bean 覆盖。 */
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @BeforeEach
    void reset() {
        clock.setInstant(T0);
        jdbcTemplate.update("DELETE FROM publish_snapshot_entry");
        jdbcTemplate.update("DELETE FROM publish_snapshot");
        jdbcTemplate.update("DELETE FROM vulnerability_exception");
        jdbcTemplate.update("DELETE FROM vulnerability_advisory");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 构造含 app/lib/util 三个精确条目的锁定图：app:1 -> lib[1,1], util[1,1]。 */
    private LockFileData createLockedGraph() {
        artifactService.registerArtifact(rid(), new RegisterArtifactRequest(
                "app", 1, List.of(
                new com.example.starter.api.dto.DependencySpec("lib", 1, 1),
                new com.example.starter.api.dto.DependencySpec("util", 1, 1))));
        artifactService.registerArtifact(rid(), new RegisterArtifactRequest("lib", 1, List.of()));
        artifactService.registerArtifact(rid(), new RegisterArtifactRequest("util", 1, List.of()));
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("app", 1, 3L));
        return new LockFileData(lock);
    }

    private record LockFileData(LockFileResponse lock) {
        long id() {
            return lock.id();
        }
    }

    private AdvisoryRequest advisory(String vid, String name, int version, String severity,
                                     Instant expiresAt) {
        return new AdvisoryRequest(vid, name, version, severity, expiresAt);
    }

    /** 默认两条未过期 CRITICAL（lib/util）+ 一条 HIGH(app) + 一条已过期 CRITICAL(app)。 */
    private void seedDefaultAdvisories() {
        securityService.upsertAdvisory(advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        securityService.upsertAdvisory(advisory(CVE_UTIL, "util", 1, "CRITICAL", T0.plusSeconds(3600)));
        securityService.upsertAdvisory(advisory(CVE_HIGH, "app", 1, "HIGH", T0.plusSeconds(3600)));
        securityService.upsertAdvisory(advisory(CVE_PAST, "app", 1, "CRITICAL", T0.minusSeconds(60)));
    }

    private ExceptionRequest exceptionRequest(long lockId, String vid, Instant expiresAt) {
        return new ExceptionRequest(lockId, vid, expiresAt, "已评估风险，临时豁免");
    }

    private ExceptionResponse firstReview(long lockId, String vid, String reviewer) {
        return securityService.confirmException(rid(),
                exceptionRequest(lockId, vid, T0.plusSeconds(3600)), reviewer);
    }

    private ExceptionResponse secondReview(long lockId, String vid, String reviewer) {
        return securityService.confirmException(rid(),
                exceptionRequest(lockId, vid, T0.plusSeconds(3600)), reviewer);
    }

    private GateBlockedException expectGateBlocked(Runnable action) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                action::run, GateBlockedException.class);
    }

    // ------------------------------------------------------------------
    // 主流程：默认拒绝 + 命中查询
    // ------------------------------------------------------------------

    @Test
    void publishBlockedByDefaultListsAllUnexpiredCriticalHits() {
        LockFileData graph = createLockedGraph();
        seedDefaultAdvisories();

        GateBlockedException ex = expectGateBlocked(
                () -> securityService.publish(rid(), graph.id()));
        assertThat(ex.getStatus()).isEqualTo(422);
        // 列出全部命中制品：lib/util 两个未过期 CRITICAL；HIGH 与已过期公告不阻断。
        assertThat(ex.getBlocked()).extracting("vulnerabilityId", "artifactName", "artifactVersion")
                .containsExactlyInAnyOrder(
                        tuple(CVE_LIB, "lib", 1), tuple(CVE_UTIL, "util", 1));
        assertThat(securityService.listPublishes(graph.id())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_snapshot", Integer.class))
                .isZero();
    }

    @Test
    void vulnerabilityHitsQueryReportsAllActiveAdvisoriesAndNoneScope() {
        LockFileData graph = createLockedGraph();
        seedDefaultAdvisories();

        List<VulnerabilityHitResponse> hits = securityService.listVulnerabilityHits(graph.id());
        // 命中包含全部未过期公告（含 HIGH），已过期 CVE_PAST 不出现。
        assertThat(hits).extracting("vulnerabilityId", "severity", "exceptionStatus")
                .containsExactlyInAnyOrder(
                        tuple(CVE_LIB, "CRITICAL", "NONE"),
                        tuple(CVE_UTIL, "CRITICAL", "NONE"),
                        tuple(CVE_HIGH, "HIGH", "NONE"));
    }

    // ------------------------------------------------------------------
    // 双人豁免
    // ------------------------------------------------------------------

    @Test
    void singleReviewerLeavesPendingAndStillBlocks() {
        LockFileData graph = createLockedGraph();
        seedDefaultAdvisories();

        ExceptionResponse pending = firstReview(graph.id(), CVE_LIB, "alice");
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.reviewer1()).isEqualTo("alice");
        assertThat(pending.reviewer2()).isNull();
        assertThat(pending.confirmedAt()).isNull();

        // 同一审核人重复确认不能成为第二审核人。
        ExceptionResponse replay = firstReview(graph.id(), CVE_LIB, "alice");
        assertThat(replay.id()).isEqualTo(pending.id());
        assertThat(replay.status()).isEqualTo("PENDING");

        expectGateBlocked(() -> securityService.publish(rid(), graph.id()));
    }

    @Test
    void twoDifferentReviewersConfirmImmutableSnapshotAndAllowPublish() {
        LockFileData graph = createLockedGraph();
        seedDefaultAdvisories();
        firstReview(graph.id(), CVE_LIB, "alice");
        firstReview(graph.id(), CVE_UTIL, "alice");

        ExceptionResponse libConfirmed = secondReview(graph.id(), CVE_LIB, "bob");
        ExceptionResponse utilConfirmed = secondReview(graph.id(), CVE_UTIL, "bob");
        assertThat(libConfirmed.status()).isEqualTo("CONFIRMED");
        assertThat(libConfirmed.reviewer2()).isEqualTo("bob");
        assertThat(libConfirmed.confirmedAt()).isEqualTo(T0);

        PublishSnapshotResponse published = securityService.publish(rid(), graph.id());
        assertThat(published.lockFileId()).isEqualTo(graph.id());
        assertThat(published.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1), tuple("util", 1));

        List<VulnerabilityHitResponse> hits = securityService.listVulnerabilityHits(graph.id());
        assertThat(hits).filteredOn(h -> h.vulnerabilityId().equals(CVE_LIB))
                .singleElement().extracting("exceptionStatus").isEqualTo("CONFIRMED");

        // 已确认双人快照不可变：第三审核人不能追加。
        assertThatThrownBy(() -> secondReview(graph.id(), CVE_LIB, "carol"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 精确作用域
    // ------------------------------------------------------------------

    @Test
    void exceptionIsScopedToExactLockGraphVersionAndNotReusedAcrossGraphs() {
        LockFileData graph1 = createLockedGraph();
        seedDefaultAdvisories();
        // 同一根、同一条目重新解析生成另一个精确锁定图版本（不同 ID）。
        LockFileResponse lock2 = artifactService.createLock(rid(), new LockRequest("app", 1, 3L));

        firstReview(graph1.id(), CVE_LIB, "alice");
        secondReview(graph1.id(), CVE_LIB, "bob");

        // 豁免不跨锁定图复用：graph2 上该漏洞作用域为 NONE，发布仍被拦截。
        List<VulnerabilityHitResponse> graph2Hits = securityService.listVulnerabilityHits(lock2.id());
        assertThat(graph2Hits).filteredOn(h -> h.vulnerabilityId().equals(CVE_LIB))
                .singleElement().extracting("exceptionStatus").isEqualTo("NONE");
        GateBlockedException ex = expectGateBlocked(() -> securityService.publish(rid(), lock2.id()));
        assertThat(ex.getBlocked()).extracting("vulnerabilityId")
                .contains(CVE_LIB, CVE_UTIL);
    }

    // ------------------------------------------------------------------
    // 到期门禁
    // ------------------------------------------------------------------

    @Test
    void expiredExceptionBlocksNewPublishButKeepsHistoricalSnapshot() {
        LockFileData graph = createLockedGraph();
        seedDefaultAdvisories();
        // 豁免 10 秒后到期。
        securityService.confirmException(rid(),
                new ExceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(10), "临时豁免"), "alice");
        securityService.confirmException(rid(),
                new ExceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(10), "临时豁免"), "bob");
        securityService.confirmException(rid(),
                new ExceptionRequest(graph.id(), CVE_UTIL, T0.plusSeconds(10), "临时豁免"), "alice");
        securityService.confirmException(rid(),
                new ExceptionRequest(graph.id(), CVE_UTIL, T0.plusSeconds(10), "临时豁免"), "bob");

        PublishSnapshotResponse first = securityService.publish(rid(), graph.id());

        clock.advanceSeconds(11);
        assertThat(securityService.listVulnerabilityHits(graph.id()))
                .filteredOn(h -> h.vulnerabilityId().equals(CVE_LIB))
                .singleElement().extracting("exceptionStatus").isEqualTo("EXPIRED");
        expectGateBlocked(() -> securityService.publish(rid(), graph.id()));

        // 历史发布快照不被到期改写。
        List<PublishSnapshotResponse> publishes = securityService.listPublishes(graph.id());
        assertThat(publishes).hasSize(1);
        assertThat(publishes.get(0).id()).isEqualTo(first.id());
        assertThat(publishes.get(0).entries()).isEqualTo(first.entries());
    }

    @Test
    void expiredAdvisoryNoLongerBlocksAfterItExpires() {
        LockFileData graph = createLockedGraph();
        // 仅一条 CRITICAL，5 秒后到期。
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(5)));
        expectGateBlocked(() -> securityService.publish(rid(), graph.id()));

        clock.advanceSeconds(6);
        assertThat(securityService.listVulnerabilityHits(graph.id())).isEmpty();
        // 无未过期 CRITICAL：发布成功。
        assertThat(securityService.publish(rid(), graph.id())).isNotNull();
    }

    // ------------------------------------------------------------------
    // 撤销
    // ------------------------------------------------------------------

    @Test
    void revokeOnlyAffectsFuturePublishesAndKeepsSnapshot() {
        LockFileData graph = createLockedGraph();
        seedDefaultAdvisories();
        firstReview(graph.id(), CVE_LIB, "alice");
        ExceptionResponse lib = secondReview(graph.id(), CVE_LIB, "bob");
        firstReview(graph.id(), CVE_UTIL, "alice");
        secondReview(graph.id(), CVE_UTIL, "bob");
        assertThat(lib.status()).isEqualTo("CONFIRMED");

        PublishSnapshotResponse before = securityService.publish(rid(), graph.id());

        ExceptionResponse revoked = securityService.revokeException(rid(), lib.id(), "alice");
        assertThat(revoked.status()).isEqualTo("REVOKED");

        GateBlockedException ex = expectGateBlocked(() -> securityService.publish(rid(), graph.id()));
        assertThat(ex.getBlocked()).extracting("vulnerabilityId").containsExactly(CVE_LIB);
        // 撤销不改写已成功快照。
        List<PublishSnapshotResponse> publishes = securityService.listPublishes(graph.id());
        assertThat(publishes).hasSize(1);
        assertThat(publishes.get(0).id()).isEqualTo(before.id());

        // 重复撤销 409。
        assertThatThrownBy(() -> securityService.revokeException(rid(), lib.id(), "alice"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 第二人确认时再次校验
    // ------------------------------------------------------------------

    @Test
    void secondConfirmationRechecksAdvisoryStillHitsAndFutureExpiry() {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        firstReview(graph.id(), CVE_LIB, "alice");

        // 公告在第二人确认前更新为已过期 → 漏洞不再命中 → 422。
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.minusSeconds(1)));
        String bobRequest = rid();
        assertThatThrownBy(() -> securityService.confirmException(bobRequest,
                        exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600)), "bob"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 失败不占 requestId、不产生 CONFIRMED。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, bobRequest)).isZero();
        assertThat(securityService.listExceptions(graph.id())).singleElement()
                .extracting(ExceptionResponse::status).isEqualTo("PENDING");

        // 公告恢复未过期后，同一 requestId 可用于成功确认（失败未占键）。
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        ExceptionResponse confirmed = securityService.confirmException(bobRequest,
                exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600)), "bob");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void secondConfirmationAfterExceptionExpiryRejected() {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        securityService.confirmException(rid(),
                new ExceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(10), "临时"), "alice");

        clock.advanceSeconds(11);
        String bobRequest = rid();
        assertThatThrownBy(() -> securityService.confirmException(bobRequest,
                        new ExceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(10), "临时"), "bob"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, bobRequest)).isZero();
        assertThat(securityService.listExceptions(graph.id())).singleElement()
                .extracting(ExceptionResponse::status).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------
    // 多漏洞：缺一整次拒绝
    // ------------------------------------------------------------------

    @Test
    void multipleVulnerabilitiesEachNeedValidExceptionMissingOneRejectsWhole() {
        LockFileData graph = createLockedGraph();
        seedDefaultAdvisories();
        // 仅为 CVE_LIB 完成双人豁免，CVE_UTIL 缺失。
        firstReview(graph.id(), CVE_LIB, "alice");
        secondReview(graph.id(), CVE_LIB, "bob");

        GateBlockedException ex = expectGateBlocked(() -> securityService.publish(rid(), graph.id()));
        assertThat(ex.getBlocked()).extracting("vulnerabilityId").containsExactly(CVE_UTIL);

        firstReview(graph.id(), CVE_UTIL, "alice");
        secondReview(graph.id(), CVE_UTIL, "bob");
        assertThat(securityService.publish(rid(), graph.id())).isNotNull();
    }

    // ------------------------------------------------------------------
    // 公告更新裁决：CRITICAL 降级为 HIGH 后不再拦截
    // ------------------------------------------------------------------

    @Test
    void advisoryUpdateDowngradingSeverityRelievesGate() {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        expectGateBlocked(() -> securityService.publish(rid(), graph.id()));

        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "HIGH", T0.plusSeconds(3600)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM vulnerability_advisory", Integer.class)).isEqualTo(1);
        assertThat(securityService.publish(rid(), graph.id())).isNotNull();
    }

    // ------------------------------------------------------------------
    // 幂等：exceptionKey 与发布 requestId
    // ------------------------------------------------------------------

    @Test
    void sameExceptionKeyReplaysFirstResultAcrossRequestsAndDoesNotDuplicate() {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        ExceptionRequest request = exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600));

        ExceptionResponse a1 = securityService.confirmException(rid(), request, "alice");
        // 不同 requestId、同指纹：重放首次 PENDING 结果，不新增行。
        ExceptionResponse a2 = securityService.confirmException(rid(), request, "alice");
        assertThat(a2.id()).isEqualTo(a1.id());
        assertThat(a2.status()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM vulnerability_exception", Integer.class)).isEqualTo(1);

        ExceptionResponse b1 = securityService.confirmException(rid(), request, "bob");
        ExceptionResponse b2 = securityService.confirmException(rid(), request, "bob");
        assertThat(b1.id()).isEqualTo(a1.id());
        assertThat(b1.status()).isEqualTo("CONFIRMED");
        assertThat(b2.id()).isEqualTo(b1.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM vulnerability_exception", Integer.class)).isEqualTo(1);
    }

    @Test
    void failedPublishDoesNotConsumeRequestIdWhichLaterSucceeds() {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        String publishRequest = rid();
        expectGateBlocked(() -> securityService.publish(publishRequest, graph.id()));
        // 失败不占 requestId。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, publishRequest)).isZero();

        firstReview(graph.id(), CVE_LIB, "alice");
        secondReview(graph.id(), CVE_LIB, "bob");
        // 同一 requestId 完成豁免后成功发布。
        PublishSnapshotResponse published = securityService.publish(publishRequest, graph.id());
        assertThat(published).isNotNull();

        // 再次重放返回同一快照，不重复写。
        assertThat(securityService.publish(publishRequest, graph.id()).id())
                .isEqualTo(published.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM publish_snapshot", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentSecondReviewersResolveToOneConfirmationByCommitOrder() throws Exception {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        securityService.confirmException(rid(), exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600)), "alice");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        String[] reviewers = {"bob", "carol", "dave", "erin"};
        for (int i = 0; i < threads; i++) {
            final String reviewer = reviewers[i];
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    securityService.confirmException(rid(),
                            exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600)), reviewer);
                    return 200;
                } catch (ApiException e) {
                    return e.getStatus();
                }
            }));
        }
        pool.shutdown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : futures) {
            int status = f.get(30, TimeUnit.SECONDS);
            assertThat(status).isIn(200, 409);
            if (status == 200) {
                ok++;
            } else {
                conflict++;
            }
        }
        // 仅一名第二审核人能把 PENDING 升级为 CONFIRMED。
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        ExceptionResponse finalState = securityService.listExceptions(graph.id()).get(0);
        assertThat(finalState.status()).isEqualTo("CONFIRMED");
        assertThat(finalState.reviewer2()).isIn((Object[]) reviewers);
    }

    @Test
    void concurrentSameReviewerSameKeyAllReplaySingleConfirmation() throws Exception {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        securityService.confirmException(rid(), exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600)), "alice");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String bobRequest = rid();
        ExceptionRequest request = exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600));
        List<Future<ExceptionResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return securityService.confirmException(bobRequest, request, "bob");
            }));
        }
        pool.shutdown();
        ExceptionResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<ExceptionResponse> f : futures) {
            ExceptionResponse r = f.get(30, TimeUnit.SECONDS);
            assertThat(r.id()).isEqualTo(first.id());
            assertThat(r.status()).isEqualTo("CONFIRMED");
            assertThat(r.reviewer2()).isEqualTo("bob");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM vulnerability_exception", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentPublishWithValidExceptionProducesDistinctSnapshotsAndCommitOrder() throws Exception {
        LockFileData graph = createLockedGraph();
        securityService.upsertAdvisory(
                advisory(CVE_LIB, "lib", 1, "CRITICAL", T0.plusSeconds(3600)));
        securityService.confirmException(rid(), exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600)), "alice");
        securityService.confirmException(rid(), exceptionRequest(graph.id(), CVE_LIB, T0.plusSeconds(3600)), "bob");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String requestId = rid();
            futures.add(pool.submit((Callable<Object>) () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return securityService.publish(requestId, graph.id());
                } catch (GateBlockedException e) {
                    return e;
                }
            }));
        }
        pool.shutdown();
        for (Future<Object> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isInstanceOf(PublishSnapshotResponse.class);
        }
        assertThat(securityService.listPublishes(graph.id())).hasSize(threads);
    }
}
