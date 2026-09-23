package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EdgeChangeRequest;
import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.ProposalVoteRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.GraphEvidenceView;
import com.example.starter.incident.dto.Responses.ProposalView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依赖图变更提案服务测试（真实 H2 MySQL 兼容内存库）：
 * 覆盖名册冻结、法定人数、兼任席位一票多席、整图后态校验（环/关闭事件/
 * 已完成任务新前置/进行中任务强制边）、整体回滚、版本失配与 requestId 幂等。
 */
@SpringBootTest
class ProposalServiceTest {

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM proposal_votes");
        jdbc.update("DELETE FROM proposal_roster_entries");
        jdbc.update("DELETE FROM dependency_change_proposals");
        jdbc.update("DELETE FROM incident_dependency_edges");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("MERGE INTO dependency_graph_meta (id, graph_version, updated_at)"
                + " KEY (id) VALUES (1, 1, CURRENT_TIMESTAMP(6))");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private EdgeChangeRequest add(String from, String to) {
        return new EdgeChangeRequest("ADD", from, to);
    }

    private EdgeChangeRequest delete(String from, String to) {
        return new EdgeChangeRequest("DELETE", from, to);
    }

    private ProposalCreateRequest proposal(String proposalKey, long version, String reviewer,
                                           List<EdgeChangeRequest> changes) {
        return new ProposalCreateRequest(key(), proposalKey, version, "业务说明", reviewer, changes);
    }

    private void voteYes(String proposalKey, String person) {
        proposalService.vote(proposalKey, person, new ProposalVoteRequest(key(), "YES"));
    }

