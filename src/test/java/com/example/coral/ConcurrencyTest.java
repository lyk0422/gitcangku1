package com.example.coral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.QuadratStatus;
import com.example.coral.domain.SealSnapshot;
import com.example.coral.service.QuadratService;
import com.example.coral.service.TransplantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发边界：真实H2上协调并发操作并设超时，核对业务结果与最终数据。
 * 行锁保证按事务提交顺序裁决，任何时刻活跃数不超过12。
 */
@SpringBootTest
class ConcurrencyTest {

    @Autowired
    private TransplantService transplantService;
    @Autowired
    private QuadratService quadratService;
    @Autowired
    private JdbcTemplate jdbc;

    private final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(4);

    /** 并发操作结果：value非空表示成功，error非空表示业务冲突。 */
    private record Outcome<T>(T value, Exception error) {
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM seal_snapshot");
        jdbc.update("DELETE FROM transplant");
        jdbc.update("DELETE FROM quadrat");
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private <T> List<Outcome<T>> race(Callable<T> a, Callable<T> b) throws Exception {
        var start = new CountDownLatch(1);
        Callable<Outcome<T>> wrapA = () -> {
            start.await();
            try {
                return new Outcome<>(a.call(), null);
            } catch (Exception e) {
                return new Outcome<>(null, e);
            }
        };
        Callable<Outcome<T>> wrapB = () -> {
            start.await();
            try {
                return new Outcome<>(b.call(), null);
            } catch (Exception e) {
                return new Outcome<>(null, e);
            }
        };
        var fa = pool.submit(wrapA);
        var fb = pool.submit(wrapB);
        start.countDown();
        return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
    }

    @Test
    void capacityRaceNeverExceedsTwelve() throws Exception {
        quadratService.create("Q-CR", "容量竞争样方");
        for (int i = 1; i <= 11; i++) {
            transplantService.register("req-cr-" + i, "P-CR-" + i, "苗圃A", "Q-CR");
        }
        var outcomes = race(
                () -> transplantService.register("req-cr-a", "P-CR-A", "苗圃A", "Q-CR"),
                () -> transplantService.register("req-cr-b", "P-CR-B", "苗圃A", "Q-CR"));
        long succeeded = outcomes.stream().filter(o -> o.value() != null).count();
        assertEquals(1, succeeded, "12上限下两个并发登记只能成功一个");
        var q = quadratService.findByCode("Q-CR");
        assertEquals(12, quadratService.plantsOf(q.id()).stream()
                .filter(t -> t.status() != PlantStatus.DEAD).count());
    }

    @Test
    void deathReleaseAndNewTransplantDecidedByCommitOrder() throws Exception {
        quadratService.create("Q-DR", "释放竞争样方");
        for (int i = 1; i <= 12; i++) {
            transplantService.register("req-dr-" + i, "P-DR-" + i, "苗圃A", "Q-DR");
        }
        var outcomes = race(
                () -> transplantService.review("req-dr-kill", "P-DR-1", PlantStatus.DEAD, "死亡"),
                () -> transplantService.register("req-dr-new", "P-DR-NEW", "苗圃B", "Q-DR"));
        // 死亡复核必须成功
        assertTrue(outcomes.get(0).error() == null, "死亡复核应成功: " + outcomes.get(0).error());
        var q = quadratService.findByCode("Q-DR");
        var plants = quadratService.plantsOf(q.id());
        long active = plants.stream().filter(t -> t.status() != PlantStatus.DEAD).count();
        // 按提交顺序裁决：新移植成功则活跃12，失败则11；任何时刻不超过12
        assertTrue(active <= 12, "活跃数不得超过12，实际: " + active);
        boolean registered = outcomes.get(1).error() == null;
        assertEquals(registered ? 12 : 11, active);
        assertEquals(PlantStatus.DEAD, transplantService.findByPlantCode("P-DR-1").status());
    }

    @Test
    void sealAndReviewRaceYieldsOneConsistentVersion() throws Exception {
        quadratService.create("Q-SR", "封存竞争样方");
        transplantService.register("req-sr-1", "P-SR-1", "苗圃A", "Q-SR");
        var outcomes = race(
                () -> transplantService.review("req-sr-r1", "P-SR-1", PlantStatus.ALIVE, null),
                () -> quadratService.seal("Q-SR"));
        var q = quadratService.findByCode("Q-SR");
        var snapshot = quadratService.snapshotOf(q.id());
        if (q.status() == QuadratStatus.SEALED) {
            // 复核先提交：封存成功且快照与最终数据一致
            assertTrue(snapshot.isPresent());
            SealSnapshot s = snapshot.get();
            assertEquals(0, s.pendingCount());
            assertEquals(1, s.aliveCount());
            assertEquals(List.of("P-SR-1"), s.plantCodes());
            assertEquals(PlantStatus.ALIVE, transplantService.findByPlantCode("P-SR-1").status());
        } else {
            // 封存先检查：存在待复核而失败，复核随后成功，样方仍开放
            assertTrue(snapshot.isEmpty());
            assertTrue(outcomes.get(1).error() != null
                    && outcomes.get(1).error().getMessage().contains("待复核"));
            assertEquals(PlantStatus.ALIVE, transplantService.findByPlantCode("P-SR-1").status());
        }
        // 只可能形成一个清单版本
        assertEquals(q.status() == QuadratStatus.SEALED ? 1 : 0,
                jdbc.queryForObject("SELECT COUNT(*) FROM seal_snapshot", Integer.class).intValue());
    }

    @Test
    void doubleSealRaceKeepsSingleSnapshot() throws Exception {
        quadratService.create("Q-DS", "双封存样方");
        transplantService.register("req-ds-1", "P-DS-1", "苗圃A", "Q-DS");
        transplantService.review("req-ds-r1", "P-DS-1", PlantStatus.ALIVE, null);
        var outcomes = race(() -> quadratService.seal("Q-DS"), () -> quadratService.seal("Q-DS"));
        assertTrue(outcomes.stream().allMatch(o -> o.error() == null));
        // 两个并发封存得到同一个清单版本
        assertEquals(outcomes.get(0).value().id(), outcomes.get(1).value().id());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seal_snapshot", Integer.class).intValue());
    }
}