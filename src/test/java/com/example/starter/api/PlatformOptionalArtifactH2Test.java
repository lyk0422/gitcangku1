package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.OptionalDependencyView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
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
 * 目标平台与可选依赖的 H2（MODE=MySQL）数据库与业务测试：
 * 平台登记/迁移、平台筛选、可选依赖加入/跳过与锁文件固化、旧锁文件兼容、
 * 平台相关幂等语义及锁定/撤回真实并发。
 */
@SpringBootTest
class PlatformOptionalArtifactH2Test {

    private static final String LINUX = "linux/amd64";
    private static final String DARWIN = "darwin/arm64";

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_optional");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_platform");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max, false);
    }

    private static DependencySpec opt(String name, int min, int max) {
        return new DependencySpec(name, min, max, true);
    }

    private static LockRequest lock(String name, int version, long expected, String platform) {
        return new LockRequest(name, version, expected, platform);
    }

    private RegisterArtifactRequest artifact(String name, int version, List<String> platforms,
                                             DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps), platforms);
    }

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps), null);
    }

    // ------------------------------------------------------------------
    // 平台登记与 ANY 兼容
    // ------------------------------------------------------------------

    @Test
    void registerWithoutPlatformsDefaultsToAnyAndPersists() {
        ArtifactResponse response = service.registerArtifact(requestId(), artifact("app", 1));
        assertThat(response.platforms()).containsExactly("ANY");

        List<String> platforms = jdbcTemplate.queryForList(
                "SELECT platform FROM artifact_platform p JOIN artifact a ON a.id = p.artifact_id "
                        + "WHERE a.name = 'app' AND a.version = 1 ORDER BY platform", String.class);
        assertThat(platforms).containsExactly("ANY");
    }

    @Test
    void registerWithPlatformsEchoesSortedPlatformsAndPersistsEach() {
        ArtifactResponse response = service.registerArtifact(requestId(),
                artifact("app", 1, List.of(DARWIN, LINUX)));
        assertThat(response.platforms()).containsExactly(DARWIN, LINUX);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_platform p JOIN artifact a ON a.id = p.artifact_id "
                        + "WHERE a.name = 'app'", Integer.class)).isEqualTo(2);
    }

    @Test
    void optionalFlagDefaultsToFalseAndPersists() {
        // 旧的三参依赖构造：optional 必须落库为 0。
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("app", 1, List.of(new DependencySpec("lib", 1, 2)), null));
        Integer optional = jdbcTemplate.queryForObject(
                "SELECT optional FROM artifact_dependency d JOIN artifact a ON a.id = d.artifact_id "
                        + "WHERE a.name = 'app' AND d.name = 'lib'", Integer.class);
        assertThat(optional).isZero();
    }

    @Test
    void optionalFlagTruePersistsAsOne() {
        service.registerArtifact(requestId(),
                artifact("app", 1, opt("plugin", 1, 1)));
        Integer optional = jdbcTemplate.queryForObject(
                "SELECT optional FROM artifact_dependency d JOIN artifact a ON a.id = d.artifact_id "
                        + "WHERE a.name = 'app' AND d.name = 'plugin'", Integer.class);
        assertThat(optional).isEqualTo(1);
    }

    @Test
    void moreThanTenPlatformsReturns400() {
        List<String> platforms = List.of(
                "linux/amd64", "linux/arm64", "darwin/amd64", "darwin/arm64",
                "windows/amd64", "aix/ppc64", "solaris/sparc", "freebsd/amd64",
                "openbsd/arm64", "netbsd/amd64", "os400/ppc64");
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, platforms)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void mixedAnyAndConcretePlatformReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, List.of("ANY", LINUX))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void malformedPlatformReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, List.of("linux-amd64"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void missingTargetPlatformReturns400() {
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 0L, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void anyTargetPlatformReturns400() {
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 0L, "ANY")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 平台筛选
    // ------------------------------------------------------------------

    @Test
    void exactRootWithoutTargetPlatformSupportReturns422AndSavesNothing() {
        service.registerArtifact(requestId(), artifact("app", 1, List.of(DARWIN)));
        assertThatThrownBy(() -> service.createLock(requestId(),
                lock("app", 1, 1L, LINUX)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_optional", Integer.class))
                .isZero();
        // 失败不占键、不推进版本。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(1L);
    }

    @Test
    void mandatoryResolutionFiltersCandidatesByPlatform() {
        // lib2 仅 darwin，lib1 仅 linux；linux 锁定必须回退 lib1。
        service.registerArtifact(requestId(), artifact("app", 1, List.of(LINUX), dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 2, List.of(DARWIN)));
        service.registerArtifact(requestId(), artifact("lib", 1, List.of(LINUX)));

        LockFileResponse lockFile = service.createLock(requestId(),
                lock("app", 1, 3L, LINUX));
        assertThat(lockFile.targetPlatform()).isEqualTo(LINUX);
        assertThat(lockFile.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
    }

    @Test
    void noMandatoryCandidateForPlatformReturns422WithoutLockFile() {
        service.registerArtifact(requestId(), artifact("app", 1, List.of(LINUX), dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1, List.of(DARWIN)));
        assertThatThrownBy(() -> service.createLock(requestId(), lock("app", 1, 2L, LINUX)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 可选依赖加入/跳过与锁文件固化
    // ------------------------------------------------------------------

    @Test
    void optionalIncludedWithMandatoryClosureIsPersistedInStableOrder() {
        // app:1(linux) 可选 plugin[1,1]；plugin1 -> 必选 util[1,1]；另声明必选 core。
        service.registerArtifact(requestId(),
                artifact("app", 1, List.of(LINUX), dep("core", 1, 1), opt("plugin", 1, 1)));
        service.registerArtifact(requestId(), artifact("core", 1, List.of(LINUX)));
        service.registerArtifact(requestId(),
                artifact("plugin", 1, List.of(LINUX), dep("util", 1, 1)));
        service.registerArtifact(requestId(), artifact("util", 1, List.of(LINUX)));

        LockFileResponse lockFile = service.createLock(requestId(),
                lock("app", 1, 4L, LINUX));

        assertThat(lockFile.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("core", 1),
                        tuple("plugin", 1), tuple("util", 1));
        assertThat(lockFile.optionalDependencies()).singleElement()
                .satisfies(o -> {
                    assertThat(o.included()).isTrue();
                    assertThat(o.sourceName()).isEqualTo("app");
                    assertThat(o.dependencyName()).isEqualTo("plugin");
                    assertThat(o.targetVersion()).isEqualTo(1);
                    assertThat(o.reason()).isNull();
                });

        // 历史查询返回同样固化结果。
        LockFileResponse queried = service.getLock(lockFile.id());
        assertThat(queried.targetPlatform()).isEqualTo(LINUX);
        assertThat(queried.entries()).isEqualTo(lockFile.entries());
        assertThat(queried.optionalDependencies()).isEqualTo(lockFile.optionalDependencies());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT target_platform FROM lock_file WHERE id = ?", String.class, lockFile.id()))
                .isEqualTo(LINUX);
    }

    @Test
    void optionalSkippedIsPersistedWithReasonButLockStillSucceeds() {
        // 必选 core 可解；可选 ghost 不存在：锁成功，明细 skipped 并带稳定原因。
        service.registerArtifact(requestId(),
                artifact("app", 1, List.of(LINUX), dep("core", 1, 1), opt("ghost", 1, 1)));
        service.registerArtifact(requestId(), artifact("core", 1, List.of(LINUX)));

        LockFileResponse lockFile = service.createLock(requestId(),
                lock("app", 1, 2L, LINUX));
        assertThat(lockFile.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("core", 1));
        OptionalDependencyView outcome = lockFile.optionalDependencies().get(0);
        assertThat(outcome.included()).isFalse();
        assertThat(outcome.targetVersion()).isNull();
        assertThat(outcome.reason()).contains("ghost").contains("[1,1]");

        Integer included = jdbcTemplate.queryForObject(
                "SELECT included FROM lock_file_optional WHERE lock_file_id = ?",
                Integer.class, lockFile.id());
        assertThat(included).isZero();
    }

    @Test
    void optionalCandidateOnOtherPlatformIsSkippedNotFailed() {
        service.registerArtifact(requestId(),
                artifact("app", 1, List.of(LINUX), opt("plugin", 1, 1)));
        service.registerArtifact(requestId(), artifact("plugin", 1, List.of(DARWIN)));

        LockFileResponse lockFile = service.createLock(requestId(),
                lock("app", 1, 2L, LINUX));
        assertThat(lockFile.entries()).extracting("name").containsExactly("app");
        assertThat(lockFile.optionalDependencies()).singleElement()
                .satisfies(o -> {
                    assertThat(o.included()).isFalse();
                    assertThat(o.reason()).contains(LINUX);
                });
    }

    @Test
    void sameOptionalDeclarationIncludedAfterSuccessfulReplay() {
        // 重放必须返回原锁（含 optional 明细），不重新解析。
        service.registerArtifact(requestId(),
                artifact("app", 1, List.of(LINUX), opt("plugin", 1, 2)));
        service.registerArtifact(requestId(), artifact("plugin", 2, List.of(LINUX)));
        service.registerArtifact(requestId(), artifact("plugin", 1, List.of(LINUX)));
        String rid = requestId();

        LockFileResponse first = service.createLock(rid, lock("app", 1, 3L, LINUX));
        assertThat(first.optionalDependencies().get(0).targetVersion()).isEqualTo(2);

        // 撤回 plugin2 后仓库前进；同参重放仍返回固化的旧结果。
        service.withdrawArtifact(requestId(), "plugin", 2);
        LockFileResponse replay = service.createLock(rid, lock("app", 1, 3L, LINUX));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.optionalDependencies()).isEqualTo(first.optionalDependencies());
        assertThat(replay.entries()).isEqualTo(first.entries());
    }

    @Test
    void differentTargetPlatformWithSameRequestIdReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1, List.of(LINUX, DARWIN)));
        String rid = requestId();
        service.createLock(rid, lock("app", 1, 1L, LINUX));
        assertThatThrownBy(() -> service.createLock(rid, lock("app", 1, 1L, DARWIN)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdAndPlatformReplaysSingleLockFile() {
        service.registerArtifact(requestId(),
                artifact("app", 1, List.of(LINUX), opt("plugin", 1, 1)));
        service.registerArtifact(requestId(), artifact("plugin", 1, List.of(LINUX)));
        String rid = requestId();

        LockFileResponse first = service.createLock(rid, lock("app", 1, 2L, LINUX));
        LockFileResponse replay = service.createLock(rid, lock("app", 1, 2L, LINUX));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_optional", Integer.class))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 旧锁文件兼容：平台特性上线前 target_platform 为 NULL
    // ------------------------------------------------------------------

    @Test
    void legacyLockFileWithoutTargetPlatformRemainsQueryable() {
        // 直接构造一行平台上线前的旧锁文件与旧条目（无 optional 行）。
        jdbcTemplate.update("INSERT INTO artifact (name, version, withdrawn, created_at) "
                + "VALUES ('legacy-app', 1, 0, CURRENT_TIMESTAMP(6))");
        jdbcTemplate.update("INSERT INTO lock_file (root_name, root_version, target_platform, "
                + "repository_version, request_id, created_at) "
                + "VALUES ('legacy-app', 1, NULL, 7, 'legacy-rid', CURRENT_TIMESTAMP(6))");
        Number lockId = jdbcTemplate.queryForObject(
                "SELECT id FROM lock_file WHERE request_id = 'legacy-rid'", Long.class);
        jdbcTemplate.update("INSERT INTO lock_file_entry (lock_file_id, name, version) VALUES (?, 'legacy-app', 1)",
                lockId);

        LockFileResponse response = service.getLock(lockId.longValue());
        assertThat(response.targetPlatform()).isNull();
        assertThat(response.entries()).extracting("name", "version")
                .containsExactly(tuple("legacy-app", 1));
        assertThat(response.optionalDependencies()).isEmpty();
        assertThat(service.listLocks()).singleElement()
                .satisfies(l -> assertThat(l.targetPlatform()).isNull());
    }

    // ------------------------------------------------------------------
    // 幂等：平台与 optional 参与同参判定
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdDifferentPlatformSetReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1, List.of(LINUX)));
        assertThatThrownBy(() -> service.registerArtifact(rid, artifact("app", 1, List.of(DARWIN))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdDifferentOptionalFlagReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1, dep("lib", 1, 1)));
        assertThatThrownBy(() -> service.registerArtifact(rid, artifact("app", 1, opt("lib", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void anyMigrationInsertIsIdempotentForArtifactsAlreadyHavingPlatforms() {
        // 登记一个具体平台制品（迁移语句不应再给它补 ANY）。
        service.registerArtifact(requestId(), artifact("app", 1, List.of(LINUX)));
        service.registerArtifact(requestId(), artifact("legacy", 1));

        // 重新执行 schema.sql 末尾的兼容迁移语句：已具平台者不得重复，无平台者补 ANY。
        jdbcTemplate.update("INSERT INTO artifact_platform (artifact_id, platform) "
                + "SELECT a.id, 'ANY' FROM artifact a "
                + "WHERE NOT EXISTS (SELECT 1 FROM artifact_platform p WHERE p.artifact_id = a.id)");

        Map<String, List<String>> platformsByArtifact = jdbcTemplate.query(
                "SELECT a.name, p.platform FROM artifact a "
                        + "JOIN artifact_platform p ON p.artifact_id = a.id ORDER BY a.name, p.platform",
                rs -> {
                    java.util.Map<String, List<String>> result = new java.util.TreeMap<>();
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString(1), k -> new java.util.ArrayList<>())
                                .add(rs.getString(2));
                    }
                    return result;
                });
        assertThat(platformsByArtifact.get("app")).containsExactly(LINUX);
        assertThat(platformsByArtifact.get("legacy")).containsExactly("ANY");
    }

    // ------------------------------------------------------------------
    // 并发：可选锁与撤回基于 expectedRepositoryVersion 的一致快照
    // ------------------------------------------------------------------

    @Test
    void optionalLockAndWithdrawConcurrencyHonorsSnapshotVersion() throws Exception {
        // app:1(linux) 可选 plugin[1,1]；plugin1(linux)。初始版本 2。
        service.registerArtifact(requestId(),
                artifact("app", 1, List.of(LINUX), opt("plugin", 1, 1)));
        service.registerArtifact(requestId(), artifact("plugin", 1, List.of(LINUX)));
        long versionBefore = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRid = requestId();
        String withdrawRid = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRid, lock("app", 1, versionBefore, LINUX));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.withdrawArtifact(withdrawRid, "plugin", 1);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, withdrawTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);
        if (lockOutcome instanceof LockFileResponse lockFile) {
            // 锁先提交：固化旧版本与 plugin1 included，撤回后锁文件不改写。
            assertThat(lockFile.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(lockFile.optionalDependencies()).singleElement()
                    .satisfies(o -> assertThat(o.included()).isTrue());
            LockFileResponse queried = service.getLock(lockFile.id());
            assertThat(queried.entries()).isEqualTo(lockFile.entries());
            assertThat(queried.optionalDependencies()).isEqualTo(lockFile.optionalDependencies());
        } else {
            // 撤回先提交：期望版本过期，409 且无锁文件。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
        }
    }

    @Test
    void concurrentSameLockRequestIdWithPlatformResolvesToSingleResult() throws Exception {
        int threads = 6;
        service.registerArtifact(requestId(),
                artifact("app", 1, List.of(LINUX), opt("plugin", 1, 1)));
        service.registerArtifact(requestId(), artifact("plugin", 1, List.of(LINUX)));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        List<Future<LockFileResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.createLock(rid, lock("app", 1, 2L, LINUX));
            }));
        }
        pool.shutdown();

        LockFileResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<LockFileResponse> future : futures) {
            LockFileResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.id()).isEqualTo(first.id());
            assertThat(response.optionalDependencies()).isEqualTo(first.optionalDependencies());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_optional", Integer.class))
                .isEqualTo(1);
    }
}
