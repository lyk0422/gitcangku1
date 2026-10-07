package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
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
 * 基于嵌入式 H2（MODE=MySQL）的服务层数据库与业务测试：
 * 主流程、失败分支、幂等重放、事务回滚与体积字段持久化。
 */
@SpringBootTest
class ArtifactServiceH2Test {

    private static final String OPERATOR = "packer-li";
    private static final long BIG_BUDGET = 1_000_000L;

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
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
        return new RegisterArtifactRequest(name, version, null, List.of(deps));
    }

    private RegisterArtifactRequest artifactSized(String name, int version, long sizeBytes,
                                                  DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, sizeBytes, List.of(deps));
    }

    private LockRequest lockRequest(String name, int version, long expected, long budget) {
        return new LockRequest(name, version, expected, budget);
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void registerPersistsSizeBytesAndIncrementsRepositoryVersion() {
        ArtifactResponse a1 = service.registerArtifact(requestId(), OPERATOR,
                artifactSized("app", 1, 7L, dep("lib", 1, 2)));
        assertThat(a1.repositoryVersion()).isEqualTo(1);
        assertThat(a1.sizeBytes()).isEqualTo(7L);
        assertThat(a1.withdrawn()).isFalse();
        assertThat(a1.dependencies()).extracting("name", "minimumVersion", "maximumVersion")
                .containsExactly(tuple("lib", 1, 2));

        ArtifactResponse a2 = service.registerArtifact(requestId(), OPERATOR,
                artifact("lib", 2));
        assertThat(a2.repositoryVersion()).isEqualTo(2);
        // 旧客户端未声明 sizeBytes，按 0 兼容。
        assertThat(a2.sizeBytes()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT size_bytes FROM artifact WHERE name = 'lib' AND version = 2",
                Long.class)).isEqualTo(0L);
    }

    @Test
    void lockProducesSortedEntriesWithSizesAndPersistsBudgetTotals() {
        service.registerArtifact(requestId(), OPERATOR,
                artifactSized("app", 1, 2L, dep("util", 1, 1), dep("lib", 1, 2)));
        service.registerArtifact(requestId(), OPERATOR, artifactSized("lib", 2, 4L));
        service.registerArtifact(requestId(), OPERATOR, artifactSized("lib", 1, 9L));
        service.registerArtifact(requestId(), OPERATOR, artifactSized("util", 1, 1L));

        LockFileResponse lock = service.createLock(requestId(), OPERATOR,
                lockRequest("app", 1, 4L, 10L));

        assertThat(lock.repositoryVersion()).isEqualTo(4L);
        assertThat(lock.budgetBytes()).isEqualTo(10L);
        // 2 + 4(lib2) + 1(util1) = 7
        assertThat(lock.totalSizeBytes()).isEqualTo(7L);
        assertThat(lock.operator()).isEqualTo(OPERATOR);
        assertThat(lock.entries()).extracting("name", "version", "sizeBytes")
                .containsExactly(tuple("app", 1, 2L), tuple("lib", 2, 4L), tuple("util", 1, 1L));

        // 合计与预算落库；历史查询一致。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT total_size_bytes FROM lock_file WHERE id = ?", Long.class, lock.id()))
                .isEqualTo(7L);
        assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void duplicateArtifactVersionReturns409() {
        service.registerArtifact(requestId(), OPERATOR, artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(requestId(), OPERATOR, artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void withdrawMissingArtifactReturns404() {
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), OPERATOR, "ghost", 9))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void doubleWithdrawReturns409AndSecondWithdrawConsumesNoKey() {
        service.registerArtifact(requestId(), OPERATOR, artifact("app", 1));
        service.withdrawArtifact(requestId(), OPERATOR, "app", 1);
        long versionAfterFirstWithdraw = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), OPERATOR, "app", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionAfterFirstWithdraw);
    }

    @Test
    void lockWithStaleExpectedRepositoryVersionReturns409WithoutSaving() {
        service.registerArtifact(requestId(), OPERATOR, artifact("app", 1));
        assertThatThrownBy(() -> service.createLock(requestId(), OPERATOR,
                lockRequest("app", 1, 0L, BIG_BUDGET)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
    }

    @Test
    void lockOnWithdrawnRootReturns409() {
        service.registerArtifact(requestId(), OPERATOR, artifact("app", 1));
        service.withdrawArtifact(requestId(), OPERATOR, "app", 1);
        assertThatThrownBy(() -> service.createLock(requestId(), OPERATOR,
                lockRequest("app", 1, 2L, BIG_BUDGET)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockOnMissingRootReturns404() {
        assertThatThrownBy(() -> service.createLock(requestId(), OPERATOR,
                lockRequest("ghost", 1, 0L, BIG_BUDGET)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void infeasibleDependencyLockReturns422AndPersistsNothing() {
        service.registerArtifact(requestId(), OPERATOR,
                artifactSized("app", 1, 0L, dep("lib", 2, 2)));
        service.registerArtifact(requestId(), OPERATOR, artifact("lib", 1));

        assertThatThrownBy(() -> service.createLock(requestId(), OPERATOR,
                lockRequest("app", 1, 2L, BIG_BUDGET)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(2L);
    }

    @Test
    void registeringTwentyFirstNameReturns422() {
        for (int i = 1; i <= 20; i++) {
            service.registerArtifact(requestId(), OPERATOR, artifact("name" + i, 1));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), OPERATOR,
                artifact("overflow", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void registeringSixthVersionOfNameReturns422() {
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), OPERATOR, artifact("app", v));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), OPERATOR,
                artifact("app", 6)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void invalidDependencyRangeReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(), OPERATOR,
                artifact("app", 1, dep("lib", 3, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateDependencyNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(), OPERATOR,
                artifact("app", 1, dep("lib", 1, 1), dep("lib", 2, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void negativeSizeBytesReturns400AndRegisterStillHonorsNameCapacity() {
        // 负值：400，且不占名称/版本容量——随后仍可登记 5 个版本。
        assertThatThrownBy(() -> service.registerArtifact(requestId(), OPERATOR,
                artifactSized("app", 1, -1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), OPERATOR, artifact("app", v));
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'app'", Integer.class)).isEqualTo(5);
    }

    @Test
    void missingOperatorReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "  ", artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.createLock(requestId(), null,
                lockRequest("app", 1, 0L, BIG_BUDGET)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }
}
