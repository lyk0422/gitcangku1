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
 * 附件脱敏并发与状态边界测试：真实并发提交，验证同附件同规则版本只生成一个脱敏版本、
 * 不同规则版本按提交顺序生成递增版本、同 requestId 并发只占一键、脱敏与删除按序裁决不复活墓碑。
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
        jdbcTemplate.update("DELETE FROM snapshot_attachment_ref");
        jdbcTemplate.update("DELETE FROM attachment_redaction");
        jdbcTemplate.update("DELETE FROM redaction_batch");
        jdbcTemplate.update("DELETE FROM observation_attachment");
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

    private void setup(String observationId, String attachmentId) {
        observationService.create(new CreateObservationRequest(
                "req-setup-" + observationId, observationId, "站点A", "1.0", "备注"));
        redactionService.registerAttachment(observationId, new RegisterAttachmentRequest(
                attachmentId, "记录.txt", "text/plain", 1024L, "张三身份证110101199001011234"));
    }

    private void registerRule(String code, int version) {
        redactionService.registerRule(code, version, "规则" + code + version);
    }

    private CreateRedactionRequest redactionRequest(String requestId, String batchId, String attachmentId,
                                                    String code, int ruleVersion, String summary) {
        return new CreateRedactionRequest(requestId, batchId, "officer-a", "对外展示",
                List.of(new CreateRedactionRequest.RedactionItem(
                        attachmentId, code, ruleVersion, summary, "身份证替换为***")));
    }

    @Test
    void concurrentSameAttachmentSameRuleVersionYieldsSingleRedaction() throws Exception {
        setup("obs-1", "att-1");
        registerRule("PII", 1);

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    redactionService.createRedactions("obs-1", redactionRequest(
                            "req-same-" + index, "batch-same-" + index, "att-1", "PII", 1, "身份证***"));
                    return "200";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("200"::equals).hasSize(1);
        assertThat(results).filteredOn("422"::equals).hasSize(3);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-1'", Integer.class);
        assertThat(rows).isEqualTo(1);
        Integer versionNo = jdbcTemplate.queryForObject(
                "SELECT attachment_version FROM attachment_redaction WHERE attachment_id = 'att-1'", Integer.class);
        assertThat(versionNo).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRuleVersionsProduceIncrementingVersions() throws Exception {
        setup("obs-2", "att-2");
        registerRule("PII", 1);
        registerRule("PII", 2);
        registerRule("PII", 3);

        List<Callable<String>> tasks = new ArrayList<>();
        for (int v = 1; v <= 3; v++) {
            final int ruleVersion = v;
            tasks.add(() -> {
                try {
                    redactionService.createRedactions("obs-2", redactionRequest(
                            "req-diff-" + ruleVersion, "batch-diff-" + ruleVersion,
                            "att-2", "PII", ruleVersion, "脱敏v" + ruleVersion));
                    return "200";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).allMatch("200"::equals);
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT attachment_version FROM attachment_redaction WHERE attachment_id = 'att-2' "
                        + "ORDER BY attachment_version ASC", Integer.class);
        assertThat(versions).containsExactly(1, 2, 3);
        List<Integer> ruleVersions = jdbcTemplate.queryForList(
                "SELECT rule_version FROM attachment_redaction WHERE attachment_id = 'att-2' "
                        + "ORDER BY attachment_version ASC", Integer.class);
        assertThat(ruleVersions).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleBatchResult() throws Exception {
        setup("obs-3", "att-3");
        registerRule("PII", 1);

        List<Callable<RedactionService.RedactionBatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> redactionService.createRedactions("obs-3", redactionRequest(
                    "req-replay-1", "batch-replay-1", "att-3", "PII", 1, "身份证***")));
        }
        List<RedactionService.RedactionBatchOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().versions().get(0).attachmentVersion() == 1);
        assertThat(outcomes).allMatch(outcome ->
                outcome.body().versions().get(0).redactionId().equals(outcomes.get(0).body()
                        .versions().get(0).redactionId()));
        Integer redactionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-3'", Integer.class);
        assertThat(redactionRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-replay-1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentRedactionAndDeleteAreOrderedWithoutResurrectingTombstone() throws Exception {
        setup("obs-4", "att-4");
        registerRule("PII", 1);

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        redactionService.createRedactions("obs-4", redactionRequest(
                                "req-order-1", "batch-order-1", "att-4", "PII", 1, "身份证***"));
                        return "redact-200";
                    } catch (ApiException e) {
                        return "redact-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-4",
                                new DeleteObservationRequest("req-order-2", 1));
                        return "delete-200";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 删除先生效则脱敏遇墓碑 422；脱敏先生效则删除仍成功（脱敏不推进观测版本）。
        assertThat(results).contains("delete-200");
        ObservationResponse current = observationService.getCurrent("obs-4");
        assertThat(current.deleted()).isTrue();
        assertThat(current.version()).isEqualTo(2);
        // 墓碑不被并发操作复活
        assertThat(current.location()).isNull();
        Integer redactionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-4'", Integer.class);
        if (results.contains("redact-200")) {
            assertThat(redactionRows).isEqualTo(1);
        } else {
            assertThat(results).contains("redact-422");
            assertThat(redactionRows).isZero();
        }
        // 删除后再脱敏必须 422，且不产生任何版本
        try {
            redactionService.createRedactions("obs-4", redactionRequest(
                    "req-order-3", "batch-order-3", "att-4", "PII", 1, "身份证***"));
            throw new IllegalStateException("expected 422 on tombstoned observation");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(422);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-4'", Integer.class))
                .isEqualTo(redactionRows);
    }

    @Test
    void failedBatchRollsBackPlaceholderAndAllVersionRows() throws Exception {
        setup("obs-5", "att-5");
        registerRule("PII", 1);

        // 同批次内含重复附件：整批 422
        CreateRedactionRequest request = new CreateRedactionRequest(
                "req-rollback-1", "batch-rollback-1", "officer-a", "理由",
                List.of(
                        new CreateRedactionRequest.RedactionItem(
                                "att-5", "PII", 1, "脱敏1", "替换1"),
                        new CreateRedactionRequest.RedactionItem(
                                "att-5", "PII", 1, "脱敏2", "替换2")));
        try {
            redactionService.createRedactions("obs-5", request);
            throw new IllegalStateException("expected 422 for duplicate attachment in batch");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(422);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-5'", Integer.class))
                .isZero();
        // 失败不占键：同一 requestId 换合法批次后成功
        RedactionService.RedactionBatchOutcome outcome = redactionService.createRedactions("obs-5",
                redactionRequest("req-rollback-1", "batch-rollback-2", "att-5", "PII", 1, "身份证***"));
        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.body().versions().get(0).attachmentVersion()).isEqualTo(1);
    }
}
