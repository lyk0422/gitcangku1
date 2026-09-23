package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 限时授权委托链 API 集成测试（真实 H2 数据库）：
 * 覆盖多层委托写入依据、最短路径、环/深度/跨域限制、到期层级、边撤销级联、
 * 续建版本、幂等与失败整体回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class DelegationApiTest {

    private static final Instant T0 = TestClockConfiguration.BASE_TIME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableTimeSource clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_delegation");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.setNow(T0);
    }

    private ResultActions grant(String requestId, String subject, Instant expiresAt) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","expiresAt":"%s"}
                        """.formatted(requestId, subject, expiresAt)));
    }

    private ResultActions delegate(String requestId, String key, String subject, int epoch,
                                   String delegator, String processor, Instant expiresAt) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/delegations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","delegationKey":"%s","subjectKey":"%s","purpose":"RESEARCH",
                         "epoch":%d,"delegatorKey":"%s","processorKey":"%s","expiresAt":"%s"}
                        """.formatted(requestId, key, subject, epoch, delegator, processor, expiresAt)));
    }

    private ResultActions revokeDelegation(String requestId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/delegations/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","delegationKey":"%s"}
                        """.formatted(requestId, key)));
    }

    private ResultActions writeVia(String requestId, String subject, String recordKey,
                                   String pathJson, String versionsJson) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","recordKey":"%s",
                         "payload":"data","delegationPath":%s,"edgeVersions":%s}
                        """.formatted(requestId, subject, recordKey, pathJson, versionsJson)));
    }

    private ResultActions chain(String subject, int epoch, String processor) throws Exception {
        return mockMvc.perform(get("/api/v1/consents/delegations/chain")
                .param("subjectKey", subject)
                .param("purpose", "RESEARCH")
                .param("epoch", String.valueOf(epoch))
                .param("processorKey", processor));
    }

    private int count(String sql) {
        Integer cnt = jdbc.queryForObject(sql, Integer.class);
        return cnt == null ? 0 : cnt;
    }

    private void setupTwoLevelChain() throws Exception {
        grant("g1", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        delegate("d1", "k1", "alice", 1, "alice", "p1", T0.plusSeconds(1800))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        delegate("d2", "k2", "alice", 1, "p1", "p2", T0.plusSeconds(900))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 主流程：委托链写入与不可变依据 ----------

    @Test
    void processorWritesWithFullChainAndVersionsAndBasisIsPersisted() throws Exception {
        setupTwoLevelChain();
        writeVia("w1", "alice", "rec-1", "[\"p1\",\"p2\"]", "[1,1]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.delegationPath[0]").value("p1"))
                .andExpect(jsonPath("$.delegationPath[1]").value("p2"))
                .andExpect(jsonPath("$.edgeVersions[0]").value(1))
                .andExpect(jsonPath("$.edgeVersions[1]").value(1))
                .andExpect(jsonPath("$.evaluatedAt").value(T0.toString()));
        // 持久化的不可变写入依据
        assertThat(jdbc.queryForObject(
                "SELECT delegation_path FROM consent_record WHERE record_key = 'rec-1'", String.class))
                .isEqualTo("[\"p1\",\"p2\"]");
        assertThat(jdbc.queryForObject(
                "SELECT edge_versions FROM consent_record WHERE record_key = 'rec-1'", String.class))
                .isEqualTo("[1,1]");
        // 当前有效链只读查询返回最短路径及各边版本
        chain("alice", 1, "p2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(2))
                .andExpect(jsonPath("$.edges[0].delegatorKey").value("alice"))
                .andExpect(jsonPath("$.edges[0].version").value(1))
                .andExpect(jsonPath("$.edges[1].processorKey").value("p2"));
    }

    @Test
    void intermediateProcessorWritesWithPrefixPath() throws Exception {
        setupTwoLevelChain();
        writeVia("w1", "alice", "rec-1", "[\"p1\"]", "[1]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delegationPath.length()").value(1));
    }

    // ---------- 失败分支：缺失 / 撤销 / 过期 / 版本 / 最短路径 ----------

    @Test
    void writeWithMissingEdgeReturns403AndWritesNothing() throws Exception {
        setupTwoLevelChain();
        writeVia("w-bad", "alice", "rec-x", "[\"p1\",\"p9\"]", "[1,1]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_PATH_INVALID"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w-bad'")).isZero();
    }

    @Test
    void writeThroughRevokedEdgeReturns403AndOnlyAffectsFutureWrites() throws Exception {
        setupTwoLevelChain();
        writeVia("w1", "alice", "rec-1", "[\"p1\",\"p2\"]", "[1,1]").andExpect(status().isOk());
        revokeDelegation("rd1", "k1").andExpect(status().isOk());
        // 撤销边后，下游 p2 的新写入被拒，历史记录保留
        writeVia("w2", "alice", "rec-2", "[\"p1\",\"p2\"]", "[1,1]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_EDGE_REVOKED"));
        // 同 requestId 同参重放仍返回首次结果（撤边只影响后续“新”写入）
        writeVia("w1", "alice", "rec-1", "[\"p1\",\"p2\"]", "[1,1]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-1"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        // 有效链查询不再包含 p2
        chain("alice", 1, "p2").andExpect(status().isNotFound());
    }

    @Test
    void writeThroughExpiredEdgeReturns403() throws Exception {
        setupTwoLevelChain();
        // p1->p2 在 T0+900 到期；时钟推进到 T0+1000，p1 本身仍有效
        clock.setNow(T0.plusSeconds(1000));
        writeVia("w-bad", "alice", "rec-x", "[\"p1\",\"p2\"]", "[1,1]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_EDGE_EXPIRED"));
        // 上层 p1 仍可写
        writeVia("w1", "alice", "rec-1", "[\"p1\"]", "[1]").andExpect(status().isOk());
        // p1 到期后 p1 也不可写，主体直写不受影响
        clock.setNow(T0.plusSeconds(1900));
        writeVia("w-bad2", "alice", "rec-x", "[\"p1\"]", "[1]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_EDGE_EXPIRED"));
        writeVia("w2", "alice", "rec-2", "[]", "[]").andExpect(status().isOk());
    }

    @Test
    void writeWithStaleVersionAfterRenewalReturns403AndNewVersionSucceeds() throws Exception {
        setupTwoLevelChain();
        revokeDelegation("rd1", "k1").andExpect(status().isOk());
        // 续建同一有向边，版本递增到 2
        delegate("d1-renew", "k1b", "alice", 1, "alice", "p1", T0.plusSeconds(1800))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 旧版本 1 已撤销 -> 403
        writeVia("w-old", "alice", "rec-x", "[\"p1\"]", "[1]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_EDGE_REVOKED"));
        // 新版本 2 写入成功
        writeVia("w-new", "alice", "rec-1", "[\"p1\"]", "[2]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edgeVersions[0]").value(2));
    }

    @Test
    void renewExpiredEdgeWithoutRevocationCreatesNextVersion() throws Exception {
        setupTwoLevelChain();
        clock.setNow(T0.plusSeconds(950));
        // k2 已到期但 status 仍 ACTIVE：允许续建为版本 2
        delegate("d2-renew", "k2b", "alice", 1, "p1", "p2", T0.plusSeconds(1700))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        clock.setNow(T0.plusSeconds(1000));
        writeVia("w1", "alice", "rec-1", "[\"p1\",\"p2\"]", "[1,2]").andExpect(status().isOk());
    }

    @Test
    void writeWithNonExistentVersionButExistingEdgeReturns409() throws Exception {
        setupTwoLevelChain();
        writeVia("w-bad", "alice", "rec-x", "[\"p1\",\"p2\"]", "[1,9]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATION_VERSION_CONFLICT"));
    }

    @Test
    void writeWithNonShortestPathReturns409() throws Exception {
        setupTwoLevelChain();
        // 增加直达边 alice->p2，形成更短路径
        delegate("d3", "k3", "alice", 1, "alice", "p2", T0.plusSeconds(900))
                .andExpect(status().isOk());
        // 绕远路径 [p1,p2] 不再是最短有效路径 -> 409
        writeVia("w-long", "alice", "rec-x", "[\"p1\",\"p2\"]", "[1,1]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATION_PATH_NOT_SHORTEST"));
        // 最短直达路径可写
        writeVia("w-short", "alice", "rec-1", "[\"p2\"]", "[1]").andExpect(status().isOk());
    }

    @Test
    void pathAndVersionSizeMismatchReturns400() throws Exception {
        setupTwoLevelChain();
        writeVia("w-bad", "alice", "rec-x", "[\"p1\",\"p2\"]", "[1]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELEGATION_PATH_INVALID"));
    }

    // ---------- 委托建立限制 ----------

    @Test
    void chainDeeperThanFiveLayersIsRejected() throws Exception {
        grant("g1", "alice", T0.plusSeconds(7200)).andExpect(status().isOk());
        delegate("d1", "k1", "alice", 1, "alice", "p1", T0.plusSeconds(7000)).andExpect(status().isOk());
        delegate("d2", "k2", "alice", 1, "p1", "p2", T0.plusSeconds(6900)).andExpect(status().isOk());
        delegate("d3", "k3", "alice", 1, "p2", "p3", T0.plusSeconds(6800)).andExpect(status().isOk());
        delegate("d4", "k4", "alice", 1, "p3", "p4", T0.plusSeconds(6700)).andExpect(status().isOk());
        // 第 5 层允许
        delegate("d5", "k5", "alice", 1, "p4", "p5", T0.plusSeconds(6600))
                .andExpect(status().isOk());
        // 第 6 层拒绝
        delegate("d6", "k6", "alice", 1, "p5", "p6", T0.plusSeconds(6500))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_FORBIDDEN"));
    }

    @Test
    void cyclicDelegationIsRejectedAndRolledBack() throws Exception {
        grant("g1", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        delegate("d1", "k1", "alice", 1, "alice", "p1", T0.plusSeconds(1800)).andExpect(status().isOk());
        delegate("d2", "k2", "alice", 1, "p1", "p2", T0.plusSeconds(1700)).andExpect(status().isOk());
        // p2 -> alice 形成环
        delegate("d3", "k3", "alice", 1, "p2", "alice", T0.plusSeconds(1600))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_FORBIDDEN"));
        // 自环也拒绝
        delegate("d4", "k4", "alice", 1, "p1", "p1", T0.plusSeconds(1600))
                .andExpect(status().isForbidden());
        // 失败整体回滚：未留下任何边或幂等记录
        assertThat(count("SELECT COUNT(*) FROM consent_delegation WHERE delegation_key IN ('k3','k4')"))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id IN ('d3','d4')"))
                .isZero();
        // 图仍可用：p1 正常写
        writeVia("w1", "alice", "rec-1", "[\"p1\"]", "[1]").andExpect(status().isOk());
    }

    @Test
    void duplicateActiveEdgeReturns409AndDelegationKeyIsGloballyUnique() throws Exception {
        grant("g1", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        delegate("d1", "k1", "alice", 1, "alice", "p1", T0.plusSeconds(1800)).andExpect(status().isOk());
        // 同一有向边仍有效时重复建立
        delegate("d2", "k2", "alice", 1, "alice", "p1", T0.plusSeconds(1700))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATION_DUPLICATE_EDGE"));
        // delegationKey 全局唯一：另一主体也不能复用 k1
        grant("gb", "bob", T0.plusSeconds(3600)).andExpect(status().isOk());
        delegate("d3", "k1", "bob", 1, "bob", "x1", T0.plusSeconds(1800))
                .andExpect(status().isConflict());
    }

    @Test
    void delegationExpiresLaterThanGrantOrParentIsRejected() throws Exception {
        grant("g1", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        // 晚于授权到期
        delegate("d-bad1", "kb1", "alice", 1, "alice", "p1", T0.plusSeconds(3601))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELEGATION_EXPIRES_INVALID"));
        delegate("d1", "k1", "alice", 1, "alice", "p1", T0.plusSeconds(1800)).andExpect(status().isOk());
        // 晚于上级委托到期
        delegate("d-bad2", "kb2", "alice", 1, "p1", "p2", T0.plusSeconds(1801))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELEGATION_EXPIRES_INVALID"));
        // 到期时刻不晚于当前时刻
        delegate("d-bad3", "kb3", "alice", 1, "alice", "p3", T0)
                .andExpect(status().isBadRequest());
    }

    @Test
    void delegationFromUnknownDelegatorOutsideChainIsRejected() throws Exception {
        grant("g1", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        // 委托方 outsider 不在 alice 链内：禁止跨 subject/purpose/epoch 的委托
        delegate("d-bad", "kb", "alice", 1, "outsider", "p1", T0.plusSeconds(1000))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_FORBIDDEN"));
        // 不存在的代次
        delegate("d-bad2", "kb2", "alice", 9, "alice", "p1", T0.plusSeconds(1000))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotDelegateUnderRevokedOrExpiredGrant() throws Exception {
        grant("g1", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        jdbc.update("UPDATE consent_grant SET status = 'REVOKED' WHERE subject_key = 'alice'");
        delegate("d-bad", "kb", "alice", 1, "alice", "p1", T0.plusSeconds(1000))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        jdbc.update("UPDATE consent_grant SET status = 'ACTIVE', expires_at = ? WHERE subject_key = 'alice'",
                java.sql.Timestamp.from(T0.minusSeconds(1)));
        delegate("d-bad2", "kb2", "alice", 1, "alice", "p1", T0.plusSeconds(1000))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GRANT_EXPIRED"));
    }

    // ---------- 主体撤回收紧：整链失效，新代隔离 ----------

    @Test
    void subjectRevocationInvalidatesWholeChainAndNewEpochIsIsolated() throws Exception {
        setupTwoLevelChain();
        writeVia("w1", "alice", "rec-1", "[\"p1\",\"p2\"]", "[1,1]").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/consents/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r1","subjectKey":"alice","purpose":"RESEARCH","epoch":1}
                                """))
                .andExpect(status().isOk());
        // 旧代整条链失效：处理方写入 410，委托新增 410，链查询 410
        writeVia("w2", "alice", "rec-2", "[\"p1\",\"p2\"]", "[1,1]")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        delegate("d3", "k3", "alice", 1, "p2", "p3", T0.plusSeconds(800))
                .andExpect(status().isGone());
        chain("alice", 1, "p2").andExpect(status().isGone());
        // 新代次：旧边不可用，旧数据不可读
        grant("g2", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        writeVia("w3", "alice", "rec-1", "[\"p1\",\"p2\"]", "[1,1]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_PATH_INVALID"));
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "alice")
                        .param("purpose", "RESEARCH")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        // 新代重新委托后可写
        delegate("nd1", "nk1", "alice", 2, "alice", "p1", T0.plusSeconds(1800)).andExpect(status().isOk());
        writeVia("w4", "alice", "rec-new", "[\"p1\"]", "[1]").andExpect(status().isOk());
    }

    // ---------- 委托幂等 ----------

    @Test
    void delegationIdempotencyReplayConflictAndFailureDoesNotConsumeKey() throws Exception {
        grant("g1", "alice", T0.plusSeconds(3600)).andExpect(status().isOk());
        delegate("d1", "k1", "alice", 1, "alice", "p1", T0.plusSeconds(1800)).andExpect(status().isOk());
        // 同 requestId 同参重放
        delegate("d1", "k1", "alice", 1, "alice", "p1", T0.plusSeconds(1800))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 同 requestId 异参 -> 409
        delegate("d1", "k1", "alice", 1, "alice", "p2", T0.plusSeconds(1800))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 失败的委托不占 requestId：稍后用相同 requestId 成功
        delegate("d-fail", "kfail", "alice", 1, "alice", "p1", T0.plusSeconds(1700))
                .andExpect(status().isConflict());
        delegate("d-fail", "kfail", "alice", 1, "alice", "p9", T0.plusSeconds(1700))
                .andExpect(status().isOk());
        // 撤销幂等：重复撤销已撤销边（新 requestId）-> 409；同 requestId 重放回首次结果
        revokeDelegation("rd1", "k1").andExpect(status().isOk());
        revokeDelegation("rd2", "k1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELEGATION_ALREADY_REVOKED"));
        revokeDelegation("rd1", "k1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 撤销不存在的边 -> 404
        revokeDelegation("rdx", "no-such-key")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELEGATION_NOT_FOUND"));
    }
}
