package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.AidRequestRequest;
import com.example.starter.incident.dto.Requests.AidReturnRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.AidLeaseDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨事件互助征调并发边界测试（真实 H2 内存库，多线程各自独立事务）：
 * 同资源并发征调不得使来源可用量为负；同 requestId 并发仅一次双侧账务；
 * 排他与共享并发至多一笔成立；并发归还不得超过未归还量、不得重复释放；
 * 双向并发征调按事件 id 升序加锁，不死锁。
 */
@SpringBootTest
class MutualAidConcurrencyTest {

    private static final Instant EXPIRES = Instant.now().plusSeconds(3600);

    @Autowired
    private MutualAidService service;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resource_aid_returns");
        jdbc.update("DELETE FROM resource_aid_leases");
        jdbc.update("DELETE FROM incident_resource_inventories");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incidents");
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

    /**
     * 并发提交一批任务并收集结果（成功值或异常），用栅栏保证真实并发竞争。
     */
    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
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

    private long count422(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 422).count();
    }

    @Test
    void concurrentRequestsSameResource_availableNeverNegative() throws Exception {
        report("A");
        report("B");
        register("A", "R", 5);

        // 库存 5，三笔各征调 2：至多两笔成功（合计 4），一笔 422
        List<Callable<AidLeaseDetailView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String requestId = "REQ-C-" + i;
            tasks.add(() -> service.requestAid("alice",
                    aidRequest(requestId, "A", "B", "R", 2, false)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(AidLeaseDetailView.class::isInstance).count();
        assertThat(successes).isEqualTo(2);
        assertThat(count422(results)).isEqualTo(1);

        Integer available = jdbc.queryForObject(
                "SELECT available_quantity FROM incident_resource_inventories"
                        + " WHERE incident_id=(SELECT id FROM incidents WHERE incident_key='A')"
                        + " AND resource_code='R'", Integer.class);
        Integer leaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class);
        Integer leasedSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM resource_aid_leases", Integer.class);
        assertThat(available).isEqualTo(1);
        assertThat(available).isGreaterThanOrEqualTo(0);
        assertThat(leaseCount).isEqualTo(2);
        assertThat(leasedSum).isEqualTo(4);
    }

    @Test
    void concurrentSameRequestId_singleDoubleSidedEffect() throws Exception {
        report("A");
        report("B");
        register("A", "R", 5);

        String requestId = "REQ-SAME";
        List<Callable<AidLeaseDetailView>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.requestAid("alice",
                    aidRequest(requestId, "A", "B", "R", 3, false)));
        }
        List<Object> results = runConcurrently(tasks);

        List<AidLeaseDetailView> successes = results.stream()
                .filter(AidLeaseDetailView.class::isInstance)
                .map(AidLeaseDetailView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        // 仅一次双侧账务：一条租约、A 仅扣减一次到 2、一条幂等键
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM incident_resource_inventories"
                        + " WHERE incident_id=(SELECT id FROM incidents WHERE incident_key='A')"
                        + " AND resource_code='R'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, requestId))
                .isEqualTo(1);
    }

    @Test
    void concurrentExclusiveAndShared_atMostOneLease() throws Exception {
        report("A");
        report("B");
        report("C");
        register("A", "R", 5);

        List<Object> results = runConcurrently(List.of(
                () -> service.requestAid("alice", aidRequest("REQ-EX", "A", "B", "R", 5, true)),
                () -> service.requestAid("alice", aidRequest("REQ-SH", "A", "C", "R", 5, false))));

        long successes = results.stream().filter(AidLeaseDetailView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(count422(results)).isEqualTo(1);
        // 无论谁先提交，都只有一条租约、扣减 5 件，可用量不为负
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM incident_resource_inventories"
                        + " WHERE incident_id=(SELECT id FROM incidents WHERE incident_key='A')"
                        + " AND resource_code='R'", Integer.class)).isZero();
    }

    @Test
    void concurrentReturns_neverExceedOutstanding_orReleaseTwice() throws Exception {
        report("A");
        report("B");
        register("A", "R", 5);
        service.requestAid("alice", aidRequest("REQ-RET", "A", "B", "R", 3, false));
        assertThat(service.listInventories("A").get(0).availableQuantity()).isEqualTo(2);

        // 待还 3，并发两笔各还 2：仅一笔成功，另一笔 422
        List<Callable<AidLeaseDetailView>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = uuid();
            tasks.add(() -> service.returnLease("REQ-RET", "bob", new AidReturnRequest(commandKey, 2)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(AidLeaseDetailView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(count422(results)).isEqualTo(1);

        AidLeaseDetailView after = service.getLease("REQ-RET");
        assertThat(after.lease().status()).isEqualTo("ACTIVE");
        assertThat(after.lease().returnedQuantity()).isEqualTo(2);
        assertThat(after.lease().outstandingQuantity()).isEqualTo(1);
        assertThat(after.returns()).hasSize(1);
        // 来源仅加回一次：2 + 2 = 4
        assertThat(after.sourceInventory().availableQuantity()).isEqualTo(4);

        // 还清最后一件后关闭，来源回到 5
        AidLeaseDetailView settled = service.returnLease("REQ-RET", "bob",
                new AidReturnRequest(uuid(), 1));
        assertThat(settled.lease().status()).isEqualTo("CLOSED");
        assertThat(settled.sourceInventory().availableQuantity()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_returns", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentBidirectionalRequests_differentResources_bothSucceedNoDeadlock() throws Exception {
        report("A");
        report("B");
        register("A", "R", 5);
        register("B", "S", 5);

        List<Object> results = runConcurrently(List.of(
                () -> service.requestAid("alice", aidRequest("REQ-AB", "A", "B", "R", 2, false)),
                () -> service.requestAid("alice", aidRequest("REQ-BA", "B", "A", "S", 3, false))));

        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        assertThat(service.getLease("REQ-AB").sourceInventory().availableQuantity()).isEqualTo(3);
        assertThat(service.getLease("REQ-BA").sourceInventory().availableQuantity()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_aid_leases", Integer.class)).isEqualTo(2);
    }
}
