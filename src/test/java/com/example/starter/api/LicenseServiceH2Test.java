package com.example.starter.api;

import com.example.starter.api.dto.BindNoticeRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LicenseCheckView;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeBindingResponse;
import com.example.starter.api.dto.NoticeTextResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterNoticeTextRequest;
import com.example.starter.api.dto.RegisterPolicyRequest;
import com.example.starter.api.dto.LicensePolicyResponse;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
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
 * 基于嵌入式 H2（MODE=MySQL）的许可证告知与发布门禁数据库测试：
 * 覆盖策略作用域、依赖闭包路径、文本版本状态、地区覆盖、批量原子发布、
 * 历史快照固化以及发布 noticeKey 的并发幂等边界。
 */
@SpringBootTest
class LicenseServiceH2Test {

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private void registerArtifact(String name, int version, DependencySpec... deps) {
        artifactService.registerArtifact(rid(),
                new RegisterArtifactRequest(name, version, List.of(deps)));
    }

    /**
     * 构建标准图：app:1 -> lib[1,1]、util[1,1]；lib:1 -> trans[1,1]。
     * 闭包：app（根）、lib/util（直接）、trans（传递）。注册 4 个制品后仓库版本为 4。
     */
    private LockFileResponse standardGraphLockedAt4() {
        registerArtifact("app", 1, dep("lib", 1, 1), dep("util", 1, 1));
        registerArtifact("lib", 1, dep("trans", 1, 1));
        registerArtifact("util", 1);
        registerArtifact("trans", 1);
        return artifactService.createLock(rid(), new LockRequest("app", 1, 4L));
    }

    private LicensePolicyResponse coordinatePolicy(String name, Integer version,
                                                   String license, String action) {
        return licenseService.registerPolicy(rid(), new RegisterPolicyRequest(
                "COORDINATE", null, name, version, license, action));
    }

    private LicensePolicyResponse lockPolicy(long lockFileId, String name,
                                             String license, String action) {
        return licenseService.registerPolicy(rid(), new RegisterPolicyRequest(
                "LOCK", lockFileId, name, null, license, action));
    }

    private NoticeTextResponse createApprovedNotice(String key, int version, String license,
                                                    List<String> regions) {
        licenseService.registerNoticeText(rid(), new RegisterNoticeTextRequest(
                key, version, license, "notice-body-" + key + "-" + version, regions));
        return licenseService.approveNoticeText(rid(), key, version);
    }

    private NoticeBindingResponse bindCoordinate(String name, Integer version, String license,
                                                 String key, int textVersion) {
        return licenseService.bindNotice(rid(), new BindNoticeRequest(
                "COORDINATE", null, name, version, license, key, textVersion));
    }

    private NoticeBindingResponse bindLock(long lockFileId, String name, String license,
                                           String key, int textVersion) {
        return licenseService.bindNotice(rid(), new BindNoticeRequest(
                "LOCK", lockFileId, name, null, license, key, textVersion));
    }

