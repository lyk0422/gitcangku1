package com.example.starter.curtailment;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 削减调度接口测试：草稿、替换分配、发布容量校验、取消释放、历史与幂等。
 */
class DispatchApiTest extends AbstractApiTest {

    @Test
    void fullLifecycle() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        createCommitment("cmd-c2", "cm-2", "site-2", FROM, TO, "100");

        // 创建草稿
        createDispatch("cmd-d1", "dp-1", "feeder-1", "60",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");

        getJson("/api/dispatches/dp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.allocations", hasSize(1)));

        // 凭 expectedVersion 整体替换分配
        putJson("/api/dispatches/dp-1/allocations", """
                {"commandKey":"cmd-r1","expectedVersion":1,
                 "allocations":[{"siteId":"site-1","powerKw":"30"},{"siteId":"site-2","powerKw":"30"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.allocations", hasSize(2)));

        // 发布
        publish("cmd-p1", "dp-1");
        getJson("/api/dispatches/dp-1")
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());

        // 当前已发布查询
        getJson("/api/dispatches/published")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].dispatchKey").value("dp-1"));
        getJson("/api/dispatches/published?feederId=feeder-x")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 取消：释放容量，保留原始分配
        cancel("cmd-x1", "dp-1");
        getJson("/api/dispatches/dp-1")
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.allocations", hasSize(2)));
        getJson("/api/dispatches/published")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 历史完整保留
        getJson("/api/dispatches/dp-1/history")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventType", containsInAnyOrder(
                        "CREATED", "ALLOCATIONS_REPLACED", "PUBLISHED", "CANCELLED")));
    }

    @Test
    void draftValidationFailures() throws Exception {
        // 分配之和不等于目标功率
        postJson("/api/dispatches", """
                {"commandKey":"cmd-d1","dispatchKey":"dp-1","feederId":"f1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"60",
                 "allocations":[{"siteId":"site-1","powerKw":"50"}]}
                """.formatted(EXEC_FROM, EXEC_TO)).andExpect(status().isBadRequest());
        // 同一站点多条分配
        postJson("/api/dispatches", """
                {"commandKey":"cmd-d2","dispatchKey":"dp-2","feederId":"f1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"60",
                 "allocations":[{"siteId":"site-1","powerKw":"30"},{"siteId":"site-1","powerKw":"30"}]}
                """.formatted(EXEC_FROM, EXEC_TO)).andExpect(status().isBadRequest());
        // 分配功率为零
        postJson("/api/dispatches", """
                {"commandKey":"cmd-d3","dispatchKey":"dp-3","feederId":"f1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"60",
                 "allocations":[{"siteId":"site-1","powerKw":"0"}]}
                """.formatted(EXEC_FROM, EXEC_TO)).andExpect(status().isBadRequest());
        // 分配条数超过 50
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",")
                    .append("{\"siteId\":\"site-").append(i).append("\",\"powerKw\":\"1\"}");
        }
        many.append("]");
        postJson("/api/dispatches", """
                {"commandKey":"cmd-d4","dispatchKey":"dp-4","feederId":"f1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"51","allocations":%s}
                """.formatted(EXEC_FROM, EXEC_TO, many)).andExpect(status().isBadRequest());
        // 空分配
        postJson("/api/dispatches", """
                {"commandKey":"cmd-d5","dispatchKey":"dp-5","feederId":"f1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"60","allocations":[]}
                """.formatted(EXEC_FROM, EXEC_TO)).andExpect(status().isBadRequest());
    }

    @Test
    void replaceAllocationsVersionAndStateConflicts() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        createDispatch("cmd-d1", "dp-1", "feeder-1", "60",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");

        // 版本不匹配
        putJson("/api/dispatches/dp-1/allocations", """
                {"commandKey":"cmd-r1","expectedVersion":9,
                 "allocations":[{"siteId":"site-1","powerKw":"60"}]}
                """).andExpect(status().isConflict());

        // 发布后不能再替换
        publish("cmd-p1", "dp-1");
        putJson("/api/dispatches/dp-1/allocations", """
                {"commandKey":"cmd-r2","expectedVersion":2,
                 "allocations":[{"siteId":"site-1","powerKw":"60"}]}
                """).andExpect(status().isConflict());
    }

    @Test
    void publishRequiresCoveringCommitment() throws Exception {
        // 无任何承诺
        createDispatch("cmd-d1", "dp-1", "feeder-1", "60",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");
        postJson("/api/dispatches/dp-1/publish", "{\"commandKey\":\"cmd-p1\"}")
                .andExpect(status().isUnprocessableEntity());

        // 承诺未完整覆盖执行区间
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, "2026-10-01T11:00:00Z", "100");
        postJson("/api/dispatches/dp-1/publish", "{\"commandKey\":\"cmd-p2\"}")
                .andExpect(status().isUnprocessableEntity());

        // 失败后仍为草稿
        getJson("/api/dispatches/dp-1")
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void publishExceedingCapacityFailsAndStaysDraft() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        createDispatch("cmd-d1", "dp-1", "feeder-1", "60",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");
        createDispatch("cmd-d2", "dp-2", "feeder-1", "50",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"50\"}]");
        publish("cmd-p1", "dp-1");

        // 60 + 50 > 100：容量不足
        postJson("/api/dispatches/dp-2/publish", "{\"commandKey\":\"cmd-p2\"}")
                .andExpect(status().isUnprocessableEntity());
        getJson("/api/dispatches/dp-2")
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 恰好占满容量可以发布
        createDispatch("cmd-d3", "dp-3", "feeder-1", "40",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"40\"}]");
        postJson("/api/dispatches/dp-3/publish", "{\"commandKey\":\"cmd-p3\"}")
                .andExpect(status().isOk());

        // 时间重叠才累计：不重叠的调度不受已发布占用影响
        createDispatch("cmd-d4", "dp-4", "feeder-1", "100",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"100\"}]");
        // dp-4 与已发布调度区间相同，100 > 剩余 0，失败
        postJson("/api/dispatches/dp-4/publish", "{\"commandKey\":\"cmd-p4\"}")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void publishOnSuspendedCommitmentFails() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        createDispatch("cmd-d1", "dp-1", "feeder-1", "60",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");
        postJson("/api/commitments/cm-1/suspend", "{\"commandKey\":\"cmd-s1\"}")
                .andExpect(status().isOk());

        // 已暂停承诺不接受新发布
        postJson("/api/dispatches/dp-1/publish", "{\"commandKey\":\"cmd-p1\"}")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cancelReleasesCapacity() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        createDispatch("cmd-d1", "dp-1", "feeder-1", "90",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"90\"}]");
        publish("cmd-p1", "dp-1");

        createDispatch("cmd-d2", "dp-2", "feeder-1", "90",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"90\"}]");
        postJson("/api/dispatches/dp-2/publish", "{\"commandKey\":\"cmd-p2\"}")
                .andExpect(status().isUnprocessableEntity());

        // 取消后立即释放容量
        cancel("cmd-x1", "dp-1");
        postJson("/api/dispatches/dp-2/publish", "{\"commandKey\":\"cmd-p3\"}")
                .andExpect(status().isOk());
    }

    @Test
    void publishAndCancelStateConflicts() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        createDispatch("cmd-d1", "dp-1", "feeder-1", "60",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");

        // 草稿不能取消
        postJson("/api/dispatches/dp-1/cancel", "{\"commandKey\":\"cmd-x1\"}")
                .andExpect(status().isConflict());

        publish("cmd-p1", "dp-1");
        // 重复发布（不同命令键）：状态冲突
        postJson("/api/dispatches/dp-1/publish", "{\"commandKey\":\"cmd-p2\"}")
                .andExpect(status().isConflict());

        cancel("cmd-x2", "dp-1");
        // 重复取消：状态冲突
        postJson("/api/dispatches/dp-1/cancel", "{\"commandKey\":\"cmd-x3\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void dispatchIdempotency() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        String created = createDispatch("cmd-d1", "dp-1", "feeder-1", "60",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");

        // 创建重放：同键同参返回首次结果
        String replay = postJson("/api/dispatches", """
                {"commandKey":"cmd-d1","dispatchKey":"dp-1","feederId":"feeder-1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"60",
                 "allocations":[{"siteId":"site-1","powerKw":"60"}]}
                """.formatted(EXEC_FROM, EXEC_TO))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(created);

        // 同键改参：409
        postJson("/api/dispatches", """
                {"commandKey":"cmd-d1","dispatchKey":"dp-9","feederId":"feeder-1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"60",
                 "allocations":[{"siteId":"site-1","powerKw":"60"}]}
                """.formatted(EXEC_FROM, EXEC_TO)).andExpect(status().isConflict());

        // 发布重放：同键返回首次结果而非状态冲突
        String published = publish("cmd-p1", "dp-1");
        String publishReplay = postJson("/api/dispatches/dp-1/publish", "{\"commandKey\":\"cmd-p1\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(publishReplay).isEqualTo(published);

        // 取消重放
        String cancelled = cancel("cmd-x1", "dp-1");
        String cancelReplay = postJson("/api/dispatches/dp-1/cancel", "{\"commandKey\":\"cmd-x1\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(cancelReplay).isEqualTo(cancelled);
    }

    @Test
    void unknownDispatchReturns404() throws Exception {
        getJson("/api/dispatches/dp-none").andExpect(status().isNotFound());
        getJson("/api/dispatches/dp-none/history").andExpect(status().isNotFound());
        postJson("/api/dispatches/dp-none/publish", "{\"commandKey\":\"cmd-p1\"}")
                .andExpect(status().isNotFound());
        postJson("/api/dispatches/dp-none/cancel", "{\"commandKey\":\"cmd-x1\"}")
                .andExpect(status().isNotFound());
        putJson("/api/dispatches/dp-none/allocations", """
                {"commandKey":"cmd-r1","expectedVersion":1,
                 "allocations":[{"siteId":"site-1","powerKw":"60"}]}
                """).andExpect(status().isNotFound());
    }

    @Test
    void decimalPrecisionBoundary() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "0.003");
        createCommitment("cmd-c2", "cm-2", "site-2", FROM, TO, "0.003");

        // 三位小数精确相加等于目标功率，可创建并发布
        createDispatch("cmd-d1", "dp-1", "feeder-1", "0.003",
                "[{\"siteId\":\"site-1\",\"powerKw\":\"0.001\"},{\"siteId\":\"site-2\",\"powerKw\":\"0.002\"}]");
        postJson("/api/dispatches/dp-1/publish", "{\"commandKey\":\"cmd-p1\"}")
                .andExpect(status().isOk());

        // 超出 3 位小数的分配被拒绝
        postJson("/api/dispatches", """
                {"commandKey":"cmd-d2","dispatchKey":"dp-2","feederId":"f1",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"0.0001",
                 "allocations":[{"siteId":"site-1","powerKw":"0.0001"}]}
                """.formatted(EXEC_FROM, EXEC_TO)).andExpect(status().isBadRequest());
    }
}
