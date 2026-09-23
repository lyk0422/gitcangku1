package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.GraphActivateRequest;
import com.example.starter.incident.dto.Requests.GraphProposalCreateRequest;
import com.example.starter.incident.dto.Requests.GraphProposalEdgeRequest;
import com.example.starter.incident.dto.Requests.GraphVoteRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.GraphView;
import com.example.starter.incident.dto.Responses.ProposalView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依赖图变更提案服务测试：覆盖提案创建校验与边集规范化、名册冻结、法定人数票决、
 * 兼任席位一票多席、激活整图校验（成环/关闭事件/已完成任务前置/强制边）、
 * 整体回滚、requestId 幂等与按 graphVersion 证据查询。
 */
@SpringBootTest
class GraphProposalServiceTest {

    @Autowired
    private GraphProposalService graphService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM graph_snapshots");
        jdbc.update("DELETE FROM graph_proposal_votes");
        jdbc.update("DELETE FROM graph_proposal_roster");
        jdbc.update("DELETE FROM graph_proposal_edges");
        jdbc.update("DELETE FROM graph_proposals");
        jdbc.update("DELETE FROM graph_edges");
        jdbc.update("DELETE FROM graph_version");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "依赖图场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private long graphVersion() {
        return graphService.getGraph().version();
    }

    private static GraphProposalEdgeRequest add(String from, String to) {
        return new GraphProposalEdgeRequest("ADD", from, to);
    }

    private static GraphProposalEdgeRequest remove(String from, String to) {
        return new GraphProposalEdgeRequest("REMOVE", from, to);
    }

    private GraphProposalCreateRequest createReq(String requestId, String proposalKey,
                                                 String reviewer, GraphProposalEdgeRequest... edges) {
        return new GraphProposalCreateRequest(requestId, proposalKey, graphVersion(),
                "变更说明", reviewer, List.of(edges));
    }

    private ProposalView approve(String proposalKey, String person) {
        return graphService.vote(proposalKey, person, new GraphVoteRequest(key(), "APPROVE"));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void createProposal_mainFlow_rosterFrozen() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        long version = graphVersion();

        ProposalView created = graphService.createProposal("pm-1",
                createReq(key(), "GP-1", "carol", add("INC-A", "INC-B")));

        assertThat(created.proposalKey()).isEqualTo("GP-1");
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.proposer()).isEqualTo("pm-1");
        assertThat(created.safetyReviewer()).isEqualTo("carol");
        assertThat(created.expectedGraphVersion()).isEqualTo(version);
        assertThat(created.appliedGraphVersion()).isNull();
        assertThat(created.edges()).hasSize(1);
        assertThat(created.edges().get(0).operation()).isEqualTo("ADD");
        assertThat(created.edges().get(0).fromIncidentKey()).isEqualTo("INC-A");
        assertThat(created.edges().get(0).toIncidentKey()).isEqualTo("INC-B");
        // 名册冻结：两名受影响事件指挥官 + 一名安全审核员
        assertThat(created.roster()).hasSize(3);
        assertThat(created.roster()).anySatisfy(s -> {
            assertThat(s.role()).isEqualTo("COMMANDER");
            assertThat(s.incidentKey()).isEqualTo("INC-A");
            assertThat(s.person()).isEqualTo("alice");
        });
        assertThat(created.roster()).anySatisfy(s -> {
            assertThat(s.role()).isEqualTo("COMMANDER");
            assertThat(s.incidentKey()).isEqualTo("INC-B");
            assertThat(s.person()).isEqualTo("bob");
        });
        assertThat(created.roster()).anySatisfy(s -> {
            assertThat(s.role()).isEqualTo("SAFETY_REVIEWER");
            assertThat(s.incidentKey()).isNull();
            assertThat(s.person()).isEqualTo("carol");
        });
        assertThat(created.votes()).isEmpty();
        assertThat(created.beforeSnapshot()).isNull();
        assertThat(created.afterSnapshot()).isNull();
        // 创建不改图
        assertThat(graphVersion()).isEqualTo(version);
        assertThat(graphService.getGraph().edges()).isEmpty();
        // 详情查询一致
        assertThat(graphService.getProposal("GP-1")).isEqualTo(created);
    }

    @Test
    void createProposal_validation() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // 空边集
        assertApiStatus(() -> graphService.createProposal("pm",
                        new GraphProposalCreateRequest(key(), "GP-V1", graphVersion(), "r", "carol",
                                List.of())),
                HttpStatus.BAD_REQUEST);
        // 超过 50 条边（去重后仍超限；条数校验先于事件解析）
        List<GraphProposalEdgeRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(add("INC-A", "INC-X" + i));
        }
        assertApiStatus(() -> graphService.createProposal("pm",
                        new GraphProposalCreateRequest(key(), "GP-V2", graphVersion(), "r", "carol",
                                tooMany)),
                HttpStatus.BAD_REQUEST);
        // 自环边
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(key(), "GP-V3", "carol", add("INC-A", "INC-A"))),
                HttpStatus.BAD_REQUEST);
        // 未知边操作
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(key(), "GP-V4", "carol",
                                new GraphProposalEdgeRequest("LINK", "INC-A", "INC-B"))),
                HttpStatus.BAD_REQUEST);
        // 同一对事件同时新增与删除
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(key(), "GP-V5", "carol", add("INC-A", "INC-B"),
                                remove("INC-A", "INC-B"))),
                HttpStatus.BAD_REQUEST);
        // 引用不存在事件
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(key(), "GP-V6", "carol", add("INC-A", "INC-404"))),
                HttpStatus.NOT_FOUND);
        // 受影响事件无指挥官，无法冻结名册
        incidentService.report(new ReportRequest("INC-C", "S2", "s", "r"));
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(key(), "GP-V7", "carol", add("INC-A", "INC-C"))),
                HttpStatus.CONFLICT);
        // expectedGraphVersion 与当前版本不匹配
        assertApiStatus(() -> graphService.createProposal("pm",
                        new GraphProposalCreateRequest(key(), "GP-V8", graphVersion() + 1, "r",
                                "carol", List.of(add("INC-A", "INC-B")))),
                HttpStatus.CONFLICT);
        // 必填字段为空
        assertApiStatus(() -> graphService.createProposal("pm",
                        new GraphProposalCreateRequest(key(), "GP-V9", graphVersion(), " ", "carol",
                                List.of(add("INC-A", "INC-B")))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> graphService.createProposal("pm",
                        new GraphProposalCreateRequest(key(), "GP-V9", graphVersion(), "r", " ",
                                List.of(add("INC-A", "INC-B")))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> graphService.createProposal("pm",
                        new GraphProposalCreateRequest(key(), "GP-V9", null, "r", "carol",
                                List.of(add("INC-A", "INC-B")))),
                HttpStatus.BAD_REQUEST);
        // proposalKey 全局唯一
        graphService.createProposal("pm", createReq(key(), "GP-DUP", "carol", add("INC-A", "INC-B")));
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(key(), "GP-DUP", "carol", add("INC-B", "INC-A"))),
                HttpStatus.CONFLICT);
    }

    @Test
    void createProposal_edgeNormalizationAndRequestId() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        String requestId = key();
        // 重复边结构化去重：3 条输入去重为 2 条
        ProposalView first = graphService.createProposal("pm",
                createReq(requestId, "GP-N1", "dave",
                        add("INC-A", "INC-B"), add("INC-A", "INC-B"), add("INC-B", "INC-C")));
        assertThat(first.edges()).hasSize(2);
        // 同 requestId 边换序视为同参：重放首次响应
        ProposalView replay = graphService.createProposal("pm",
                createReq(requestId, "GP-N1", "dave",
                        add("INC-B", "INC-C"), add("INC-A", "INC-B")));
        assertThat(replay).isEqualTo(first);
        Integer proposalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_proposals", Integer.class);
        assertThat(proposalCount).isEqualTo(1);
        // 同 requestId 异参 → 409
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(requestId, "GP-N1", "dave", add("INC-A", "INC-C"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> graphService.createProposal("other-pm",
                        createReq(requestId, "GP-N1", "dave",
                                add("INC-A", "INC-B"), add("INC-B", "INC-C"))),
                HttpStatus.CONFLICT);
    }

    @Test
    void createProposal_failureDoesNotConsumeRequestId() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        String requestId = key();
        // 业务失败（事件不存在）事务回滚，不占键
        assertApiStatus(() -> graphService.createProposal("pm",
                        createReq(requestId, "GP-F1", "carol", add("INC-A", "INC-404"))),
                HttpStatus.NOT_FOUND);
        ProposalView ok = graphService.createProposal("pm",
                createReq(requestId, "GP-F1", "carol", add("INC-A", "INC-B")));
        assertThat(ok.status()).isEqualTo("PENDING");
    }

    @Test
    void vote_quorumAndRejection() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        graphService.createProposal("pm", createReq(key(), "GP-Q1", "carol", add("INC-A", "INC-B")));

        // 非名册成员不能投票
        assertApiStatus(() -> graphService.vote("GP-Q1", "mallory",
                new GraphVoteRequest(key(), "APPROVE")), HttpStatus.CONFLICT);
        // 非法表决方向
        assertApiStatus(() -> graphService.vote("GP-Q1", "alice",
                new GraphVoteRequest(key(), "MAYBE")), HttpStatus.BAD_REQUEST);
        // 部分赞成：仍 PENDING
        ProposalView afterAlice = approve("GP-Q1", "alice");
        assertThat(afterAlice.status()).isEqualTo("PENDING");
        assertThat(afterAlice.votes()).hasSize(1);
        // 重复投票 → 409（仅首票有效）
        assertApiStatus(() -> graphService.vote("GP-Q1", "alice",
                new GraphVoteRequest(key(), "APPROVE")), HttpStatus.CONFLICT);
        ProposalView afterBob = approve("GP-Q1", "bob");
        assertThat(afterBob.status()).isEqualTo("PENDING");
        // 全体指挥官且安全审核员赞成后 APPROVED
        ProposalView approved = approve("GP-Q1", "carol");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.votes()).hasSize(3);
        // APPROVED 后不能再投票
        assertApiStatus(() -> graphService.vote("GP-Q1", "alice",
                new GraphVoteRequest(key(), "REJECT")), HttpStatus.CONFLICT);

        // 任一反对即整案 REJECTED
        graphService.createProposal("pm", createReq(key(), "GP-Q2", "carol", add("INC-B", "INC-A")));
        approve("GP-Q2", "alice");
        ProposalView rejected = graphService.vote("GP-Q2", "carol",
                new GraphVoteRequest(key(), "REJECT"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertApiStatus(() -> graphService.vote("GP-Q2", "bob",
                new GraphVoteRequest(key(), "APPROVE")), HttpStatus.CONFLICT);
        // REJECTED 不能激活
        assertApiStatus(() -> graphService.activate("GP-Q2", "alice",
                new GraphActivateRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void vote_requestIdIdempotency() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        graphService.createProposal("pm", createReq(key(), "GP-I1", "carol", add("INC-A", "INC-B")));
        String voteReq = key();
        ProposalView first = graphService.vote("GP-I1", "alice",
                new GraphVoteRequest(voteReq, "APPROVE"));
        ProposalView replay = graphService.vote("GP-I1", "alice",
                new GraphVoteRequest(voteReq, "APPROVE"));
        assertThat(replay).isEqualTo(first);
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_proposal_votes", Integer.class);
        assertThat(voteCount).isEqualTo(1);
        // 同 requestId 改表决 → 409
        assertApiStatus(() -> graphService.vote("GP-I1", "alice",
                new GraphVoteRequest(voteReq, "REJECT")), HttpStatus.CONFLICT);
    }

    @Test
    void vote_multiRolePerson_singleVoteSatisfiesAllSeats() {
        // alice 同时是两个受影响事件的指挥官，且兼任安全审核员：一人一票满足全部席位
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        ProposalView created = graphService.createProposal("pm",
                createReq(key(), "GP-M1", "alice", add("INC-A", "INC-B")));
        assertThat(created.roster()).hasSize(3);
        assertThat(created.roster()).allSatisfy(s -> assertThat(s.person()).isEqualTo("alice"));

        ProposalView after = approve("GP-M1", "alice");
        assertThat(after.status()).isEqualTo("APPROVED");
        assertThat(after.votes()).hasSize(1);
        assertThat(after.votes().get(0).person()).isEqualTo("alice");
    }

    @Test
    void roster_frozenAcrossTransfer() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        graphService.createProposal("pm", createReq(key(), "GP-T1", "carol", add("INC-A", "INC-B")));
        // 创建后指挥交接不改写名册：新指挥官无投票权，冻结的旧指挥官仍可投票
        incidentService.initiateTransfer("INC-A", "alice", new TransferRequest(key(), "dave"));
        incidentService.acceptTransfer("INC-A", "dave", new TransferAcceptRequest(key()));

        assertApiStatus(() -> graphService.vote("GP-T1", "dave",
                new GraphVoteRequest(key(), "APPROVE")), HttpStatus.CONFLICT);
        ProposalView afterAlice = approve("GP-T1", "alice");
        assertThat(afterAlice.status()).isEqualTo("PENDING");
        assertThat(afterAlice.roster()).anySatisfy(s -> {
            assertThat(s.incidentKey()).isEqualTo("INC-A");
            assertThat(s.person()).isEqualTo("alice");
        });
    }

    @Test
    void activate_mainFlow_generatesSingleNewVersionAndSnapshots() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        // 既有边 INC-B -> INC-C（任务声明）
        incidentService.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-C")));
        long baseVersion = graphVersion();
        graphService.createProposal("pm",
                createReq(key(), "GP-A1", "dave", add("INC-A", "INC-B"), remove("INC-B", "INC-C")));
        approve("GP-A1", "alice");
        approve("GP-A1", "bob");
        approve("GP-A1", "carol");
        approve("GP-A1", "dave");
        // REMOVE 的边仍被 OPEN 任务声明为强制：先完成任务解除强制
        assertApiStatus(() -> graphService.activate("GP-A1", "alice",
                new GraphActivateRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        incidentService.changeStatus("INC-C", "carol", new StatusRequest(key(), "CONTAINED"));
        incidentService.completeTask("INC-B", "T-1", "bob", new TaskActionRequest(key()));

        ProposalView activated = graphService.activate("GP-A1", "alice",
                new GraphActivateRequest(key()));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
        assertThat(activated.appliedGraphVersion()).isEqualTo(baseVersion + 1);
        // 图只生成一个新版本
        assertThat(graphVersion()).isEqualTo(baseVersion + 1);
        GraphView graph = graphService.getGraph();
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).fromIncidentKey()).isEqualTo("INC-A");
        assertThat(graph.edges().get(0).toIncidentKey()).isEqualTo("INC-B");
        // 前后边集快照
        assertThat(activated.beforeSnapshot().graphVersion()).isEqualTo(baseVersion);
        assertThat(activated.beforeSnapshot().edges()).hasSize(1);
        assertThat(activated.beforeSnapshot().edges().get(0).fromIncidentKey()).isEqualTo("INC-B");
        assertThat(activated.afterSnapshot().graphVersion()).isEqualTo(baseVersion + 1);
        assertThat(activated.afterSnapshot().edges()).hasSize(1);
        assertThat(activated.afterSnapshot().edges().get(0).fromIncidentKey()).isEqualTo("INC-A");
        // 按 graphVersion 还原提案证据
        assertThat(graphService.listByGraphVersion(baseVersion)).isEmpty();
        List<ProposalView> evidence = graphService.listByGraphVersion(baseVersion + 1);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0)).isEqualTo(activated);
        // 重复激活（异 requestId）→ 409；同 requestId 重放首次响应
        assertApiStatus(() -> graphService.activate("GP-A1", "alice",
                new GraphActivateRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void activate_requestIdIdempotency() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        graphService.createProposal("pm", createReq(key(), "GP-AI", "carol", add("INC-A", "INC-B")));
        approve("GP-AI", "alice");
        approve("GP-AI", "bob");
        approve("GP-AI", "carol");
        String activateReq = key();
        ProposalView first = graphService.activate("GP-AI", "alice",
                new GraphActivateRequest(activateReq));
        ProposalView replay = graphService.activate("GP-AI", "alice",
                new GraphActivateRequest(activateReq));
        assertThat(replay).isEqualTo(first);
        assertThat(graphVersion()).isEqualTo(first.appliedGraphVersion());
        Integer edgeCount = jdbc.queryForObject("SELECT COUNT(*) FROM graph_edges", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void activate_requiresApprovedAndRosterMember() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        graphService.createProposal("pm", createReq(key(), "GP-S1", "carol", add("INC-A", "INC-B")));
        // PENDING 不能激活
        assertApiStatus(() -> graphService.activate("GP-S1", "alice",
                new GraphActivateRequest(key())), HttpStatus.CONFLICT);
        approve("GP-S1", "alice");
        approve("GP-S1", "bob");
        approve("GP-S1", "carol");
        // 非名册成员不能激活
        assertApiStatus(() -> graphService.activate("GP-S1", "mallory",
                new GraphActivateRequest(key())), HttpStatus.CONFLICT);
        // 提案不存在
        assertApiStatus(() -> graphService.activate("GP-404", "alice",
                new GraphActivateRequest(key())), HttpStatus.NOT_FOUND);
    }

    @Test
    void activate_versionMismatch_conflictAndGraphUnchanged() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        graphService.createProposal("pm", createReq(key(), "GP-V1", "dave", add("INC-A", "INC-B")));
        approve("GP-V1", "alice");
        approve("GP-V1", "bob");
        approve("GP-V1", "dave");
        // 其他图变更（任务新增边）先行提交，图版本推进
        incidentService.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-A")));
        long versionAfterTask = graphVersion();
        assertApiStatus(() -> graphService.activate("GP-V1", "alice",
                new GraphActivateRequest(key())), HttpStatus.CONFLICT);
        // 整案失败不改图：版本与边集保持任务提交后的状态
        assertThat(graphVersion()).isEqualTo(versionAfterTask);
        GraphView graph = graphService.getGraph();
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).fromIncidentKey()).isEqualTo("INC-C");
        // 提案仍为 APPROVED，不进入 ACTIVATED
        assertThat(graphService.getProposal("GP-V1").status()).isEqualTo("APPROVED");
    }

    @Test
    void activate_cycleRejected_atomically() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // 既有边 INC-B -> INC-A
        incidentService.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-A")));
        long version = graphVersion();
        graphService.createProposal("pm", createReq(key(), "GP-C1", "carol", add("INC-A", "INC-B")));
        approve("GP-C1", "alice");
        approve("GP-C1", "bob");
        approve("GP-C1", "carol");
        // 后态 A->B 与 B->A 成环：422 且不改图
        assertApiStatus(() -> graphService.activate("GP-C1", "alice",
                new GraphActivateRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(graphVersion()).isEqualTo(version);
        assertThat(graphService.getGraph().edges()).hasSize(1);
        assertThat(graphService.getProposal("GP-C1").status()).isEqualTo("APPROVED");
        Integer snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_snapshots", Integer.class);
        assertThat(snapshotCount).isZero();
    }

    @Test
    void activate_closedIncidentRejected() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        graphService.createProposal("pm", createReq(key(), "GP-Z1", "carol", add("INC-A", "INC-B")));
        approve("GP-Z1", "alice");
        approve("GP-Z1", "bob");
        approve("GP-Z1", "carol");
        // 投票后目标事件关闭：新增边引用关闭事件 → 422
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CLOSED"));
        long version = graphVersion();
        assertApiStatus(() -> graphService.activate("GP-Z1", "alice",
                new GraphActivateRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(graphVersion()).isEqualTo(version);
        assertThat(graphService.getGraph().edges()).isEmpty();
    }

    @Test
    void activate_doneTaskUnsatisfiedPrerequisiteRejected() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // INC-A 有已完成任务
        incidentService.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        incidentService.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        graphService.createProposal("pm", createReq(key(), "GP-D1", "carol", add("INC-A", "INC-B")));
        approve("GP-D1", "alice");
        approve("GP-D1", "bob");
        approve("GP-D1", "carol");
        // INC-B 未解除阻塞：新增边会让已完成任务新增未满足前置依赖 → 422
        assertApiStatus(() -> graphService.activate("GP-D1", "alice",
                new GraphActivateRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 目标事件遏制后前置依赖已满足：可激活
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        ProposalView activated = graphService.activate("GP-D1", "alice",
                new GraphActivateRequest(key()));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
    }

    @Test
    void activate_removeMandatoryEdgeRejected_thenAllowedAfterTaskDone() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        incidentService.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-B")));
        graphService.createProposal("pm",
                createReq(key(), "GP-R1", "carol", remove("INC-A", "INC-B")));
        approve("GP-R1", "alice");
        approve("GP-R1", "bob");
        approve("GP-R1", "carol");
        // 边仍被进行中任务声明为强制：422 且不改图
        long version = graphVersion();
        assertApiStatus(() -> graphService.activate("GP-R1", "alice",
                new GraphActivateRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(graphVersion()).isEqualTo(version);
        assertThat(graphService.getGraph().edges()).hasSize(1);
        // 任务完成后边不再强制：新提案可删除
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        incidentService.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        graphService.createProposal("pm",
                createReq(key(), "GP-R2", "carol", remove("INC-A", "INC-B")));
        approve("GP-R2", "alice");
        approve("GP-R2", "bob");
        approve("GP-R2", "carol");
        ProposalView activated = graphService.activate("GP-R2", "alice",
                new GraphActivateRequest(key()));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
        assertThat(graphService.getGraph().edges()).isEmpty();
    }

    @Test
    void activate_removeNonexistentEdge_conflict() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        graphService.createProposal("pm",
                createReq(key(), "GP-RX", "carol", remove("INC-A", "INC-B")));
        approve("GP-RX", "alice");
        approve("GP-RX", "bob");
        approve("GP-RX", "carol");
        // 要删除的边不在当前图中 → 409
        assertApiStatus(() -> graphService.activate("GP-RX", "alice",
                new GraphActivateRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void getGraph_reflectsTaskEdgesWithStableOrder() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        assertThat(graphService.getGraph().version()).isEqualTo(0);
        assertThat(graphService.getGraph().edges()).isEmpty();
        incidentService.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-A", "INC-B")));
        GraphView graph = graphService.getGraph();
        assertThat(graph.version()).isEqualTo(1);
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.edges().get(0).toIncidentKey()).isEqualTo("INC-A");
        assertThat(graph.edges().get(1).toIncidentKey()).isEqualTo("INC-B");
        // 重复声明同一边不再推进版本
        incidentService.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "T-2", "G", "t2", List.of("INC-A")));
        assertThat(graphService.getGraph().version()).isEqualTo(1);
    }

    @Test
    void getProposal_notFound() {
        assertApiStatus(() -> graphService.getProposal("GP-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> graphService.vote("GP-404", "alice",
                new GraphVoteRequest(key(), "APPROVE")), HttpStatus.NOT_FOUND);
    }
}
