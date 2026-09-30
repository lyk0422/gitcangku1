package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.dto.SiteBlockConfigRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 中心区组名额的纯计算规则，不依赖数据库，便于单元测试覆盖边界：
 * 配置区组不得重复；剩余额度 = 当前名额 - 已分配量（不小于 0）；
 * 缩减后的名额不得低于启用冻结量与已分配量中的较大者。
 */
public final class SiteQuotaPolicy {

    private SiteQuotaPolicy() {
    }

    /**
     * 校验一次配置版本提交的区组清单：至少 1 个、区组编号不重复。
     * 字段非空与取值范围由 Bean Validation 负责，这里只校验跨字段规则。
     */
    public static void validateConfigBlocks(List<SiteBlockConfigRequest> blocks) {
        Set<String> seen = new HashSet<>();
        for (SiteBlockConfigRequest block : blocks) {
            String code = block.blockCode();
            if (!seen.add(code)) {
                throw ApiException.badRequest("同一配置版本中区组编号不得重复: " + code);
            }
        }
    }

    /** 剩余可分配额度；已分配量不可能超过名额（由写入门禁保证），防御性截断到 0。 */
    public static int remaining(int quota, int allocated) {
        return Math.max(0, quota - allocated);
    }

    /** 是否还可以在该区组继续分配。 */
    public static boolean canAllocate(int quota, int allocated) {
        return allocated < quota;
    }

    /**
     * 缩减后的名额是否合法：不得低于启用冻结量，也不得低于已分配量。
     */
    public static boolean canReduceTo(int newQuota, int frozenQuota, int allocated) {
        return newQuota >= frozenQuota && newQuota >= allocated;
    }
}
