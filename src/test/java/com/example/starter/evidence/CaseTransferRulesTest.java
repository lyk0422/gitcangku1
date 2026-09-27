package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨案移交纯领域规则单元测试：证物列表规范化排序、令版本生效区间左闭右开、
 * requestId 指纹对规范化负载稳定且对异参敏感。
 */
class CaseTransferRulesTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 27, 8, 0, 0);

    private TransferOrder order(OrderStatus status, LocalDateTime validFrom, LocalDateTime validTo) {
        return new TransferOrder(1L, "O-1", 1, "CASE-A", "CASE-B", status, validFrom, validTo, T);
    }

    @Test
    void normalizeKeysSortsAndKeepsAllKeys() {
        List<String> normalized = CaseTransferService.normalizeKeys(List.of("ev-3", "ev-1", "ev-2"));
        assertThat(normalized).containsExactly("ev-1", "ev-2", "ev-3");
    }

    @Test
    void normalizeKeysRejectsDuplicatesWith400() {
        assertThatThrownBy(() -> CaseTransferService.normalizeKeys(List.of("ev-1", "ev-2", "ev-1")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void orderWindowIsLeftClosed() {
        // now == validFrom：生效（含边界）
        TransferOrder o = order(OrderStatus.VALID, T, T.plusHours(1));
        assertThat(CaseTransferService.isOrderEffective(o, T)).isTrue();
    }

    @Test
    void orderWindowIsRightOpen() {
        // now == validTo：失效（不含边界）
        TransferOrder o = order(OrderStatus.VALID, T.minusHours(1), T);
        assertThat(CaseTransferService.isOrderEffective(o, T)).isFalse();
        // 早 1 秒仍生效
        assertThat(CaseTransferService.isOrderEffective(o, T.minusSeconds(1))).isTrue();
    }

    @Test
    void orderNullValidToMeansOpenEnded() {
        TransferOrder o = order(OrderStatus.VALID, T.minusDays(1), null);
        assertThat(CaseTransferService.isOrderEffective(o, T.plusDays(30))).isTrue();
    }

    @Test
    void revokedOrderIsNeverEffectiveInsideWindow() {
        TransferOrder o = order(OrderStatus.REVOKED, T.minusHours(1), T.plusHours(1));
        assertThat(CaseTransferService.isOrderEffective(o, T)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fingerprintIsStableForCanonicalPayloadButDetectsParameterChange() {
        ObjectMapper mapper = new ObjectMapper();
        IdempotencyAdvisor advisor = new IdempotencyAdvisor(null, mapper);

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("orderNo", "O-1");
        canonical.put("orderVersion", 1);
        canonical.put("fromCaseKey", "CASE-A");
        canonical.put("toCaseKey", "CASE-B");
        canonical.put("fromCustodian", "alice");
        canonical.put("toCustodian", "bob");
        // 证物列表已按 evidenceKey 规范化排序（控制器在计算指纹前完成排序）
        canonical.put("evidenceKeys", List.of("ev-1", "ev-2", "ev-3"));

        String h1 = advisor.hashPayload(CaseTransferService.OP_CASE_TRANSFER, "alice", canonical);
        String h1Again = advisor.hashPayload(CaseTransferService.OP_CASE_TRANSFER, "alice",
                mapper.convertValue(canonical, Map.class));
        assertThat(h1).isEqualTo(h1Again);

        // 异参（令版本不同）必须得到不同指纹
        Map<String, Object> otherVersion = mapper.convertValue(canonical, Map.class);
        otherVersion.put("orderVersion", 2);
        assertThat(advisor.hashPayload(CaseTransferService.OP_CASE_TRANSFER, "alice", otherVersion))
                .isNotEqualTo(h1);

        // 异参（操作者不同）必须得到不同指纹
        assertThat(advisor.hashPayload(CaseTransferService.OP_CASE_TRANSFER, "mallory", canonical))
                .isNotEqualTo(h1);

        // 异参（双案不同）必须得到不同指纹
        Map<String, Object> otherCase = mapper.convertValue(canonical, Map.class);
        otherCase.put("toCaseKey", "CASE-C");
        assertThat(advisor.hashPayload(CaseTransferService.OP_CASE_TRANSFER, "alice", otherCase))
                .isNotEqualTo(h1);

        // 异参（证物集合不同）必须得到不同指纹
        Map<String, Object> otherItems = mapper.convertValue(canonical, Map.class);
        otherItems.put("evidenceKeys", List.of("ev-1", "ev-2"));
        assertThat(advisor.hashPayload(CaseTransferService.OP_CASE_TRANSFER, "alice", otherItems))
                .isNotEqualTo(h1);
    }
}
