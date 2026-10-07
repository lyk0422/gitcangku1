package com.example.starter.api;

import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 使用独立内存库验证 schema.sql/data.sql 自动初始化的合成数据：
 * root:1（2 字节）依赖 a[1,2]，a2=9、a1=5，均依赖共享 b:1=3；预算 10 选 a1 总 10。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:artifact_lock_seed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class SyntheticSeedDataTest {

    @Autowired
    private ArtifactService service;

    @Test
    void seedDataLocksA1UnderBudget10AndCountsSharedBOnce() {
        // data.sql 完成 4 个制品 + 3 条依赖登记，仓库版本仍为 0（种子不经过写业务）。
        LockFileResponse lock = service.createLock("seed-lock-budget-10", "seed-operator",
                new LockRequest("root", 1, 0L, 10L));
        assertThat(lock.totalSizeBytes()).isEqualTo(10L);
        assertThat(lock.budgetBytes()).isEqualTo(10L);
        assertThat(lock.entries()).extracting("name", "version", "sizeBytes")
                .containsExactly(tuple("a", 1, 5L), tuple("b", 1, 3L), tuple("root", 1, 2L));
    }

    @Test
    void seedDataWithBudget9HasNoSolution() {
        try {
            service.createLock("seed-lock-budget-9-missing", "seed-operator",
                    new LockRequest("root", 1, 0L, 9L));
            throw new AssertionError("预算 9 应无解 422");
        } catch (com.example.starter.support.ApiException e) {
            assertThat(e.getStatus()).isEqualTo(422);
        }
    }
}
