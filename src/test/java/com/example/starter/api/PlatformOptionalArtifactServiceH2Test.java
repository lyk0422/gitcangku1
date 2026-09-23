package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.OptionalDependencyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 目标平台与可选依赖的 H2（MODE=MySQL）端到端测试。
 *
 * <p>覆盖：平台登记/筛选、不兼容精确根 422、必选回退不受可选影响、
 * 可选加入（含必选闭包）与跳过（稳定原因）及持久化、必选无解 422 不落库、
 * 平台与 optional 参数校验、targetPlatform 参与幂等、同参并发锁定只产生一个锁文件。
 */
@SpringBootTest
class PlatformOptionalArtifactServiceH2Test {

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
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 登记指定平台与依赖（依赖可带 optional）。 */
    private ArtifactResponse register(String name, int version, List<String> platforms,
                                      DependencySpec... deps) {
        return service.registerArtifact(rid(),
                new RegisterArtifactRequest(name, version, List.of(deps), platforms));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max, false);
    }

    private static DependencySpec opt(String name, int min, int max) {
        return new DependencySpec(name, min, max, true);
    }

    // ------------------------------------------------------------------
    // 平台登记与筛选
    // ------------------------------------------------------------------

    @Test
    void registerDefaultsPlatformsToAnyAndEchoesPlatforms() {
        ArtifactResponse response = service.registerArtifact(rid(),
                new RegisterArtifactRequest("app", 1, List.of(), null));
        assertThat(response.platforms()).containsExactly("ANY");
        String stored = jdbcTemplate.queryForObject(
                "SELECT platforms FROM artifact WHERE name = 'app' AND version = 1", String.class);
        assertThat(stored).isEqualTo("ANY");
    }

    @Test
    void registerWithExplicitPlatformsPersistsAndEchoesThem() {
        ArtifactResponse response = register("app", 1, List.of(LINUX, DARWIN));
        assertThat(response.platforms()).containsExactly(LINUX, DARWIN);
    }

    @Test
    void lockPicksOnlyPlatformCompatibleVersionsAndFixesTargetPlatform() {
        // lib3 linux、lib2 darwin、lib1 linux；app(any) 必选 lib[1,3]。
        register("app", 1, List.of("ANY"), dep("lib", 1, 3));
        register("lib", 3, List.of(LINUX));
        register("lib", 2, List.of(DARWIN));
        register("lib", 1, List.of(LINUX));

        LockFileResponse linuxLock = service.createLock(rid(),
                new LockRequest("app", 1, 4L, LINUX));
        assertThat(linuxLock.targetPlatform()).isEqualTo(LINUX);
        assertThat(linuxLock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 3));

        LockFileResponse darwinLock = service.createLock(rid(),
                new LockRequest("app", 1, 4L, DARWIN));
        assertThat(darwinLock.targetPlatform()).isEqualTo(DARWIN);
        assertThat(darwinLock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));

        // targetPlatform 已固化到 lock_file。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT target_platform FROM lock_file WHERE id = ?",
                String.class, linuxLock.id())).isEqualTo(LINUX);
    }

    @Test
    void incompatiblePreciseRootReturns422AndSavesNothing() {
        register("app", 1, List.of(DARWIN));
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, 1L, LINUX)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(1L);
    }

    @Test
    void mandatoryDependencyWithoutPlatformCompatibleVersionReturns422() {
        register("app", 1, List.of(LINUX), dep("lib", 1, 1));
        register("lib", 1, List.of(DARWIN));

        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, 2L, LINUX)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 可选依赖：加入 / 跳过 / 持久化
    // ------------------------------------------------------------------

    @Test
    void optionalDependencyIncludedWithHighestVersionAndMandatoryClosureIsPersisted() {
        // app 可选 plugin[1,2]；plugin2 必选 core2，plugin1 必选 core1。
        register("app", 1, List.of("ANY"), opt("plugin", 1, 2));
        register("plugin", 2, List.of("ANY"), dep("core", 2, 2));
        register("plugin", 1, List.of("ANY"), dep("core", 1, 1));
        register("core", 2, List.of("ANY"));
        register("core", 1, List.of("ANY"));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, 5L, LINUX));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("core", 2), tuple("plugin", 2));
        assertThat(lock.optionalDependencies()).singleElement().satisfies(d -> {
            assertThat(d.included()).isTrue();
            assertThat(d.sourceName()).isEqualTo("app");
            assertThat(d.dependencyName()).isEqualTo("plugin");
            assertThat(d.selectedVersion()).isEqualTo(2);
            assertThat(d.reason()).isNull();
        });

        // 查询接口回读一致（判定随锁文件持久化，顺序稳定）。
        LockFileResponse reloaded = service.getLock(lock.id());
        assertThat(reloaded.targetPlatform()).isEqualTo(LINUX);
        assertThat(reloaded.optionalDependencies()).usingRecursiveComparison()
                .isEqualTo(lock.optionalDependencies());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_optional WHERE lock_file_id = ? AND included = 1",
                Integer.class, lock.id())).isEqualTo(1);
    }

    @Test
    void optionalSkippedWhenClosureInfeasibleButLockStillSucceeds() {
        // app 可选 plug[1,1]；plug1 必选 need[2,2]，只有 need1：闭包不可行。
        register("app", 1, List.of("ANY"), opt("plug", 1, 1));
        register("plug", 1, List.of("ANY"), dep("need", 2, 2));
        register("need", 1, List.of("ANY"));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, 3L, LINUX));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1));
        assertThat(lock.optionalDependencies()).singleElement().satisfies(d -> {
            assertThat(d.included()).isFalse();
            assertThat(d.reason()).startsWith("infeasible-mandatory-closure");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_optional WHERE lock_file_id = ? AND included = 0",
                Integer.class, lock.id())).isEqualTo(1);
    }

    @Test
    void optionalAlreadySelectedOutOfRangeIsSkippedAndNeverUpgradesSelection() {
        // 必选：app -> lib[1,1] 且 mid[1,1]，把 lib 固定为 1；
        // mid 可选 lib[2,2]：目标已选且不满足，跳过，绝不升级到 lib2。
        register("app", 1, List.of("ANY"), dep("lib", 1, 1), dep("mid", 1, 1));
        register("mid", 1, List.of("ANY"), opt("lib", 2, 2));
        register("lib", 2, List.of("ANY"));
        register("lib", 1, List.of("ANY"));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, 4L, LINUX));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1), tuple("mid", 1));
        OptionalDependencyResponse decision = lock.optionalDependencies().get(0);
        assertThat(decision.included()).isFalse();
        assertThat(decision.reason()).startsWith("selected-version-out-of-range");
    }

    @Test
    void unresolvableOptionalDependencyDoesNotFailLockOrDegradeMandatory() {
        // 必选取 lib2；lib2 可选不存在的 ghost。可选失败，锁定仍成功，必选不降级。
        register("app", 1, List.of("ANY"), dep("lib", 1, 2));
        register("lib", 2, List.of("ANY"), opt("ghost", 1, 1));
        register("lib", 1, List.of("ANY"));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, 3L, LINUX));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));
        assertThat(lock.optionalDependencies()).singleElement().satisfies(d -> {
            assertThat(d.included()).isFalse();
            assertThat(d.reason()).startsWith("no-compatible-candidate");
        });
    }

    @Test
    void optionalDependenciesPersistedInStableSourceThenDependencyOrder() {
        // app 必选 mid，且可选 zeta/alpha；mid 可选 extra。
        register("app", 1, List.of("ANY"),
                dep("mid", 1, 1), opt("zeta", 1, 1), opt("alpha", 1, 1));
        register("mid", 1, List.of("ANY"), opt("extra", 1, 1));
        register("alpha", 1, List.of("ANY"));
        register("zeta", 1, List.of("ANY"));
        register("extra", 1, List.of("ANY"));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, 5L, LINUX));
        assertThat(lock.optionalDependencies())
                .extracting(OptionalDependencyResponse::sourceName,
                        OptionalDependencyResponse::dependencyName)
                .containsExactly(tuple("app", "alpha"),
                        tuple("app", "zeta"),
                        tuple("mid", "extra"));
        // 直查数据库，按 id（写入顺序）升序同样稳定。
        List<String> sources = jdbcTemplate.queryForList(
                "SELECT source_name || ':' || dependency_name AS edge FROM lock_file_optional "
                        + "WHERE lock_file_id = ? ORDER BY id ASC", String.class, lock.id());
        assertThat(sources).containsExactly("app:alpha", "app:zeta", "mid:extra");
    }

    @Test
    void optionalFlagIsPersistedAndMandatoryInfeasibilityRollsBackOptionalRows() {
        // app 必选 miss[1,1]（不存在），同时登记一个可选 good：必选失败整体回滚。
        register("app", 1, List.of("ANY"), dep("miss", 1, 1), opt("good", 1, 1));
        register("good", 1, List.of("ANY"));

        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, 2L, LINUX)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_optional", Integer.class))
                .isZero();

        // optional 标记确实落库：app 的两条依赖按名称，good=1(可选)、miss=0(必选)。
        List<Map<String, Object>> flags = jdbcTemplate.queryForList(
                "SELECT name, optional AS flag FROM artifact_dependency WHERE artifact_id = "
                        + "(SELECT id FROM artifact WHERE name = 'app' AND version = 1) "
                        + "ORDER BY name ASC");
        assertThat(flags).hasSize(2);
        assertThat(flags.get(0).get("name")).isEqualTo("good");
        assertThat(((Number) flags.get(0).get("flag")).intValue()).isEqualTo(1);
        assertThat(flags.get(1).get("name")).isEqualTo("miss");
        assertThat(((Number) flags.get(1).get("flag")).intValue()).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // 参数校验
    // ------------------------------------------------------------------

    @Test
    void moreThanTenPlatformsReturn400() {
        List<String> platforms = List.of(
                "linux/amd64", "linux/arm64", "darwin/amd64", "darwin/arm64",
                "windows/amd64", "windows/arm64", "aix/ppc64", "solaris/sparc",
                "freebsd/amd64", "netbsd/arm64", "openbsd/amd64");
        assertThatThrownBy(() -> register("app", 1, platforms))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void anyMixedWithConcretePlatformReturns400() {
        assertThatThrownBy(() -> register("app", 1, List.of("ANY", LINUX)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void malformedPlatformReturns400() {
        assertThatThrownBy(() -> register("app", 1, List.of("linux-amd64")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void nonOsArchTargetPlatformReturns400() {
        register("app", 1, List.of("ANY"));
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, 1L, "ANY")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, 1L, "linux")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 兼容迁移：旧数据 ANY、旧锁文件查询结构不变
    // ------------------------------------------------------------------

    @Test
    void legacyLockRowWithNullTargetPlatformQueriesWithoutNewFields() {
        // 模拟迁移前历史锁文件：target_platform 为 NULL，无可选判定记录。
        jdbcTemplate.update("INSERT INTO lock_file (root_name, root_version, repository_version, "
                + "target_platform, request_id, created_at) VALUES ('legacyapp', 1, 7, NULL, ?, "
                + "CURRENT_TIMESTAMP(6))", rid());
        Long lockId = jdbcTemplate.queryForObject(
                "SELECT id FROM lock_file WHERE root_name = 'legacyapp'", Long.class);
        jdbcTemplate.update("INSERT INTO lock_file_entry (lock_file_id, name, version) "
                + "VALUES (?, 'legacyapp', 1)", lockId);

        LockFileResponse one = service.getLock(lockId);
        assertThat(one.targetPlatform()).isNull();
        assertThat(one.optionalDependencies()).isNull();
        assertThat(one.entries()).extracting("name", "version")
                .containsExactly(tuple("legacyapp", 1));

        LockFileResponse listed = service.listLocks().get(0);
        assertThat(listed.targetPlatform()).isNull();
        assertThat(listed.optionalDependencies()).isNull();
    }

    // ------------------------------------------------------------------
    // 幂等：targetPlatform 与 optional 参与参数摘要
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdDifferentTargetPlatformReturns409() {
        register("app", 1, List.of("ANY"), dep("lib", 1, 1));
        register("lib", 1, List.of(LINUX, DARWIN));
        String lockRid = rid();
        LockFileResponse first = service.createLock(lockRid,
                new LockRequest("app", 1, 2L, LINUX));
        assertThatThrownBy(() -> service.createLock(lockRid,
                new LockRequest("app", 1, 2L, DARWIN)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 同参重放返回原锁。
        assertThat(service.createLock(lockRid,
                new LockRequest("app", 1, 2L, LINUX)).id()).isEqualTo(first.id());
    }

    @Test
    void sameRequestIdDifferentOptionalFlagReturns409OnRegister() {
        String registerRid = rid();
        service.registerArtifact(registerRid,
                new RegisterArtifactRequest("app", 1,
                        List.of(new DependencySpec("lib", 1, 1, false)),
                        List.of("ANY")));
        assertThatThrownBy(() -> service.registerArtifact(registerRid,
                new RegisterArtifactRequest("app", 1,
                        List.of(new DependencySpec("lib", 1, 1, true)),
                        List.of("ANY"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedPlatformLockDoesNotConsumeRequestId() {
        register("app", 1, List.of(DARWIN));
        String lockRid = rid();
        assertThatThrownBy(() -> service.createLock(lockRid,
                new LockRequest("app", 1, 1L, LINUX)))
                .isInstanceOf(ApiException.class);

        // 同一 requestId 改用兼容平台应成功（失败不占键）。
        LockFileResponse later = service.createLock(lockRid,
                new LockRequest("app", 1, 1L, DARWIN));
        assertThat(later.targetPlatform()).isEqualTo(DARWIN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, lockRid)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdSameTargetProducesSingleLock() throws Exception {
        register("app", 1, List.of("ANY"), dep("lib", 1, 1));
        register("lib", 1, List.of("ANY"));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String lockRid = rid();

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.createLock(lockRid,
                            new LockRequest("app", 1, 2L, LINUX));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        pool.shutdown();

        Long firstId = null;
        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(LockFileResponse.class);
            long id = ((LockFileResponse) outcome).id();
            if (firstId == null) {
                firstId = id;
            } else {
                assertThat(id).isEqualTo(firstId);
            }
        }
        List<Map<String, Object>> lockRows = jdbcTemplate.queryForList(
                "SELECT id, request_id FROM lock_file");
        assertThat(lockRows).as("lock rows: %s", lockRows).hasSize(1);
        // 仅本次并发锁定的 requestId 对应一条成功记录（两次登记另有各自 requestId）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, lockRid)).isEqualTo(1);
    }
}
