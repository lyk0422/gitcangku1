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

import com.example.starter.incident.dto.Requests.AgencyAckRequest;
import com.example.starter.incident.dto.Requests.AgencyConfigRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.AgencyAckView;
import com.example.starter.incident.dto.Responses.AgencyGateView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部机构回执并发边界测试：配置修改乐观并发、同机构同版本回执唯一、
 * 同 ackKey 并发单次生效、回执与 HIGH 任务完成/事件关闭按提交顺序裁决。
 */
@SpringBootTest
class AgencyGateConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agency_ack_keys");
        jdbc.update("DELETE FROM incident_agency_acks");
        jdbc.update("DELETE FROM incident_agency_configs");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private static String ackKey() {
        return "ACK-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    /**
     * 并发提交一批操作并收集结果（成功值或异常）。
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

    private static long countConflicts(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
    }

    @Test
    void concurrentConfig_sameExpectedVersion_singleSuccess() throws Exception {
        commanding("INC-C1", "alice");
        // 两个并发配置都携带 expectedVersion=0：按提交顺序恰一个成功，另一个 409
        List<Object> results = runConcurrently(List.of(
                () -> service.configureAgencies("INC-C1", "alice",
                        new AgencyConfigRequest(key(), 0, List.of("A"))),
                () -> service.configureAgencies("INC-C1", "alice",
                        new AgencyConfigRequest(key(), 0, List.of("B")))));

        long successes = results.stream().filter(AgencyGateView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        // 只产生一个配置版本
        assertThat(service.agencyGate("INC-C1").currentVersion()).isEqualTo(1);
        assertThat(service.agencyGate("INC-C1").configs()).hasSize(1);
    }

    @Test
    void concurrentAck_sameAgencySameVersion_singleTerminal() throws Exception {
        commanding("INC-C2", "alice");
        service.configureAgencies("INC-C2", "alice",
                new AgencyConfigRequest(key(), 0, List.of("A")));
        // 同一机构并发提交两条不同 ackKey 的回执：恰一条终态落库
        List<Object> results = runConcurrently(List.of(
                () -> service.submitAgencyAck("INC-C2", "agency-A",
                        new AgencyAckRequest(ackKey(), "A", "CONFIRM", null)),
                () -> service.submitAgencyAck("INC-C2", "agency-A",
                        new AgencyAckRequest(ackKey(), "A", "CONFIRM", null))));

        long successes = results.stream().filter(AgencyAckView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        Integer ackRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_acks WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key='INC-C2')",
                Integer.class);
        assertThat(ackRows).isEqualTo(1);
    }

    @Test
    void concurrentAck_sameAckKey_singleEffect() throws Exception {
        commanding("INC-C3", "alice");
        service.configureAgencies("INC-C3", "alice",
                new AgencyConfigRequest(key(), 0, List.of("A")));
        String sharedKey = ackKey();
        List<Callable<AgencyAckView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> service.submitAgencyAck("INC-C3", "agency-A",
                    new AgencyAckRequest(sharedKey, "A", "CONFIRM", null)));
        }
        List<Object> results = runConcurrently(calls);

        List<AgencyAckView> successes = results.stream().filter(AgencyAckView.class::isInstance)
                .map(AgencyAckView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且回执只落库一条
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer ackRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_acks WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key='INC-C3')",
                Integer.class);
        assertThat(ackRows).isEqualTo(1);
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agency_ack_keys WHERE ack_key = ?", Integer.class, sharedKey);
        assertThat(keyRows).isEqualTo(1);
    }

    @Test
    void concurrentAckAndHighTaskComplete_commitOrderWins() throws Exception {
        commanding("INC-C4", "alice");
        service.configureAgencies("INC-C4", "alice",
                new AgencyConfigRequest(key(), 0, List.of("A")));
        service.createTask("INC-C4", "alice",
                new TaskCreateRequest(key(), "T-H", "NET", "高优", List.of(), "HIGH"));

        // 并发：机构 A 确认 与 完成 HIGH 任务
        List<Object> results = runConcurrently(List.of(
                () -> service.submitAgencyAck("INC-C4", "agency-A",
                        new AgencyAckRequest(ackKey(), "A", "CONFIRM", null)),
                () -> service.completeTask("INC-C4", "T-H", "alice",
                        new TaskActionRequest(key()))));

        // 确认必然成功；完成是否成功取决于其读取时确认是否已提交
        assertThat(results.get(0)).isInstanceOf(AgencyAckView.class);
        TaskView task = service.getTask("INC-C4", "T-H");
        Object completeResult = results.get(1);
        if (task.status().equals("DONE")) {
            assertThat(completeResult).isInstanceOf(TaskView.class);
        } else {
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    @Test
    void concurrentRejectAndClose_commitOrderConsistent() throws Exception {
        commanding("INC-C5", "alice");
        service.configureAgencies("INC-C5", "alice",
                new AgencyConfigRequest(key(), 0, List.of("A")));
        // 推进到 RESOLVED（无 OPEN 任务）
        service.changeStatus("INC-C5", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-C5", "alice", new StatusRequest(key(), "RESOLVED"));

        // 并发：机构 A 拒绝 与 关闭事件
        List<Object> results = runConcurrently(List.of(
                () -> service.submitAgencyAck("INC-C5", "agency-A",
                        new AgencyAckRequest(ackKey(), "A", "REJECT", "未达成一致")),
                () -> service.changeStatus("INC-C5", "alice",
                        new StatusRequest(key(), "CLOSED"))));

        IncidentView end = service.get("INC-C5");
        Object rejectResult = results.get(0);
        Object closeResult = results.get(1);
        if ("CLOSED".equals(end.status())) {
            // 关闭先提交：拒绝因事件已关闭被拒（409），事件保持 CLOSED
            assertThat(closeResult).isInstanceOf(IncidentView.class);
            assertThat(rejectResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        } else {
            // 拒绝先提交：事件进入 EXTERNAL_BLOCKED，关闭因非法流转被拒（422）
            assertThat(end.status()).isEqualTo("EXTERNAL_BLOCKED");
            assertThat(rejectResult).isInstanceOf(AgencyAckView.class);
            assertThat(closeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }
}
