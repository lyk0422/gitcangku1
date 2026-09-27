package com.example.starter.observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 维护窗口有效集折叠逻辑（纯函数，便于单元测试）：按维护版本顺序重放只追加的窗口行，
 * REGISTER 行加入有效集；CORRECTION 行替换或撤回其目标窗口。目标窗口必须在折叠时点仍有效，
 * 即历史窗口不可二次修改，只能继续对当前有效窗口追加更正。
 */
public final class EffectiveMaintenanceWindows {

    private EffectiveMaintenanceWindows() {
    }

    /**
     * 按版本升序折叠窗口行为当前有效窗口集合（返回顺序不保证，匹配命中时再按版本判定）。
     *
     * @param rows 维护版本升序的窗口行（登记/更正/撤回）
     * @return 当前仍有效的带时间区间窗口（撤回行不出现，已被替换的登记行不出现）
     */
    public static List<MaintenanceWindow> fold(List<MaintenanceWindow> rows) {
        // windowId -> 当前有效窗口行；登记与替换更正放入，撤回与被替换者移除
        Map<String, MaintenanceWindow> effective = new LinkedHashMap<>();
        for (MaintenanceWindow row : rows) {
            switch (row.kind()) {
                case REGISTER -> effective.put(row.windowId(), row);
                case CORRECTION -> {
                    String target = row.supersedesWindowId();
                    if (!effective.containsKey(target)) {
                        throw ApiException.badRequest(
                                "target window is not effective (already superseded/retracted or unknown): " + target);
                    }
                    effective.remove(target);
                    if (!row.retracted()) {
                        effective.put(row.windowId(), row);
                    }
                }
            }
        }
        return new ArrayList<>(effective.values());
    }

    /**
     * 左闭右开区间重叠判定：[startA, endA) 与 [startB, endB) 重叠当且仅当 startA &lt; endB 且 startB &lt; endA。
     * 端点相接（一个的 end 等于另一个的 start）不算重叠。
     */
    public static boolean overlaps(Instant startA, Instant endA, Instant startB, Instant endB) {
        return startA.isBefore(endB) && startB.isBefore(endA);
    }

    /**
     * 在有效窗口集合中匹配 UTC 时刻（start &lt;= t &lt; end）；理论上登记时已杜绝重叠，
     * 若数据上存在多个命中，取维护版本最高（最近一次登记/更正）者，保证结果确定。
     *
     * @return 命中的有效窗口；未命中返回 null
     */
    public static MaintenanceWindow match(List<MaintenanceWindow> effectiveWindows, Instant instant) {
        MaintenanceWindow hit = null;
        for (MaintenanceWindow window : effectiveWindows) {
            if (window.contains(instant)
                    && (hit == null || window.version() > hit.version())) {
                hit = window;
            }
        }
        return hit;
    }
}
