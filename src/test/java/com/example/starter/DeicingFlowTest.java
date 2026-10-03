package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.model.FluidType;
import com.example.starter.model.SlotStatus;
import com.example.starter.service.ConflictException;
import com.example.starter.service.DeicingService;
import com.example.starter.service.TakeoffService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 除冰主流程与拒绝分支测试，真实写入 H2（MODE=MySQL）。
 * 合成场景：10:00 完成液型 A 除冰，10:19 可确认，10:20 被拒并须重新申请。
 */
@SpringBootTest
class DeicingFlowTest {

    private static final Instant T0950 = Instant.parse("2026-01-01T09:50:00Z");
    private static final Instant T1000 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1019 = Instant.parse("2026-01-01T10:19:00Z");
    private static final Instant T1020 = Instant.parse("2026-01-01T10:20:00Z");

    @Autowired
    private DeicingService deicingService;
    @Autowired
    private TakeoffService takeoffService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0950);
        }
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM takeoff_rejection");
        jdbc.update("DELETE FROM takeoff_clearance");
        jdbc.update("DELETE FROM deicing_completion");
        jdbc.update("DELETE FROM deicing_slot");
        clock.set(T0950);
    }

    @Test
    void confirmBeforeDeadlineSucceeds() {
        var slot = deicingService.applySlot("req-a1", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        var completion = deicingService.completeSlot(slot.id(), FluidType.A);
        assertThat(completion.completedAt()).isEqualTo(T1000);
        assertThat(completion.deadlineAt()).isEqualTo(T1020);

        clock.set(T1019);
        var clearance = takeoffService.confirmTakeoff("req-t1", 1L);
        assertThat(clearance.confirmedAt()).isEqualTo(T1019);
        assertThat(clearance.fluidType()).isEqualTo(FluidType.A);
        assertThat(clearance.completedAt()).isEqualTo(T1000);
        assertThat(clearance.deadlineAt()).isEqualTo(T1020);
        assertThat(clearance.completionId()).isEqualTo(completion.id());
    }

    @Test
    void confirmAtDeadlineRejectedThenReapply() {
        var slot = deicingService.applySlot("req-b1", 1L, 2L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        deicingService.completeSlot(slot.id(), FluidType.A);

        clock.set(T1020);
        assertThatThrownBy(() -> takeoffService.confirmTakeoff("req-t2", 2L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("过期");
        Integer rejections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM takeoff_rejection WHERE flight_id = 2", Integer.class);
        assertThat(rejections).isEqualTo(1);

        // 逾期航班可重新申请除冰，旧完成记录与拒绝历史保留。
        var slot2 = deicingService.applySlot("req-b2", 1L, 2L,
                Instant.parse("2026-01-01T10:30:00Z"), Instant.parse("2026-01-01T11:00:00Z"));
        clock.set(Instant.parse("2026-01-01T10:35:00Z"));
        var completion2 = deicingService.completeSlot(slot2.id(), FluidType.B);
        assertThat(completion2.deadlineAt()).isEqualTo(Instant.parse("2026-01-01T11:10:00Z"));
        clock.set(Instant.parse("2026-01-01T10:40:00Z"));
        var clearance = takeoffService.confirmTakeoff("req-t3", 2L);
        assertThat(clearance.fluidType()).isEqualTo(FluidType.B);
        assertThat(clearance.completionId()).isEqualTo(completion2.id());
        Integer completions = jdbc.queryForObject("SELECT COUNT(*) FROM deicing_completion", Integer.class);
        assertThat(completions).isEqualTo(2);
    }

    @Test
    void overlappingSlotRejectedButTouchingAllowed() {
        deicingService.applySlot("req-c1", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        assertThatThrownBy(() -> deicingService.applySlot("req-c2", 1L, 2L,
                Instant.parse("2026-01-01T09:30:00Z"), Instant.parse("2026-01-01T10:30:00Z")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重叠");
        // 左闭右开：紧邻开始不算重叠。
        var next = deicingService.applySlot("req-c3", 1L, 2L, T1000,
                Instant.parse("2026-01-01T11:00:00Z"));
        assertThat(next.status()).isEqualTo(SlotStatus.SCHEDULED);
        // 其他车辆不受限。
        deicingService.applySlot("req-c4", 2L, 2L,
                Instant.parse("2026-01-01T09:30:00Z"), Instant.parse("2026-01-01T10:30:00Z"));
    }

    @Test
    void cancelRules() {
        var slot = deicingService.applySlot("req-d1", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        var cancelled = deicingService.cancelSlot(slot.id());
        assertThat(cancelled.status()).isEqualTo(SlotStatus.CANCELLED);
        // 取消后车辆时隙被释放，可再申请重叠区间。
        deicingService.applySlot("req-d2", 1L, 2L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        // 已取消的时隙不能完成作业。
        assertThatThrownBy(() -> deicingService.completeSlot(slot.id(), FluidType.A))
                .isInstanceOf(ConflictException.class);

        var slot2 = deicingService.applySlot("req-d3", 2L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        deicingService.completeSlot(slot2.id(), FluidType.B);
        // 已完成的作业不能通过取消撤掉。
        assertThatThrownBy(() -> deicingService.cancelSlot(slot2.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不能取消");
    }

    @Test
    void applyIdempotentReplayAndConflict() {
        var slot = deicingService.applySlot("req-e1", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        var replay = deicingService.applySlot("req-e1", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        assertThat(replay.id()).isEqualTo(slot.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM deicing_slot WHERE request_id = 'req-e1'", Integer.class);
        assertThat(count).isEqualTo(1);

        assertThatThrownBy(() -> deicingService.applySlot("req-e1", 2L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("参数不一致");
    }

    @Test
    void confirmWithoutCompletionRejected() {
        deicingService.applySlot("req-f1", 1L, 3L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        assertThatThrownBy(() -> takeoffService.confirmTakeoff("req-t9", 3L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未完成");
        Integer rejections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM takeoff_rejection WHERE flight_id = 3", Integer.class);
        assertThat(rejections).isEqualTo(1);
    }

    @Test
    void duplicateConfirmDoesNotCreateSecondClearance() {
        var slot = deicingService.applySlot("req-g1", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        deicingService.completeSlot(slot.id(), FluidType.A);
        clock.set(T1019);
        var first = takeoffService.confirmTakeoff("req-t7", 1L);
        var replay = takeoffService.confirmTakeoff("req-t7", 1L);
        assertThat(replay.id()).isEqualTo(first.id());
        // 不同 requestId 对同一完成记录再次确认，不能生成第二次放行。
        assertThatThrownBy(() -> takeoffService.confirmTakeoff("req-t8", 1L))
                .isInstanceOf(ConflictException.class);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM takeoff_clearance WHERE flight_id = 1", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}