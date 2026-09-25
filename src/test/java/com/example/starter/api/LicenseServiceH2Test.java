package com.example.starter.api;

import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LicensePolicyRequest;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MissingNoticeView;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeCoverageView;
import com.example.starter.api.dto.NoticeTextRequest;
import com.example.starter.api.dto.PolicyHitView;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 许可证告知与发布门禁的 H2（MODE=MySQL）数据库测试：
 * 覆盖策略作用域、依赖闭包命中路径、文本版本状态机、地区覆盖、
 * 批量原子发布、历史快照固化以及并发幂等边界。
 */
@SpringBootTest
class LicenseServiceH2Test {

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM publish_snapshot_entry");
        jdbcTemplate.update("DELETE FROM publish_snapshot_lock");
        jdbcTemplate.update("DELETE FROM publish_snapshot");
        jdbcTemplate.update("DELETE FROM license_policy");
        jdbcTemplate.update("DELETE FROM notice_text");
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

    private static RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    /** 构造 app:1 -> lib:1 -> util:1 依赖链并锁定，返回锁文件 ID（仓库版本 3）。 */
    private long createChainLock() {
        artifactService.registerArtifact(rid(), artifact("app", 1, dep("lib", 1, 1)));
        artifactService.registerArtifact(rid(), artifact("lib", 1, dep("util", 1, 1)));
        artifactService.registerArtifact(rid(), artifact("util", 1));
        return artifactService.createLock(rid(), new LockRequest("app", 1, 3L)).id();
    }

    private void approvedText(String textKey, int version, String... regions) {
        licenseService.registerNoticeText(rid(),
                new NoticeTextRequest(textKey, version, "合成告知文本", List.of(regions)));
        licenseService.approveNoticeText(rid(), textKey, version);
    }

    private static LicensePolicyRequest artifactPolicy(String name, Integer version,
                                                       String textKey, Integer textVersion) {
        return new LicensePolicyRequest("ARTIFACT", null, name, version,
                "NOTICE_REQUIRED", textKey, textVersion);
    }

    private static LicensePolicyRequest lockPolicy(long lockFileId, String textKey, Integer textVersion) {
        return new LicensePolicyRequest("LOCK_FILE", lockFileId, null, null,
                "NOTICE_REQUIRED", textKey, textVersion);
    }

