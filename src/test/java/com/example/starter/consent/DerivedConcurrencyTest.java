package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.consent.dto.DeriveRequest;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.NumericWriteRequest;
import com.example.starter.consent.dto.SourceRef;

/**
 * 数值/派生写入并发边界测试：竞争不产生重复记录、不泄露唯一键异常，业务与去重原子提交。
 */
@SpringBootTest
class DerivedConcurrencyTest {

    @Autowired
    private ConsentService consentService;

    @Autowired
    private NumericRecordService numericRecordService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM derived_ancestor");
        jdbc.update("DELETE FROM derived_source");
        jdbc.update("DELETE FROM numeric_record");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    /**
     * 并发执行相同写入任务，返回每次调用的结果码（OK 或 ApiException 业务码）。
     */
    private List<String> runConcurrently(List<Callable<String>> tasks) throws Exception {
        int threads = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> coordinated = new ArrayList<>();
        for (Callable<String> task : tasks) {
            coordinated.add(() -> {
                ready.countDown();
                start.await();
                return task.call();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : coordinated) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    @Test
    void concurrentNumericWritesSameKeyProduceSingleRecord() throws Exception {
        consentService.grant(new GrantRequest("g-1", "subj-a", Purpose.RESEARCH));
        int threads = 8;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "w-concurrent-" + i;
            tasks.add(() -> {
                try {
                    numericRecordService.writeNumeric(
                            new NumericWriteRequest(requestId, "op-1", "subj-a", "n1", 7L, 1));
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<String> results = runConcurrently(tasks);
        // 恰好一个成功，其余为目标键冲突 409，无唯一键异常泄露
        assertThat(results.stream().filter("OK"::equals)).hasSize(1);
        assertThat(results.stream().filter("RECORD_KEY_CONFLICT"::equals)).hasSize(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM numeric_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'NUMERIC_WRITE'"))
                .isEqualTo(1);
    }

    @Test
    void concurrentDerivesSameTargetKeyProduceSingleRecord() throws Exception {
        consentService.grant(new GrantRequest("g-a", "subj-a", Purpose.RESEARCH));
        consentService.grant(new GrantRequest("g-b", "subj-b", Purpose.RESEARCH));
        consentService.grant(new GrantRequest("g-c", "subj-c", Purpose.RESEARCH));
        numericRecordService.writeNumeric(new NumericWriteRequest("w-a", "op-1", "subj-a", "n1", 2L, 1));
        numericRecordService.writeNumeric(new NumericWriteRequest("w-b", "op-1", "subj-b", "n1", 3L, 1));
        int threads = 8;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "d-concurrent-" + i;
            tasks.add(() -> {
                try {
                    numericRecordService.derive(new DeriveRequest(requestId, "op-1", "subj-c", "sum", 1,
                            List.of(new SourceRef("subj-a", 1, "n1"), new SourceRef("subj-b", 1, "n1"))));
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<String> results = runConcurrently(tasks);
        assertThat(results.stream().filter("OK"::equals)).hasSize(1);
        assertThat(results.stream().filter("RECORD_KEY_CONFLICT"::equals)).hasSize(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM numeric_record WHERE record_key = 'sum'")).isEqualTo(1);
        // 溯源与记录原子提交：直接来源 2 条、祖先闭包 2 条，无半成品
        assertThat(count("SELECT COUNT(*) FROM derived_source WHERE target_record_key = 'sum'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM derived_ancestor WHERE target_record_key = 'sum'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'DERIVE'")).isEqualTo(1);
    }
}
