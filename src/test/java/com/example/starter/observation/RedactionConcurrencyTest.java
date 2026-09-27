package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附件脱敏并发边界测试：同一附件同一规则版本并发只生成一个版本、
 * 同 requestId 并发重放只占一键、脱敏与观测删除按提交顺序串行裁决。
 */
@SpringBootTest
class RedactionConcurrencyTest {

    @Autowired
    private RedactionService redactionService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_evidence");
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

    private void seedObservationWithAttachment(String observationId, String attachmentId) {
        observationService.create(new CreateObservationRequest(
                "req-c-" + observationId, observationId, "站点A", "1.0", "初始备注"));
        redactionService.registerAttachment(observationId, ActorRole.REDACTOR,
                new RegisterAttachmentRequest("req-a-" + attachmentId, attachmentId,
                        "原始摘要-" + attachmentId, "text/plain"));
    }

    private void registerRule(String ruleVersion) {
        redactionService.registerRule(ActorRole.ADMIN,
                new RegisterRuleRequest("req-r-" + ruleVersion, ruleVersion, "规则"));
    }

    private CreateRedactionRequest redactRequest(String requestId, String ruleVersion,
                                                 String attachmentId, String note) {
        return new CreateRedactionRequest(requestId, ruleVersion,
                List.of(new CreateRedactionRequest.Item(attachmentId, note)));
    }

    private int redactionVersionCount(String attachmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = ?",
                Integer.class, attachmentId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentSameAttachmentSameRuleVersionYieldsSingleVersion() throws Exception {
        registerRule("rule-c1");
        seedObservationWithAttachment("obs-c1", "att-c1");

        // 不同 requestId、同一附件同一规则版本并发：唯一键串行化，恰好一个成功，其余 409
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    redactionService.createRedactions("obs-c1", ActorRole.REDACTOR,
                            redactRequest("req-cr" + index, "rule-c1", "att-c1", "替换" + index));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(3);
        assertThat(redactionVersionCount("att-c1")).isEqualTo(1);
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM attachment_redaction WHERE attachment_id = 'att-c1'",
                Integer.class);
        assertThat(maxVersion).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdSameParamsReplaySingleResult() throws Exception {
        registerRule("rule-c2");
        seedObservationWithAttachment("obs-c2", "att-c2");

        List<Callable<RedactionService.RedactionOutcome<RedactionBatchResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> redactionService.createRedactions("obs-c2", ActorRole.REDACTOR,
                    redactRequest("req-replay", "rule-c2", "att-c2", "替换并发")));
        }
        List<RedactionService.RedactionOutcome<RedactionBatchResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().versions().size() == 1);
        assertThat(outcomes).allMatch(outcome -> outcome.body().versions().get(0).version() == 1);
        // 所有并发响应携带同一 redactionId：确实重放首次结果而非各自生成
        String firstRedactionId = outcomes.get(0).body().versions().get(0).redactionId();
        assertThat(outcomes).allMatch(
                outcome -> outcome.body().versions().get(0).redactionId().equals(firstRedactionId));
        assertThat(redactionVersionCount("att-c2")).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-replay'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentRedactionAndDeleteSerializeByCommitOrder() throws Exception {
        registerRule("rule-c3");
        seedObservationWithAttachment("obs-c3", "att-c3");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        redactionService.createRedactions("obs-c3", ActorRole.REDACTOR,
                                redactRequest("req-redact-race", "rule-c3", "att-c3", "替换"));
                        return "redact-ok";
                    } catch (ApiException e) {
                        return "redact-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-c3", new DeleteObservationRequest("req-del-race", 1));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 串行化后两者都按各自前置条件裁决：删除必成功；脱敏在删除前提交则成功，否则 403
        assertThat(results).contains("delete-ok");
        assertThat(results).anyMatch(result -> result.equals("redact-ok") || result.equals("redact-403"));
        if (results.contains("redact-403")) {
            assertThat(redactionVersionCount("att-c3")).isZero();
        } else {
            assertThat(redactionVersionCount("att-c3")).isEqualTo(1);
        }
        // 无论顺序如何，观测最终为墓碑且版本前进到 2
        ObservationResponse current = observationService.getCurrent("obs-c3");
        assertThat(current.deleted()).isTrue();
        assertThat(current.version()).isEqualTo(2);
    }

    @Test
    void concurrentRedactionAndRuleRevokeSerializeByCommitOrder() throws Exception {
        registerRule("rule-c4");
        seedObservationWithAttachment("obs-c4", "att-c4");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        redactionService.createRedactions("obs-c4", ActorRole.REDACTOR,
                                redactRequest("req-redact-rule-race", "rule-c4", "att-c4", "替换"));
                        return "redact-ok";
                    } catch (ApiException e) {
                        return "redact-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        redactionService.revokeRule("rule-c4", ActorRole.ADMIN,
                                new RevokeRuleRequest("req-revoke-rule-race", "规则作废"));
                        return "revoke-ok";
                    } catch (ApiException e) {
                        return "revoke-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("revoke-ok");
        // 脱敏先于撤销提交则成功；否则看到已撤销规则返回 422
        assertThat(results).anyMatch(result -> result.equals("redact-ok") || result.equals("redact-422"));
        if (results.contains("redact-422")) {
            assertThat(redactionVersionCount("att-c4")).isZero();
        } else {
            assertThat(redactionVersionCount("att-c4")).isEqualTo(1);
        }
        // 规则最终必为已撤销
        assertThat(redactionService.getRule("rule-c4", ActorRole.ADMIN).status()).isEqualTo("REVOKED");
    }
}
