package com.example.starter.blind;

import com.example.starter.blind.dto.SubmitSiteConfigRequest.BlockQuotaRequest;
import com.example.starter.blind.service.BlockQuotaRules;
import com.example.starter.blind.service.BlockQuotaRules.FrozenBlock;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 区组名额纯规则单元测试：配置连续性校验与按剩余额度选块边界。
 */
class BlockQuotaRulesTest {

    private BlockQuotaRequest q(int blockNo, int quota) {
        return new BlockQuotaRequest(blockNo, quota);
    }

    @Test
    void validateContiguous_acceptsSingleAndSequentialBlocks() {
        BlockQuotaRules.validateContiguous(List.of(q(1, 10)));
        BlockQuotaRules.validateContiguous(Arrays.asList(q(1, 0), q(2, 3), q(3, 100)));
    }

    @Test
    void validateContiguous_rejectsGap() {
        ApiException ex = assertThrows(ApiException.class, () ->
                BlockQuotaRules.validateContiguous(Arrays.asList(q(1, 1), q(3, 1))));
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    void validateContiguous_rejectsDuplicateAndNotStartingAtOne() {
        assertEquals(400, assertThrows(ApiException.class, () ->
                BlockQuotaRules.validateContiguous(Arrays.asList(q(1, 1), q(1, 1)))).getStatus().value());
        assertEquals(400, assertThrows(ApiException.class, () ->
                BlockQuotaRules.validateContiguous(List.of(q(2, 1)))).getStatus().value());
    }

    @Test
    void selectFirstAvailable_picksFirstBlockWithRemainingQuota() {
        List<FrozenBlock> frozen = List.of(
                new FrozenBlock(1, 2), new FrozenBlock(2, 10), new FrozenBlock(3, 1));
        Map<Integer, Integer> used = new HashMap<>(Map.of(1, 2, 2, 10, 3, 0));
        // 区组1、2已满，选中第一个有余量的区组3
        assertEquals(3, BlockQuotaRules.selectFirstAvailable(frozen, b -> used.getOrDefault(b, 0)));

        // 区组2释放一个逻辑余量（此处直接改用量模拟），应选中区组2而非区组3
        used.put(2, 9);
        assertEquals(2, BlockQuotaRules.selectFirstAvailable(frozen, b -> used.getOrDefault(b, 0)));
    }

    @Test
    void selectFirstAvailable_allFull_returnsMinusOne() {
        List<FrozenBlock> frozen = List.of(
                new FrozenBlock(1, 1), new FrozenBlock(2, 0), new FrozenBlock(3, 2));
        Map<Integer, Integer> used = Map.of(1, 1, 3, 2);
        assertEquals(-1, BlockQuotaRules.selectFirstAvailable(frozen, b -> used.getOrDefault(b, 0)));
    }

    @Test
    void selectFirstAvailable_zeroQuotaBlock_isSkipped() {
        List<FrozenBlock> frozen = List.of(new FrozenBlock(1, 0), new FrozenBlock(2, 5));
        assertEquals(2, BlockQuotaRules.selectFirstAvailable(frozen, b -> 0));
    }

    @Test
    void selectFirstAvailable_neverExceedsFrozenQuota() {
        // 用量恰好等于冻结量时不得再选中；少一个则可选中
        List<FrozenBlock> frozen = List.of(new FrozenBlock(1, 10));
        assertEquals(-1, BlockQuotaRules.selectFirstAvailable(frozen, b -> 10));
        assertEquals(1, BlockQuotaRules.selectFirstAvailable(frozen, b -> 9));
    }
}