    @Test
    void quorum_allCommandersAndReviewerYes_activatesAtomically() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        ProposalView created = proposalService.create("alice",
                proposal("PROP-1", 1, "sec", List.of(add("INC-A", "INC-B"))));
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.roster()).extracting(r -> r.personId() + ":" + r.role())
                .containsExactlyInAnyOrder("alice:COMMANDER", "bob:COMMANDER", "sec:SAFETY_REVIEWER");

        voteYes("PROP-1", "alice");
        voteYes("PROP-1", "bob");
        assertThat(proposalService.get("PROP-1").status()).isEqualTo("PENDING");

        ProposalView activated = proposalService.vote("PROP-1", "sec",
                new ProposalVoteRequest(key(), "YES"));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
        assertThat(activated.activatedGraphVersion()).isEqualTo(2L);
        assertThat(activated.beforeEdges()).isEmpty();
        assertThat(activated.afterEdges()).hasSize(1);
        assertThat(activated.afterEdges().get(0).fromIncidentKey()).isEqualTo("INC-A");
        assertThat(activated.afterEdges().get(0).toIncidentKey()).isEqualTo("INC-B");
        assertThat(activated.afterEdges().get(0).source()).isEqualTo("PROPOSAL");

        GraphEvidenceView v2 = proposalService.evidenceAtVersion(2);
        assertThat(v2.graphVersion()).isEqualTo(2);
        assertThat(v2.activatedProposalKey()).isEqualTo("PROP-1");
        assertThat(v2.edges()).hasSize(1);
        GraphEvidenceView v1 = proposalService.evidenceAtVersion(1);
        assertThat(v1.edges()).isEmpty();
        Integer version = jdbc.queryForObject(
                "SELECT graph_version FROM dependency_graph_meta WHERE id = 1", Integer.class);
        assertThat(version).isEqualTo(2);
    }

    @Test
    void anyNo_rejectsImmediately_andRejectsFurtherVotes() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        proposalService.create("alice",
                proposal("PROP-NO", 1, "sec", List.of(add("INC-A", "INC-B"))));

        ProposalView rejected = proposalService.vote("PROP-NO", "bob",
                new ProposalVoteRequest(key(), "NO"));
        assertThat(rejected.status()).isEqualTo("REJECTED");

        assertThatThrownBy(() -> voteYes("PROP-NO", "alice"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(proposalService.evidenceAtVersion(1).edges()).isEmpty();
    }

    @Test
    void nonRosterMember_cannotVote() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        proposalService.create("alice",
                proposal("PROP-X", 1, "sec", List.of(add("INC-A", "INC-B"))));
        assertThatThrownBy(() -> voteYes("PROP-X", "carol"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        // 失败不占票：名册仍无人投票，提案保持 PENDING
        assertThat(proposalService.get("PROP-X").votes()).isEmpty();
    }

    @Test
    void samePersonHoldingMultipleSeats_oneVoteCoversAllSeats() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // 安全审核员 alice 同时是 INC-A 指挥官：名册 3 个席位但只有 2 名人员
        proposalService.create("alice",
                proposal("PROP-DUAL", 1, "alice", List.of(add("INC-A", "INC-B"))));
        ProposalView view = proposalService.get("PROP-DUAL");
        assertThat(view.roster()).hasSize(3);

        voteYes("PROP-DUAL", "alice");
        assertThat(proposalService.get("PROP-DUAL").status()).isEqualTo("PENDING");
        // 同一人员不能重复投票（即使承担多个席位）
        assertThatThrownBy(() -> voteYes("PROP-DUAL", "alice"))
                .isInstanceOf(ApiException.class);
        ProposalView activated = proposalService.vote("PROP-DUAL", "bob",
                new ProposalVoteRequest(key(), "YES"));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
        assertThat(activated.votes()).hasSize(2);
    }

    @Test
    void rosterFreeze_notRewrittenByLaterCommanderTransfer() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        proposalService.create("alice",
                proposal("PROP-FREEZE", 1, "sec", List.of(add("INC-A", "INC-B"))));
        // 提案创建后指挥交接：bob → carol
        incidentService.initiateTransfer("INC-B", "bob",
                new com.example.starter.incident.dto.Requests.TransferRequest(key(), "carol"));
        incidentService.acceptTransfer("INC-B", "carol",
                new com.example.starter.incident.dto.Requests.TransferAcceptRequest(key()));

        // carol 不在冻结名册，不能投票；原 bob 仍在名册
        assertThatThrownBy(() -> voteYes("PROP-FREEZE", "carol"))
                .isInstanceOf(ApiException.class);
        voteYes("PROP-FREEZE", "alice");
        voteYes("PROP-FREEZE", "bob");
        ProposalView activated = proposalService.vote("PROP-FREEZE", "sec",
                new ProposalVoteRequest(key(), "YES"));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
        assertThat(activated.roster()).extracting(r -> r.personId())
                .containsExactlyInAnyOrder("alice", "bob", "sec");
    }

    @Test
    void activate_cycleDetectedOnPostState_wholeRollback() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // 先激活 A→B（版本升到 2）
        proposalService.create("alice",
                proposal("PROP-AB", 1, "sec", List.of(add("INC-A", "INC-B"))));
        voteYes("PROP-AB", "alice");
        voteYes("PROP-AB", "bob");
        voteYes("PROP-AB", "sec");

        // 再提案 B→A：后态成环，激活失败 409，整案回滚
        proposalService.create("bob",
                proposal("PROP-CYCLE", 2, "sec", List.of(add("INC-B", "INC-A"))));
        voteYes("PROP-CYCLE", "alice");
        voteYes("PROP-CYCLE", "bob");
        assertThatThrownBy(() -> voteYes("PROP-CYCLE", "sec"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));

        // 图未改、版本未升、触发投票随事务回滚（票不保留，可重投）
        Integer version = jdbc.queryForObject(
                "SELECT graph_version FROM dependency_graph_meta WHERE id = 1", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_dependency_edges", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
        assertThat(proposalService.get("PROP-CYCLE").status()).isEqualTo("PENDING");
        assertThat(proposalService.get("PROP-CYCLE").votes()).hasSize(2);
    }

    @Test
    void activate_closedIncidentReferenced_unprocessable() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CLOSED"));

        proposalService.create("alice",
                proposal("PROP-CLOSED", 1, "sec", List.of(add("INC-A", "INC-B"))));
        voteYes("PROP-CLOSED", "alice");
        voteYes("PROP-CLOSED", "bob");
        assertThatThrownBy(() -> voteYes("PROP-CLOSED", "sec"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThat(proposalService.get("PROP-CLOSED").status()).isEqualTo("PENDING");
    }

    @Test
    void activate_doneTaskGainsUnmetPrerequisite_unprocessable() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // A 下一个无阻塞任务已完成
        incidentService.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        incidentService.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        // 任务创建未引入边，图版本仍为 1
        assertThat(jdbc.queryForObject("SELECT graph_version FROM dependency_graph_meta WHERE id = 1",
                Integer.class)).isEqualTo(1);

        // 为 A 新增指向未遏制 B 的前置：A 已有 DONE 任务，422
        proposalService.create("alice",
                proposal("PROP-DONE", 1, "sec", List.of(add("INC-A", "INC-B"))));
        voteYes("PROP-DONE", "alice");
        voteYes("PROP-DONE", "bob");
        assertThatThrownBy(() -> voteYes("PROP-DONE", "sec"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // B 遏制后前置已满足：前两名赞成票已在各自事务提交，仅 sec 的触发票随回滚不保留，
        // sec 重新投票即可在新后态下激活
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        ProposalView activated = proposalService.vote("PROP-DONE", "sec",
                new ProposalVoteRequest(key(), "YES"));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
        assertThat(activated.votes()).hasSize(3);
    }

    @Test
    void activate_deleteEdgeDeclaredByOpenTask_conflict() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // 任务引入 A→B（TASK 来源，图版本升到 2）
        incidentService.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-B")));

        proposalService.create("alice",
                proposal("PROP-DEL", 2, "sec", List.of(delete("INC-A", "INC-B"))));
        voteYes("PROP-DEL", "alice");
        voteYes("PROP-DEL", "bob");
        assertThatThrownBy(() -> voteYes("PROP-DEL", "sec"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_dependency_edges", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void activate_deleteEdgeAfterTaskDone_succeedsAndCleansDeclaration() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        incidentService.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-B")));
        incidentService.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        incidentService.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));

        proposalService.create("alice",
                proposal("PROP-DEL2", 2, "sec", List.of(delete("INC-A", "INC-B"))));
        voteYes("PROP-DEL2", "alice");
        voteYes("PROP-DEL2", "bob");
        ProposalView activated = proposalService.vote("PROP-DEL2", "sec",
                new ProposalVoteRequest(key(), "YES"));
        assertThat(activated.status()).isEqualTo("ACTIVATED");
        assertThat(activated.afterEdges()).isEmpty();
        Integer blockers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(blockers).isZero();
    }

    @Test
    void activate_versionAdvancedSinceCreation_conflictAndNoChange() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        // 提案 1：A→B 期望版本 1
        proposalService.create("alice",
                proposal("PROP-V1", 1, "sec", List.of(add("INC-A", "INC-B"))));
        // 提案 2：A→C 同样基于版本 1，先激活，图版本升到 2
        proposalService.create("alice",
                proposal("PROP-V2", 1, "sec-2", List.of(add("INC-A", "INC-C"))));
        for (String p : List.of("alice", "bob", "sec")) {
            voteYes("PROP-V1", p);
        }
        // PROP-V1 已在 PROP-V2 之前激活（版本 2）；PROP-V2 投票完成时基线失配
        voteYes("PROP-V2", "alice");
        voteYes("PROP-V2", "carol");
        assertThatThrownBy(() -> voteYes("PROP-V2", "sec-2"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(proposalService.get("PROP-V2").status()).isEqualTo("PENDING");
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_dependency_edges", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void create_expectedVersionMismatch_conflictAndKeyNotOccupied() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        String requestId = key();
        var req = new ProposalCreateRequest(requestId, "PROP-M", 99, "说明", "sec",
                List.of(add("INC-A", "INC-B")));
        assertThatThrownBy(() -> proposalService.create("alice", req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        // 失败不占键：同一 requestId 改正参数后可成功
        var fixed = new ProposalCreateRequest(requestId, "PROP-M", 1, "说明", "sec",
                List.of(add("INC-A", "INC-B")));
        ProposalView created = proposalService.create("alice", fixed);
        assertThat(created.status()).isEqualTo("PENDING");
    }

    @Test
    void create_replayedWithReorderedEdges_sameResponse() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        String requestId = key();
        var first = new ProposalCreateRequest(requestId, "PROP-R", 1, "说明", "sec",
                List.of(add("INC-A", "INC-B")));
        ProposalView v1 = proposalService.create("alice", first);
        // 同参换序 + 结构化重复条目，视为同参重放
        var replay = new ProposalCreateRequest(requestId, "PROP-R", 1, "说明", "sec",
                List.of(add("INC-A", "INC-B"), add("INC-A", "INC-B")));
        ProposalView v2 = proposalService.create("alice", replay);
        assertThat(v2).isEqualTo(v1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dependency_change_proposals WHERE proposal_key = 'PROP-R'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void create_sameRequestIdDifferentParams_conflict() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        String requestId = key();
        proposalService.create("alice", new ProposalCreateRequest(requestId, "PROP-D1", 1,
                "说明", "sec", List.of(add("INC-A", "INC-B"))));
        assertThatThrownBy(() -> proposalService.create("alice",
                new ProposalCreateRequest(requestId, "PROP-D2", 1, "说明", "sec",
                        List.of(add("INC-B", "INC-A")))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void create_invalidChangeCountsAndSelfLoop_badRequest() {
        commanding("INC-A", "alice");
        assertThatThrownBy(() -> proposalService.create("alice",
                new ProposalCreateRequest(key(), "PROP-E1", 1, "n", "sec", List.of())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> proposalService.create("alice",
                new ProposalCreateRequest(key(), "PROP-E2", 1, "n", "sec",
                        List.of(new EdgeChangeRequest("ADD", "INC-A", "INC-A")))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        List<EdgeChangeRequest> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(add("INC-A", "INC-B"));
        }
        assertThatThrownBy(() -> proposalService.create("alice",
                new ProposalCreateRequest(key(), "PROP-E3", 1, "n", "sec", tooMany)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void vote_idempotentReplay_returnsFirstResponse() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        proposalService.create("alice",
                proposal("PROP-IR", 1, "sec", List.of(add("INC-A", "INC-B"))));
        String requestId = key();
        ProposalView first = proposalService.vote("PROP-IR", "alice",
                new ProposalVoteRequest(requestId, "YES"));
        ProposalView replay = proposalService.vote("PROP-IR", "alice",
                new ProposalVoteRequest(requestId, "YES"));
        assertThat(replay).isEqualTo(first);
        assertThat(proposalService.get("PROP-IR").votes()).hasSize(1);
    }

    @Test
    void evidence_currentVersionWithoutProposal_liveEdges() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        GraphEvidenceView current = proposalService.evidenceAtVersion(1);
        assertThat(current.graphVersion()).isEqualTo(1);
        assertThat(current.activatedProposalKey()).isNull();
        assertThat(current.edges()).isEmpty();
    }
}
