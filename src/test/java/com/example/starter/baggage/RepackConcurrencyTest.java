package com.example.starter.baggage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.RepackDtos.ActivateRepackRequest;
import com.example.starter.baggage.RepackDtos.ContainerPackRequest;
import com.example.starter.baggage.RepackDtos.CreateRepackRequest;
import com.example.starter.baggage.RepackDtos.RepackActivatedResponse;
import com.example.starter.baggage.RepackDtos.RepackSourceRequest;
import com.example.starter.baggage.RepackDtos.RepackTargetRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 容器重封并发边界测试（真实 H2 MySQL 兼容库与真实行锁）：
 * 两张重封单并发抢同一源容器、重封与卸载到达并发、同 repackKey 并发创建。
 * 任何提交顺序下，一件行李不能同时属于两个有效容器，也不能无容器（卸载场景除外）。
 */
@SpringBootTest
class RepackConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private RepackService repackService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM repack_evidence");
        jdbcTemplate.update("DELETE FROM repack_order");
        jdbcTemplate.update("DELETE FROM bag_container_chain");
        jdbcTemplate.update("DELETE FROM baggage_container");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentRepacksOfSameSource_exactlyOneWinner() throws Exception {
        seedContainers();

        createRepack("RP-A",
                List.of(new RepackSourceRequest("C1", 1, "SEAL-C1")),
                List.of(new RepackTargetRequest("A1", "SEAL-A1", List.of("BAG1")),
                        new RepackTargetRequest("A2", "SEAL-A2", List.of("BAG2"))));
        createRepack("RP-B",
                List.of(new RepackSourceRequest("C1", 1, "SEAL-C1")),
                List.of(new RepackTargetRequest("B1", "SEAL-B1", List.of("BAG1")),
                        new RepackTargetRequest("B2", "SEAL-B2", List.of("BAG2"))));

        List<Callable<Object>> tasks = List.of(
                () -> repackService.activateRepack("RP-A",
                        new ActivateRepackRequest(UUID.randomUUID().toString(), "op-a", "rev-a")),
                () -> repackService.activateRepack("RP-B",
                        new ActivateRepackRequest(UUID.randomUUID().toString(), "op-b", "rev-b")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RepackActivatedResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        assertSingleValidBinding(List.of("BAG1", "BAG2"));

        // 败方目标容器不得残留，源容器只关闭一次（version=2），胜方目标 SEALED 且为唯一有效容器
        Integer sealed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage_container WHERE status = 'SEALED'", Integer.class);
        // C2 始终 SEALED，胜方两个目标 SEALED
        assertThat(sealed).isEqualTo(3);
        Integer c1Version = jdbcTemplate.queryForObject(
                "SELECT version FROM baggage_container WHERE container_no = 'C1'", Integer.class);
        assertThat(c1Version).isEqualTo(2);
    }

    @Test
    void concurrentRepackAndArrive_submissionOrderKeepsConsistency() throws Exception {
        seedContainers();

        createRepack("RP-VS-ARRIVE",
                List.of(new RepackSourceRequest("C1", 1, "SEAL-C1"),
                        new RepackSourceRequest("C2", 1, "SEAL-C2")),
                List.of(new RepackTargetRequest("N1", "SEAL-N1",
                        List.of("BAG1", "BAG2", "BAG3", "BAG4"))));

        List<Callable<Object>> tasks = List.of(
                () -> repackService.activateRepack("RP-VS-ARRIVE",
                        new ActivateRepackRequest(UUID.randomUUID().toString(), "op-1", "rev-1")),
                () -> baggageService.arrive("LEG1", new ArriveRequest(
                        UUID.randomUUID().toString(), List.of("BAG1", "BAG2", "BAG3", "BAG4"))));
        List<Object> results = runConcurrently(tasks);

        long repackSuccess = results.stream().filter(RepackActivatedResponse.class::isInstance).count();
        long repackRejected = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409 || ex.getStatus().value() == 422)
                .count();
        // 到达以航段清单为准始终成功；重封要么成功要么被到达抢先而拒绝，恰好一种结局
        assertThat(repackSuccess + repackRejected).isEqualTo(1);

        if (repackSuccess == 1) {
            // 重封先提交后到达：行李最终已卸载无容器，N1 曾 SEALED 但到达收口为 UNLOADED，无行李悬挂
            Integer nullAfterUnload = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bag WHERE container_no IS NOT NULL", Integer.class);
            assertThat(nullAfterUnload).isZero();
            String n1Status = jdbcTemplate.queryForObject(
                    "SELECT status FROM baggage_container WHERE container_no = 'N1'", String.class);
            assertThat(n1Status).isEqualTo("UNLOADED");
        } else {
            // 到达先提交：重封被拒绝，无目标容器、无证据
            Integer n1 = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM baggage_container WHERE container_no = 'N1'", Integer.class);
            assertThat(n1).isZero();
            Integer evidence = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM repack_evidence WHERE repack_key = 'RP-VS-ARRIVE'",
                    Integer.class);
            assertThat(evidence).isZero();
        }
        // 任何结局下行李均已随到达卸下
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE loaded_leg_id IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void concurrentCreateSameRepackKey_onlyOneSucceeds() throws Exception {
        seedContainers();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int suffix = i;
            tasks.add(() -> repackService.createRepack(new CreateRepackRequest(
                    UUID.randomUUID().toString(), "RP-RACE",
                    List.of(new RepackSourceRequest("C1", 1, "SEAL-C1")),
                    List.of(new RepackTargetRequest("R" + suffix, "SEAL-R" + suffix,
                            List.of("BAG1", "BAG2"))))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> !(r instanceof ApiException)).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repack_order WHERE repack_key = 'RP-RACE'", Integer.class);
        assertThat(orders).isEqualTo(1);
        // 创建是纯预览：不产生任何目标容器
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM baggage_container", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentActivateSameRepackKey_singleEffectWithTwoPeople() throws Exception {
        seedContainers();
        createRepack("RP-SAME",
                List.of(new RepackSourceRequest("C1", 1, "SEAL-C1"),
                        new RepackSourceRequest("C2", 1, "SEAL-C2")),
                List.of(new RepackTargetRequest("S1", "SEAL-S1",
                        List.of("BAG1", "BAG2", "BAG3", "BAG4"))));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> repackService.activateRepack("RP-SAME",
                    new ActivateRepackRequest(requestId, "op-1", "rev-1")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result ->
                assertThat(result).isInstanceOf(RepackActivatedResponse.class));
        Integer evidence = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repack_evidence WHERE repack_key = 'RP-SAME'", Integer.class);
        assertThat(evidence).isEqualTo(4);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
        assertSingleValidBinding(List.of("BAG1", "BAG2", "BAG3", "BAG4"));
    }

    /** 注册航段、4 件行李、装载封舱并封装 C1={BAG1,BAG2}、C2={BAG3,BAG4}。 */
    private void seedContainers() {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), "LEG1", "PEK", "SHA"));
        for (String bag : List.of("BAG1", "BAG2", "BAG3", "BAG4")) {
            baggageService.registerBag(new RegisterBagRequest(
                    UUID.randomUUID().toString(), bag, List.of("LEG1")));
        }
        baggageService.load("LEG1", new BaggageDtos.LoadRequest(
                UUID.randomUUID().toString(), 1, List.of("BAG1", "BAG2", "BAG3", "BAG4")));
        baggageService.seal("LEG1", new BaggageDtos.SealRequest(
                UUID.randomUUID().toString(), 2));
        repackService.packContainer(new ContainerPackRequest(
                UUID.randomUUID().toString(), "C1", "LEG1", "PEK-T1", "SEAL-C1",
                List.of("BAG2", "BAG1")));
        repackService.packContainer(new ContainerPackRequest(
                UUID.randomUUID().toString(), "C2", "LEG1", "PEK-T1", "SEAL-C2",
                List.of("BAG4", "BAG3")));
    }

    private void createRepack(String key, List<RepackSourceRequest> sources,
                              List<RepackTargetRequest> targets) {
        repackService.createRepack(new CreateRepackRequest(
                UUID.randomUUID().toString(), key, sources, targets));
    }

    /**
     * 断言每件行李恰好绑定一个存在且 SEALED 的有效容器。
     * bag.container_no 为单列，结构上不可能同时指向两个容器；这里验证无悬挂、无指向已关闭容器。
     */
    private void assertSingleValidBinding(List<String> bagTags) {
        for (String bagTag : bagTags) {
            List<String> bindings = jdbcTemplate.queryForList(
                    "SELECT c.status FROM bag b JOIN baggage_container c ON b.container_no = c.container_no"
                            + " WHERE b.bag_tag = ?", String.class, bagTag);
            assertThat(bindings)
                    .as("行李 " + bagTag + " 必须恰好属于一个有效容器")
                    .containsExactly("SEALED");
        }
        Integer orphanBags = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag b LEFT JOIN baggage_container c ON b.container_no = c.container_no"
                        + " WHERE b.container_no IS NOT NULL AND c.container_no IS NULL",
                Integer.class);
        assertThat(orphanBags).as("不得有行李指向不存在的容器").isZero();
        Integer boundToClosed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag b JOIN baggage_container c ON b.container_no = c.container_no"
                        + " WHERE c.status <> 'SEALED'", Integer.class);
        assertThat(boundToClosed).as("不得有行李仍绑定已关闭容器").isZero();
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（业务异常包装为返回值）。 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
                } catch (ApiException ex) {
                    return ex;
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
    }
}
