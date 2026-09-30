package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.dto.SubmitSiteConfigRequest.BlockQuotaRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 区组名额纯规则：配置连续性校验与按区组顺序选择第一个仍有剩余的区组。
 * 不依赖数据库与 Spring，便于单元测试直接覆盖边界。
 */
public final class BlockQuotaRules {

    private BlockQuotaRules() {
    }

    /** 已冻结区组的运行时视图。 */
    public record FrozenBlock(int blockNo, int frozenQuota) {
    }

    /**
     * 校验区组号从 1 开始连续、不重复；名额非负由 Bean Validation 保证。
     */
    public static void validateContiguous(List<BlockQuotaRequest> blocks) {
        Set<Integer> seen = new HashSet<>();
        int expected = 1;
        for (BlockQuotaRequest block : blocks) {
            if (block.blockNo() == null || block.quota() == null) {
                throw ApiException.badRequest("区组号与名额不能为空");
            }
            if (block.blockNo() != expected) {
                throw ApiException.badRequest(
                        "区组号必须从 1 开始连续且不重复，期望区组 " + expected
                                + "，实际收到 " + block.blockNo());
            }
            if (!seen.add(block.blockNo())) {
                throw ApiException.badRequest("区组号不能重复: " + block.blockNo());
            }
            expected++;
        }
    }

    /**
     * 按 blockNo 升序选择第一个 已分配量 &lt; 冻结量 的区组。
     *
     * @param frozenBlocks 已按 blockNo 升序排列的冻结区组
     * @param usedOf       按 blockNo 查询已分配量的函数
     * @return 中选区组号；全部区组已满返回 -1
     */
    public static int selectFirstAvailable(List<FrozenBlock> frozenBlocks,
                                           java.util.function.ToIntFunction<Integer> usedOf) {
        for (FrozenBlock block : frozenBlocks) {
            if (usedOf.applyAsInt(block.blockNo()) < block.frozenQuota()) {
                return block.blockNo();
            }
        }
        return -1;
    }
}
