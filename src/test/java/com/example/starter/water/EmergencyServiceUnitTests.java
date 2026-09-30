package com.example.starter.water;

import com.example.starter.water.EmergencyRepository.ActivationRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 紧急储备纯单元测试：不启动 Spring、不访问数据库。
 * 覆盖启用前置校验（在任何账本读写之前裁决）与启用额度精确计算。
 * 本特性没有基于时间的业务分支（时间戳仅留痕），因此无需可控时钟用例。
 */
class EmergencyServiceUnitTests {

    /** 仓储/事务全部为 null：仅当被调用方法在访问任何依赖前就完成校验时，用例才有意义。 */
    private final EmergencyService service = new EmergencyService(null, null, null, null);

    @Test
    void duplicateConfirmerRejectedBeforeLedgerAccess() {
        ApiException ex = assertThrows(ApiException.class, () -> service.activate(
                "cmd-1", "req-1", "ev-1", 1L, "6", "role-jia", "role-jia"));
        assertEquals(422, ex.status().value());
        assertEquals("DUPLICATE_CONFIRMER", ex.code());
    }

    @Test
    void invalidArgumentsRejectedBeforeLedgerAccess() {
        // 非法水量
        assert400(() -> service.activate("cmd-1", "req-1", "ev-1", 1L, "0", "jia", "yi"));
        assert400(() -> service.activate("cmd-1", "req-1", "ev-1", 1L, "1.0001", "jia", "yi"));
        // 缺窗口
        assert400(() -> service.activate("cmd-1", "req-1", "ev-1", null, "1", "jia", "yi"));
        // 非法键
        assert400(() -> service.activate("cmd-1", "bad request!", "ev-1", 1L, "1", "jia", "yi"));
        // 核销/归还缺操作人
        assert400(() -> service.writeOff("cmd-1", "req-1", "1", null));
        assert400(() -> service.reserveReturn("cmd-1", "req-1", "1", " "));
        // 核销/归还非法水量
        assert400(() -> service.writeOff("cmd-1", "req-1", "-2", "jia"));
        // 事件标题为空/过长
        assert400(() -> service.registerEvent("cmd-1", "ev-1", "  ", ""));
        String tooLong = "x".repeat(257);
        assert400(() -> service.registerEvent("cmd-1", "ev-1", tooLong, ""));
        String descriptionTooLong = "d".repeat(1025);
        assert400(() -> service.registerEvent("cmd-1", "ev-1", "title", descriptionTooLong));
    }

    @Test
    void outstandingIsExactDecimalArithmetic() {
        ActivationRow full = new ActivationRow(1L, "req-1", "ev-1", 7L, new BigDecimal("6.000"),
                BigDecimal.ZERO, BigDecimal.ZERO, "jia", "yi", "ACTIVE", 1L);
        assertEquals(0, full.outstanding().compareTo(new BigDecimal("6.000")));

        ActivationRow partial = new ActivationRow(1L, "req-1", "ev-1", 7L, new BigDecimal("6.000"),
                new BigDecimal("2.000"), new BigDecimal("3.500"), "jia", "yi", "ACTIVE", 1L);
        // 6 - 2 - 3.5 = 0.5 精确
        assertEquals(0, partial.outstanding().compareTo(new BigDecimal("0.500")));

        ActivationRow settled = new ActivationRow(1L, "req-1", "ev-1", 7L, new BigDecimal("6.000"),
                new BigDecimal("2.000"), new BigDecimal("4.000"), "jia", "yi", "ACTIVE", 1L);
        assertEquals(0, settled.outstanding().compareTo(BigDecimal.ZERO));
    }

    private void assert400(ExecutableThrowing runnable) {
        ApiException ex = assertThrows(ApiException.class, runnable::run);
        assertEquals(400, ex.status().value());
    }

    private interface ExecutableThrowing {
        void run();
    }
}
