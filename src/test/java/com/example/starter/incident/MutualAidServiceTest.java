package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.LeaseReturnRequest;
import com.example.starter.incident.dto.Requests.MutualAidRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.LeaseDetailView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.ResourceInventoryView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨事件互助资源征调服务测试：覆盖题干主流程、来源不足/排他占用/超额归还的 422
 * 与无部分变更、requestId 幂等（同参重放、改数量/目标 409）、到期不虚构归还、
 * 分次归还流水、历史租约两端事件保持不变。时间使用可控 Clock，不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class MutualAidServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    @Autowired
    private MutualAidService mutualAid;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_resource_returns");
        jdbc.update("DELETE FROM incident_resource_leases");
        jdbc.update("DELETE FROM incident_resource_inventories");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidents.report(new ReportRequest(incidentKey, "S2", "互助场景", "reporter-1"));
        incidents.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private ResourceInventoryView register(String incidentKey, String commander,
                                           String resourceCode, long qty) {
        return mutualAid.register(incidentKey, commander,
                new ResourceRegisterRequest(key(), resourceCode, qty));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private long availableOf(String incidentKey, String resourceCode) {
        Long qty = jdbc.queryForObject(
                "SELECT i.available_qty FROM incident_resource_inventories i"
                        + " JOIN incidents n ON n.id = i.incident_id"
                        + " WHERE n.incident_key = ? AND i.resource_code = ?",
                Long.class, incidentKey, resourceCode);
        assertThat(qty).isNotNull();
        return qty;
    }

    @Test
    void mainFlow_request5of3ReturnTwiceAndOverReturn422() {
        // 事件 A 有资源 R 5 件，征调 3 件给 B：A 剩 2、B 租约 3
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);

        LeaseView lease = mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-1", "INC-A", "R", 3, 60));
        assertThat(lease.status()).isEqualTo("OPEN");
        assertThat(lease.quantity()).isEqualTo(3);
        assertThat(lease.returnedQty()).isZero();
        assertThat(lease.outstandingQty()).isEqualTo(3);
        assertThat(lease.sourceIncidentKey()).isEqualTo("INC-A");
        assertThat(lease.targetIncidentKey()).isEqualTo("INC-B");
        assertThat(lease.expiresAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(lease.closedAt()).isNull();
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);

        // B 归还 2：待还 1，A 可用恢复到 4，租约仍 OPEN
        LeaseView afterFirst = mutualAid.returnResource("INC-B", "REQ-1", "bob",
                new LeaseReturnRequest(2));
        assertThat(afterFirst.returnedQty()).isEqualTo(2);
        assertThat(afterFirst.outstandingQty()).isEqualTo(1);
        assertThat(afterFirst.status()).isEqualTo("OPEN");
        assertThat(afterFirst.closedAt()).isNull();
        assertThat(availableOf("INC-A", "R")).isEqualTo(4L);

        // 再归还 2（待还仅 1）：422，且无部分变更（待还仍 1、A 可用仍 4、流水仍 1 条）
        assertApiStatus(() -> mutualAid.returnResource("INC-B", "REQ-1", "bob",
                new LeaseReturnRequest(2)), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(availableOf("INC-A", "R")).isEqualTo(4L);
        Integer returnRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resource_returns", Integer.class);
        assertThat(returnRows).isEqualTo(1);

        // 归还剩余 1：恰好结清关闭，A 可用恢复 5；关闭后再归还 422，不重复释放来源额度
        LeaseView closed = mutualAid.returnResource("INC-B", "REQ-1", "bob",
                new LeaseReturnRequest(1));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.returnedQty()).isEqualTo(3);
        assertThat(closed.outstandingQty()).isZero();
        assertThat(closed.closedAt()).isEqualTo(T0);
        assertThat(availableOf("INC-A", "R")).isEqualTo(5L);

        assertApiStatus(() -> mutualAid.returnResource("INC-B", "REQ-1", "bob",
                new LeaseReturnRequest(1)), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(availableOf("INC-A", "R")).isEqualTo(5L);

        // 关联查询：来源扣减台账、目标租约、分次归还流水
        LeaseDetailView detail = mutualAid.getLease("INC-B", "REQ-1");
        assertThat(detail.lease().status()).isEqualTo("CLOSED");
        assertThat(detail.sourceInventory().incidentKey()).isEqualTo("INC-A");
        assertThat(detail.sourceInventory().availableQty()).isEqualTo(5L);
        assertThat(detail.returns()).hasSize(2);
        assertThat(detail.returns().get(0).quantity()).isEqualTo(2L);
        assertThat(detail.returns().get(0).returnedBy()).isEqualTo("bob");
        assertThat(detail.returns().get(0).returnedAt()).isEqualTo(T0);
        assertThat(detail.returns().get(1).quantity()).isEqualTo(1L);

        // 来源事件也可查询同一关联视图
        LeaseDetailView fromSource = mutualAid.getLease("INC-A", "REQ-1");
        assertThat(fromSource.lease().id()).isEqualTo(detail.lease().id());
    }

    @Test
    void insufficientSource_422AndNoPartialChange() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 2);

        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-X1", "INC-A", "R", 3, 60)),
                HttpStatus.UNPROCESSABLE_ENTITY);

        // 两侧均不留部分变更：A 可用仍 2，无租约、无流水
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);
        Integer leases = jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_leases",
                Integer.class);
        assertThat(leases).isZero();
        Integer returns = jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_returns",
                Integer.class);
        assertThat(returns).isZero();
        // 失败不占 requestId：换用同键的合法数量可以成功
        LeaseView retry = mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-X1", "INC-A", "R", 2, 60));
        assertThat(retry.quantity()).isEqualTo(2);
    }

    @Test
    void exclusiveLeaseOccupied_422() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        register("INC-A", "alice", "R", 5);
        mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-O1", "INC-A", "R", 3, 60));
        // R 尚有 3 件未归还：即便账面可用 2 件，也不得再征调（排他租约占用）
        assertApiStatus(() -> mutualAid.requestAid("INC-C", "carol",
                new MutualAidRequest("REQ-O2", "INC-A", "R", 2, 60)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_leases",
                Integer.class)).isEqualTo(1);

        // 原租约结清后，资源解除排他占用，可再次征调
        mutualAid.returnResource("INC-B", "REQ-O1", "bob", new LeaseReturnRequest(3));
        LeaseView second = mutualAid.requestAid("INC-C", "carol",
                new MutualAidRequest("REQ-O3", "INC-A", "R", 5, 60));
        assertThat(second.status()).isEqualTo("OPEN");
        assertThat(availableOf("INC-A", "R")).isZero();
    }

    @Test
    void requestIdReplay_sameParamsReturnsFirstResult() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);
        MutualAidRequest request = new MutualAidRequest("REQ-R1", "INC-A", "R", 3, 60);

        LeaseView first = mutualAid.requestAid("INC-B", "bob", request);
        LeaseView replay = mutualAid.requestAid("INC-B", "bob", request);
        assertThat(replay).isEqualTo(first);
        // 重放不重复扣减、不重复建租约
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_leases",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = 'REQ-R1'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void requestIdReplay_changedQuantityOrTarget_409() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        register("INC-A", "alice", "R", 5);
        mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-C1", "INC-A", "R", 3, 60));

        // 同键改数量：409
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-C1", "INC-A", "R", 2, 60)), HttpStatus.CONFLICT);
        // 同键改目标事件：409
        assertApiStatus(() -> mutualAid.requestAid("INC-C", "carol",
                new MutualAidRequest("REQ-C1", "INC-A", "R", 3, 60)), HttpStatus.CONFLICT);
        // 同键改来源：409
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-C1", "INC-C", "R", 3, 60)), HttpStatus.CONFLICT);
        // 冲突重放不动账：A 仍可用 2、仍只有 1 条租约
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_leases",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredLease_doesNotFabricateReturnAndStillSettleable() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);
        LeaseView lease = mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-E1", "INC-A", "R", 3, 60));
        assertThat(lease.expiresAt()).isEqualTo(T0.plusSeconds(3600));

        // 越过到期时刻：不自动虚构归还，租约仍 OPEN、待还仍 3、A 可用仍 2
        ((ControllableClock) clock).setInstant(T0.plusSeconds(7200));
        LeaseDetailView overdue = mutualAid.getLease("INC-B", "REQ-E1");
        assertThat(overdue.lease().status()).isEqualTo("OPEN");
        assertThat(overdue.lease().outstandingQty()).isEqualTo(3);
        assertThat(overdue.returns()).isEmpty();
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);

        // 到期后仍可按实际归还结清，归还时刻记录为当前时刻
        LeaseView closed = mutualAid.returnResource("INC-B", "REQ-E1", "bob",
                new LeaseReturnRequest(3));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isEqualTo(T0.plusSeconds(7200));
        assertThat(availableOf("INC-A", "R")).isEqualTo(5L);
    }

    @Test
    void historyLeases_keepOriginalEndpoints() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);
        mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-H1", "INC-A", "R", 2, 60));
        mutualAid.returnResource("INC-B", "REQ-H1", "bob", new LeaseReturnRequest(2));

        // 历史（已关闭）租约仍保留首次征调的来源与目标事件
        LeaseDetailView detail = mutualAid.getLease("INC-A", "REQ-H1");
        assertThat(detail.lease().sourceIncidentKey()).isEqualTo("INC-A");
        assertThat(detail.lease().targetIncidentKey()).isEqualTo("INC-B");
        assertThat(detail.lease().status()).isEqualTo("CLOSED");

        var forA = mutualAid.listLeases("INC-A");
        assertThat(forA.incidentKey()).isEqualTo("INC-A");
        assertThat(forA.leases()).hasSize(1);
        assertThat(forA.leases().get(0).requestId()).isEqualTo("REQ-H1");
        var forB = mutualAid.listLeases("INC-B");
        assertThat(forB.leases()).hasSize(1);
        assertThat(forB.leases().get(0).sourceIncidentKey()).isEqualTo("INC-A");
        var forC = mutualAid.listLeases(createThirdIncident());
        assertThat(forC.leases()).isEmpty();
    }

    private String createThirdIncident() {
        commanding("INC-C9", "carol");
        return "INC-C9";
    }

    @Test
    void permissionAndValidationGuards() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);

        // 非目标事件当前指挥人发起征调：409
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "mallory",
                new MutualAidRequest("REQ-P1", "INC-A", "R", 1, 60)), HttpStatus.CONFLICT);
        // 数量非法：400
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-P2", "INC-A", "R", 0, 60)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-P3", "INC-A", "R", 1, 0)), HttpStatus.BAD_REQUEST);
        // 来源/目标相同：400
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-P4", "INC-B", "R", 1, 60)), HttpStatus.BAD_REQUEST);
        // 来源不存在：404
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-P5", "INC-X", "R", 1, 60)), HttpStatus.NOT_FOUND);
        // 来源无该资源台账：422
        assertApiStatus(() -> mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-P6", "INC-A", "NOPE", 1, 60)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 非目标指挥人归还：409
        mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest("REQ-P7", "INC-A", "R", 1, 60));
        assertApiStatus(() -> mutualAid.returnResource("INC-B", "REQ-P7", "mallory",
                new LeaseReturnRequest(1)), HttpStatus.CONFLICT);
        // 租约不属于路径事件：404
        assertApiStatus(() -> mutualAid.returnResource("INC-A", "REQ-P7", "alice",
                new LeaseReturnRequest(1)), HttpStatus.NOT_FOUND);
        // 资源重复登记不同总量：409
        assertApiStatus(() -> register("INC-A", "alice", "R", 6), HttpStatus.CONFLICT);
    }

    @Test
    void registerIsIdempotentForSameQuantity() {
        commanding("INC-A", "alice");
        ResourceInventoryView first = register("INC-A", "alice", "R", 5);
        ResourceInventoryView again = register("INC-A", "alice", "R", 5);
        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_inventories",
                Integer.class)).isEqualTo(1);
    }
}
