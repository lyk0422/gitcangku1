package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import com.example.starter.incident.dto.Responses.HandoverHistoryView;
import com.example.starter.incident.dto.Responses.HandoverIncidentSummaryView;
import com.example.starter.incident.dto.Responses.HandoverView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 联合指挥交接服务测试：覆盖闭包计算、冻结摘要、422/400/403/409 失败分支、
 * 单事务整体切换与不可变快照、commandKey 幂等（换序同参/异参 409/失败不占键）、
 * 版本号随状态/任务/升级变化及只读查询。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class JointHandoverServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private JointHandoverService handoverService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM joint_handover_snapshots");
        jdbc.update("DELETE FROM joint_handover_incidents");
        jdbc.update("DELETE FROM joint_handovers");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "故障 " + incidentKey, "r"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void openTask(String incidentKey, String commander, String taskKey,
                          List<String> blockers) {
        incidentService.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "G", "任务 " + taskKey, blockers));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> details(ApiException e) {
        return (List<Object>) e.details();
    }

    private HandoverView initiate(String handoverKey, String actor, String to,
                                  List<String> incidentKeys) {
        return handoverService.initiate(actor, new HandoverInitiateRequest(
                key(), handoverKey, to, incidentKeys));
    }

    @Test
    void mainFlow_closureFreezeAcceptAndSnapshot() {
        // 依赖链：A 的 OPEN 任务阻塞 B，B 的 OPEN 任务阻塞 C；均由 alice 指挥
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("C", "alice");
        openTask("A", "alice", "TA", List.of("B"));
        openTask("B", "alice", "TB", List.of("C"));

        // 遗漏闭包事件 C → 422 并列出缺失事件
        ApiException ex = catchApi(() -> initiate("H-MISS", "alice", "bob", List.of("A", "B")));
        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getMessage()).contains("C");
        assertThat(details(ex)).containsExactly("C");

        // 恰好覆盖闭包（换序提交同参）→ 冻结成功
        HandoverView preview = initiate("H-1", "alice", "bob", List.of("C", "A", "B"));
        assertThat(preview.status()).isEqualTo("PENDING");
        assertThat(preview.handoverVersion()).hasSize(64);
        assertThat(preview.closureIncidentKeys()).containsExactly("A", "B", "C");
        assertThat(preview.submittedIncidentKeys()).containsExactly("C", "A", "B");
        assertThat(preview.acceptedAt()).isNull();
        List<HandoverIncidentSummaryView> rows = preview.summary().incidents();
        assertThat(rows).extracting(HandoverIncidentSummaryView::incidentKey)
                .containsExactly("A", "B", "C");
        // 接管后事件版本为 1；OPEN 任务版本为 0；无未确认升级
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.commander()).isEqualTo("alice");
            assertThat(r.status()).isEqualTo("COMMANDING");
            assertThat(r.incidentVersion()).isEqualTo(1);
            assertThat(r.escalationVersion()).isNull();
        });
        assertThat(rows.get(0).openTasks()).singleElement().satisfies(t -> {
            assertThat(t.taskKey()).isEqualTo("TA");
            assertThat(t.version()).isZero();
            assertThat(t.status()).isEqualTo("OPEN");
            assertThat(t.blockers()).containsExactly("B");
        });
        assertThat(rows.get(1).openTasks()).singleElement().satisfies(t ->
                assertThat(t.blockers()).containsExactly("C"));
        assertThat(rows.get(2).openTasks()).isEmpty();

        // 接受：单事务整体切换
        HandoverView accepted = handoverService.accept("bob", "H-1",
                new HandoverAcceptRequest(key(), preview.handoverVersion(), preview.summary()));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.acceptedAt()).isEqualTo(T0);
        for (String ik : List.of("A", "B", "C")) {
            IncidentView after = incidentService.get(ik);
            assertThat(after.commander()).isEqualTo("bob");
            assertThat(after.status()).isEqualTo("COMMANDING");
        }

        // 不可变闭包快照对应切换时一致状态：指挥人 bob、版本 +1、OPEN 任务与冻结一致
        HandoverDetailView detail = handoverService.detail("H-1");
        assertThat(detail.snapshots()).hasSize(3);
        assertThat(detail.snapshots()).allSatisfy(s -> {
            assertThat(s.commander()).isEqualTo("bob");
            assertThat(s.incidentStatus()).isEqualTo("COMMANDING");
            assertThat(s.incidentVersion()).isEqualTo(2);
        });
        assertThat(detail.snapshots().get(0).openTasks()).singleElement().satisfies(t -> {
            assertThat(t.taskKey()).isEqualTo("TA");
            assertThat(t.blockers()).containsExactly("B");
        });
        assertThat(detail.closureIncidents()).extracting(c -> c.incidentKey())
                .containsExactly("A", "B", "C");
        assertThat(detail.closureIncidents()).allMatch(c -> c.inSubmitted());

        // 历史查询：发起人与接收人均可见
        HandoverHistoryView bobHistory = handoverService.history("bob");
        assertThat(bobHistory.handovers()).singleElement()
                .satisfies(h -> assertThat(h.handover().handoverKey()).isEqualTo("H-1"));
        assertThat(handoverService.history("alice").handovers()).hasSize(1);

        // 只读查询不写数据
        long handoverRows = jdbc.queryForObject("SELECT COUNT(*) FROM joint_handovers", Long.class);
        handoverService.detail("H-1");
        handoverService.history("bob");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM joint_handovers", Long.class))
                .isEqualTo(handoverRows);
    }

    @Test
    void closure_onlyFollowsOpenTasksAndUnresolvedBlockers() {
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("D", "alice");
        commanding("E", "alice");
        openTask("A", "alice", "OPEN-T", List.of("B"));
        // 已取消（非 OPEN）任务的阻塞关系不参与闭包（取消不受阻塞门禁限制）
        openTask("A", "alice", "CANCEL-T", List.of("D"));
        incidentService.cancelTask("A", "CANCEL-T", "alice", new TaskActionRequest(key()));
        // E 进入 CONTAINED：阻塞关系视为已完成，不进入闭包（任务保持 OPEN）
        openTask("A", "alice", "BLOCK-E", List.of("E"));
        incidentService.changeStatus("E", "alice", new StatusRequest(key(), "CONTAINED"));

        HandoverView preview = initiate("H-CL", "alice", "bob", List.of("A", "B"));
        // D 仅被 CANCELLED 任务阻塞、E 已 CONTAINED，均不在闭包
        assertThat(preview.closureIncidentKeys()).containsExactly("A", "B");
    }

    @Test
    void transitiveClosure_autoExpandsAndLocks() {
        // A → B → C → D 四级传递依赖，只提交 A、B 时依次缺 C、D
        for (String ik : List.of("A", "B", "C", "D")) {
            commanding(ik, "alice");
        }
        openTask("A", "alice", "TA", List.of("B"));
        openTask("B", "alice", "TB", List.of("C"));
        openTask("C", "alice", "TC", List.of("D"));

        ApiException first = catchApi(() -> initiate("H-X1", "alice", "bob", List.of("A", "B")));
        assertThat(first.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(details(first)).containsExactly("C", "D");

        ApiException second = catchApi(() -> initiate("H-X2", "alice", "bob",
                List.of("A", "B", "C")));
        assertThat(details(second)).containsExactly("D");

        HandoverView preview = initiate("H-X3", "alice", "bob", List.of("A", "B", "C", "D"));
        assertThat(preview.closureIncidentKeys()).containsExactly("A", "B", "C", "D");
        // 全部为提交事件
        assertThat(handoverService.detail("H-X3").closureIncidents())
                .allMatch(c -> c.inSubmitted());
    }

    @Test
    void transitiveDependencyOwnedByOther_missing422ThenIncluding403() {
        commanding("A", "alice");
        commanding("B", "bob");
        commanding("C", "alice");
        openTask("A", "alice", "TA", List.of("B"));

        // 两个本人事件满足最少 2 个提交事件；传递依赖 B 由 bob 指挥且未提交 → 422
        ApiException missing = catchApi(() -> initiate("H-OWN1", "alice", "carol",
                List.of("A", "C")));
        assertThat(missing.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(details(missing)).containsExactly("B");

        // 直接把他人指挥事件混入提交集合 → 403
        ApiException forbidden = catchApi(() -> initiate("H-OWN2", "alice", "carol",
                List.of("A", "B", "C")));
        assertThat(forbidden.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidSubmission_400Branches() {
        commanding("A", "alice");
        commanding("B", "alice");
        commanding("R", "alice");
        // 事件数少于 2
        assertApiStatus(() -> initiate("H-S1", "alice", "bob", List.of("A")),
                HttpStatus.BAD_REQUEST);
        // 事件数多于 20
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String ik = "M" + i;
            commanding(ik, "alice");
            many.add(ik);
        }
        List<String> tooMany = new ArrayList<>(many);
        tooMany.add("A");
        assertApiStatus(() -> initiate("H-S2", "alice", "bob", tooMany),
                HttpStatus.BAD_REQUEST);
        // 重复键
        assertApiStatus(() -> initiate("H-S3", "alice", "bob", List.of("A", "A")),
                HttpStatus.BAD_REQUEST);
        // 接收人就是发起人
        assertApiStatus(() -> initiate("H-S4", "alice", "alice", List.of("A", "B")),
                HttpStatus.BAD_REQUEST);
        // 混入终态（RESOLVED）事件：其无 OPEN 任务时可直达 RESOLVED
        incidentService.changeStatus("R", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("R", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> initiate("H-S5", "alice", "bob", List.of("A", "R")),
                HttpStatus.BAD_REQUEST);
        // 不存在的事件 → 404
        assertApiStatus(() -> initiate("H-S6", "alice", "bob", List.of("A", "NOPE")),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicateHandoverKey_409() {
        commanding("A", "alice");
        commanding("B", "alice");
        initiate("H-DUP", "alice", "bob", List.of("A", "B"));
        assertApiStatus(() -> initiate("H-DUP", "alice", "carol", List.of("A", "B")),
                HttpStatus.CONFLICT);
    }

    @Test
    void staleAccept_anyChange409AndNoPartialSwitch() {
        // 场景 1：接受前一个事件状态推进（版本变化）
        commanding("A", "alice");
        commanding("B", "alice");
        openTask("A", "alice", "TA", List.of("B"));
        HandoverView p1 = initiate("H-V1", "alice", "bob", List.of("A", "B"));
        incidentService.changeStatus("B", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> handoverService.accept("bob", "H-V1",
                new HandoverAcceptRequest(key(), p1.handoverVersion(), p1.summary())),
                HttpStatus.CONFLICT);
        assertNoSwitchAndNoSnapshot("H-V1", List.of("A", "B"));

        // 场景 2：OPEN 任务被取消（任务从冻结集合消失）
        HandoverView p2 = initiate("H-V2", "alice", "bob", List.of("A", "B"));
        incidentService.cancelTask("A", "TA", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> handoverService.accept("bob", "H-V2",
                new HandoverAcceptRequest(key(), p2.handoverVersion(), p2.summary())),
                HttpStatus.CONFLICT);
        assertNoSwitchAndNoSnapshot("H-V2", List.of("A", "B"));

        // 场景 3：新增 OPEN 任务
        HandoverView p3 = initiate("H-V3", "alice", "bob", List.of("A", "B"));
        openTask("B", "alice", "TNEW", List.of());
        assertApiStatus(() -> handoverService.accept("bob", "H-V3",
                new HandoverAcceptRequest(key(), p3.handoverVersion(), p3.summary())),
                HttpStatus.CONFLICT);
        assertNoSwitchAndNoSnapshot("H-V3", List.of("A", "B"));

        // 场景 4：单事件转交先提交，指挥人变化（A 已合法切给 carol，B 仍为 alice）
        HandoverView p4 = initiate("H-V4", "alice", "bob", List.of("A", "B"));
        incidentService.initiateTransfer("A", "alice", new TransferRequest(key(), "carol"));
        incidentService.acceptTransfer("A", "carol", new TransferAcceptRequest(key()));
        assertApiStatus(() -> handoverService.accept("bob", "H-V4",
                new HandoverAcceptRequest(key(), p4.handoverVersion(), p4.summary())),
                HttpStatus.CONFLICT);
        assertThat(incidentService.get("A").commander()).isEqualTo("carol");
        assertThat(incidentService.get("B").commander()).isEqualTo("alice");
        assertThat(handoverService.detail("H-V4").handover().status()).isEqualTo("PENDING");
        assertThat(handoverService.detail("H-V4").snapshots()).isEmpty();

        // 场景 5：expectedHandoverVersion 篡改（使用新的一对事件，避免前序状态干扰）
        commanding("A2", "alice");
        commanding("B2", "alice");
        HandoverView p5 = initiate("H-V5", "alice", "bob", List.of("A2", "B2"));
        assertApiStatus(() -> handoverService.accept("bob", "H-V5",
                new HandoverAcceptRequest(key(), "deadbeef", p5.summary())),
                HttpStatus.CONFLICT);
        assertThat(incidentService.get("A2").commander()).isEqualTo("alice");
        assertThat(incidentService.get("B2").commander()).isEqualTo("alice");
        assertThat(handoverService.detail("H-V5").handover().status()).isEqualTo("PENDING");
        assertThat(handoverService.detail("H-V5").snapshots()).isEmpty();
    }

    @Test
    void staleAccept_unacknowledgedEscalationChanged409() {
        // 接管时刻 T0：S2 期限 15 分钟 → T0+900；之后推进时钟到期限时刻触发升级
        commanding("A", "alice");
        commanding("B", "alice");
        openTask("A", "alice", "TA", List.of("B"));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(900));
        incidentService.checkEscalation("A", new EscalationCheckRequest(key()));
        HandoverView preview = initiate("H-E1", "alice", "bob", List.of("A", "B"));
        HandoverIncidentSummaryView rowA = preview.summary().incidents().get(0);
        assertThat(rowA.escalationVersion()).isZero();

        // 冻结后确认升级 → 版本变化且不再是未确认升级
        incidentService.acknowledgeEscalation("A", "alice",
                new EscalationAckRequest(key(), "升级说明"));
        assertApiStatus(() -> handoverService.accept("bob", "H-E1",
                new HandoverAcceptRequest(key(), preview.handoverVersion(), preview.summary())),
                HttpStatus.CONFLICT);
        assertNoSwitchAndNoSnapshot("H-E1", List.of("A", "B"));
    }

    @Test
    void staleAccept_terminalEventRejected() {
        commanding("A", "alice");
        commanding("B", "alice");
        openTask("A", "alice", "TA", List.of("B"));
        HandoverView preview = initiate("H-T1", "alice", "bob", List.of("A", "B"));
        // B 无 OPEN 任务，推进到 RESOLVED（终态事件拒绝接受）
        incidentService.changeStatus("B", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("B", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> handoverService.accept("bob", "H-T1",
                new HandoverAcceptRequest(key(), preview.handoverVersion(), preview.summary())),
                HttpStatus.CONFLICT);
        assertNoSwitchAndNoSnapshot("H-T1", List.of("A", "B"));
    }

    @Test
    void accept_wrongRecipientAndTerminalHandover() {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView preview = initiate("H-A1", "alice", "bob", List.of("A", "B"));
        // 非指定接收人 → 409
        assertApiStatus(() -> handoverService.accept("carol", "H-A1",
                new HandoverAcceptRequest(key(), preview.handoverVersion(), preview.summary())),
                HttpStatus.CONFLICT);
        // 合法接受
        handoverService.accept("bob", "H-A1",
                new HandoverAcceptRequest(key(), preview.handoverVersion(), preview.summary()));
        // 终态交接单重复接受 → 409
        assertApiStatus(() -> handoverService.accept("bob", "H-A1",
                new HandoverAcceptRequest(key(), preview.handoverVersion(), preview.summary())),
                HttpStatus.CONFLICT);
        // 接受不存在的交接单 → 404
        assertApiStatus(() -> handoverService.accept("bob", "NOPE",
                new HandoverAcceptRequest(key(), preview.handoverVersion(), preview.summary())),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void initiate_idempotentReorderAndDifferentParams() {
        commanding("A", "alice");
        commanding("B", "alice");
        String commandKey = key();
        HandoverView first = handoverService.initiate("alice", new HandoverInitiateRequest(
                commandKey, "H-IDEM", "bob", List.of("A", "B")));
        // 集合换序、同操作同操作者同结构化参数 → 重放首次响应
        HandoverView replay = handoverService.initiate("alice", new HandoverInitiateRequest(
                commandKey, "H-IDEM", "bob", List.of("B", "A")));
        assertThat(replay).isEqualTo(first);
        // 同键异参（换接收人）→ 409
        assertApiStatus(() -> handoverService.initiate("alice", new HandoverInitiateRequest(
                        commandKey, "H-IDEM", "carol", List.of("A", "B"))),
                HttpStatus.CONFLICT);
        // 同键不同操作（accept 复用）→ 409
        assertApiStatus(() -> handoverService.accept("bob", "H-IDEM",
                        new HandoverAcceptRequest(commandKey, first.handoverVersion(),
                                first.summary())),
                HttpStatus.CONFLICT);
    }

    @Test
    void initiate_failureDoesNotOccupyKey() {
        commanding("A", "alice");
        commanding("B", "alice");
        openTask("A", "alice", "TA", List.of("B"));
        String commandKey = key();
        // 首次失败：闭包还隐含 C？这里构造遗漏需要 3 个事件
        commanding("C", "alice");
        openTask("B", "alice", "TB", List.of("C"));
        assertApiStatus(() -> handoverService.initiate("alice", new HandoverInitiateRequest(
                        commandKey, "H-FK", "bob", List.of("A", "B"))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 失败不占键：同键以完整参数重试成功
        HandoverView ok = handoverService.initiate("alice", new HandoverInitiateRequest(
                commandKey, "H-FK", "bob", List.of("A", "B", "C")));
        assertThat(ok.closureIncidentKeys()).containsExactly("A", "B", "C");
    }

    @Test
    void accept_idempotentReplay() {
        commanding("A", "alice");
        commanding("B", "alice");
        HandoverView preview = initiate("H-AR", "alice", "bob", List.of("A", "B"));
        String commandKey = key();
        HandoverView first = handoverService.accept("bob", "H-AR",
                new HandoverAcceptRequest(commandKey, preview.handoverVersion(), preview.summary()));
        // 同键同参重放：返回首次 ACCEPTED 响应，不再产生切换/快照
        HandoverView replay = handoverService.accept("bob", "H-AR",
                new HandoverAcceptRequest(commandKey, preview.handoverVersion(), preview.summary()));
        assertThat(replay).isEqualTo(first);
        Long snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handover_snapshots s JOIN joint_handovers h"
                        + " ON s.handover_id = h.id WHERE h.handover_key = 'H-AR'", Long.class);
        assertThat(snapshots).isEqualTo(2);
        // 同键异参 → 409
        assertApiStatus(() -> handoverService.accept("bob", "H-AR",
                        new HandoverAcceptRequest(commandKey, "other", preview.summary())),
                HttpStatus.CONFLICT);
    }

    private void assertNoSwitchAndNoSnapshot(String handoverKey, List<String> incidentKeys) {
        for (String ik : incidentKeys) {
            IncidentView view = incidentService.get(ik);
            assertThat(view.commander()).isEqualTo("alice");
        }
        HandoverDetailView detail = handoverService.detail(handoverKey);
        assertThat(detail.handover().status()).isEqualTo("PENDING");
        assertThat(detail.snapshots()).isEmpty();
    }

    private ApiException catchApi(ThrowingCallable call) {
        try {
            call.call();
            throw new AssertionError("期望抛出 ApiException");
        } catch (ApiException e) {
            return e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }
}