    private ReleaseRequest releaseRequest(List<Long> lockIds, List<String> regions) {
        return new ReleaseRequest(lockIds, regions);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM release_snapshot_entry");
        jdbcTemplate.update("DELETE FROM release_snapshot_item");
        jdbcTemplate.update("DELETE FROM release_snapshot");
        jdbcTemplate.update("DELETE FROM license_notice_binding");
        jdbcTemplate.update("DELETE FROM license_notice_text");
        jdbcTemplate.update("DELETE FROM license_policy");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    // ------------------------------------------------------------------
    // 主流程：策略作用域 + 传递闭包路径 + 发布快照
    // ------------------------------------------------------------------

    @Test
    void releaseWithTransitiveNoticeSucceedsAndPersistsSnapshot() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("trans", null, "GPL-3.0", "NOTICE_REQUIRED");
        createApprovedNotice("gpl", 1, "GPL-3.0", List.of("CN", "US"));
        bindCoordinate("trans", null, "GPL-3.0", "gpl", 1);

        LicenseCheckView check = licenseService.checkLock(lock.id(), List.of("cn"));
        assertThat(check.missing()).isEmpty();
        assertThat(check.hits()).extracting("name", "licenseId", "action")
                .containsExactly(tuple("trans", "GPL-3.0", "NOTICE_REQUIRED"));
        assertThat(check.hits().get(0).direct()).isFalse();
        assertThat(check.hits().get(0).path())
                .containsExactly("app:1", "lib:1", "trans:1");

        ReleaseSnapshotResponse release = licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("cn")));
        assertThat(release.items()).hasSize(1);
        ReleaseSnapshotResponse.ReleasedItem item = release.items().get(0);
        assertThat(item.regions()).containsExactly("CN");
        assertThat(item.entries()).hasSize(4);
        assertThat(item.entries()).extracting("name", "version", "direct", "licenseId",
                "noticeKey", "noticeVersion")
                .contains(
                        tuple("app", 1, true, null, null, null),
                        tuple("lib", 1, true, null, null, null),
                        tuple("trans", 1, false, "GPL-3.0", "gpl", 1),
                        tuple("util", 1, true, null, null, null));
        ReleaseSnapshotResponse.ReleasedEntry transEntry = item.entries().stream()
                .filter(e -> e.name().equals("trans")).findFirst().orElseThrow();
        assertThat(transEntry.noticeRegions()).containsExactly("CN", "US");

        // 历史查询一致。
        assertThat(licenseService.listReleases()).hasSize(1);
        assertThat(licenseService.getRelease(release.releaseId()).items().get(0).entries())
                .isEqualTo(item.entries());
    }

    @Test
    void lockScopedPolicyAndBindingOnlyAffectThatLock() {
        LockFileResponse lock1 = standardGraphLockedAt4();
        // 另一个独立图 app2:1，无依赖；注册后仓库版本 5。
        registerArtifact("app2", 1);
        LockFileResponse lock2 = artifactService.createLock(rid(),
                new LockRequest("app2", 1, 5L));

        lockPolicy(lock1.id(), "util", "MIT", "NOTICE_REQUIRED");
        createApprovedNotice("mit", 1, "MIT", List.of("CN"));
        bindLock(lock1.id(), "util", "MIT", "mit", 1);

        // lock1 上 util 命中 LOCK 策略且合规。
        assertThat(licenseService.checkLock(lock1.id(), List.of("CN")).missing()).isEmpty();
        // lock2 不受该 LOCK 策略影响：无命中。
        LicenseCheckView check2 = licenseService.checkLock(lock2.id(), List.of("CN"));
        assertThat(check2.hits()).isEmpty();
        assertThat(check2.missing()).isEmpty();

        ReleaseSnapshotResponse batch = licenseService.release(rid(),
                releaseRequest(List.of(lock1.id(), lock2.id()), List.of("CN")));
        assertThat(batch.items()).hasSize(2);
    }

    @Test
    void allowedPolicyHitsButRequiresNoNotice() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("lib", null, "Apache-2.0", "ALLOWED");

        LicenseCheckView check = licenseService.checkLock(lock.id(), List.of("CN"));
        assertThat(check.missing()).isEmpty();
        assertThat(check.hits()).extracting("name", "action")
                .containsExactly(tuple("lib", "ALLOWED"));

        ReleaseSnapshotResponse release = licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN")));
        ReleaseSnapshotResponse.ReleasedEntry lib = release.items().get(0).entries().stream()
                .filter(e -> e.name().equals("lib")).findFirst().orElseThrow();
        assertThat(lib.licenseId()).isEqualTo("Apache-2.0");
        assertThat(lib.noticeKey()).isNull();
    }

    // ------------------------------------------------------------------
    // 失败分支：缺失告知、未批准、已撤销、地区不覆盖、闭包外目标
    // ------------------------------------------------------------------

    @Test
    void releaseWithoutBindingReturns422WithStablePathAndPersistsNothing() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("trans", null, "GPL-3.0", "NOTICE_REQUIRED");
        createApprovedNotice("gpl", 1, "GPL-3.0", List.of("CN"));
        // 故意不绑定。

        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    @SuppressWarnings("unchecked")
                    List<LicenseCheckView> views = (List<LicenseCheckView>) e.getDetails();
                    assertThat(views).hasSize(1);
                    LicenseCheckView.MissingNotice missing = views.get(0).missing().get(0);
                    assertThat(missing.reason()).isEqualTo("NOTICE_MISSING");
                    assertThat(missing.name()).isEqualTo("trans");
                    assertThat(missing.direct()).isFalse();
                    assertThat(missing.path()).containsExactly("app:1", "lib:1", "trans:1");
                });
        assertThat(licenseService.listReleases()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_item", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_entry", Integer.class)).isZero();
    }

    @Test
    void draftBoundTextBlocksReleaseUntilApproved() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("util", null, "MIT", "NOTICE_REQUIRED");
        licenseService.registerNoticeText(rid(), new RegisterNoticeTextRequest(
                "mit", 1, "MIT", "draft-body", List.of("CN")));
        bindCoordinate("util", null, "MIT", "mit", 1);

        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    @SuppressWarnings("unchecked")
                    List<LicenseCheckView> views = (List<LicenseCheckView>) e.getDetails();
                    assertThat(views.get(0).missing().get(0).reason())
                            .isEqualTo("TEXT_NOT_APPROVED");
                });

        // 批准后同图可发布。
        licenseService.approveNoticeText(rid(), "mit", 1);
        ReleaseSnapshotResponse release = licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN")));
        assertThat(release.items()).hasSize(1);
    }

    @Test
    void regionNotCoveredReturns422AndNarrowOnlyAffectsLaterReleases() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("util", null, "MIT", "NOTICE_REQUIRED");
        createApprovedNotice("mit", 1, "MIT", List.of("CN", "US"));
        bindCoordinate("util", null, "MIT", "mit", 1);

        // 首次发布 CN,US 成功并固化。
        ReleaseSnapshotResponse first = licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("cn", "us")));
        long firstReleaseId = first.releaseId();

        // 缩窄到仅 CN。
        NoticeTextResponse narrowed = licenseService.narrowNoticeRegions(rid(), "mit", 1,
                new NarrowRegionsRequest(List.of("CN")));
        assertThat(narrowed.regions()).containsExactly("CN");

        // 非子集缩窄 422。
        assertThatThrownBy(() -> licenseService.narrowNoticeRegions(rid(), "mit", 1,
                new NarrowRegionsRequest(List.of("JP"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(422));

        // 后续发布 CN,US 因地区不覆盖被拒。
        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN", "US"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    @SuppressWarnings("unchecked")
                    List<LicenseCheckView> views = (List<LicenseCheckView>) e.getDetails();
                    LicenseCheckView.MissingNotice missing = views.get(0).missing().get(0);
                    assertThat(missing.reason()).isEqualTo("REGION_NOT_COVERED");
                    assertThat(missing.detail()).contains("US");
                });

        // 历史快照地区固化为 CN,US，不被缩窄改写。
        ReleaseSnapshotResponse historical = licenseService.getRelease(firstReleaseId);
        assertThat(historical.items().get(0).regions()).containsExactly("CN", "US");
        assertThat(historical.items().get(0).entries().stream()
                .filter(e -> e.name().equals("util")).findFirst().orElseThrow()
                .noticeRegions()).containsExactly("CN", "US");
    }

    @Test
    void withdrawnTextBlocksLaterReleasesButHistoricalSnapshotStays() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("util", null, "MIT", "NOTICE_REQUIRED");
        createApprovedNotice("mit", 1, "MIT", List.of("CN"));
        bindCoordinate("util", null, "MIT", "mit", 1);

        ReleaseSnapshotResponse first = licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN")));
        licenseService.withdrawNoticeText(rid(), "mit", 1);

        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    @SuppressWarnings("unchecked")
                    List<LicenseCheckView> views = (List<LicenseCheckView>) e.getDetails();
                    assertThat(views.get(0).missing().get(0).reason())
                            .isEqualTo("TEXT_WITHDRAWN");
                });

        ReleaseSnapshotResponse historical = licenseService.getRelease(first.releaseId());
        assertThat(historical.items().get(0).entries().stream()
                .filter(e -> e.name().equals("util")).findFirst().orElseThrow()
                .noticeVersion()).isEqualTo(1);
    }

    @Test
    void batchReleaseIsAtomicWhenSecondLockNonCompliant() {
        LockFileResponse good = standardGraphLockedAt4(); // 无策略，天然合规
        registerArtifact("app2", 1, dep("trans", 1, 1)); // 仓库版本 5
        LockFileResponse bad = artifactService.createLock(rid(),
                new LockRequest("app2", 1, 5L));
        coordinatePolicy("trans", null, "GPL-3.0", "NOTICE_REQUIRED");
        // 全部图都会命中 trans 策略，但都没有绑定告知。

        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(good.id(), bad.id()), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    @SuppressWarnings("unchecked")
                    List<LicenseCheckView> views = (List<LicenseCheckView>) e.getDetails();
                    // 稳定返回全部不合规图，按请求图顺序。
                    assertThat(views).extracting("lockFileId")
                            .containsExactly(good.id(), bad.id());
                });
        // 整批不发布任何部分。
        assertThat(licenseService.listReleases()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_item", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_entry", Integer.class)).isZero();
    }

    @Test
    void policyTargetOutsideLockClosureReturns422() {
        LockFileResponse lock = standardGraphLockedAt4();
        assertThatThrownBy(() -> lockPolicy(lock.id(), "ghost", "MIT", "NOTICE_REQUIRED"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void releaseUnknownLockReturns404AndDuplicateLockIdsReturn400() {
        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(9999L), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        LockFileResponse lock = standardGraphLockedAt4();
        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(lock.id(), lock.id()), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 文本生命周期与幂等
    // ------------------------------------------------------------------

    @Test
    void noticeTextLifecycleAndDuplicateVersionReturn409() {
        licenseService.registerNoticeText(rid(), new RegisterNoticeTextRequest(
                "k", 1, "MIT", "body", List.of("cn")));
        assertThatThrownBy(() -> licenseService.registerNoticeText(rid(),
                new RegisterNoticeTextRequest("k", 1, "MIT", "body", List.of("cn"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        NoticeTextResponse approved = licenseService.approveNoticeText(rid(), "k", 1);
        assertThat(approved.status()).isEqualTo("APPROVED");
        // 批准幂等：再次批准返回同一状态。
        assertThat(licenseService.approveNoticeText(rid(), "k", 1).status())
                .isEqualTo("APPROVED");

        NoticeTextResponse withdrawn = licenseService.withdrawNoticeText(rid(), "k", 1);
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        // 草稿批准后撤销；已撤销再批准必须 409。
        licenseService.registerNoticeText(rid(), new RegisterNoticeTextRequest(
                "k2", 1, "MIT", "body", List.of("cn")));
        licenseService.approveNoticeText(rid(), "k2", 1);
        licenseService.withdrawNoticeText(rid(), "k2", 1);
        assertThatThrownBy(() -> licenseService.approveNoticeText(rid(), "k2", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdSameParamsReplaysReleaseAndDifferentParamsReturn409() {
        LockFileResponse lock = standardGraphLockedAt4();
        String key = rid();
        ReleaseRequest request = releaseRequest(List.of(lock.id()), List.of("CN"));

        ReleaseSnapshotResponse first = licenseService.release(key, request);
        ReleaseSnapshotResponse replay = licenseService.release(key, request);
        assertThat(replay.releaseId()).isEqualTo(first.releaseId());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> licenseService.release(key,
                releaseRequest(List.of(lock.id()), List.of("JP"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameKeyAfterNoticeTextVersionChangeReturns409() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("util", null, "MIT", "NOTICE_REQUIRED");
        createApprovedNotice("mit", 1, "MIT", List.of("CN"));
        createApprovedNotice("mit", 2, "MIT", List.of("CN"));
        bindCoordinate("util", null, "MIT", "mit", 1);

        String key = rid();
        ReleaseRequest request = releaseRequest(List.of(lock.id()), List.of("CN"));
        ReleaseSnapshotResponse first = licenseService.release(key, request);
        assertThat(first.items().get(0).entries().stream()
                .filter(e -> e.name().equals("util")).findFirst().orElseThrow()
                .noticeVersion()).isEqualTo(1);

        // 重新绑定到文本版本 2（提交顺序裁决，最新绑定胜出）；同键同锁同地区但文本版本异参。
        bindCoordinate("util", null, "MIT", "mit", 2);
        assertThatThrownBy(() -> licenseService.release(key, request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 首次发布快照仍固化文本版本 1。
        assertThat(licenseService.getRelease(first.releaseId()).items().get(0).entries().stream()
                .filter(e -> e.name().equals("util")).findFirst().orElseThrow()
                .noticeVersion()).isEqualTo(1);
    }

    @Test
    void failedReleaseDoesNotConsumeNoticeKey() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("util", null, "MIT", "NOTICE_REQUIRED");
        licenseService.registerNoticeText(rid(), new RegisterNoticeTextRequest(
                "mit", 1, "MIT", "body", List.of("CN")));
        bindCoordinate("util", null, "MIT", "mit", 1);
        String key = rid();
        ReleaseRequest request = releaseRequest(List.of(lock.id()), List.of("CN"));

        assertThatThrownBy(() -> licenseService.release(key, request))
                .isInstanceOf(ApiException.class);
        // 失败不占键：补批准后同键同参成功。
        licenseService.approveNoticeText(rid(), "mit", 1);
        ReleaseSnapshotResponse later = licenseService.release(key, request);
        assertThat(later.items()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeySameParamsProducesSingleRelease() throws Exception {
        LockFileResponse lock = standardGraphLockedAt4();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String key = rid();
        ReleaseRequest request = releaseRequest(List.of(lock.id()), List.of("CN"));

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Object> task = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return licenseService.release(key, request);
                } catch (ApiException e) {
                    return e;
                }
            };
            futures.add(pool.submit(task));
        }
        pool.shutdown();

        long winnerId = -1;
        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(ReleaseSnapshotResponse.class);
            long id = ((ReleaseSnapshotResponse) outcome).releaseId();
            if (winnerId == -1) {
                winnerId = id;
            } else {
                assertThat(id).isEqualTo(winnerId);
            }
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_item", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_entry", Integer.class)).isEqualTo(4);
    }

    @Test
    void mismatchedLicenseBindingIsRejectedAtGate() {
        LockFileResponse lock = standardGraphLockedAt4();
        coordinatePolicy("util", null, "GPL-3.0", "NOTICE_REQUIRED");
        createApprovedNotice("mit", 1, "MIT", List.of("CN"));
        // 绑定声明许可证 GPL-3.0，但文本许可证为 MIT。
        bindCoordinate("util", null, "GPL-3.0", "mit", 1);

        assertThatThrownBy(() -> licenseService.release(rid(),
                releaseRequest(List.of(lock.id()), List.of("CN"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    @SuppressWarnings("unchecked")
                    List<LicenseCheckView> views = (List<LicenseCheckView>) e.getDetails();
                    assertThat(views.get(0).missing().get(0).reason())
                            .isEqualTo("NOTICE_MISSING");
                });
    }
}
