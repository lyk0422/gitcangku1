package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.domain.MaintenanceStatus;

/**
 * 保养状态判定纯单元测试：覆盖 OK / DUE / OVERDUE 边界（区间左闭右开，边界含等于）。
 */
class MaintenanceStatusTests {

    @Test
    void evaluate_boundariesWithoutThreshold() {
        assertEquals("OK", MaintenanceStatus.evaluate(0, 100, null));
        assertEquals("OK", MaintenanceStatus.evaluate(99, 100, null));
        // 达到周期（含边界）即 DUE
        assertEquals("DUE", MaintenanceStatus.evaluate(100, 100, null));
        assertEquals("DUE", MaintenanceStatus.evaluate(101, 100, null));
    }

    @Test
    void evaluate_boundariesWithThreshold() {
        Long threshold = 150L;
        assertEquals("OK", MaintenanceStatus.evaluate(99, 100, threshold));
        assertEquals("DUE", MaintenanceStatus.evaluate(100, 100, threshold));
        assertEquals("DUE", MaintenanceStatus.evaluate(149, 100, threshold));
        // 达到逾期阈值（含边界）即 OVERDUE
        assertEquals("OVERDUE", MaintenanceStatus.evaluate(150, 100, threshold));
        assertEquals("OVERDUE", MaintenanceStatus.evaluate(200, 100, threshold));
    }

    @Test
    void differences_actualRequiredAndShortage() {
        // 差额：未达周期时为正的剩余分钟
        assertEquals(50, MaintenanceStatus.remainingMinutes(50, 100));
        assertEquals(0, MaintenanceStatus.remainingMinutes(100, 100));
        // 已超周期：差额为负，表示实际值超出要求值
        assertEquals(-30, MaintenanceStatus.remainingMinutes(130, 100));

        assertEquals(0, MaintenanceStatus.overdueByMinutes(149, 150L));
        assertEquals(0, MaintenanceStatus.overdueByMinutes(150, 150L));
        assertEquals(25, MaintenanceStatus.overdueByMinutes(175, 150L));
        // 未配置逾期阈值时不计算逾期差额
        assertEquals(0, MaintenanceStatus.overdueByMinutes(999, null));
    }
}
