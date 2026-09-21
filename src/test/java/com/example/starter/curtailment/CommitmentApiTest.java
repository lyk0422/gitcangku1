package com.example.starter.curtailment;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 容量承诺接口测试：创建校验、区间重叠、暂停规则与幂等。
 */
class CommitmentApiTest extends AbstractApiTest {

    @Test
    void createCommitmentSuccess() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "10.5");

        getJson("/api/commitments/cm-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitmentKey").value("cm-1"))
                .andExpect(jsonPath("$.siteId").value("site-1"))
                .andExpect(jsonPath("$.maxPowerKw").value("10.5"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createCommitmentRejectsInvalidParams() throws Exception {
        // 小数位超过 3 位
        postJson("/api/commitments", """
                {"commandKey":"cmd-b1","commitmentKey":"cm-b1","siteId":"site-1",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"1.0001"}
                """.formatted(FROM, TO)).andExpect(status().isBadRequest());
        // 区间起点不早于终点
        postJson("/api/commitments", """
                {"commandKey":"cmd-b2","commitmentKey":"cm-b2","siteId":"site-1",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"1"}
                """.formatted(TO, FROM)).andExpect(status().isBadRequest());
        // 功率为零
        postJson("/api/commitments", """
                {"commandKey":"cmd-b3","commitmentKey":"cm-b3","siteId":"site-1",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"0"}
                """.formatted(FROM, TO)).andExpect(status().isBadRequest());
        // 缺少 commandKey
        postJson("/api/commitments", """
                {"commitmentKey":"cm-b4","siteId":"site-1",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"1"}
                """.formatted(FROM, TO)).andExpect(status().isBadRequest());
    }

    @Test
    void overlappingCommitmentRejectedButAdjacentAllowed() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "10");

        // 相邻区间合法
        postJson("/api/commitments", """
                {"commandKey":"cmd-c2","commitmentKey":"cm-2","siteId":"site-1",
                 "validFrom":"%s","validTo":"2026-10-03T00:00:00Z","maxPowerKw":"10"}
                """.formatted(TO)).andExpect(status().isOk());

        // 重叠区间冲突
        postJson("/api/commitments", """
                {"commandKey":"cmd-c3","commitmentKey":"cm-3","siteId":"site-1",
                 "validFrom":"2026-10-01T12:00:00Z","validTo":"2026-10-03T00:00:00Z","maxPowerKw":"10"}
                """).andExpect(status().isConflict());

        // 不同站点允许重叠
        postJson("/api/commitments", """
                {"commandKey":"cmd-c4","commitmentKey":"cm-4","siteId":"site-2",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"10"}
                """.formatted(FROM, TO)).andExpect(status().isOk());
    }

    @Test
    void duplicateCommitmentKeyConflict() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "10");
        postJson("/api/commitments", """
                {"commandKey":"cmd-c2","commitmentKey":"cm-1","siteId":"site-2",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"5"}
                """.formatted(FROM, TO)).andExpect(status().isConflict());
    }

    @Test
    void idempotentReplayAndChangedParams() throws Exception {
        String first = createCommitment("cmd-same", "cm-1", "site-1", FROM, TO, "10");

        // 同键同参重放：返回首次结果，且不产生新记录
        String replay = postJson("/api/commitments", """
                {"commandKey":"cmd-same","commitmentKey":"cm-1","siteId":"site-1",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"10"}
                """.formatted(FROM, TO))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(first);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_commitment WHERE commitment_key = 'cm-1'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);

        // 同键改参：409
        postJson("/api/commitments", """
                {"commandKey":"cmd-same","commitmentKey":"cm-1","siteId":"site-1",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"20"}
                """.formatted(FROM, TO)).andExpect(status().isConflict());
    }

    @Test
    void suspendLifecycle() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "10");

        postJson("/api/commitments/cm-1/suspend", "{\"commandKey\":\"cmd-s1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // 重复暂停（不同命令键）：状态冲突
        postJson("/api/commitments/cm-1/suspend", "{\"commandKey\":\"cmd-s2\"}")
                .andExpect(status().isConflict());

        // 同键重放：返回首次结果
        postJson("/api/commitments/cm-1/suspend", "{\"commandKey\":\"cmd-s1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void suspendBlockedByPublishedDispatch() throws Exception {
        createCommitment("cmd-c1", "cm-1", "site-1", FROM, TO, "100");
        createDispatch("cmd-d1", "dp-1", "feeder-1", "60", "[{\"siteId\":\"site-1\",\"powerKw\":\"60\"}]");
        publish("cmd-p1", "dp-1");

        // 已发布调度落在承诺区间内：不能暂停
        postJson("/api/commitments/cm-1/suspend", "{\"commandKey\":\"cmd-s1\"}")
                .andExpect(status().isConflict());

        // 取消调度后即可暂停
        cancel("cmd-x1", "dp-1");
        postJson("/api/commitments/cm-1/suspend", "{\"commandKey\":\"cmd-s2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void missingCommitmentReturns404() throws Exception {
        getJson("/api/commitments/cm-none").andExpect(status().isNotFound());
        postJson("/api/commitments/cm-none/suspend", "{\"commandKey\":\"cmd-s1\"}")
                .andExpect(status().isNotFound());
    }
}
