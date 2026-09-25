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
import com.example.starter.incident.dto.Responses.AgencyConfigView;
import com.example.starter.incident.dto.Responses.AgencyReceiptView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部机构回执并发边界测试：验证同 ackKey 并发单次生效、同机构同版本终态回执唯一、
 * 配置替换按提交顺序裁决、回执与 HIGH 任务完成/事件关闭并发形成合法终态。
 */
@SpringBootTest
class AgencyAckConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_agency_receipts");
        jdbc.update("DELETE FROM incident_agency_configs");
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

    private static long countStatus(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentAck_sameAckKey_singleEffect() throws Exception {
        commanding("INC-P1", "alice");
        service.configureAgencies("INC-P1", "alice",
                new AgencyConfigRequest(0L, List.of("FIRE")));
        String ackKey = key();
        List<Callable<AgencyReceiptView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.submitAgencyAck("INC-P1",
                    new AgencyAckRequest(ackKey, "FIRE", 1L, "CONFIRM", null)));
        }
        List<Object> results = runConcurrently(tasks);

        List<AgencyReceiptView> successes = results.stream()
                .filter(AgencyReceiptView.class::isInstance)
                .map(AgencyReceiptView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且回执只落库一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts", Integer.class);
        assertThat(receiptCount).isEqualTo(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, ackKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentAck_sameAgencyDifferentKeys_singleTerminalReceipt() throws Exception {
        commanding("INC-P2", "alice");
        service.configureAgencies("INC-P2", "alice",
                new AgencyConfigRequest(0L, List.of("FIRE")));
        // 同一机构并发提交两条不同 ackKey 的回执：恰好一条终态回执
        List<Object> results = runConcurrently(List.of(
                () -> service.submitAgencyAck("INC-P2",
                        new AgencyAckRequest(key(), "FIRE", 1L, "CONFIRM", null)),
                () -> service.submitAgencyAck("INC-P2",
                        new AgencyAckRequest(key(), "FIRE", 1L, "REJECT", "拒绝"))));

        long successes = results.stream().filter(AgencyReceiptView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        Integer receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts", Integer.class);
        assertThat(receiptCount).isEqualTo(1);
        // 若拒绝先提交，事件进入阻断态；最终状态与唯一回执一致
        String receiptType = jdbc.queryForObject(
                "SELECT receipt_type FROM incident_agency_receipts", String.class);
        IncidentView end = service.get("INC-P2");
        if ("REJECT".equals(receiptType)) {
            assertThat(end.status()).isEqualTo("EXTERNAL_BLOCKED");
        } else {
            assertThat(end.status()).isEqualTo("COMMANDING");
        }
    }

    @Test
    void concurrentAck_distinctAgencies_bothRecorded() throws Exception {
        commanding("INC-P3", "alice");
        service.configureAgencies("INC-P3", "alice",
                new AgencyConfigRequest(0L, List.of("FIRE", "MEDIC")));
        List<Object> results = runConcurrently(List.of(
                () -> service.submitAgencyAck("INC-P3",
                        new AgencyAckRequest(key(), "FIRE", 1L, "CONFIRM", null)),
                () -> service.submitAgencyAck("INC-P3",
                        new AgencyAckRequest(key(), "MEDIC", 1L, "CONFIRM", null))));

        long successes = results.stream().filter(AgencyReceiptView.class::isInstance).count();
        assertThat(successes).isEqualTo(2);
        AgencyConfigView view = service.agencyConfig("INC-P3");
        assertThat(view.pendingAgencies()).isEmpty();
        assertThat(view.receipts()).hasSize(2);
    }

    @Test
    void concurrentConfigure_sameExpectedVersion_singleSuccess() throws Exception {
        commanding("INC-P4", "alice");
        // 并发首次配置（expectedVersion 均为 0）：按提交顺序裁决，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.configureAgencies("INC-P4", "alice",
                        new AgencyConfigRequest(0L, List.of("FIRE"))),
                () -> service.configureAgencies("INC-P4", "alice",
                        new AgencyConfigRequest(0L, List.of("MEDIC")))));

        long successes = results.stream().filter(AgencyConfigView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        Integer configCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_configs", Integer.class);
        assertThat(configCount).isEqualTo(1);
        assertThat(service.agencyConfig("INC-P4").version()).isEqualTo(1);
    }

    @Test
    void concurrentRejectAndHighTaskComplete_commitOrderWins() throws Exception {
        commanding("INC-P5", "alice");
        service.configureAgencies("INC-P5", "alice",
                new AgencyConfigRequest(0L, List.of("FIRE")));
        service.createTask("INC-P5", "alice",
                new TaskCreateRequest(key(), "T-H", "G", "高危处置", List.of(), "HIGH"));

        // 并发：机构拒绝 与 完成 HIGH 任务（未完成前门禁本就拦截，此处验证拒绝后仍一致）
        List<Object> results = runConcurrently(List.of(
                () -> service.submitAgencyAck("INC-P5",
                        new AgencyAckRequest(key(), "FIRE", 1L, "REJECT", "无法支援")),
                () -> service.completeTask("INC-P5", "T-H", "alice",
                        new TaskActionRequest(key()))));

        // 拒绝必然成功；完成无论先后都被门禁拦截（422）：拒绝前未完成确认，拒绝后已阻断
        assertThat(results.get(0)).isInstanceOf(AgencyReceiptView.class);
        assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        IncidentView end = service.get("INC-P5");
        assertThat(end.status()).isEqualTo("EXTERNAL_BLOCKED");
        assertThat(service.getTask("INC-P5", "T-H").status()).isEqualTo("OPEN");
    }

    @Test
    void concurrentConfirmAndHighTaskComplete_commitOrderWins() throws Exception {
        commanding("INC-P6", "alice");
        service.configureAgencies("INC-P6", "alice",
                new AgencyConfigRequest(0L, List.of("FIRE")));
        service.createTask("INC-P6", "alice",
                new TaskCreateRequest(key(), "T-H", "G", "高危处置", List.of(), "HIGH"));

        // 并发：唯一必需机构确认 与 完成 HIGH 任务
        List<Object> results = runConcurrently(List.of(
                () -> service.submitAgencyAck("INC-P6",
                        new AgencyAckRequest(key(), "FIRE", 1L, "CONFIRM", null)),
                () -> service.completeTask("INC-P6", "T-H", "alice",
                        new TaskActionRequest(key()))));

        // 确认必然成功；完成是否成功取决于其评估时确认是否已提交
        assertThat(results.get(0)).isInstanceOf(AgencyReceiptView.class);
        TaskView task = service.getTask("INC-P6", "T-H");
        Object completeResult = results.get(1);
        if (task.status().equals("DONE")) {
            assertThat(completeResult).isInstanceOf(TaskView.class);
        } else {
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            // 确认已生效：重试即可完成
            TaskView done = service.completeTask("INC-P6", "T-H", "alice",
                    new TaskActionRequest(key()));
            assertThat(done.status()).isEqualTo("DONE");
        }
    }

    @Test
    void concurrentCloseAndAck_commitOrderWins() throws Exception {
        commanding("INC-P7", "alice");
        service.configureAgencies("INC-P7", "alice",
                new AgencyConfigRequest(0L, List.of("FIRE")));
        service.changeStatus("INC-P7", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-P7", "alice", new StatusRequest(key(), "RESOLVED"));

        // 并发：关闭事件 与 机构确认回执
        List<Object> results = runConcurrently(List.of(
                () -> service.changeStatus("INC-P7", "alice",
                        new StatusRequest(key(), "CLOSED")),
                () -> service.submitAgencyAck("INC-P7",
                        new AgencyAckRequest(key(), "FIRE", 1L, "CONFIRM", null))));

        IncidentView end = service.get("INC-P7");
        assertThat(end.status()).isEqualTo("CLOSED");
        Object ackResult = results.get(1);
        Integer receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts", Integer.class);
        if (ackResult instanceof ApiException) {
            // 关闭先提交：回执被拒（409），不占回执
            assertThat(((ApiException) ackResult).status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(receiptCount).isZero();
        } else {
            // 回执先提交：确认不阻断关闭，回执已落库且关闭仍成功
            assertThat(ackResult).isInstanceOf(AgencyReceiptView.class);
            assertThat(results.get(0)).isInstanceOf(IncidentView.class);
            assertThat(receiptCount).isEqualTo(1);
        }
    }
}
