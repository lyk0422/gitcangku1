package com.example.starter.blind;

import com.example.starter.blind.dto.SiteBlockConfigRequest;
import com.example.starter.blind.service.SiteQuotaPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名额规则纯单元测试：区组去重、剩余额度边界、缩减下限（冻结量与已分配量取大）。
 */
class SiteQuotaPolicyTest {

    private SiteBlockConfigRequest block(String code, int quota) {
        return new SiteBlockConfigRequest(code, quota);
    }

    @Test
    void validateConfigBlocks_duplicateCode_throwsBadRequest() {
        ApiException ex = assertThrows(ApiException.class, () ->
                SiteQuotaPolicy.validateConfigBlocks(List.of(block("A", 10), block("A", 5))));
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    void validateConfigBlocks_distinctCodes_passes() {
        SiteQuotaPolicy.validateConfigBlocks(List.of(block("A", 10), block("B", 5)));
    }

    @Test
    void remaining_isQuotaMinusAllocated_andNeverNegative() {
        assertEquals(7, SiteQuotaPolicy.remaining(10, 3));
        assertEquals(0, SiteQuotaPolicy.remaining(10, 10));
        // 防御：已分配量异常超过名额时截断到 0，不返回负数
        assertEquals(0, SiteQuotaPolicy.remaining(10, 11));
    }

    @Test
    void canAllocate_strictlyBelowQuota() {
        assertTrue(SiteQuotaPolicy.canAllocate(10, 9));
        assertFalse(SiteQuotaPolicy.canAllocate(10, 10));
    }

    @Test
    void canReduceTo_mustBeAtLeastBothFrozenAndAllocated() {
        // 低于冻结量：即使尚无分配也拒绝
        assertFalse(SiteQuotaPolicy.canReduceTo(5, 10, 0));
        // 恰好等于冻结量：允许
        assertTrue(SiteQuotaPolicy.canReduceTo(10, 10, 0));
        // 高于冻结量：允许（上调名额不违反冻结下限）
        assertTrue(SiteQuotaPolicy.canReduceTo(20, 10, 15));
        // 高于冻结量但低于已分配量：拒绝
        assertFalse(SiteQuotaPolicy.canReduceTo(14, 10, 15));
        // 同时满足两者：允许
        assertTrue(SiteQuotaPolicy.canReduceTo(15, 10, 15));
    }
}
