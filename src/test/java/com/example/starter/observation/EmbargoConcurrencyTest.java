package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁运发布并发边界测试：真实并发提交，验证同一簇并发到期发布只生成一个快照、
 * 并发更正附页序号唯一、同 requestId 并发重放只占一键。使用可控 UTC 时钟，不靠休眠判定。
 */
@SpringBootTest
class EmbargoConcurrencyTest {

    private static final Instant FIXED_NOW = Instant.parse("2027-06-01T00:00:00Z");

    @Autowired
    private ObservationService observationService;

    @Autowired
    private EmbargoService embargoService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(FIXED_NOW);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM disclosure_appendix");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM observation_pending_conflict");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        now.set(FIXED_NOW);
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<T>> futures = synchronizedTasks.stream().map(executor::submit).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers not ready in time");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "初始备注"));
    }

    private void setEmbargo(String requestId, String observationId) {
        embargoService.setEmbargoes(new SetEmbargoRequest(requestId, "op-1",
                List.of(new SetEmbargoItem(observationId, FIXED_NOW, "PUBLIC"))));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentReleaseGeneratesSingleSnapshot() throws Exception {
        createObservation("obs-cr1", "req-cr0");
        setEmbargo("req-cr1", "obs-cr1");

        List<Callable<EmbargoService.ReleaseOutcome>> tasks = List.of(
                () -> embargoService.release("obs-cr1", new ReleaseRequest("req-cr2", "op-1")),
                () -> embargoService.release("obs-cr1", new ReleaseRequest("req-cr3", "op-2")));
        List<EmbargoService.ReleaseOutcome> outcomes = runConcurrently(tasks);

        // 恰好一个首次发布（201），另一个拿到既有快照（200）；内容一致
        assertThat(outcomes.stream().map(EmbargoService.ReleaseOutcome::status))
                .containsExactlyInAnyOrder(200, 201);
        assertThat(outcomes.stream().map(o -> o.body().releasedAtUtc()))
                .allMatch(FIXED_NOW::equals);
        assertThat(outcomes.stream().map(o -> o.body().version())).allMatch(v -> v == 1);

        assertThat(count("SELECT COUNT(*) FROM disclosure_snapshot WHERE observation_id = ?", "obs-cr1"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM request_log WHERE request_id IN (?, ?)",
                "req-cr2", "req-cr3")).isEqualTo(2);
    }

    @Test
    void concurrentCorrectionsAppendUniqueSequentialAppendices() throws Exception {
        createObservation("obs-cc1", "req-cc0");
        setEmbargo("req-cc1", "obs-cc1");
        embargoService.release("obs-cc1", new ReleaseRequest("req-cc2", "op-1"));

        List<Callable<EmbargoService.CorrectOutcome>> tasks = List.of(
                () -> embargoService.correct("obs-cc1",
                        new CorrectionRequest("req-cc3", "op-1", "站点X", "2.5", "备注X", "复核一")),
                () -> embargoService.correct("obs-cc1",
                        new CorrectionRequest("req-cc4", "op-2", "站点Y", "3.5", "备注Y", "复核二")));
        List<EmbargoService.CorrectOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().map(EmbargoService.CorrectOutcome::status))
                .allMatch(status -> status == 201);
        assertThat(outcomes.stream().map(o -> o.body().seq()))
                .containsExactlyInAnyOrder(1, 2);
        assertThat(count("SELECT COUNT(*) FROM disclosure_appendix WHERE observation_id = ?", "obs-cc1"))
                .isEqualTo(2);

        // 快照未被更正改写
        DisclosureResponse disclosure = embargoService.getDisclosure("obs-cc1");
        assertThat(disclosure.location()).isEqualTo("站点A");
        assertThat(disclosure.appendixCount()).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdSetEmbargoReplaysSingleKey() throws Exception {
        createObservation("obs-cs1", "req-cs0");

        List<Callable<EmbargoService.SetEmbargoOutcome>> tasks = List.of(
                () -> embargoService.setEmbargoes(new SetEmbargoRequest("req-cs1", "op-1",
                        List.of(new SetEmbargoItem("obs-cs1", FIXED_NOW, "PUBLIC")))),
                () -> embargoService.setEmbargoes(new SetEmbargoRequest("req-cs1", "op-1",
                        List.of(new SetEmbargoItem("obs-cs1", FIXED_NOW, "PUBLIC")))));
        List<EmbargoService.SetEmbargoOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().map(EmbargoService.SetEmbargoOutcome::status))
                .allMatch(status -> status == 201);
        assertThat(outcomes.stream().map(o -> o.body().setAtUtc())).allMatch(FIXED_NOW::equals);
        assertThat(count("SELECT COUNT(*) FROM observation_embargo WHERE observation_id = ?", "obs-cs1"))
                .isEqualTo(1);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cs1'", Integer.class);
        assertThat(logCount).isEqualTo(1);
    }
}