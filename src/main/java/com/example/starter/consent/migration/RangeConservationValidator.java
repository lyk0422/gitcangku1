package com.example.starter.consent.migration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.consent.ApiException;

/**
 * 用途拆分的静态规则校验（无状态、无数据库依赖）。
 *
 * <p>规则：
 * <ul>
 *   <li>新用途数量 2～10 个，代码在本次迁移内唯一且不得与目录中已有代码重复；</li>
 *   <li>每个新范围非空且为旧用途范围的子集，因此新范围并集不会扩大旧范围（允许存在空隙，空隙属性映射为 UNMAPPED）；</li>
 *   <li>新范围两两不相交，保证任一记录属性至多命中一个目标，否则整单 422；</li>
 *   <li>合并历史替代边后替代关系不得形成环。</li>
 * </ul>
 */
public final class RangeConservationValidator {

    static final String CODE_MIGRATION_INVALID = "MIGRATION_INVALID";

    private RangeConservationValidator() {
    }

    /**
     * 校验拆分规格。
     *
     * @param source             旧用途范围
     * @param targets            新用途规格（2～10 个）
     * @param existingPurposes   catalogVersion 中已存在的全部用途代码
     * @param existingSupersedes 历史累积替代边：替代者代码 -&gt; 被其替代的代码（每个替代者至多一条）
     */
    public static void validate(LongRange source,
                                List<PurposeTarget> targets,
                                Set<String> existingPurposes,
                                Map<String, String> existingSupersedes) {
        if (targets == null || targets.size() < 2 || targets.size() > 10) {
            throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "一次拆分必须包含 2～10 个新用途");
        }
        Set<String> seen = new HashSet<>();
        for (PurposeTarget target : targets) {
            if (target == null || target.purpose() == null || target.purpose().isBlank()) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "新用途代码不能为空");
            }
            String code = target.purpose();
            if (!seen.add(code)) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "新用途代码重复: " + code);
            }
            if (existingPurposes.contains(code)) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "用途代码已存在于当前目录: " + code);
            }
            LongRange range = target.range();
            if (range == null || range.isEmpty()) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "用途范围必须非空: " + code);
            }
            if (!range.isSubsetOf(source)) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID,
                        "用途范围越出旧用途范围: " + code);
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            for (int j = i + 1; j < targets.size(); j++) {
                if (targets.get(i).range().overlaps(targets.get(j).range())) {
                    throw ApiException.unprocessable(CODE_MIGRATION_INVALID,
                            "新用途范围存在重叠，会导致记录出现多个目标: "
                                    + targets.get(i).purpose() + " / " + targets.get(j).purpose());
                }
            }
        }
        assertNoCycle(targets, existingSupersedes);
    }

    /**
     * 返回属性命中的唯一目标代码；未命中任何新范围时返回 {@code null}（即 UNMAPPED）。
     */
    public static String uniqueTarget(List<PurposeTarget> targets, long attribute) {
        String hit = null;
        for (PurposeTarget target : targets) {
            if (target.range().contains(attribute)) {
                if (hit != null) {
                    throw ApiException.unprocessable(CODE_MIGRATION_INVALID,
                            "记录属性命中多个目标用途: " + hit + " / " + target.purpose());
                }
                hit = target.purpose();
            }
        }
        return hit;
    }

    private static void assertNoCycle(List<PurposeTarget> targets, Map<String, String> existingSupersedes) {
        for (PurposeTarget start : targets) {
            // 沿“替代者 -> 被替代者”方向从本新用途出发探测，回到任一已访问节点即为环
            Deque<String> stack = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            if (start.supersedes() == null || start.supersedes().isBlank()) {
                continue;
            }
            visited.add(start.purpose());
            stack.push(start.supersedes());
            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (!visited.add(current)) {
                    throw ApiException.unprocessable(CODE_MIGRATION_INVALID,
                            "替代关系形成环: " + start.purpose());
                }
                String next = null;
                for (PurposeTarget other : targets) {
                    if (other.purpose().equals(current)) {
                        next = other.supersedes();
                        break;
                    }
                }
                if (next == null) {
                    next = existingSupersedes.get(current);
                }
                if (next != null && !next.isBlank()) {
                    stack.push(next);
                }
            }
        }
    }
}
