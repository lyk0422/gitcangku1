package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.ClearanceStatus;
import com.example.starter.domain.DeicingSlot;
import com.example.starter.domain.FluidType;
import com.example.starter.domain.SlotStatus;
import com.example.starter.domain.TakeoffClearance;
import com.example.starter.service.ConflictException;
import com.example.starter.service.DeicingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

/** 规则测试：时隙重叠、幂等重放、取消边界、重复起飞确认。 */
@SpringBootTest
@Import(TestClockConfig.class)
@Sql(scripts = "/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DeicingRuleTest {

    private static final Instant T_1000 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private DeicingService service;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(T_1000);
    }

    @Test
    void overlappingSlotRejectedButAdjacentAllowed() {
        service.applySlot("a1", 1, 1, T_1000, T_1000.plusSeconds(1800));
        // 同一车辆重叠时隙返回409
        assertThrows(ConflictException.class, () -> service.applySlot("a2", 2, 1,
                T_1000.plusSeconds(900), T_1000.plusSeconds(2700)));
        // 左闭右开：端点相接不算重叠
        DeicingSlot adjacent = service.applySlot("a3", 2, 1,
                T_1000.plusSeconds(1800), T_1000.plusSeconds(3600));
        assertEquals(SlotStatus.BOOKED, adjacent.status());
        // 其他车辆同一时段不受影响
        DeicingSlot otherVehicle = service.applySlot("a4", 2, 2,
                T_1000.plusSeconds(900), T_1000.plusSeconds(2700));
        assertNotNull(otherVehicle);
    }

    @Test
    void applyIdempotentReplayAndParamMismatch() {
        DeicingSlot first = service.applySlot("req-1", 1, 1, T_1000, T_1000.plusSeconds(1800));
        DeicingSlot replay = service.applySlot("req-1", 1, 1, T_1000, T_1000.plusSeconds(1800));
        assertEquals(first.id(), replay.id());
        // 同requestId异参返回409
        assertThrows(ConflictException.class, () -> service.applySlot("req-1", 2, 1,
                T_1000, T_1000.plusSeconds(1800)));
    }

    @Test
    void cancelReleasesVehicleAndCompletedCannotBeCancelled() {
        // 未开始时隙可取消并释放车辆
        DeicingSlot future = service.applySlot("c1", 1, 1,
                T_1000.plusSeconds(3600), T_1000.plusSeconds(5400));
        DeicingSlot cancelled = service.cancelSlot(future.id());
        assertEquals(SlotStatus.CANCELLED, cancelled.status());
        DeicingSlot rebooked = service.applySlot("c2", 2, 1,
                T_1000.plusSeconds(3600), T_1000.plusSeconds(5400));
        assertEquals(SlotStatus.BOOKED, rebooked.status());

        // 已开始的时隙不能取消
        DeicingSlot started = service.applySlot("c3", 1, 2,
                T_1000.minusSeconds(600), T_1000.plusSeconds(600));
        assertThrows(ConflictException.class, () -> service.cancelSlot(started.id()));

        // 已完成的作业不能通过取消撤掉
        DeicingSlot done = service.applySlot("c4", 2, 2,
                T_1000.plusSeconds(3600), T_1000.plusSeconds(5400));
        service.completeSlot(done.id(), FluidType.A);
        assertThrows(ConflictException.class, () -> service.cancelSlot(done.id()));
        // 已取消的时隙不能再完成
        assertThrows(ConflictException.class, () -> service.completeSlot(future.id(), FluidType.A));
    }

    @Test
    void confirmWithoutCompletionRejectedAndRecorded() {
        TakeoffClearance rejected = service.confirmTakeoff("t1", 3);
        assertEquals(ClearanceStatus.REJECTED, rejected.status());
        assertEquals("除冰作业未完成", rejected.reason());
        List<TakeoffClearance> history = service.flightClearances(3);
        assertEquals(1, history.size());
        assertEquals(ClearanceStatus.REJECTED, history.get(0).status());
    }

    @Test
    void duplicateTakeoffConfirmNeverClearsTwice() {
        DeicingSlot slot = service.applySlot("d1", 1, 1, T_1000, T_1000.plusSeconds(1800));
        service.completeSlot(slot.id(), FluidType.A);
        clock.setInstant(T_1000.plusSeconds(600));

        TakeoffClearance cleared = service.confirmTakeoff("tk-1", 1);
        assertEquals(ClearanceStatus.CLEARED, cleared.status());
        // 同requestId同参重放返回原记录
        TakeoffClearance replay = service.confirmTakeoff("tk-1", 1);
        assertEquals(cleared.id(), replay.id());
        // 同requestId异参409
        assertThrows(ConflictException.class, () -> service.confirmTakeoff("tk-1", 2));
        // 不同requestId重复确认不能生成第二次放行
        assertThrows(ConflictException.class, () -> service.confirmTakeoff("tk-2", 1));
        List<TakeoffClearance> history = service.flightClearances(1);
        assertEquals(1, history.stream()
                .filter(c -> c.status() == ClearanceStatus.CLEARED).count());
    }

    @Test
    void fluidBDeadlineIs35Minutes() {
        DeicingSlot slot = service.applySlot("b1", 1, 1, T_1000, T_1000.plusSeconds(1800));
        var completion = service.completeSlot(slot.id(), FluidType.B);
        assertEquals(T_1000.plusSeconds(35 * 60), completion.deadlineAt());
        clock.setInstant(T_1000.plusSeconds(35 * 60));
        TakeoffClearance rejected = service.confirmTakeoff("tb-1", 1);
        assertEquals(ClearanceStatus.REJECTED, rejected.status());
    }
}