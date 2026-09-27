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
 * 附件脱敏并发与幂等边界测试：真实并发提交，验证同一附件同一规则版本只生成一个版本、
 * 行锁串行化、同键并发重放只占一键、脱敏与合并按提交顺序裁决。
 */
@SpringBootTest
class AttachmentConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_version_attachment");
        jdbcTemplate.update("DELETE FROM attachment_redaction");
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

    private void createObservation(String observationId, String requestId) {
        observationService.create(new CreateObservationRequest(
                requestId, observationId, "站点A", "1.0", "初始备注"));
    }

    private void registerAttachment(String observationId, String attachmentId, String requestId) {
        attachmentService.register(observationId,
                new RegisterAttachmentRequest(requestId, attachmentId, "原始摘要"));
    }

    private void registerRule(String ruleVersion, String requestId) {
        attachmentService.registerRule(new RegisterRuleRequest(requestId, ruleVersion, "规则描述"));
    }

    private RedactRequest redactRequest(String requestId, String ruleVersion,
                                        String attachmentId, String redactedSummary) {
        return new RedactRequest(requestId, ruleVersion, "替换说明", "REDACTOR",
                List.of(new RedactRequest.RedactItem(attachmentId, redactedSummary)));
    }

    private int redactionCount(String attachmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = ?",
                Integer.class, attachmentId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentRedactSameAttachmentSameRuleYieldsSingleVersion() throws Exception {
        createObservation("obs-c1", "req-c0");
        registerAttachment("obs-c1", "att-1", "req-c1");
        registerRule("rv-1", "req-c2");

        // 4 个并发请求对同一附件使用同一规则版本：只生成一个脱敏版本
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    attachmentService.redact("obs-c1",
                            redactRequest("req-cr" + index, "rv-1", "att-1", "脱敏摘要"));
                    return "201";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("201"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(3);
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    @Test
    void concurrentRedactSameAttachmentDifferentRulesBothApply() throws Exception {
        createObservation("obs-c2", "req-c3");
        registerAttachment("obs-c2", "att-1", "req-c4");
        registerRule("rv-1", "req-c5");
        registerRule("rv-2", "req-c6");

        // 两个并发请求使用不同规则版本：行锁串行化后都成功，版本为 1 和 2
        List<Callable<AttachmentService.ApiOutcome<RedactResponse>>> tasks = List.of(
                () -> attachmentService.redact("obs-c2",
                        redactRequest("req-c7", "rv-1", "att-1", "脱敏一")),
                () -> attachmentService.redact("obs-c2",
                        redactRequest("req-c8", "rv-2", "att-1", "脱敏二")));
        List<AttachmentService.ApiOutcome<RedactResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes)
                .map(outcome -> outcome.body().items().get(0).redactionVersion())
                .containsExactlyInAnyOrder(1, 2);
        assertThat(redactionCount("att-1")).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleResult() throws Exception {
        createObservation("obs-c3", "req-c9");
        registerAttachment("obs-c3", "att-1", "req-c10");
        registerRule("rv-1", "req-c11");

        // 同一 requestId、相同参数并发提交 4 次：全部返回同一结果，只产生一个版本
        List<Callable<AttachmentService.ApiOutcome<RedactResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> attachmentService.redact("obs-c3",
                    redactRequest("req-c12", "rv-1", "att-1", "脱敏摘要")));
        }
        List<AttachmentService.ApiOutcome<RedactResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes)
                .allMatch(outcome -> outcome.body().items().get(0).redactionVersion() == 1);
        assertThat(redactionCount("att-1")).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-c12'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentRedactAndMergeSerializeOnObservation() throws Exception {
        createObservation("obs-c4", "req-c13");
        registerAttachment("obs-c4", "att-1", "req-c14");
        registerRule("rv-1", "req-c15");

        // 脱敏与合并并发：按提交顺序裁决，两者都成功，固化引用与最终状态一致
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        attachmentService.redact("obs-c4",
                                redactRequest("req-c16", "rv-1", "att-1", "脱敏摘要"));
                        return "redact-201";
                    } catch (ApiException e) {
                        return "redact-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-c4",
                                new MergeObservationRequest("req-c17", 1, "站点A", "1.0", "合并备注"));
                        return "merge-200";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("redact-201", "merge-200");
        assertThat(redactionCount("att-1")).isEqualTo(1);
        // 观测版本 2 的固化引用：合并先于脱敏则引用原始摘要（null），否则引用版本 1
        List<FrozenReferenceView> references = attachmentService
                .getFrozenReferences("obs-c4", 2, "EVIDENCE");
        assertThat(references).hasSize(1);
        assertThat(references.get(0).redactionVersion() == null
                || references.get(0).redactionVersion() == 1).isTrue();
        // 无论顺序如何，公开视图都展示最新有效脱敏版本
        AttachmentPublicView view = attachmentService.getPublicView("obs-c4", "att-1");
        assertThat(view.source()).isEqualTo("REDACTION");
        assertThat(view.redactionVersion()).isEqualTo(1);
    }

    @Test
    void concurrentRevokeSameVersionYieldsSingleSuccess() throws Exception {
        createObservation("obs-c5", "req-c18");
        registerAttachment("obs-c5", "att-1", "req-c19");
        registerRule("rv-1", "req-c20");
        attachmentService.redact("obs-c5", redactRequest("req-c21", "rv-1", "att-1", "脱敏摘要"));

        // 两个并发撤销同一版本：一个成功，一个 409，版本只被撤销一次
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        attachmentService.revoke("obs-c5", "att-1", 1,
                                new RevokeRedactionRequest("req-c22", "REDACTOR", "原因一"));
                        return "200";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        attachmentService.revoke("obs-c5", "att-1", 1,
                                new RevokeRedactionRequest("req-c23", "REDACTOR", "原因二"));
                        return "200";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("200", "409");
        // 版本保留且只记录一次撤销
        assertThat(redactionCount("att-1")).isEqualTo(1);
        RedactionVersionView view = attachmentService.getVersionView("obs-c5", "att-1", 1, "EVIDENCE");
        assertThat(view.status()).isEqualTo("REVOKED");
        assertThat(view.revokedAtUtc()).isNotNull();
    }
}
