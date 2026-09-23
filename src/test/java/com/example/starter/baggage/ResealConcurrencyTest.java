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

import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.ContainerDtos.ConfirmResealRequest;
import com.example.starter.baggage.ContainerDtos.ContainerLoadRequest;
import com.example.starter.baggage.ContainerDtos.ContainerSealRequest;
import com.example.starter.baggage.ContainerDtos.CreateContainerRequest;
import com.example.starter.baggage.ContainerDtos.CreateResealOrderRequest;
import com.example.starter.baggage.ContainerDtos.ResealOrderResponse;
import com.example.starter.baggage.ContainerDtos.ResealSourceRequest;
import com.example.starter.baggage.ContainerDtos.ResealTargetRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 容器重封并发边界测试：两个重封单并发激活只成功一个；
 * 同一确认请求并发重放只生效一次；重封与短卸登记并发按提交顺序串行，
 * 任何时刻一件行李只属于一个有效容器。
 */
@SpringBootTest
class ResealConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private ContainerService containerService;

    @Autowired
    private ResealService resealService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_container_history");
        jdbcTemplate.update("DELETE FROM container_bag");
        jdbcTemplate.update("DELETE FROM container");
        jdbcTemplate.update("DELETE FROM reseal_order");
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
    void concurrentTwoReseals_singleWinner() throws Exception {
        setupSealedContainer("C1", "SEAL-1");
        createOrder("RK-1", "T1", "N1");
        createOrder("RK-2", "T2", "N2");
        confirm("RK-1", "op1");
        confirm("RK-2", "op1");

        List<Object> results = runConcurrently(List.of(
                () -> resealService.confirm("RK-1",
                        new ConfirmResealRequest(UUID.randomUUID().toString(), "rev1")),
                () -> resealService.confirm("RK-2",
                        new ConfirmResealRequest(UUID.randomUUID().toString(), "rev1"))));

        long activated = results.stream()
                .filter(ResealOrderResponse.class::isInstance)
                .map(ResealOrderResponse.class::cast)
                .filter(r -> "ACTIVATED".equals(r.status()))
                .count();
        long rejected = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409 || ex.getStatus().value() == 422)
                .count();
        assertThat(activated).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        // 源容器只被关闭一次，目标容器只创建赢家的，行李全部且仅属于赢家容器
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM container WHERE container_id = 'C1'", String.class))
                .isEqualTo("CLOSED_REPACKED");
        Integer targetCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container WHERE container_id IN ('T1','T2')", Integer.class);
        assertThat(targetCount).isEqualTo(1);
        Integer bagRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container_bag WHERE bag_tag IN ('BAG01','BAG02')",
                Integer.class);
        assertThat(bagRows).isEqualTo(2);
        String winner = jdbcTemplate.queryForObject(
                "SELECT container_id FROM container WHERE container_id IN ('T1','T2')",
                String.class);
        Integer inWinner = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container_bag WHERE container_id = ?", Integer.class, winner);
        assertThat(inWinner).isEqualTo(2);
        // 输家重封单仍待确认，未部分生效
        String loserKey = "T1".equals(winner) ? "RK-2" : "RK-1";
        assertThat(resealService.getOrder(loserKey).status()).isEqualTo("PENDING_CONFIRM");
    }

    @Test
    void concurrentSameConfirmRequestId_singleEffect() throws Exception {
        setupSealedContainer("C1", "SEAL-1");
        createOrder("RK-1", "T1", "N1");
        confirm("RK-1", "op1");

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> resealService.confirm("RK-1",
                    new ConfirmResealRequest(requestId, "rev1")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ResealOrderResponse.class);
            assertThat(((ResealOrderResponse) result).status()).isEqualTo("ACTIVATED");
        });
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
        // 源容器版本只推进一次（3 -> 4），容器链每件行李只追加一次
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM container WHERE container_id = 'C1'", Integer.class);
        assertThat(version).isEqualTo(4);
        Integer chainRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_container_history WHERE bag_tag = 'BAG01'",
                Integer.class);
        assertThat(chainRows).isEqualTo(2);
    }

    @Test
    void concurrentResealAndShortUnload_serializedAndConsistent() throws Exception {
        setupSealedContainer("C1", "SEAL-1");
        // 封舱航段，使差异到达（短卸登记）可与重封并发
        baggageService.seal("LEG1", new SealRequest(UUID.randomUUID().toString(), 2));
        createOrder("RK-1", "T1", "N1");
        confirm("RK-1", "op1");

        List<Object> results = runConcurrently(List.of(
                () -> resealService.confirm("RK-1",
                        new ConfirmResealRequest(UUID.randomUUID().toString(), "rev1")),
                () -> baggageService.arriveDifference("LEG1",
                        new DifferenceArriveRequest(UUID.randomUUID().toString(), 3,
                                List.of("BAG02")))));

        Object confirmResult = results.get(0);
        Object differenceResult = results.get(1);
        // 任何时刻一件行李只属于一个有效容器
        Integer bagRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM container_bag WHERE bag_tag IN ('BAG01','BAG02')",
                Integer.class);
        assertThat(bagRows).isEqualTo(2);

        if (confirmResult instanceof ResealOrderResponse response) {
            // 重封先成功：源容器关闭、目标封签，短卸登记仍可按提交顺序随后完成
            assertThat(response.status()).isEqualTo("ACTIVATED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM container WHERE container_id = 'C1'", String.class))
                    .isEqualTo("CLOSED_REPACKED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM container WHERE container_id = 'T1'", String.class))
                    .isEqualTo("SEALED");
            Integer inTarget = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM container_bag WHERE container_id = 'T1'", Integer.class);
            assertThat(inTarget).isEqualTo(2);
        } else {
            // 短卸登记先完成：重封整单 422 回滚，源容器与行李归属不变
            assertThat(confirmResult).isInstanceOf(ApiException.class);
            assertThat(((ApiException) confirmResult).getStatus().value()).isEqualTo(422);
            assertThat(differenceResult).isNotInstanceOf(ApiException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM container WHERE container_id = 'C1'", String.class))
                    .isEqualTo("SEALED");
            Integer inSource = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM container_bag WHERE container_id = 'C1'", Integer.class);
            assertThat(inSource).isEqualTo(2);
            assertThat(resealService.getOrder("RK-1").status()).isEqualTo("PENDING_CONFIRM");
        }
    }

    /** 登记 LEG1 与 BAG01/BAG02，装载后创建容器、装箱并封签（容器版本推进到 3）。 */
    private void setupSealedContainer(String containerId, String sealNo) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), "LEG1", "PEK", "SHA"));
        for (String bagTag : List.of("BAG01", "BAG02")) {
            baggageService.registerBag(
                    new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, List.of("LEG1")));
        }
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 1,
                List.of("BAG01", "BAG02")));
        containerService.createContainer(new CreateContainerRequest(
                UUID.randomUUID().toString(), containerId, "LEG1", "HP1"));
        containerService.load(containerId, new ContainerLoadRequest(
                UUID.randomUUID().toString(), 1, List.of("BAG01", "BAG02")));
        containerService.seal(containerId, new ContainerSealRequest(
                UUID.randomUUID().toString(), 2, sealNo));
    }

    private void createOrder(String repackKey, String targetId, String newSealNo) {
        resealService.createOrder(new CreateResealOrderRequest(
                UUID.randomUUID().toString(), repackKey, "op1", "rev1",
                List.of(new ResealSourceRequest("C1", 3, "SEAL-1")),
                List.of(new ResealTargetRequest(targetId, newSealNo, List.of("BAG01", "BAG02")))));
    }

    private void confirm(String repackKey, String confirmerId) {
        resealService.confirm(repackKey,
                new ConfirmResealRequest(UUID.randomUUID().toString(), confirmerId));
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
