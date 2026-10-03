package com.example.starter.timber;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 拆分入参的纯函数校验：重量守恒与子批次编号唯一，不依赖数据库，便于单元测试。
 */
public final class SplitValidator {

    private SplitValidator() {
    }

    /**
     * 校验子批次重量均为正且总和恰好等于父批次当前可拆重量。
     *
     * @return 校验失败原因，通过时为空
     */
    public static Optional<String> checkWeightConservation(int parentWeightKg, List<SplitChildInput> children) {
        if (children == null || children.isEmpty()) {
            return Optional.of("子批次列表不能为空");
        }
        long sum = 0;
        for (SplitChildInput child : children) {
            if (child.weightKg() <= 0) {
                return Optional.of("子批次重量必须为正整数: " + child.batchNo());
            }
            sum += child.weightKg();
        }
        if (sum != parentWeightKg) {
            return Optional.of("子批次重量总和" + sum + "必须等于父批次可拆重量" + parentWeightKg);
        }
        return Optional.empty();
    }

    /**
     * 校验同一请求内子批次编号不重复。
     *
     * @return 校验失败原因，通过时为空
     */
    public static Optional<String> checkDistinctBatchNos(List<SplitChildInput> children) {
        Set<String> seen = new HashSet<>();
        for (SplitChildInput child : children) {
            if (!seen.add(child.batchNo())) {
                return Optional.of("子批次编号重复: " + child.batchNo());
            }
        }
        return Optional.empty();
    }
}