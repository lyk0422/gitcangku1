package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ReceiptBatchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等指纹规范化纯单元测试：覆盖操作者、操作类型、规范化对象集合与全部结果相关字段；
 * 指纹为 JSON 编码（键值经转义），不依赖未转义分隔符拼接。
 */
class FingerprintNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ReceiptBatchRequest batch(String requestId, String operator,
                                      ReceiptBatchRequest.ReceiptItem... items) {
        return new ReceiptBatchRequest(requestId, operator, List.of(items));
    }

    private ReceiptBatchRequest.ReceiptItem item(String reservation, String campaign,
                                                 String visitor, long receiptAt) {
        return new ReceiptBatchRequest.ReceiptItem(reservation, campaign, visitor, receiptAt);
    }

    @Test
    @DisplayName("集合顺序不影响指纹：乱序提交产生同一规范化指纹")
    void fingerprint_isOrderIndependent_forNormalizedSet() {
        ReceiptBatchRequest a = batch("req-1", "op-1",
                item("r1", "c1", "v1", 100L),
                item("r2", "c1", "v2", 200L));
        ReceiptBatchRequest b = batch("req-1", "op-1",
                item("r2", "c1", "v2", 200L),
                item("r1", "c1", "v1", 100L));

        assertEquals(FingerprintNormalizer.receiptBatch(mapper, a),
                FingerprintNormalizer.receiptBatch(mapper, b));
    }

    @Test
    @DisplayName("指纹覆盖操作者：操作者不同则指纹不同")
    void fingerprint_coversOperator() {
        ReceiptBatchRequest a = batch("req-1", "op-1", item("r1", "c1", "v1", 100L));
        ReceiptBatchRequest b = batch("req-1", "op-2", item("r1", "c1", "v1", 100L));
        assertNotEquals(FingerprintNormalizer.receiptBatch(mapper, a),
                FingerprintNormalizer.receiptBatch(mapper, b));
    }

    @Test
    @DisplayName("指纹覆盖全部结果相关字段：归属公告、访客、回执时刻任一变化即不同")
    void fingerprint_coversAllResultFields() {
        ReceiptBatchRequest base = batch("req-1", "op-1", item("r1", "c1", "v1", 100L));
        assertNotEquals(FingerprintNormalizer.receiptBatch(mapper, base),
                FingerprintNormalizer.receiptBatch(mapper,
                        batch("req-1", "op-1", item("r1", "c2", "v1", 100L))));
        assertNotEquals(FingerprintNormalizer.receiptBatch(mapper, base),
                FingerprintNormalizer.receiptBatch(mapper,
                        batch("req-1", "op-1", item("r1", "c1", "v2", 100L))));
        assertNotEquals(FingerprintNormalizer.receiptBatch(mapper, base),
                FingerprintNormalizer.receiptBatch(mapper,
                        batch("req-1", "op-1", item("r1", "c1", "v1", 101L))));
    }

    @Test
    @DisplayName("指纹不包含 requestId：同参不同幂等键规范化后业务参数指纹相同")
    void fingerprint_excludesRequestId() {
        ReceiptBatchRequest a = batch("key-a", "op-1", item("r1", "c1", "v1", 100L));
        ReceiptBatchRequest b = batch("key-b", "op-1", item("r1", "c1", "v1", 100L));
        assertEquals(FingerprintNormalizer.receiptBatch(mapper, a),
                FingerprintNormalizer.receiptBatch(mapper, b));
    }

    @Test
    @DisplayName("禁止未转义分隔符拼接：字段中出现分隔/引号字符时由 JSON 转义，且不会伪造相邻字段")
    void fingerprint_isJsonEscaped_noDelimiterInjection() {
        // 构造两个语义不同、若用裸 '|' 拼接会碰撞的请求；JSON 编码必须区分它们
        ReceiptBatchRequest injection = batch("req-1", "op-1",
                item("r1\",receiptAtUtc:999", "c1", "v1", 100L));
        ReceiptBatchRequest other = batch("req-1", "op-1",
                item("r1", "c1", "v1", 999L));
        String fpInjection = FingerprintNormalizer.receiptBatch(mapper, injection);
        String fpOther = FingerprintNormalizer.receiptBatch(mapper, other);
        assertNotEquals(fpInjection, fpOther);
        // 引号被 JSON 转义为反斜杠+引号，裸分隔结构不会出现在指纹中
        assertFalse(fpInjection.contains("\"receiptAtUtc\":999"),
                "注入字符串必须被转义，不能伪造同层字段");
        assertTrue(fpInjection.contains("\\\""), "JSON 应对引号进行反斜杠转义");
    }

    @Test
    @DisplayName("操作类型进入指纹：不同操作即便参数相似也不相同")
    void fingerprint_coversOperation() {
        String confirm = FingerprintNormalizer.reservationAction(mapper, "CONFIRM", "r1");
        String cancel = FingerprintNormalizer.reservationAction(mapper, "CANCEL", "r1");
        assertNotEquals(confirm, cancel);
        assertTrue(confirm.contains("CONFIRM"));
    }
}
