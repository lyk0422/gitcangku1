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
 * 脱敏并发与幂等边界测试：真实并发提交，验证同一附件同一规则只生成一个版本、
 * 同 requestId 并发重放只占一键、脱敏与删除按提交顺序裁决且不复活墓碑。
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
        jdbcTemplate.update("DELETE FROM observation_version_attachment");
        jdbcTemplate.update("DELETE FROM redaction_version");
        jdbcTemplate.update("DELETE FROM redaction_rule");
        jdbcTemplate.update("DELETE FROM observation_attachment");
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

    private void setup(String obs, String attachment) {
        observationService.create(new CreateObservationRequest("req-c-" + obs, obs, "站点A", "1.0", "备注"));
        redactionService.registerAttachment(obs, new RegisterAttachmentRequest(
                "req-a-" + attachment, "REDACTOR", attachment, "a.txt", "text/plain", "秘密数据"));
        redactionService.registerRule(new RegisterRuleRequest(
                "req-r-rule-v1", "REDACTOR", "rule-v1", "d", List.of("秘密"), "***"));
    }

    private RedactAttachmentsRequest redactReq(String requestId, String attachment) {
        return new RedactAttachmentsRequest(requestId, "REDACTOR", "op-1", "rule-v1",
                List.of(new RedactAttachmentsRequest.Item(attachment, "隐藏")));
    }

    @Test
    void concurrentRedactSameAttachmentSameRuleYieldsSingleVersion() throws Exception {
        setup("obs-c1", "att-c1");

        // 两个不同 requestId 对同一附件同一规则并发脱敏：串行化后一个成功一个 409，只生成一个版本
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        redactionService.redact("obs-c1", redactReq("req-cc1", "att-c1"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        redactionService.redact("obs-c1", redactReq("req-cc2", "att-c1"));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redaction_version WHERE attachment_id = 'att-c1'", Integer.class);
        assertThat(versions).isEqualTo(1);
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM redaction_version WHERE attachment_id = 'att-c1'", Integer.class);
        assertThat(maxVersion).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdRedactReplaysSingleResult() throws Exception {
        setup("obs-c2", "att-c2");

        // 同一 requestId 相同参数并发 4 次：全部返回同一结果，只生成一个版本
        List<Callable<RedactionService.OpOutcome<RedactOutcomeResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> redactionService.redact("obs-c2", redactReq("req-ci1", "att-c2")));
        }
        List<RedactionService.OpOutcome<RedactOutcomeResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.status() == 201);
        assertThat(outcomes).allMatch(o -> o.body().versions().get(0).version() == 1);
        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redaction_version WHERE attachment_id = 'att-c2'", Integer.class);
        assertThat(versions).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ci1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentRedactAndDeleteAdjudicatedByCommitOrder() throws Exception {
        setup("obs-c3", "att-c3");

        // 脱敏与删除并发：按提交顺序裁决，串行化后恰好一个先生效
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        redactionService.redact("obs-c3", redactReq("req-cd1", "att-c3"));
                        return "redact-ok";
                    } catch (ApiException e) {
                        return "redact-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("obs-c3", new DeleteObservationRequest("req-cd2", 1));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        ObservationResponse current = observationService.getCurrent("obs-c3");
        if (results.contains("redact-ok")) {
            // 脱敏先生效：删除随后成功（版本仍匹配），两者都成功
            assertThat(results).containsExactlyInAnyOrder("redact-ok", "delete-ok");
            Integer versions = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM redaction_version WHERE attachment_id = 'att-c3'", Integer.class);
            assertThat(versions).isEqualTo(1);
        } else {
            // 删除先生效：墓碑不被脱敏复活，脱敏返回 404
            assertThat(results).containsExactlyInAnyOrder("delete-ok", "redact-404");
        }
        // 无论顺序，墓碑不被复活
        assertThat(current.deleted()).isTrue();
    }
}
