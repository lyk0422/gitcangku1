package com.example.starter.baggage;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等结构化比较测试：以全局 requestId 比较操作、资源、全参数及期望版本；
 * 十进制按值、文本原样；同参重放首次完整结果、改参 409、失败不占键。
 * 基于 H2 MySQL 兼容模式真实 request_log 表。
 */
@SpringBootTest
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void structuredComparison_decimalByValueTextAsIsAndOperationIncluded() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resource", "LEG1");
        payload.put("expectedVersion", 2);
        payload.put("amount", new BigDecimal("1.0"));
        payload.put("note", "ABC");

        String first = idempotencyService.execute(key, "OP_A", 200, payload, String.class, () -> "FIRST");
        assertThat(first).isEqualTo("FIRST");

        // 十进制按值：1.00 与 1.0 同值，同参重放首次完整结果
        Map<String, Object> sameValue = new LinkedHashMap<>(payload);
        sameValue.put("amount", new BigDecimal("1.00"));
        assertThat(idempotencyService.execute(key, "OP_A", 200, sameValue, String.class, () -> "SECOND"))
                .isEqualTo("FIRST");

        // 文本原样：大小写不同视为异参 -> 409
        Map<String, Object> differentText = new LinkedHashMap<>(payload);
        differentText.put("note", "abc");
        assertConflict(key, "OP_A", differentText);

        // 操作类型不同，即使全参数相同也视为异参 -> 409
        assertConflict(key, "OP_B", payload);

        // 期望版本不同 -> 409
        Map<String, Object> differentVersion = new LinkedHashMap<>(payload);
        differentVersion.put("expectedVersion", 3);
        assertConflict(key, "OP_A", differentVersion);

        // 资源不同 -> 409
        Map<String, Object> differentResource = new LinkedHashMap<>(payload);
        differentResource.put("resource", "LEG2");
        assertConflict(key, "OP_A", differentResource);

        // 十进制值真正不同 -> 409
        Map<String, Object> differentAmount = new LinkedHashMap<>(payload);
        differentAmount.put("amount", new BigDecimal("1.5"));
        assertConflict(key, "OP_A", differentAmount);

        // 仅首次成功结果落键一次
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, key);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void failureDoesNotOccupyKeyAndSuccessReplays() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("resource", "LEG1");

        // 业务失败不占键
        assertThatThrownBy(() -> idempotencyService.execute(key, "OP_A", 200, payload,
                String.class, () -> {
                    throw ApiException.unprocessable("业务失败");
                }))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(422));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, key);
        assertThat(logs).isZero();

        // 同键随后成功，并可重放
        assertThat(idempotencyService.execute(key, "OP_A", 200, payload, String.class, () -> "OK"))
                .isEqualTo("OK");
        assertThat(idempotencyService.execute(key, "OP_A", 200, payload, String.class, () -> "OTHER"))
                .isEqualTo("OK");
    }

    private void assertConflict(String key, String operation, Map<String, Object> payload) {
        assertThatThrownBy(() -> idempotencyService.execute(key, operation, 200, payload,
                String.class, () -> "X"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
    }
}
