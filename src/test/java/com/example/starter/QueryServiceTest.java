package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.model.FluidType;
import com.example.starter.model.SlotStatus;
import com.example.starter.service.ConflictException;
import com.example.starter.service.DeicingService;
import com.example.starter.service.QueryService;
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
 * 查询测试：车辆时间轴含液型与截止时刻，航班放行记录与拒绝历史可查。
 */
@SpringBootTest
class QueryServiceTest {

    private static final Instant T0950 = Instant.parse("2026-01-01T09:50:00Z");
    private static final Instant T1000 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1019 = Instant.parse("2026-01-01T10:19:00Z");
    private static final Instant T1020 = Instant.parse("2026-01-01T10:20:00Z");

    @Autowired
    private DeicingService deicingService;
    @Autowired
    private TakeoffService takeoffService;
    @Autowired
    private QueryService queryService;
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
    void timelineShowsFluidAndDeadline() {
        var slot1 = deicingService.applySlot("req-q1", 1L, 1L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        deicingService.completeSlot(slot1.id(), FluidType.A);
        clock.set(T1019);
        takeoffService.confirmTakeoff("req-q2", 1L);
        var slot2 = deicingService.applySlot("req-q3", 1L, 2L,
                Instant.parse("2026-01-01T10:30:00Z"), Instant.parse("2026-01-01T11:00:00Z"));

        var timeline = queryService.vehicleTimeline(1L);
        assertThat(timeline).hasSize(2);
        var first = timeline.get(0);
        assertThat(first.slotId()).isEqualTo(slot1.id());
        assertThat(first.status()).isEqualTo(SlotStatus.COMPLETED);
        assertThat(first.fluidType()).isEqualTo(FluidType.A);
        assertThat(first.completedAt()).isEqualTo(T1000);
        assertThat(first.deadlineAt()).isEqualTo(T1020);
        var second = timeline.get(1);
        assertThat(second.slotId()).isEqualTo(slot2.id());
        assertThat(second.status()).isEqualTo(SlotStatus.SCHEDULED);
        assertThat(second.fluidType()).isNull();
        assertThat(second.deadlineAt()).isNull();

        var clearances = queryService.flightClearances(1L);
        assertThat(clearances).hasSize(1);
        assertThat(clearances.get(0).confirmedAt()).isEqualTo(T1019);
        assertThat(clearances.get(0).fluidType()).isEqualTo(FluidType.A);
        assertThat(clearances.get(0).deadlineAt()).isEqualTo(T1020);
    }

    @Test
    void rejectionHistoryQueryable() {
        var slot = deicingService.applySlot("req-q4", 2L, 3L,
                Instant.parse("2026-01-01T09:00:00Z"), T1000);
        clock.set(T1000);
        deicingService.completeSlot(slot.id(), FluidType.A);
        clock.set(T1020);
        try {
            takeoffService.confirmTakeoff("req-q5", 3L);
        } catch (ConflictException expected) {
            // 预期拒绝
        }
        var rejections = queryService.flightRejections(3L);
        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).reason()).contains("过期");
        assertThat(rejections.get(0).attemptedAt()).isEqualTo(T1020);
        assertThat(queryService.flightClearances(3L)).isEmpty();
    }
}