    private long snapshotCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_snapshot", Long.class);
    }

    // ------------------------------------------------------------------
    // 告知文本：登记、状态机、地区规范化
    // ------------------------------------------------------------------

    @Test
    void registerNoticeTextNormalizesRegionsAndStartsAsDraft() {
        var text = licenseService.registerNoticeText(rid(),
                new NoticeTextRequest("apache-notice", 1, "内容", List.of(" us ", "cn", "US")));
        assertThat(text.status()).isEqualTo("DRAFT");
        assertThat(text.regions()).containsExactly("CN", "US");

        NoticeCoverageView coverage = licenseService.getNoticeCoverage("apache-notice", 1);
        assertThat(coverage.regions()).containsExactly("CN", "US");
        assertThat(coverage.status()).isEqualTo("DRAFT");
    }

    @Test
    void duplicateNoticeTextVersionReturns409() {
        licenseService.registerNoticeText(rid(),
                new NoticeTextRequest("apache-notice", 1, "内容", List.of("CN")));
        assertThatThrownBy(() -> licenseService.registerNoticeText(rid(),
                new NoticeTextRequest("apache-notice", 1, "其他", List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void noticeTextStateMachineRejectsIllegalTransitions() {
        licenseService.registerNoticeText(rid(),
                new NoticeTextRequest("t", 1, "内容", List.of("CN")));
        licenseService.approveNoticeText(rid(), "t", 1);
        // 重复批准 409。
        assertThatThrownBy(() -> licenseService.approveNoticeText(rid(), "t", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        licenseService.withdrawNoticeText(rid(), "t", 1);
        // 重复撤销 409；撤销后批准 409；撤销后缩窄 409。
        assertThatThrownBy(() -> licenseService.withdrawNoticeText(rid(), "t", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThatThrownBy(() -> licenseService.approveNoticeText(rid(), "t", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThatThrownBy(() -> licenseService.narrowNoticeTextRegions(rid(), "t", 1,
                new NarrowRegionsRequest(List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void narrowRegionsRequiresStrictSubsetOfCurrent() {
        approvedText("t", 1, "CN", "US");
        // 非子集 400。
        assertThatThrownBy(() -> licenseService.narrowNoticeTextRegions(rid(), "t", 1,
                new NarrowRegionsRequest(List.of("JP"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 未缩窄（相等）400。
        assertThatThrownBy(() -> licenseService.narrowNoticeTextRegions(rid(), "t", 1,
                new NarrowRegionsRequest(List.of("CN", "US"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));

        var narrowed = licenseService.narrowNoticeTextRegions(rid(), "t", 1,
                new NarrowRegionsRequest(List.of("us")));
        assertThat(narrowed.regions()).containsExactly("US");
        assertThat(licenseService.getNoticeCoverage("t", 1).regions()).containsExactly("US");
    }

    @Test
    void missingNoticeTextReturns404() {
        assertThatThrownBy(() -> licenseService.getNoticeCoverage("ghost", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> licenseService.approveNoticeText(rid(), "ghost", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 许可证策略：作用域校验与登记失败分支
    // ------------------------------------------------------------------

    @Test
    void policyRegistrationValidatesScopeAndBaseline() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN");

        // LOCK_FILE 作用域：锁文件必须存在。
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(), lockPolicy(999L, "t", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        // ARTIFACT 作用域：制品与版本必须存在。
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(),
                artifactPolicy("ghost", null, "t", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(),
                artifactPolicy("lib", 9, "t", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        // 绑定文本必须存在。
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(),
                artifactPolicy("lib", 1, "ghost", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        // 非法作用域 / 非法要求类型 / 坐标与锁文件混用 / 绑定缺半边：400。
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(),
                new LicensePolicyRequest("GROUP", null, "lib", null, "NOTICE_REQUIRED", null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(),
                new LicensePolicyRequest("ARTIFACT", null, "lib", null, "COPYLEFT", null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(),
                new LicensePolicyRequest("LOCK_FILE", lockId, "lib", null, "NOTICE_REQUIRED", null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> licenseService.registerPolicy(rid(),
                artifactPolicy("lib", 1, "t", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));

        var policy = licenseService.registerPolicy(rid(), lockPolicy(lockId, "t", 1));
        assertThat(policy.scopeType()).isEqualTo("LOCK_FILE");
        assertThat(licenseService.listPolicies()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 命中路径与缺失告知查询
    // ------------------------------------------------------------------

    @Test
    void policyHitsExposeTransitiveDependencyPaths() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN");
        licenseService.registerPolicy(rid(), artifactPolicy("util", null, "t", 1));
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        List<PolicyHitView> hits = licenseService.listPolicyHits(lockId);
        assertThat(hits).extracting("artifactName", "hitPath")
                .containsExactly(
                        tuple("lib", "app:1>lib:1"),
                        tuple("util", "app:1>lib:1>util:1"));
        // 版本限定的策略不匹配其他版本：lib 策略限定版本 1，命中；若限定 2 则无命中。
        assertThat(hits).allSatisfy(h -> assertThat(h.lockFileId()).isEqualTo(lockId));
    }

    @Test
    void missingNoticeQueryReportsDistinguishableReasons() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN");
        // util：未绑定文本 -> MISSING_NOTICE；lib：文本地区不含 US -> NOTICE_REGION_NOT_COVERED。
        licenseService.registerPolicy(rid(), artifactPolicy("util", null, null, null));
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        List<MissingNoticeView> missing = licenseService.listMissingNotices(lockId, List.of("CN", "US"));
        assertThat(missing).extracting("artifactName", "reason", "hitPath")
                .containsExactly(
                        tuple("lib", "NOTICE_REGION_NOT_COVERED", "app:1>lib:1"),
                        tuple("util", "MISSING_NOTICE", "app:1>lib:1>util:1"));
        // 仅目标 CN 时 lib 覆盖、util 仍缺失。
        List<MissingNoticeView> cnOnly = licenseService.listMissingNotices(lockId, List.of("CN"));
        assertThat(cnOnly).extracting("artifactName", "reason")
                .containsExactly(tuple("util", "MISSING_NOTICE"));
    }

    @Test
    void hitAndMissingQueriesOnMissingLockReturn404() {
        assertThatThrownBy(() -> licenseService.listPolicyHits(999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> licenseService.listMissingNotices(999L, List.of("CN")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 发布门禁：主流程与失败分支
    // ------------------------------------------------------------------

    @Test
    void publishLocksFreezesSnapshotWithNoticeDetails() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN", "US");
        var policy = licenseService.registerPolicy(rid(), artifactPolicy("util", null, "t", 1));

        PublishResponse response = licenseService.publishLocks(
                new PublishRequest("release-1", List.of(lockId), List.of("cn")));

        assertThat(response.noticeKey()).isEqualTo("release-1");
        assertThat(response.targetRegions()).containsExactly("CN");
        assertThat(response.locks()).hasSize(1);
        var lockView = response.locks().get(0);
        assertThat(lockView.lockFileId()).isEqualTo(lockId);
        assertThat(lockView.repositoryVersion()).isEqualTo(3L);
        // app/lib 无命中策略各一行（许可字段为空），util 一行固化文本版本与地区。
        assertThat(lockView.entries()).extracting("artifactName", "policyId", "textKey", "textVersion")
                .containsExactlyInAnyOrder(
                        tuple("app", null, null, null),
                        tuple("lib", null, null, null),
                        tuple("util", policy.id(), "t", 1));
        var utilEntry = lockView.entries().stream()
                .filter(e -> e.artifactName().equals("util")).findFirst().orElseThrow();
        assertThat(utilEntry.noticeRegions()).containsExactly("CN", "US");
        assertThat(utilEntry.hitPath()).isEqualTo("app:1>lib:1>util:1");

        // 历史快照查询一致。
        PublishResponse queried = licenseService.getPublish(response.snapshotId());
        assertThat(queried).isEqualTo(response);
        assertThat(licenseService.listPublishes()).hasSize(1);
    }

    @Test
    void publishWithUnboundPolicyReturns422MissingNoticeAndPersistsNothing() {
        long lockId = createChainLock();
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, null, null));

        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("k1", List.of(lockId), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("MISSING_NOTICE");
                    assertThat(e.getMessage()).contains("app:1>lib:1");
                });
        assertThat(snapshotCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM publish_snapshot_entry", Long.class)).isZero();
    }

    @Test
    void publishWithDraftTextReturns422NotApproved() {
        long lockId = createChainLock();
        licenseService.registerNoticeText(rid(),
                new NoticeTextRequest("t", 1, "内容", List.of("CN")));
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("k1", List.of(lockId), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("NOTICE_TEXT_NOT_APPROVED");
                });
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void publishWithUncoveredRegionReturns422RegionNotCovered() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN");
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("k1", List.of(lockId), List.of("CN", "US"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("NOTICE_REGION_NOT_COVERED");
                });
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void batchPublishIsAtomicAcrossLockFiles() {
        long okLock = createChainLock();
        // 第二张锁定图：单独根 util:1。
        long badLock = artifactService.createLock(rid(), new LockRequest("util", 1, 3L)).id();
        approvedText("t", 1, "CN");
        licenseService.registerPolicy(rid(), lockPolicy(okLock, "t", 1));
        licenseService.registerPolicy(rid(), lockPolicy(badLock, null, null));

        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("batch-1", List.of(okLock, badLock), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("MISSING_NOTICE");
                });
        // 整批不发布任何部分。
        assertThat(snapshotCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM publish_snapshot_lock", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM publish_snapshot_entry", Long.class)).isZero();
    }

    @Test
    void publishRequestValidationRejectsBadInput() {
        long lockId = createChainLock();
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest(" ", List.of(lockId), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("k", List.of(), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("k", List.of(lockId, lockId), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("k", List.of(lockId), List.of("not a region!"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 不存在的锁文件 404，且不留半成品。
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("k", List.of(lockId, 999L), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThat(snapshotCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 历史快照固化：后续文本/策略变化不改写
    // ------------------------------------------------------------------

    @Test
    void snapshotIsFrozenWhenTextWithdrawnOrRegionsNarrowedAfterwards() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN", "US");
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));
        PublishResponse published = licenseService.publishLocks(
                new PublishRequest("release-1", List.of(lockId), List.of("CN")));

        // 撤销文本：只影响后续发布。
        licenseService.withdrawNoticeText(rid(), "t", 1);
        PublishResponse snapshot = licenseService.getPublish(published.snapshotId());
        assertThat(snapshot).isEqualTo(published);
        var libEntry = snapshot.locks().get(0).entries().stream()
                .filter(e -> e.artifactName().equals("lib")).findFirst().orElseThrow();
        assertThat(libEntry.textKey()).isEqualTo("t");
        assertThat(libEntry.noticeRegions()).containsExactly("CN", "US");
        // 后续发布同一锁定图：文本已撤销 -> 422。
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("release-2", List.of(lockId), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("NOTICE_TEXT_NOT_APPROVED");
                });
    }

    @Test
    void narrowedRegionsOnlyAffectSubsequentPublishes() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN", "US");
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));
        PublishResponse first = licenseService.publishLocks(
                new PublishRequest("r1", List.of(lockId), List.of("CN", "US")));

        // 缩窄为仅 US：历史快照仍固化 CN,US。
        licenseService.narrowNoticeTextRegions(rid(), "t", 1, new NarrowRegionsRequest(List.of("US")));
        var entry = licenseService.getPublish(first.snapshotId()).locks().get(0).entries().stream()
                .filter(e -> e.artifactName().equals("lib")).findFirst().orElseThrow();
        assertThat(entry.noticeRegions()).containsExactly("CN", "US");

        // 后续发布目标 CN -> 422；目标 US -> 成功。
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("r2", List.of(lockId), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("NOTICE_REGION_NOT_COVERED");
                });
        PublishResponse usOnly = licenseService.publishLocks(
                new PublishRequest("r3", List.of(lockId), List.of("US")));
        var usEntry = usOnly.locks().get(0).entries().stream()
                .filter(e -> e.artifactName().equals("lib")).findFirst().orElseThrow();
        assertThat(usEntry.noticeRegions()).containsExactly("US");
    }

    // ------------------------------------------------------------------
    // noticeKey 幂等：同键同参重放、异参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void sameNoticeKeySameParamsReplaysFirstResponse() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN");
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        PublishRequest request = new PublishRequest("release-1", List.of(lockId), List.of("CN"));
        PublishResponse first = licenseService.publishLocks(request);
        PublishResponse replay = licenseService.publishLocks(request);

        assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = 'release-1'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void sameNoticeKeyDifferentParamsReturns409() {
        long lockId = createChainLock();
        approvedText("t", 1, "CN", "US");
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));
        licenseService.publishLocks(new PublishRequest("release-1", List.of(lockId), List.of("CN")));

        // 异地区 409。
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("release-1", List.of(lockId), List.of("US"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(snapshotCount()).isEqualTo(1);
    }

    @Test
    void failedPublishDoesNotConsumeNoticeKey() {
        long lockId = createChainLock();
        licenseService.registerNoticeText(rid(),
                new NoticeTextRequest("t", 1, "内容", List.of("CN")));
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        // 文本未批准 -> 422，不占键。
        assertThatThrownBy(() -> licenseService.publishLocks(
                new PublishRequest("release-1", List.of(lockId), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = 'release-1'",
                Long.class)).isZero();

        // 批准后同键重试成功。
        licenseService.approveNoticeText(rid(), "t", 1);
        PublishResponse response = licenseService.publishLocks(
                new PublishRequest("release-1", List.of(lockId), List.of("CN")));
        assertThat(response.snapshotId()).isPositive();
        assertThat(snapshotCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发：同键发布、发布与文本变更按提交顺序裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentPublishSameNoticeKeyResolvesToSingleSnapshot() throws Exception {
        long lockId = createChainLock();
        approvedText("t", 1, "CN");
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        PublishRequest request = new PublishRequest("release-x", List.of(lockId), List.of("CN"));

        List<Future<PublishResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return licenseService.publishLocks(request);
            }));
        }
        pool.shutdown();

        PublishResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<PublishResponse> future : futures) {
            PublishResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.snapshotId()).isEqualTo(first.snapshotId());
            assertThat(response.createdAt()).isEqualTo(first.createdAt());
        }
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = 'release-x'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentPublishAndTextWithdrawAreSerializedWithoutPartialState() throws Exception {
        long lockId = createChainLock();
        approvedText("t", 1, "CN");
        licenseService.registerPolicy(rid(), artifactPolicy("lib", 1, "t", 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String publishKey = "release-race";

        Callable<Object> publishTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return licenseService.publishLocks(
                        new PublishRequest(publishKey, List.of(lockId), List.of("CN")));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return licenseService.withdrawNoticeText(rid(), "t", 1);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(publishTask, withdrawTask));
        pool.shutdown();
        Object publishOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(com.example.starter.api.dto.NoticeTextResponse.class);
        if (publishOutcome instanceof PublishResponse published) {
            // 发布先提交：快照固化，后续文本撤销不改写。
            PublishResponse snapshot = licenseService.getPublish(published.snapshotId());
            assertThat(snapshot).isEqualTo(published);
        } else {
            // 撤销先提交：发布 422 且无半成品。
            assertThat(publishOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) publishOutcome).getStatus()).isEqualTo(422);
            assertThat(((ApiException) publishOutcome).getCode()).isEqualTo("NOTICE_TEXT_NOT_APPROVED");
            assertThat(snapshotCount()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // 数据库边界：唯一约束实际生效
    // ------------------------------------------------------------------

    @Test
    void uniqueConstraintOnNoticeTextKeyAndVersionIsEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO notice_text (text_key, version, content, regions, status, "
                + "created_at, updated_at) VALUES ('x', 1, 'c', 'CN', 'DRAFT', "
                + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notice_text (text_key, version, content, regions, status, "
                        + "created_at, updated_at) VALUES ('x', 1, 'c', 'CN', 'DRAFT', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void uniqueConstraintOnPublishNoticeKeyIsEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO publish_snapshot (notice_key, target_regions, created_at) "
                + "VALUES ('k', 'CN', CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO publish_snapshot (notice_key, target_regions, created_at) "
                        + "VALUES ('k', 'CN', CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
