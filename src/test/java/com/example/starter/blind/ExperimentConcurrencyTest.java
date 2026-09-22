package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 并发与幂等边界测试：真实并发请求经由 H2 数据库约束与事务验证，设置超时并断言最终数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:blind_test_concurrent;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperimentConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper om;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM unblind_request");
        jdbc.update("DELETE FROM experiment_seat");
        jdbc.update("DELETE FROM experiment");
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    private String rid() {
        return "REQ-" + UUID.randomUUID();
    }

    private void createExperiment(String experimentId) throws Exception {
        mvc.perform(post("/api/experiments")
                        .header("X-Actor-Id", "coord-1").header("X-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + rid() + "\",\"experimentId\":\""
                                + experimentId + "\",\"blocks\":[[\"A\",\"B\",\"A\",\"B\"],"
                                + "[\"B\",\"A\",\"B\",\"A\"]]}"))
                .andReturn();
    }

    private MvcResult assign(String experimentId, String requestId, String participantId)
            throws Exception {
        return mvc.perform(post("/api/experiments/{e}/assignments", experimentId)
                        .header("X-Actor-Id", "coord-1").header("X-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId
                                + "\",\"participantId\":\"" + participantId + "\"}"))
                .andReturn();
    }

    private <T> List<T> runConcurrently(int threads, java.util.concurrent.Callable<T> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    @Test
    void concurrentDistinctParticipantsGetDistinctSeats() throws Exception {
        createExperiment("EXP-CONC");
        List<MvcResult> results = runConcurrently(8, () -> {
            String participant = "P-" + UUID.randomUUID();
            return assign("EXP-CONC", rid(), participant);
        });
        Set<String> blindCodes = new HashSet<>();
        Set<String> participants = new HashSet<>();
        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = om.readTree(r.getResponse().getContentAsString());
            blindCodes.add(body.get("blindCode").asText());
            participants.add(body.get("participantId").asText());
        }
        assertThat(blindCodes).hasSize(8);
        assertThat(participants).hasSize(8);
        Integer assigned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_seat WHERE experiment_id = 'EXP-CONC'"
                        + " AND seat_status = 'ASSIGNED'", Integer.class);
        assertThat(assigned).isEqualTo(8);
    }

    @Test
    void concurrentSameRequestIdProducesSingleEffect() throws Exception {
        createExperiment("EXP-IDEMCONC");
        String requestId = rid();
        List<MvcResult> results = runConcurrently(6,
                () -> assign("EXP-IDEMCONC", requestId, "P-SAME"));
        String firstBody = null;
        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            if (firstBody == null) {
                firstBody = r.getResponse().getContentAsString();
            } else {
                assertThat(r.getResponse().getContentAsString()).isEqualTo(firstBody);
            }
        }
        Integer assigned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_seat WHERE experiment_id = 'EXP-IDEMCONC'"
                        + " AND participant_id IS NOT NULL", Integer.class);
        assertThat(assigned).isEqualTo(1);
        Integer keys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = '" + requestId + "'",
                Integer.class);
        assertThat(keys).isEqualTo(1);
    }

    @Test
    void concurrentSameParticipantDifferentRequestIdsOnlyOneWins() throws Exception {
        createExperiment("EXP-RACE");
        List<MvcResult> results = runConcurrently(4,
                () -> assign("EXP-RACE", rid(), "P-RACE"));
        long ok = results.stream().filter(r -> r.getResponse().getStatus() == 200).count();
        long conflicted = results.stream().filter(r -> r.getResponse().getStatus() == 409).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicted).isEqualTo(3);
        Integer assigned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_seat WHERE experiment_id = 'EXP-RACE'"
                        + " AND participant_id = 'P-RACE'", Integer.class);
        assertThat(assigned).isEqualTo(1);
    }
}
