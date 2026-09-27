package com.example.starter.batch;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 托盘隔离规则（无状态纯函数，便于单元测试）。
 *
 * <p>规则：同一托盘内类别不同的批次，只有在托盘配置了有效隔板（隔板数 ≥ 类别数 - 1）
 * 且各批次分配到不同分隔位时才能共存；同类别批次不要求隔板。
 * 装载校验始终针对“最终隔离关系”：托盘当前 ACTIVE 装载与本次请求合并后的完整布局。
 */
public final class SegregationRules {

    private SegregationRules() {
    }

    /**
     * 托盘中一件已放置批次：批次键、隔离类别、分隔位。
     */
    public record Placed(String batchKey, String category, int slotNo) {
    }

    /**
     * 请求内规范校验：批次键不得重复、分隔位不得重复。
     *
     * @return 违规原因（供 422 消息使用），全部通过返回 null
     */
    public static String findRequestDuplication(List<? extends Placed> requestEntries) {
        Set<String> batches = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (Placed entry : requestEntries) {
            if (!batches.add(entry.batchKey())) {
                return "批次在装载请求内重复: " + entry.batchKey();
            }
            if (!slots.add(entry.slotNo())) {
                return "分隔位在装载请求内重复: " + entry.slotNo();
            }
        }
        return null;
    }

    /**
     * 最终布局中不同隔离类别的数量。
     */
    public static long distinctCategoryCount(List<Placed> finalPlacement) {
        return finalPlacement.stream().map(Placed::category).distinct().count();
    }

    /**
     * 最终布局要求的最少隔板数 = 类别数 - 1（d 块隔板形成 d+1 个相互隔离的分隔区域）；
     * 单一类别时为 0。
     */
    public static int requiredDividerCount(List<Placed> finalPlacement) {
        return Math.toIntExact(Math.max(0, distinctCategoryCount(finalPlacement) - 1));
    }

    /**
     * 最终布局的分隔位是否互不相同；存在共用分隔位的两个批次即冲突。
     */
    public static boolean slotsDistinct(List<Placed> finalPlacement) {
        Set<Integer> slots = new HashSet<>();
        for (Placed placed : finalPlacement) {
            if (!slots.add(placed.slotNo())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 完整最终隔离关系校验。
     *
     * @param dividerCount   托盘当前有效隔板数（实际值）
     * @param finalPlacement 现有 ACTIVE 装载与本次请求合并后的完整布局
     * @return 违规原因（实际值/要求值/差额写入消息），通过返回 null
     */
    public static String evaluate(int dividerCount, List<Placed> finalPlacement) {
        if (!slotsDistinct(finalPlacement)) {
            return "分隔位与托盘当前装载冲突：不同批次必须分配到不同分隔位";
        }
        int required = requiredDividerCount(finalPlacement);
        if (dividerCount < required) {
            return "隔板数量不足：实际 " + dividerCount + "，要求 " + required
                    + "，差额 " + (dividerCount - required);
        }
        return null;
    }
}
