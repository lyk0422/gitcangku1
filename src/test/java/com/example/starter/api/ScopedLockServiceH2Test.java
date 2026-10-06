package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.EdgeView;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ScopedLockFileResponse;
import com.example.starter.api.dto.ScopeView;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 私有作用域锁定的 H2（MODE=MySQL）服务层测试：
 * 合成场景成功链、失败分支、期望版本一致性、幂等与操作者身份。
 */
@SpringBootTest
class ScopedLockServiceH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM scoped_lock_edge");
        jdbcTemplate.update("DELETE FROM scoped_lock_entry");
        jdbcTemplate.update("DELETE FROM scoped_lock_scope");
        jdbcTemplate.update("DELETE FROM scoped_lock");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
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

    private static DependencySpec pdep(String name, int min, int max) {
        return new DependencySpec(name, min, max, "PRIVATE");
    }

    /** 登记合成场景：根公开 X[1,1] 私有 P[1,1]，P 公开 X[2,2]，仓库版本推进到 4。 */
    private void registerSyntheticGraph() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("x", 1, 1), pdep("p", 1, 1)));
        service.registerArtifact(requestId(), artifact("x", 1));
        service.registerArtifact(requestId(), artifact("x", 2));
        service.registerArtifact(requestId(), artifact("p", 1, dep("x", 2, 2)));
    }

    // ------------------------------------------------------------------
    // 依赖作用域登记
    // ------------------------------------------------------------------

    @Test
    void registerPersistsScopeAndDefaultsToPublic() {
        ArtifactResponse response = service.registerArtifact(requestId(),
                artifact("app", 1, dep("x", 1, 1), pdep("p", 1, 1)));

        assertThat(response.dependencies()).extracting("name", "scope")
                .containsExactly(tuple("p", "PRIVATE"), tuple("x", "PUBLIC"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scope FROM artifact_dependency WHERE name = 'p'", String.class))
                .isEqualTo("PRIVATE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scope FROM artifact_dependency WHERE name = 'x'", String.class))
                .isEqualTo("PUBLIC");
    }

    @Test
    void invalidDependencyScopeReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, new DependencySpec("x", 1, 1, "INTERNAL"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 作用域锁定主流程
    // ------------------------------------------------------------------

    @Test
    void scopedLockResolvesParentAndChildScopesAndPersistsStructure() {
        registerSyntheticGraph();

        ScopedLockFileResponse lock = service.createScopedLock(requestId(),
                new LockRequest("app", 1, 4L));

        assertThat(lock.repositoryVersion()).isEqualTo(4L);
        assertThat(lock.scopes()).hasSize(2);
        ScopeView root = lock.scopes().get(0);
        assertThat(root.parentScopeId()).isNull();
        assertThat(root.depth()).isEqualTo(1);
        assertThat(root.path()).isEmpty();
        assertThat(root.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("x", 1));
        ScopeView child = lock.scopes().get(1);
        assertThat(child.parentScopeId()).isEqualTo(root.scopeId());
        assertThat(child.parentArtifactName()).isEqualTo("app");
        assertThat(child.depth()).isEqualTo(2);
        assertThat(child.path()).containsExactly("app");
        assertThat(child.entries()).extracting("name", "version")
                .containsExactly(tuple("p", 1), tuple("x", 2));
        assertThat(lock.edges()).extracting(
                EdgeView::fromScopeId, EdgeView::fromName, EdgeView::toName,
                EdgeView::toVersion, EdgeView::scope, EdgeView::toScopeId)
                .containsExactly(
                        tuple(root.scopeId(), "app", "p", 1, "PRIVATE", child.scopeId()),
                        tuple(root.scopeId(), "app", "x", 1, "PUBLIC", root.scopeId()),
                        tuple(child.scopeId(), "p", "x", 2, "PUBLIC", child.scopeId()));

        // 数据库实际落库：作用域、条目、边数量与结构一致。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_scope", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_entry", Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_edge", Integer.class))
                .isEqualTo(3);

        // 查询路径与创建路径一致（createdAt 经数据库 TIMESTAMP(6) 截断，单独比较到毫秒）。
        ScopedLockFileResponse queried = service.getScopedLock(lock.id());
        assertThat(queried).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(lock);
        assertThat(service.listScopedLocks()).singleElement()
                .usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(lock);
    }

    @Test
    void oldFlatLockEntryHasNoSolutionOnSyntheticGraph() {
        // 同一图走旧单根入口（全图同名一版）：X 需要同时为 1 和 2，无解 422。
        registerSyntheticGraph();
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void withdrawAfterScopedLockDoesNotRewriteHistory() {
        registerSyntheticGraph();
        ScopedLockFileResponse lock = service.createScopedLock(requestId(),
                new LockRequest("app", 1, 4L));

        service.withdrawArtifact(requestId(), "x", 1);

        ScopedLockFileResponse queried = service.getScopedLock(lock.id());
        assertThat(queried.scopes().get(0).entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("x", 1));
        assertThat(queried.edges()).isEqualTo(lock.edges());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void scopedLockWithStaleExpectedRepositoryVersionReturns409WithoutSaving() {
        registerSyntheticGraph();
        assertThatThrownBy(() -> service.createScopedLock(requestId(),
                new LockRequest("app", 1, 3L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_scope", Integer.class))
                .isZero();
    }

    @Test
    void scopedLockAllPublicConflictReturns422WithoutSaving() {
        // 全部 PUBLIC：X 区间交集为空，无解且不保存部分锁。
        service.registerArtifact(requestId(), artifact("app", 1, dep("x", 1, 1), dep("p", 1, 1)));
        service.registerArtifact(requestId(), artifact("x", 1));
        service.registerArtifact(requestId(), artifact("x", 2));
        service.registerArtifact(requestId(), artifact("p", 1, dep("x", 2, 2)));

        assertThatThrownBy(() -> service.createScopedLock(requestId(),
                new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_scope", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_entry", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_edge", Integer.class))
                .isZero();
    }

    @Test
    void scopedLockPrivateCycleReturns422() {
        // app:1 私有依赖 b，b:1 私有依赖 app[1,1]：祖先链环。
        service.registerArtifact(requestId(), artifact("app", 1, pdep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, pdep("app", 1, 1)));

        assertThatThrownBy(() -> service.createScopedLock(requestId(),
                new LockRequest("app", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isZero();
    }

    @Test
    void scopedLockNestingBeyondFourLevelsReturns422() {
        service.registerArtifact(requestId(), artifact("app", 1, pdep("a", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 1, pdep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, pdep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("c", 1, pdep("d", 1, 1)));
        service.registerArtifact(requestId(), artifact("d", 1));

        assertThatThrownBy(() -> service.createScopedLock(requestId(),
                new LockRequest("app", 1, 5L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isZero();
    }

    @Test
    void registerWithMismatchedExpectedRepositoryVersionReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                new RegisterArtifactRequest("lib", 1, List.of(), 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 匹配的期望版本可以正常登记。
        ArtifactResponse ok = service.registerArtifact(requestId(),
                new RegisterArtifactRequest("lib", 1, List.of(), 1L));
        assertThat(ok.repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void withdrawWithMismatchedExpectedRepositoryVersionReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), null, "app", 1, 0L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        ArtifactResponse ok = service.withdrawArtifact(requestId(), null, "app", 1, 1L);
        assertThat(ok.withdrawn()).isTrue();
    }

    // ------------------------------------------------------------------
    // 幂等与操作者身份
    // ------------------------------------------------------------------

    @Test
    void scopedLockSameRequestIdReplaysOriginalResponse() {
        registerSyntheticGraph();
        String rid = requestId();
        ScopedLockFileResponse first = service.createScopedLock(rid, new LockRequest("app", 1, 4L));

        // 仓库前进后重放：仍返回首次完整响应。
        service.registerArtifact(requestId(), artifact("other", 1));
        ScopedLockFileResponse replay = service.createScopedLock(rid, new LockRequest("app", 1, 4L));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE operation = 'CREATE_SCOPED_LOCK'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void scopedLockSameRequestIdDifferentParamsReturns409() {
        registerSyntheticGraph();
        String rid = requestId();
        service.createScopedLock(rid, new LockRequest("app", 1, 4L));
        assertThatThrownBy(() -> service.createScopedLock(rid, new LockRequest("app", 1, 3L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdWithDifferentOperatorReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, "alice", artifact("app", 1));
        // 同键同参同操作者：重放成功。
        ArtifactResponse replay = service.registerArtifact(rid, "alice", artifact("app", 1));
        assertThat(replay.repositoryVersion()).isEqualTo(1L);
        // 同键同参但操作者不同：身份不同，409。
        assertThatThrownBy(() -> service.registerArtifact(rid, "bob", artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedScopedLockDoesNotConsumeRequestId() {
        registerSyntheticGraph();
        String rid = requestId();
        assertThatThrownBy(() -> service.createScopedLock(rid, new LockRequest("app", 1, 3L)))
                .isInstanceOf(ApiException.class);

        ScopedLockFileResponse later = service.createScopedLock(rid, new LockRequest("app", 1, 4L));
        assertThat(later.rootName()).isEqualTo("app");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isEqualTo(1);
    }
}
