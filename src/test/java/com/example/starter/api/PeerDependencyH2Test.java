package com.example.starter.api;

import com.example.starter.api.dto.DeclarePeersRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PeerDeclarationResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
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
 * peer 依赖的真实 H2（MODE=MySQL）服务测试：
 * 声明登记、校验、不可改、幂等重放与并发、锁定回退及锁冻结。
 */
@SpringBootTest
class PeerDependencyH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_peer");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DeclarePeersRequest peers(String operator, DependencySpec... specs) {
        return new DeclarePeersRequest(operator, List.of(specs));
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 声明登记与校验
    // ------------------------------------------------------------------

    @Test
    void declarePeersAdvancesRepositoryVersionAndPersists() {
        service.registerArtifact(requestId(), artifact("p", 1));
        PeerDeclarationResponse response = service.declarePeers(requestId(), "p", 1,
                peers("alice", dep("h", 1, 2)));

        assertThat(response.name()).isEqualTo("p");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.operator()).isEqualTo("alice");
        assertThat(response.repositoryVersion()).isEqualTo(2L);
        assertThat(response.peers()).extracting("name", "minimumVersion", "maximumVersion")
                .containsExactly(tuple("h", 1, 2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_peer", Integer.class)).isEqualTo(1);
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void declarePeersRejectsInvalidInput() {
        service.registerArtifact(requestId(), artifact("p", 1));

        // 操作者为空 → 400（Service 层校验，不只靠 DTO）。
        assertThatThrownBy(() -> service.declarePeers(requestId(), "p", 1,
                peers("  ", dep("h", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // peer 与自身同名 → 400。
        assertThatThrownBy(() -> service.declarePeers(requestId(), "p", 1,
                peers("a", dep("p", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 区间倒置 → 400。
        assertThatThrownBy(() -> service.declarePeers(requestId(), "p", 1,
                peers("a", dep("h", 3, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 名称重复 → 400。
        assertThatThrownBy(() -> service.declarePeers(requestId(), "p", 1,
                peers("a", dep("h", 1, 1), dep("h", 2, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 超过 3 条 → 400。
        assertThatThrownBy(() -> service.declarePeers(requestId(), "p", 1,
                peers("a", dep("h1", 1, 1), dep("h2", 1, 1), dep("h3", 1, 1), dep("h4", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 全部校验失败都不应推进仓库版本。
        assertThat(repositoryVersion()).isEqualTo(1L);
    }

    @Test
    void declarePeersOnMissingOrWithdrawnArtifactFails() {
        assertThatThrownBy(() -> service.declarePeers(requestId(), "ghost", 1,
                peers("a", dep("h", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        service.registerArtifact(requestId(), artifact("p", 1));
        service.withdrawArtifact(requestId(), "p", 1);
        assertThatThrownBy(() -> service.declarePeers(requestId(), "p", 1,
                peers("a", dep("h", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void declarePeersIsImmutableAfterRegistration() {
        service.registerArtifact(requestId(), artifact("p", 1));
        service.declarePeers(requestId(), "p", 1, peers("a", dep("h", 1, 1)));

        assertThatThrownBy(() -> service.declarePeers(requestId(), "p", 1,
                peers("b", dep("g", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_peer", Integer.class)).isEqualTo(1);
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // 幂等与并发
    // ------------------------------------------------------------------

    @Test
    void declarePeersReplaysWithSameParamsAndReorderedPeers() {
        service.registerArtifact(requestId(), artifact("p", 1));
        String rid = requestId();
        PeerDeclarationResponse first = service.declarePeers(rid, "p", 1,
                peers("alice", dep("h", 1, 2), dep("g", 1, 1)));

        // peer 声明换序视为同参：重放首次完整响应，不推进仓库版本。
        PeerDeclarationResponse replay = service.declarePeers(rid, "p", 1,
                peers("alice", dep("g", 1, 1), dep("h", 1, 2)));
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.peers()).isEqualTo(first.peers());
        assertThat(repositoryVersion()).isEqualTo(2L);
        // 登记与 peer 声明各一条幂等记录；重放不新增。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(2);
    }

    @Test
    void declarePeersSameRequestIdDifferentParamsReturns409() {
        service.registerArtifact(requestId(), artifact("p", 1));
        String rid = requestId();
        service.declarePeers(rid, "p", 1, peers("alice", dep("h", 1, 1)));

        // 改操作者 → 409；改 peer 区间 → 409。
        assertThatThrownBy(() -> service.declarePeers(rid, "p", 1,
                peers("bob", dep("h", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThatThrownBy(() -> service.declarePeers(rid, "p", 1,
                peers("alice", dep("h", 1, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedDeclareDoesNotConsumeRequestId() {
        String rid = requestId();
        // 制品不存在（404）失败不占键。
        assertThatThrownBy(() -> service.declarePeers(rid, "ghost", 1,
                peers("a", dep("h", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        service.registerArtifact(requestId(), artifact("p", 1));
        PeerDeclarationResponse ok = service.declarePeers(rid, "p", 1,
                peers("a", dep("h", 1, 1)));
        assertThat(ok.repositoryVersion()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void concurrentDeclareSameRequestIdResolvesToSingleResult() throws Exception {
        service.registerArtifact(requestId(), artifact("p", 1));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<PeerDeclarationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.declarePeers(rid, "p", 1, peers("alice", dep("h", 1, 2)));
            }));
        }
        pool.shutdown();

        PeerDeclarationResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<PeerDeclarationResponse> future : futures) {
            PeerDeclarationResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.repositoryVersion()).isEqualTo(first.repositoryVersion());
            assertThat(response.createdAt()).isEqualTo(first.createdAt());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_peer", Integer.class)).isEqualTo(1);
        // 登记与 peer 声明各一条幂等记录；并发重放不新增。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(2);
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void uniquePeerNamePerArtifactEnforcedByDatabase() {
        service.registerArtifact(requestId(), artifact("p", 1));
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'p' AND version = 1", Long.class);
        jdbcTemplate.update("INSERT INTO artifact_peer (artifact_id, name, minimum_version, "
                + "maximum_version) VALUES (?, 'h', 1, 1)", artifactId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_peer (artifact_id, name, minimum_version, "
                        + "maximum_version) VALUES (?, 'h', 2, 2)", artifactId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------
    // 锁定：peer 回退、不可自动引入宿主、锁冻结
    // ------------------------------------------------------------------

    /** 题干合成场景：根依赖宿主 h1 与插件 p[1,2]；p2 要求 h2，p1 要求 h1。 */
    private void setupHostAndPlugin() {
        service.registerArtifact(requestId(), artifact("root", 1, dep("h", 1, 1), dep("p", 1, 2)));
        service.registerArtifact(requestId(), artifact("h", 1));
        service.registerArtifact(requestId(), artifact("p", 1));
        service.registerArtifact(requestId(), artifact("p", 2));
        service.declarePeers(requestId(), "p", 2, peers("tester", dep("h", 2, 2)));
        service.declarePeers(requestId(), "p", 1, peers("tester", dep("h", 1, 1)));
        // 4 次登记 + 2 次 peer 声明 = 仓库版本 6。
    }

    @Test
    void lockFallsBackToPluginVersionMatchingPeerHost() {
        setupHostAndPlugin();
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("root", 1, 6L));

        assertThat(lock.repositoryVersion()).isEqualTo(6L);
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("h", 1), tuple("p", 1), tuple("root", 1));
    }

    @Test
    void lockWithoutReachableHostIs422AndDoesNotAutoAddHost() {
        // 删去普通 h 依赖：peer 不能自行引入宿主，全部候选无解 → 422 且无锁。
        service.registerArtifact(requestId(), artifact("root", 1, dep("p", 1, 2)));
        service.registerArtifact(requestId(), artifact("h", 1));
        service.registerArtifact(requestId(), artifact("p", 1));
        service.declarePeers(requestId(), "p", 1, peers("tester", dep("h", 1, 1)));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("root", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        // h 未被自动加入任何锁结果。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry WHERE name = 'h'", Integer.class)).isZero();
    }

    @Test
    void lockFrozenAfterHostAndPluginWithdrawal() {
        setupHostAndPlugin();
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("root", 1, 6L));

        // 后续撤回宿主与插件不改旧锁。
        service.withdrawArtifact(requestId(), "h", 1);
        service.withdrawArtifact(requestId(), "p", 1);

        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.repositoryVersion()).isEqualTo(6L);
        assertThat(queried.entries()).isEqualTo(lock.entries());
    }
}
