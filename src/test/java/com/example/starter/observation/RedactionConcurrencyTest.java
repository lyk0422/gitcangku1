package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附件脱敏并发边界测试：真实并发提交，验证同一附件同一规则版本只生成一个脱敏版本、
 * 多附件请求与观测合并/删除按提交顺序串行裁决、同 requestId/redactionId 并发重放只占一键。
 */
@SpringBootTest
class RedactionConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private RedactionService redactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_evidence_ref");
        jdbcTemplate.update("DELETE FROM attachment_redaction");
        jdbcTemplate.update("DELETE FROM attachment");
        jdbcTemplate.update("DELETE FROM redaction_rule");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
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

    private void setupObservationWithAttachmentAndRule(String observationId, String attachmentId,
                                                       String ruleVersion) {
        observationService.create(new CreateObservationRequest(
                "req-setup-" + observationId, observationId, "1号井口", "12.345", "巡检记录"));
        redactionService.registerRule(new RegisterRuleRequest(
                "req-setup-rule-" + ruleVersion, ruleVersion, "规则" + ruleVersion),
                RequesterRole.REDACTION_OFFICER);
        redactionService.registerAttachment(observationId, new RegisterAttachmentRequest(
                "req-setup-att-" + attachmentId, observationId, attachmentId, "原始敏感摘要"),
                RequesterRole.REDACTION_OFFICER);
    }

    private RedactAttachmentsRequest redactRequest(String requestId, String redactionId,
                                                   String attachmentId, String ruleVersion,
                                                   String note) {
        return new RedactAttachmentsRequest(requestId, redactionId,
                List.of(new RedactAttachmentsRequest.RedactItem(attachmentId, ruleVersion, note)));
    }

    @Test
    void concurrentSameAttachmentAndRuleVersionYieldsSingleRedactionVersion() throws Exception {
        setupObservationWithAttachmentAndRule("obs-c1", "att-c1", "rule-v1");

        // 同一附件、同一规则版本、不同 requestId/redactionId 并发 4 次：只生成一个脱敏版本，
        // 一个请求成功（201），其余 422；全部返回同一版本号或明确失败，无部分结果。
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    RedactionService.WriteOutcome<RedactionResponse> outcome = redactionService.redact(
                            "obs-c1",
                            redactRequest("req-cr-" + index, "red-cr-" + index, "att-c1", "rule-v1", "替换"),
                            RequesterRole.REDACTION_OFFICER);
                    return "ok-" + outcome.body().items().get(0).redactionVersion();
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn(r -> r.startsWith("ok-")).hasSize(1);
        assertThat(results).filteredOn("422"::equals).hasSize(3);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-c1'", Integer.class);
        assertThat(rows).isEqualTo(1);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT redaction_version FROM attachment_redaction WHERE attachment_id = 'att-c1'",
                Integer.class);
        assertThat(version).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRuleVersionsAssignIncrementalVersions() throws Exception {
        setupObservationWithAttachmentAndRule("obs-c2", "att-c2", "rule-v1");
        redactionService.registerRule(new RegisterRuleRequest(
                "req-c2-rule2", "rule-v2", "规则v2"), RequesterRole.REDACTION_OFFICER);

        // 两个不同规则版本并发：各生成一个脱敏版本，版本号为 1、2，不重复、不丢失。
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        RedactionService.WriteOutcome<RedactionResponse> outcome = redactionService.redact(
                                "obs-c2",
                                redactRequest("req-c2-a", "red-c2-a", "att-c2", "rule-v1", "替换v1"),
                                RequesterRole.REDACTION_OFFICER);
                        return "ok-" + outcome.body().items().get(0).redactionVersion();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        RedactionService.WriteOutcome<RedactionResponse> outcome = redactionService.redact(
                                "obs-c2",
                                redactRequest("req-c2-b", "red-c2-b", "att-c2", "rule-v2", "替换v2"),
                                RequesterRole.REDACTION_OFFICER);
                        return "ok-" + outcome.body().items().get(0).redactionVersion();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok-1", "ok-2");
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT redaction_version FROM attachment_redaction WHERE attachment_id = 'att-c2' "
                        + "ORDER BY redaction_version", Integer.class);
        assertThat(versions).containsExactly(1, 2);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleRedactionResult() throws Exception {
        setupObservationWithAttachmentAndRule("obs-c3", "att-c3", "rule-v1");

        // 同 requestId + 同 redactionId + 同参并发 4 次：全部返回同一结果，只生成一个版本、只占一键。
        List<Callable<RedactionService.WriteOutcome<RedactionResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> redactionService.redact(
                    "obs-c3",
                    redactRequest("req-c3-same", "red-c3-same", "att-c3", "rule-v1", "替换"),
                    RequesterRole.REDACTION_OFFICER));
        }
        List<RedactionService.WriteOutcome<RedactionResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.status() == 201);
        assertThat(outcomes).allMatch(o -> o.body().items().get(0).redactionVersion() == 1);
        Integer redactionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-c3'", Integer.class);
        assertThat(redactionRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-c3-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentRedactionAndDeleteAreSerializedWithoutPartialResults() throws Exception {
        setupObservationWithAttachmentAndRule("obs-c4", "att-c4", "rule-v1");

        // 脱敏与删除并发：由观测行锁串行化。先删则脱敏 404；先脱敏则删除成功，证据固化按顺序发生。
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        redactionService.redact(
                                "obs-c4",
                                redactRequest("req-c4-r", "red-c4", "att-c4", "rule-v1", "替换"),
                                RequesterRole.REDACTION_OFFICER);
                        return "redact-ok";
                    } catch (ApiException e) {
                        return "redact-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-c4", new DeleteObservationRequest("req-c4-d", 1));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-c4");
        Integer redactionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-c4'", Integer.class);
        if (current.deleted()) {
            // 删除先生效（v2 墓碑）：脱敏必为 404，不产生任何脱敏版本
            assertThat(results).containsExactlyInAnyOrder("delete-ok", "redact-404");
            assertThat(current.version()).isEqualTo(2);
            assertThat(redactionRows).isZero();
            // 墓碑版本证据固化行存在（附件当时无有效脱敏）
            Integer evidenceRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM observation_evidence_ref "
                            + "WHERE observation_id = 'obs-c4' AND observation_version = 2", Integer.class);
            assertThat(evidenceRows).isEqualTo(1);
        } else {
            // 脱敏先生效：删除成功生成墓碑，脱敏版本恰好 1 个
            assertThat(results).containsExactlyInAnyOrder("redact-ok", "delete-ok");
            assertThat(current.version()).isEqualTo(2);
            assertThat(redactionRows).isEqualTo(1);
        }
    }

    @Test
    void concurrentMultiAttachmentRequestIsAllOrNothing() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-c5-0", "obs-c5", "1号井口", "12.345", "巡检记录"));
        redactionService.registerRule(new RegisterRuleRequest(
                "req-c5-rule", "rule-v1", "规则v1"), RequesterRole.REDACTION_OFFICER);
        redactionService.registerAttachment("obs-c5", new RegisterAttachmentRequest(
                "req-c5-a1", "obs-c5", "att-c5-1", "摘要1"), RequesterRole.REDACTION_OFFICER);
        redactionService.registerAttachment("obs-c5", new RegisterAttachmentRequest(
                "req-c5-a2", "obs-c5", "att-c5-2", "摘要2"), RequesterRole.REDACTION_OFFICER);

        // 两个多附件请求并发，且两个请求都使用同一规则版本：每个附件至多一个请求成功，
        // 但单请求必须整单成功或整单失败——不允许出现只给一个附件脱敏的部分结果。
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        RedactionService.WriteOutcome<RedactionResponse> outcome = redactionService.redact(
                                "obs-c5",
                                new RedactAttachmentsRequest("req-c5-x", "red-c5-x", List.of(
                                        new RedactAttachmentsRequest.RedactItem("att-c5-1", "rule-v1", "x1"),
                                        new RedactAttachmentsRequest.RedactItem("att-c5-2", "rule-v1", "x2"))),
                                RequesterRole.REDACTION_OFFICER);
                        return "ok-" + outcome.body().items().size();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        RedactionService.WriteOutcome<RedactionResponse> outcome = redactionService.redact(
                                "obs-c5",
                                new RedactAttachmentsRequest("req-c5-y", "red-c5-y", List.of(
                                        new RedactAttachmentsRequest.RedactItem("att-c5-1", "rule-v1", "y1"),
                                        new RedactAttachmentsRequest.RedactItem("att-c5-2", "rule-v1", "y2"))),
                                RequesterRole.REDACTION_OFFICER);
                        return "ok-" + outcome.body().items().size();
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 先提交者整单成功（2 项），后提交者整单 422
        assertThat(results).containsExactlyInAnyOrder("ok-2", "422");
        Map<String, Integer> perAttachment = new java.util.HashMap<>();
        jdbcTemplate.query("SELECT attachment_id, COUNT(*) AS c FROM attachment_redaction GROUP BY attachment_id",
                (rs) -> {
                    perAttachment.put(rs.getString("attachment_id"), rs.getInt("c"));
                });
        assertThat(perAttachment).containsEntry("att-c5-1", 1).containsEntry("att-c5-2", 1);
    }
}
