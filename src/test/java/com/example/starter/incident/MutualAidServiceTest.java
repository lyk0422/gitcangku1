package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.AidRequestRequest;
import com.example.starter.incident.dto.Requests.AidReturnRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.AidLeaseDetailView;
import com.example.starter.incident.dto.Responses.AidLeaseView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨事件互助资源征调服务测试（真实 H2 内存库，MODE=MySQL）：
 * 覆盖征调/归还主流程、来源不足与排他占用 422、归还超量与重复归还 422、
 * requestId/commandKey 幂等重放与改参 409、到期不虚构归还、历史租约保留来源目标。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class MutualAidServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-23T00:00:00Z");

    @Autowired
    private MutualAidService service;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControllableClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resource_aid_returns");
        jdbc.update("DELETE FROM resource_aid_leases");
        jdbc.update("DELETE FROM incident_resource_inventories");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incidents");
        clock.setInstant(NOW);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void report(String key) {
        incidentService.report(new ReportRequest(key, "S2", "事件 " + key, "reporter-" + key));
    }

    private void register(String incidentKey, String resource, int quantity) {
        service.registerInventory(incidentKey, "alice",
                new ResourceRegisterRequest(resource, quantity));
    }

    private AidRequestRequest aidRequest(String requestId, String source, String target,
                                         String resource, int quantity, boolean exclusive) {
        return new AidRequestRequest(requestId, source, target, resource, quantity, exclusive, EXPIRES);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void requestThenPartialReturn_thenOverReturn422_thenSettleClosesLease() {
        report("A");
        report("B");
        register("A", "R", 5);

        // 征调 3 件：A 剩 2，B 名下租约 3、待还 3
        AidLeaseDetailView afterRequest = service.requestAid("alice",
                aidRequest("REQ-1", "A", "B", "R", 3, false));
        assertThat(afterRequest.lease().sourceIncidentKey()).isEqualTo("A");
        assertThat(afterRequest.lease().targetIncidentKey()).isEqualTo("B");
        assertThat(afterRequest.lease().quantity()).isEqualTo(3);
        assertThat(afterRequest.lease().returnedQuantity()).isZero();
        assertThat(afterRequest.lease().outstandingQuantity()).isEqualTo(3);
        assertThat(afterRequest.lease().status()).isEqualTo("ACTIVE");
        assertThat(afterRequest.lease().expiresAt()).isEqualTo(EXPIRES);
        assertThat(afterRequest.sourceInventory().availableQuantity()).isEqualTo(2);
        assertThat(afterRequest.returns()).isEmpty();
        assertThat(service.listInventories("A")).singleElement()
                .satisfies(i -> assertThat(i.availableQuantity()).isEqualTo(2));

        // 归还 2：待还 1，A 可用量回到 4，一条归还流水
        AidLeaseDetailView afterReturn2 = service.returnLease("REQ-1", "bob",
                new AidReturnRequest(uuid(), 2));
        assertThat(afterReturn2.lease().returnedQuantity()).isEqualTo(2);
        assertThat(afterReturn2.lease().outstandingQuantity()).isEqualTo(1);
        assertThat(afterReturn2.lease().status()).isEqualTo("ACTIVE");
        assertThat(afterReturn2.lease().closedAt()).isNull();
        assertThat(afterReturn2.sourceInventory().availableQuantity()).isEqualTo(4);
        assertThat(afterReturn2.returns()).hasSize(1);
        assertThat(afterReturn2.returns().get(0).quantity()).isEqualTo(2);
        assertThat(afterReturn2.returns().get(0).returnedBy()).isEqualTo("bob");
        assertThat(afterReturn2.returns().get(0).returnedAt()).isEqualTo(NOW);

        // 再还 2 超过待还 1：422，双侧不变（无流水、A 仍 4、租约仍待还 1）
        assertApiStatus(() -> service.returnLease("REQ-1", "bob", new AidReturnRequest(uuid(), 2)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        AidLeaseDetailView unchanged = service.getLease("REQ-1");
        assertThat(unchanged.lease().status()).isEqualTo("ACTIVE");
        assertThat(unchanged.lease().outstandingQuantity()).isEqualTo(1);
        assertThat(unchanged.sourceInventory().availableQuantity()).isEqualTo(4);
        assertThat(unchanged.returns()).hasSize(1);

        // 还清最后 1：租约关闭、记录关闭时刻、A 回到 5、两条流水
        AidLeaseDetailView settled = service.returnLease("REQ-1", "bob",
                new AidReturnRequest(uuid(), 1));
        assertThat(settled.lease().status()).isEqualTo("CLOSED");
        assertThat(settled.lease().outstandingQuantity()).isZero();
        assertThat(settled.lease().closedAt()).isEqualTo(NOW);
        assertThat(settled.sourceInventory().availableQuantity()).isEqualTo(5);
        assertThat(settled.returns()).hasSize(2);

        // CLOSED 为终态：重复归还 422，来源额度不重复释放
        assertApiStatus(() -> service.returnLease("REQ-1", "bob", new AidReturnRequest(uuid(), 1)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getLease("REQ-1").sourceInventory().availableQuantity()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_returns", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void insufficientSource_returns422_andLeavesNoPartialChange() {
        report("A");
        report("B");
        register("A", "R", 2);

        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-SHORT", "A", "B", "R", 3, false)), HttpStatus.UNPROCESSABLE_ENTITY);

        // 双侧不留部分变更：A 仍 2、无租约、无归还流水、幂等键未占用
        assertThat(service.listInventories("A")).singleElement()
                .satisfies(i -> assertThat(i.availableQuantity()).isEqualTo(2));
        assertThat(service.listLeasesByIncident("A")).isEmpty();
        assertThat(service.listLeasesByIncident("B")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_keys WHERE command_key = 'REQ-SHORT'",
                Integer.class)).isZero();

        // 失败不占键：补足库存后同一 requestId 可成功
        register("A", "R2", 5);
        AidLeaseDetailView ok = service.requestAid("alice",
                aidRequest("REQ-SHORT", "A", "B", "R2", 3, false));
        assertThat(ok.sourceInventory().availableQuantity()).isEqualTo(2);
    }

    @Test
    void unregisteredResource_returns422() {
        report("A");
        report("B");
        register("A", "R", 5);
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-NO-RES", "A", "B", "OTHER", 1, false)),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void exclusiveLease_blocksFurtherRequests_with422() {
        report("A");
        report("B");
        report("C");
        register("A", "R", 5);

        // 先成立排他租约（占用 2 件）
        AidLeaseDetailView exclusive = service.requestAid("alice",
                aidRequest("REQ-EX", "A", "B", "R", 2, true));
        assertThat(exclusive.lease().exclusive()).isTrue();

        // 资源已被排他租约占用：普通征调 422
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-AFTER-EX", "A", "C", "R", 1, false)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.listInventories("A").get(0).availableQuantity()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isEqualTo(1);

        // 排他租约全部归还关闭后，资源可再次征调
        service.returnLease("REQ-EX", "bob", new AidReturnRequest(uuid(), 2));
        assertThat(service.getLease("REQ-EX").lease().status()).isEqualTo("CLOSED");
        AidLeaseDetailView again = service.requestAid("alice",
                aidRequest("REQ-AFTER-EX", "A", "C", "R", 1, false));
        assertThat(again.sourceInventory().availableQuantity()).isEqualTo(4);
    }

    @Test
    void exclusiveRequest_whenSharedLeaseActive_returns422() {
        report("A");
        report("B");
        report("C");
        register("A", "R", 5);

        service.requestAid("alice", aidRequest("REQ-SHARED", "A", "B", "R", 2, false));
        // 仍有未结共享租约时排他征调 422
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-WANT-EX", "A", "C", "R", 1, true)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isEqualTo(1);
    }

    @Test
    void sharedLeases_canCoexistWhileStockLasts() {
        report("A");
        report("B");
        report("C");
        register("A", "R", 5);

        service.requestAid("alice", aidRequest("REQ-S1", "A", "B", "R", 2, false));
        AidLeaseDetailView second = service.requestAid("alice",
                aidRequest("REQ-S2", "A", "C", "R", 2, false));
        assertThat(second.sourceInventory().availableQuantity()).isEqualTo(1);
        // 仅剩 1，第三次再征调 2 件来源不足 422
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-S3", "A", "B", "R", 2, false)), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void sameRequestId_replaysFirstResponse_andChangedParamsReturn409() {
        report("A");
        report("B");
        report("C");
        register("A", "R", 5);

        AidRequestRequest first = aidRequest("REQ-IDEM", "A", "B", "R", 3, false);
        AidLeaseDetailView firstView = service.requestAid("alice", first);

        // 同键完全重放：返回首次响应，不再次扣减
        AidLeaseDetailView replay = service.requestAid("alice",
                aidRequest("REQ-IDEM", "A", "B", "R", 3, false));
        assertThat(replay).isEqualTo(firstView);
        assertThat(service.listInventories("A").get(0).availableQuantity()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isEqualTo(1);

        // 改数量 409
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-IDEM", "A", "B", "R", 2, false)), HttpStatus.CONFLICT);
        // 改目标事件 409
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-IDEM", "A", "C", "R", 3, false)), HttpStatus.CONFLICT);
        // 改来源事件 409
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-IDEM", "B", "A", "R", 3, false)), HttpStatus.CONFLICT);
        // 改排他标志 409
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-IDEM", "A", "B", "R", 3, true)), HttpStatus.CONFLICT);
        // 409 均不产生新账务
        assertThat(service.listInventories("A").get(0).availableQuantity()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isEqualTo(1);
    }

    @Test
    void returnCommandIdempotent_replaysWithoutDoubleRelease_andChangedQty409() {
        report("A");
        report("B");
        register("A", "R", 5);
        service.requestAid("alice", aidRequest("REQ-R-IDEM", "A", "B", "R", 3, false));

        String commandKey = uuid();
        AidLeaseDetailView first = service.returnLease("REQ-R-IDEM", "bob",
                new AidReturnRequest(commandKey, 2));
        assertThat(first.sourceInventory().availableQuantity()).isEqualTo(4);

        // 同键同参重放：不再写流水、不重复释放
        AidLeaseDetailView replay = service.returnLease("REQ-R-IDEM", "bob",
                new AidReturnRequest(commandKey, 2));
        assertThat(replay).isEqualTo(first);
        assertThat(service.getLease("REQ-R-IDEM").sourceInventory().availableQuantity()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_returns", Integer.class)).isEqualTo(1);

        // 同键改数量 409
        assertApiStatus(() -> service.returnLease("REQ-R-IDEM", "bob",
                new AidReturnRequest(commandKey, 1)), HttpStatus.CONFLICT);
    }

    @Test
    void expiredLease_isNotAutoSettled_butCanStillBeReturned() {
        report("A");
        report("B");
        register("A", "R", 5);
        service.requestAid("alice", aidRequest("REQ-EXP", "A", "B", "R", 3, false));

        // 时钟越过到期时刻：不虚构归还、不释放额度、不关闭
        clock.setInstant(EXPIRES.plusSeconds(60));
        AidLeaseView expired = service.getLease("REQ-EXP").lease();
        assertThat(expired.status()).isEqualTo("ACTIVE");
        assertThat(expired.outstandingQuantity()).isEqualTo(3);
        assertThat(expired.closedAt()).isNull();
        assertThat(service.listInventories("A").get(0).availableQuantity()).isEqualTo(2);

        // 到期后仍可人工归还并结清
        AidLeaseDetailView settled = service.returnLease("REQ-EXP", "bob",
                new AidReturnRequest(uuid(), 3));
        assertThat(settled.lease().status()).isEqualTo("CLOSED");
        assertThat(settled.sourceInventory().availableQuantity()).isEqualTo(5);
    }

    @Test
    void leaseQuery_keepsFirstSourceAndTarget_andBothIncidentsSeeHistory() {
        report("A");
        report("B");
        register("A", "R", 5);
        service.requestAid("alice", aidRequest("REQ-HIST", "A", "B", "R", 3, false));
        service.returnLease("REQ-HIST", "bob", new AidReturnRequest(uuid(), 3));

        // 关联查询：来源扣减账 + 目标租约 + 分次归还流水
        AidLeaseDetailView detail = service.getLease("REQ-HIST");
        assertThat(detail.lease().sourceIncidentKey()).isEqualTo("A");
        assertThat(detail.lease().targetIncidentKey()).isEqualTo("B");
        assertThat(detail.sourceInventory().incidentKey()).isEqualTo("A");
        assertThat(detail.sourceInventory().availableQuantity()).isEqualTo(5);
        assertThat(detail.returns()).hasSize(1);

        // 历史（已关闭）租约仍保留首次征调的来源与目标，且两个事件都能查到
        assertThat(service.listLeasesByIncident("A")).extracting(AidLeaseView::requestId)
                .containsExactly("REQ-HIST");
        assertThat(service.listLeasesByIncident("B")).extracting(AidLeaseView::requestId)
                .containsExactly("REQ-HIST");
        assertThat(service.listLeasesByIncident("A").get(0).status()).isEqualTo("CLOSED");
        assertThat(service.listLeasesByIncident("A").get(0).sourceIncidentKey()).isEqualTo("A");
        assertThat(service.listLeasesByIncident("A").get(0).targetIncidentKey()).isEqualTo("B");
    }

    @Test
    void invalidRequests_return400_andMissingIncidentReturns404() {
        report("A");
        report("B");
        register("A", "R", 5);

        assertApiStatus(() -> service.requestAid("alice",
                new AidRequestRequest("REQ-X", "A", "A", "R", 1, false, EXPIRES)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.requestAid("alice",
                new AidRequestRequest("REQ-X", "A", "B", "R", 0, false, EXPIRES)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.requestAid("alice",
                new AidRequestRequest("REQ-X", "A", "B", "R", 1, false, NOW)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.requestAid("alice",
                aidRequest("REQ-X", "A", "NO-SUCH", "R", 1, false)), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.getLease("NO-SUCH-LEASE"), HttpStatus.NOT_FOUND);
    }

    @Test
    void inventoryRegister_duplicateSameQtyIdempotent_differentQty409() {
        report("A");
        service.registerInventory("A", "alice", new ResourceRegisterRequest("R", 5));
        // 相同数量幂等返回
        assertThat(service.registerInventory("A", "alice", new ResourceRegisterRequest("R", 5))
                .availableQuantity()).isEqualTo(5);
        // 不同数量 409
        assertApiStatus(() -> service.registerInventory("A", "alice",
                new ResourceRegisterRequest("R", 4)), HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resource_inventories WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key='A')", Integer.class))
                .isEqualTo(1);
    }
}
