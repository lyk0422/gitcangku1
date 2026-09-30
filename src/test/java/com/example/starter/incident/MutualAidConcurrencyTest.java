package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.LeaseReturnRequest;
import com.example.starter.incident.dto.Requests.MutualAidRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.LeaseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨事件互助资源征调并发边界测试（真实 H2 + 真实线程）：
 * 并发征调同一资源不使来源可用量为负；同 requestId 并发仅一次双侧账务；
 * 并发归还不超额、不重复返还来源额度；相向征调按统一加锁顺序不死锁。
 * 所有并发用例以 CountDownLatch 协调真实操作并设超时，核对最终账务数据。
 */
@SpringBootTest
class MutualAidConcurrencyTest {

    @Autowired
    private MutualAidService mutualAid;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private JdbcTemplate jdbc;

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
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidents.report(new ReportRequest(incidentKey, "S2", "并发互助", "reporter-1"));
        incidents.takeover(incidentKey, commander, new TakeoverRequest("TK-" + UUID.randomUUID()));
    }

    private void register(String incidentKey, String commander, String resourceCode, long qty) {
        mutualAid.register(incidentKey, commander,
                new ResourceRegisterRequest("RG-" + UUID.randomUUID(), resourceCode, qty));
    }

    /**
     * 并发提交一批操作并收集结果（成功值或异常），统一闸门放行，整体带超时。
     */
    private static List<Object> runConcurrently(List<? extends Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
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

    private static boolean is422(Object result) {
        return result instanceof ApiException e && e.status() == HttpStatus.UNPROCESSABLE_ENTITY;
    }

    @Test
    void concurrentRequests_sameResource_neverNegative() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        register("INC-A", "alice", "R", 5);

        // 两笔各征 3（总量需求 6 > 5），由条件扣减原子裁决，恰好一笔成功
        List<Object> results = runConcurrently(List.of(
                () -> mutualAid.requestAid("INC-B", "bob",
                        new MutualAidRequest(key(), "INC-A", "R", 3, 60)),
                () -> mutualAid.requestAid("INC-C", "carol",
                        new MutualAidRequest(key(), "INC-A", "R", 3, 60))));

        long successes = results.stream().filter(LeaseView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(results.stream().filter(MutualAidConcurrencyTest::is422)).hasSize(1);
        // 最终账务：可用量 2（绝不为负），恰有一条 OPEN 租约占 3 件
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);
        List<Long> outstanding = jdbc.queryForList(
                "SELECT quantity - returned_qty FROM incident_resource_leases WHERE status = 'OPEN'",
                Long.class);
        assertThat(outstanding).containsExactly(3L);
    }

    @Test
    void concurrentRequests_sameRequestId_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);
        String requestId = key();

        List<Callable<LeaseView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> mutualAid.requestAid("INC-B", "bob",
                    new MutualAidRequest(requestId, "INC-A", "R", 3, 60)));
        }
        List<Object> results = runConcurrently(tasks);

        // 三个并发调用都拿到首次双侧账务响应（后续为重放），内容一致
        List<LeaseView> views = results.stream().filter(LeaseView.class::isInstance)
                .map(LeaseView.class::cast).toList();
        assertThat(views).hasSize(3);
        assertThat(views).allSatisfy(v -> assertThat(v).isEqualTo(views.get(0)));
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_leases",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void concurrentReturns_overQuantity_oneSucceedsNoDoubleRelease() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);
        mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest(key(), "INC-A", "R", 3, 60));
        String leaseRequest = jdbc.queryForObject(
                "SELECT request_id FROM incident_resource_leases", String.class);

        // 两笔各还 2（待还仅 3）：恰好一笔成功，另一笔 422
        List<Object> results = runConcurrently(List.of(
                () -> mutualAid.returnResource("INC-B", leaseRequest, "bob",
                        new LeaseReturnRequest(2)),
                () -> mutualAid.returnResource("INC-B", leaseRequest, "bob",
                        new LeaseReturnRequest(2))));

        long successes = results.stream().filter(LeaseView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(results.stream().filter(MutualAidConcurrencyTest::is422)).hasSize(1);
        // 累计归还 2、流水 1 条、租约仍 OPEN；来源只恢复本次的 2 件（2+2=4）
        assertThat(jdbc.queryForObject("SELECT returned_qty FROM incident_resource_leases",
                Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_returns",
                Integer.class)).isEqualTo(1);
        assertThat(availableOf("INC-A", "R")).isEqualTo(4L);
    }

    @Test
    void concurrentReturns_splitExactlySettle_bothSucceedAndCloseOnce() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);
        mutualAid.requestAid("INC-B", "bob",
                new MutualAidRequest(key(), "INC-A", "R", 3, 60));
        String leaseRequest = jdbc.queryForObject(
                "SELECT request_id FROM incident_resource_leases", String.class);

        // 一笔还 2、一笔还 1，合计恰好 3：均成功，租约关闭一次，来源恢复至 5
        List<Object> results = runConcurrently(List.of(
                () -> mutualAid.returnResource("INC-B", leaseRequest, "bob",
                        new LeaseReturnRequest(2)),
                () -> mutualAid.returnResource("INC-B", leaseRequest, "bob",
                        new LeaseReturnRequest(1))));

        assertThat(results.stream().filter(LeaseView.class::isInstance)).hasSize(2);
        assertThat(results.stream().filter(MutualAidConcurrencyTest::is422)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM incident_resource_leases",
                String.class)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT returned_qty FROM incident_resource_leases",
                Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_returns",
                Integer.class)).isEqualTo(2);
        assertThat(availableOf("INC-A", "R")).isEqualTo(5L);
    }

    @Test
    void concurrentOppositeDirectionRequests_noDeadlockBothSucceed() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        register("INC-A", "alice", "R", 5);
        register("INC-B", "bob", "S", 5);

        // 相向征调 A→B（R）与 B→A（S）：统一按事件 id 升序取锁，不发生死锁
        List<Object> results = runConcurrently(List.of(
                () -> mutualAid.requestAid("INC-B", "bob",
                        new MutualAidRequest(key(), "INC-A", "R", 3, 60)),
                () -> mutualAid.requestAid("INC-A", "alice",
                        new MutualAidRequest(key(), "INC-B", "S", 2, 60))));

        assertThat(results.stream().allMatch(LeaseView.class::isInstance)).isTrue();
        assertThat(availableOf("INC-A", "R")).isEqualTo(2L);
        assertThat(availableOf("INC-B", "S")).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_resource_leases",
                Integer.class)).isEqualTo(2);
    }
}
