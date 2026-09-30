package com.example.starter.exposure;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.Suppression;
import com.example.starter.exposure.domain.SuppressionCancelledReservation;
import com.example.starter.exposure.domain.SuppressionPeriod;
import com.example.starter.exposure.domain.SuppressionStatus;
import com.example.starter.exposure.exposure.IdempotentRunner;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SuppressionCancelledReservationRepository;
import com.example.starter.exposure.repo.SuppressionPeriodRepository;
import com.example.starter.exposure.repo.SuppressionRepository;
import com.example.starter.exposure.repo.VisitorLockRepository;
import com.example.starter.exposure.suppression.SuppressionServiceImpl;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.SuppressionJoinRequest;
import com.example.starter.exposure.web.SuppressionReleaseRequest;
import com.example.starter.exposure.web.SuppressionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抑制名单服务单元测试（无数据库）：用桩事务管理器与内存幂等存储，
 * 验证状态边界、同键重放只执行一次、异参 409 与额度释放次数。
 */
class SuppressionServiceUnitTest {

    static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    /** 立即提交的桩事务管理器：业务回调直接在当前线程执行。 */
    static class ImmediateTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private SuppressionRepository suppressionRepository;
    private SuppressionPeriodRepository periodRepository;
    private SuppressionCancelledReservationRepository cancelledRepository;
    private ReservationRepository reservationRepository;
    private LedgerRepository ledgerRepository;
    private VisitorLockRepository visitorLockRepository;
    private IdempotencyRepository idempotencyRepository;
    private SuppressionServiceImpl service;
    private final Map<String, IdempotencyRepository.IdempotencyRecord> idempotencyStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instant instant() {
                return NOW;
            }
        };
        suppressionRepository = mock(SuppressionRepository.class);
        periodRepository = mock(SuppressionPeriodRepository.class);
        cancelledRepository = mock(SuppressionCancelledReservationRepository.class);
        reservationRepository = mock(ReservationRepository.class);
        ledgerRepository = mock(LedgerRepository.class);
        visitorLockRepository = mock(VisitorLockRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);

        when(idempotencyRepository.lockById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(idempotencyStore.get(inv.getArgument(0))));
        doAnswer(inv -> {
            IdempotencyRepository.IdempotencyRecord record = inv.getArgument(0);
            idempotencyStore.put(record.requestId(), record);
            return null;
        }).when(idempotencyRepository).insert(any(), anyLong());
        when(periodRepository.listByVisitor(anyString())).thenReturn(List.of());
        when(cancelledRepository.listBySuppression(anyString())).thenReturn(List.of());
        when(reservationRepository.findConfirmedByVisitor(anyString())).thenReturn(List.of());

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        IdempotentRunner runner = new IdempotentRunner(idempotencyRepository, objectMapper,
                new TransactionTemplate(new ImmediateTransactionManager()), clock);
        service = new SuppressionServiceImpl(clock, suppressionRepository, periodRepository,
                cancelledRepository, reservationRepository, ledgerRepository, visitorLockRepository,
                runner, new TransactionTemplate(new ImmediateTransactionManager()));
    }

    @Test
    @DisplayName("已 ACTIVE 时重复加入：稳定返回，不取消、不释放、不新增周期")
    void joinWhileActive_isStableNoSideEffects() {
        when(suppressionRepository.lockById("V")).thenReturn(Optional.of(
                new Suppression("V", SuppressionStatus.ACTIVE, NOW.toEpochMilli(), null,
                        "req-old", 2, 2, NOW.toEpochMilli())));
        when(periodRepository.listByVisitor("V")).thenReturn(List.of(
                new SuppressionPeriod("s1", "V", NOW.toEpochMilli(), null, 2, 2,
                        "req-old", null, NOW.toEpochMilli())));

        SuppressionResponse response = service.join(new SuppressionJoinRequest("req-new", "V"));

        assertEquals(SuppressionStatus.ACTIVE, response.status());
        assertEquals(2, response.cancelledCount());
        verify(reservationRepository, never()).lockReservedByVisitor(anyString());
        verify(ledgerRepository, never()).releaseTotal(anyString(), any(LocalDate.class),
                org.mockito.ArgumentMatchers.anyInt());
        verify(ledgerRepository, never()).releaseVisitor(anyString(), anyString(), any(LocalDate.class),
                org.mockito.ArgumentMatchers.anyInt());
        verify(periodRepository, never()).insert(any());
        verify(suppressionRepository, never()).insert(any());
        verify(suppressionRepository, never()).reactivate(any());
    }

    @Test
    @DisplayName("首次加入：取消全部 RESERVED 并按笔释放两级额度、写周期与审计")
    void firstJoin_cancelsReservedAndReleasesLedger() {
        when(suppressionRepository.lockById("V")).thenReturn(Optional.empty());
        LocalDate day = LocalDate.of(2026, 9, 30);
        Reservation r1 = new Reservation("r1", "c1", "V", Date.valueOf(day),
                ReservationStatus.RESERVED, NOW.toEpochMilli(), NOW.toEpochMilli() + 60_000L, null);
        Reservation r2 = new Reservation("r2", "c1", "V", Date.valueOf(day),
                ReservationStatus.RESERVED, NOW.toEpochMilli(), NOW.toEpochMilli() + 60_000L, null);
        when(reservationRepository.lockReservedByVisitor("V")).thenReturn(List.of(r1, r2));
        when(reservationRepository.compareAndSetStatus(anyString(),
                any(ReservationStatus.class), any(ReservationStatus.class), anyLong())).thenReturn(true);

        SuppressionResponse response = service.join(new SuppressionJoinRequest("req-s1", "V"));

        assertEquals(SuppressionStatus.ACTIVE, response.status());
        assertEquals(2, response.cancelledCount());
        assertEquals(2, response.releasedQuota());
        verify(reservationRepository, times(2)).compareAndSetStatus(anyString(),
                any(ReservationStatus.class), any(ReservationStatus.class), anyLong());
        // 两笔预占共用同一额度账行，聚合为一次返还 2 次
        verify(ledgerRepository, times(1)).releaseTotal("c1", day, 2);
        verify(ledgerRepository, times(1)).releaseVisitor("c1", "V", day, 2);
        verify(ledgerRepository, never()).releaseTotal(anyString(), any(LocalDate.class),
                org.mockito.ArgumentMatchers.eq(1));
        verify(periodRepository, times(1)).insert(any(SuppressionPeriod.class));
        verify(suppressionRepository, times(1)).insert(any(Suppression.class));
        verify(cancelledRepository, times(1)).insertBatch(any());
    }

    @Test
    @DisplayName("同 requestId 重放：业务只执行一次，响应仍是首次取消数量")
    void sameRequestIdReplay_executesActionOnce() {
        when(suppressionRepository.lockById("V")).thenReturn(Optional.empty());
        LocalDate day = LocalDate.of(2026, 9, 30);
        Reservation r1 = new Reservation("r1", "c1", "V", Date.valueOf(day),
                ReservationStatus.RESERVED, NOW.toEpochMilli(), NOW.toEpochMilli() + 60_000L, null);
        when(reservationRepository.lockReservedByVisitor("V")).thenReturn(List.of(r1));
        when(reservationRepository.compareAndSetStatus(anyString(),
                any(ReservationStatus.class), any(ReservationStatus.class), anyLong())).thenReturn(true);
        when(periodRepository.listByVisitor("V")).thenAnswer(inv -> List.of(
                new SuppressionPeriod("s1", "V", NOW.toEpochMilli(), null, 1, 1,
                        "req-s1", null, NOW.toEpochMilli())));
        when(cancelledRepository.listBySuppression("s1")).thenReturn(List.of(
                new SuppressionCancelledReservation("s1", "r1", "c1", day, 1, NOW.toEpochMilli())));

        SuppressionResponse first = service.join(new SuppressionJoinRequest("req-s1", "V"));
        SuppressionResponse replay = service.join(new SuppressionJoinRequest("req-s1", "V"));

        assertEquals(first.suppressionId(), replay.suppressionId());
        assertEquals(1, replay.cancelledCount());
        verify(reservationRepository, times(1)).lockReservedByVisitor("V");
        verify(periodRepository, times(1)).insert(any(SuppressionPeriod.class));
    }

    @Test
    @DisplayName("同 requestId 换访客：409 且业务不执行")
    void sameRequestIdDifferentVisitor_returns409() {
        when(suppressionRepository.lockById("V")).thenReturn(Optional.empty());
        when(reservationRepository.lockReservedByVisitor("V")).thenReturn(List.of());
        service.join(new SuppressionJoinRequest("req-s1", "V"));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.join(new SuppressionJoinRequest("req-s1", "V2")));
        assertEquals(409, ex.getStatus().value());
        verify(reservationRepository, never()).lockReservedByVisitor("V2");
    }

    @Test
    @DisplayName("已 RELEASED 时重复解除：稳定返回，不重复关闭周期或改写状态")
    void releaseWhileReleased_isStableNoSideEffects() {
        when(suppressionRepository.lockById("V")).thenReturn(Optional.of(
                new Suppression("V", SuppressionStatus.RELEASED, NOW.toEpochMilli() - 1000L,
                        NOW.toEpochMilli(), "req-r1", 1, 1, NOW.toEpochMilli())));

        SuppressionResponse response = service.release("V",
                new SuppressionReleaseRequest("req-r2"));

        assertEquals(SuppressionStatus.RELEASED, response.status());
        verify(periodRepository, never()).close(anyString(), anyLong(), anyString());
        verify(suppressionRepository, never()).markReleased(
                anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("未加入过的访客解除：404")
    void releaseUnknownVisitor_returns404() {
        when(suppressionRepository.lockById("V")).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.release("V", new SuppressionReleaseRequest("req-r1")));
        assertEquals(404, ex.getStatus().value());
    }
}
