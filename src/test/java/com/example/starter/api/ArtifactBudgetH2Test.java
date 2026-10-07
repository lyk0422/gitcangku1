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
 * 体积预算、冻结历史与幂等参数（操作者/预算）的 H2 事务测试。
 */
@SpringBootTest
class ArtifactBudgetH2Test {

    private static final String OP = "packer-li";

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

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private void registerSized(String name, int version, long size, DependencySpec... deps) {
        service.registerArtifact(rid(), OP,
                new RegisterArtifactRequest(name, version, size, List.of(deps)));
    }

    /** 题干合成数据：root:1 大小 2 依赖 a[1,2]；a2 大小 9、a1 大小 5，均依赖 b:1 大小 3。 */
    private void seedSynthetic() {
        registerSized("root", 1, 2L, dep("a", 1, 2));
        registerSized("a", 2, 9L, dep("b", 1, 1));
        registerSized("a", 1, 5L, dep("b", 1, 1));
        registerSized("b", 1, 3L);
    }

    @Test
    void budget10SelectsA1WithTotal10AndCountsSharedBOnce() {
        seedSynthetic();
        LockFileResponse lock = service.createLock(rid(), OP,
                new LockRequest("root", 1, 4L, 10L));
        assertThat(lock.budgetBytes()).isEqualTo(10L);
        assertThat(lock.totalSizeBytes()).isEqualTo(10L);
        assertThat(lock.entries()).extracting("name", "version", "sizeBytes")
                .containsExactly(tuple("a", 1, 5L), tuple("b", 1, 3L), tuple("root", 1, 2L));
    }

    @Test
    void budget9RejectsEveryCombinationWith422AndNoLock() {
        seedSynthetic();
        // 最小组合 root2+a1 5+b3 = 10 > 9；所有候选回退完仍无解。
        assertThatThrownBy(() -> service.createLock(rid(), OP,
                new LockRequest("root", 1, 4L, 9L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        // 失败不推进版本、不占键：同 requestId 随后可用于成功的放宽预算锁定。
        String id = rid();
        assertThatThrownBy(() -> service.createLock(id, OP, new LockRequest("root", 1, 4L, 9L)))
                .isInstanceOf(ApiException.class);
        LockFileResponse later = service.createLock(id, OP, new LockRequest("root", 1, 4L, 10L));
        assertThat(later.totalSizeBytes()).isEqualTo(10L);
    }

    @Test
    void zeroBudgetLocksOnlyAllZeroSizeCompleteGraph() {
        registerSized("app", 1, 0L, dep("lib", 1, 1));
        registerSized("lib", 1, 0L);
        LockFileResponse lock = service.createLock(rid(), OP,
                new LockRequest("app", 1, 2L, 0L));
        assertThat(lock.totalSizeBytes()).isZero();

        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        // 根大小非 0：预算 0 无解。
        registerSized("fat", 1, 1L);
        assertThatThrownBy(() -> service.createLock(rid(), OP,
                new LockRequest("fat", 1, 3L, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void lockFreezesSizesAndDoesNotChangeWhenMetadataLaterChanges() {
        seedSynthetic();
        LockFileResponse first = service.createLock(rid(), OP,
                new LockRequest("root", 1, 4L, 14L));
        assertThat(first.entries()).extracting("name", "version", "sizeBytes")
                .contains(tuple("a", 2, 9L));
        // 直接改底层元数据体积（模拟后来补齐），锁历史不被改写。
        jdbcTemplate.update("UPDATE artifact SET size_bytes = 99 WHERE name = 'a' AND version = 2");
        LockFileResponse reloaded = service.getLock(first.id());
        assertThat(reloaded.totalSizeBytes()).isEqualTo(14L);
        assertThat(reloaded.entries()).filteredOn(e -> e.name().equals("a"))
                .singleElement().extracting("sizeBytes").isEqualTo(9L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT size_bytes FROM lock_file_entry WHERE lock_file_id = ? AND name = 'a'",
                Long.class, first.id())).isEqualTo(9L);
    }

    @Test
    void replayWithSameRequestIdParamsReturnsFirstCompleteResult() {
        seedSynthetic();
        String id = rid();
        LockRequest req = new LockRequest("root", 1, 4L, 10L);
        LockFileResponse first = service.createLock(id, OP, req);
        LockFileResponse replay = service.createLock(id, OP, req);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.totalSizeBytes()).isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isEqualTo(1);
    }

    @Test
    void oldSuccessKeyWithChangedBudgetReturns409() {
        seedSynthetic();
        String id = rid();
        service.createLock(id, OP, new LockRequest("root", 1, 4L, 10L));
        // 仅改预算：结构化参数不同 -> 409。
        assertThatThrownBy(() -> service.createLock(id, OP, new LockRequest("root", 1, 4L, 14L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameKeyDifferentOperatorReturns409() {
        seedSynthetic();
        String id = rid();
        service.createLock(id, OP, new LockRequest("root", 1, 4L, 10L));
        assertThatThrownBy(() -> service.createLock(id, "other-packer",
                new LockRequest("root", 1, 4L, 10L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void registerReplayDifferentSizeReturns409AndOldSizeWins() {
        String id = rid();
        ArtifactResponse first = service.registerArtifact(id, OP,
                new RegisterArtifactRequest("app", 1, 5L, List.of()));
        assertThat(first.sizeBytes()).isEqualTo(5L);
        assertThatThrownBy(() -> service.registerArtifact(id, OP,
                new RegisterArtifactRequest("app", 1, 6L, List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT size_bytes FROM artifact WHERE name = 'app' AND version = 1", Long.class))
                .isEqualTo(5L);
    }
}
