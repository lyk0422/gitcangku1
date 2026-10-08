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

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.ManifestProofResponse;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RecoverResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MERKLE 补到并发边界：补到与后续装载原子核验（顺序一致）、
 * 凭同一有效证明并发补到只推进一次、同键并发重放结果一致且只生效一次。
 * 全部基于 H2 MySQL 兼容模式真实数据库。
 */
@SpringBootTest
class BaggageManifestProofConcurrencyTest {

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
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
    void concurrentRecoverWithProofAndLoad_consistentOrdering() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerLeg("LEG2", "SHA", "CAN", null);
        registerBag("BAG_SHORT", List.of("LEG1", "LEG2"));
        registerBag("BAG_OK", List.of("LEG2"));
        baggageService.load("LEG1",
                new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG_SHORT")));
        baggageService.seal("LEG1",
                new SealRequest(UUID.randomUUID().toString(), 2, "op-seal"));
        baggageService.arriveDifference("LEG1", new BaggageDtos.DifferenceArriveRequest(
                UUID.randomUUID().toString(), 3, List.of(), "op-arr"));
        ManifestProofResponse proof = baggageService.getManifestProof("LEG1", "BAG_SHORT");

        // 补到（带证明）与后续航段批量装载真实并发：
        // 顺序只能是“装载失败整批不移动”或“补到先提交后装载成功”
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.recover(new RecoverRequest(
                        UUID.randomUUID().toString(), "BAG_SHORT", "LEG1", "SHA",
                        "op-rec", proof.manifestRoot(), proof.leafIndex(), proof.proof())),
                () -> baggageService.load("LEG2",
                        new LoadRequest(UUID.randomUUID().toString(), 1,
                                List.of("BAG_OK", "BAG_SHORT"))));
        List<Object> results = runConcurrently(tasks);

        boolean loadSucceeded = results.stream()
                .anyMatch(r -> r instanceof BaggageDtos.LoadResponse);
        boolean loadFailed = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .anyMatch(ex -> ex.getStatus().value() == 422);
        assertThat(loadSucceeded).isNotEqualTo(loadFailed);

        Integer okLoaded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG_OK'", Integer.class);
        if (loadSucceeded) {
            assertThat(okLoaded).isEqualTo(1);
            String shortStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BAG_SHORT'", String.class);
            assertThat(shortStatus).isEqualTo("RECOVERED");
        } else {
            assertThat(okLoaded).isZero();
            baggageService.recover(new RecoverRequest(
                    UUID.randomUUID().toString(), "BAG_SHORT", "LEG1", "SHA",
                    "op-rec", proof.manifestRoot(), proof.leafIndex(), proof.proof()));
            baggageService.load("LEG2", new LoadRequest(UUID.randomUUID().toString(), 1,
                    List.of("BAG_OK", "BAG_SHORT")));
        }
        // 根不受补到与装载影响
        assertThat(baggageService.getManifest("LEG1").manifestRoot())
                .isEqualTo(proof.manifestRoot());
    }

    @Test
    void concurrentDoubleRecover_sameProofAdvancesOnlyOnce() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerLeg("LEG2", "SHA", "CAN", null);
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        baggageService.load("LEG1",
                new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));
        baggageService.seal("LEG1",
                new SealRequest(UUID.randomUUID().toString(), 2, "op-seal"));
        baggageService.arriveDifference("LEG1", new BaggageDtos.DifferenceArriveRequest(
                UUID.randomUUID().toString(), 3, List.of(), "op-arr"));
        ManifestProofResponse proof = baggageService.getManifestProof("LEG1", "BAG1");

        // 两个不同 requestId 凭同一有效证明并发补到：只能推进一次
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> baggageService.recover(new RecoverRequest(
                    UUID.randomUUID().toString(), "BAG1", "LEG1", "SHA",
                    "op-rec", proof.manifestRoot(), proof.leafIndex(), proof.proof())));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RecoverResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer index = jdbcTemplate.queryForObject(
                "SELECT next_leg_index FROM bag WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(index).isEqualTo(1);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG1' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(events).isEqualTo(1);
    }

    @Test
    void concurrentSameRecoverRequestId_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.load("LEG1",
                new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1")));
        baggageService.seal("LEG1",
                new SealRequest(UUID.randomUUID().toString(), 2, "op-seal"));
        baggageService.arriveDifference("LEG1", new BaggageDtos.DifferenceArriveRequest(
                UUID.randomUUID().toString(), 3, List.of(), "op-arr"));
        ManifestProofResponse proof = baggageService.getManifestProof("LEG1", "BAG1");

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.recover(new RecoverRequest(
                    requestId, "BAG1", "LEG1", "SHA",
                    "op-rec", proof.manifestRoot(), proof.leafIndex(), proof.proof())));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(RecoverResponse.class);
            assertThat(((RecoverResponse) result).status()).isEqualTo("DELIVERED");
        });
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG1' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(events).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    private void registerLeg(String legId, String origin, String destination, String legType) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), legId, origin, destination, legType));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（异常包装为返回值）。 */
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
