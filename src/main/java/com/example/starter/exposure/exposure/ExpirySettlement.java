package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.BudgetAccountRepository;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 过期预占结算组件：曝光申请/查询与预算转移激活共用的懒惰结算逻辑，
 * 不依赖后台定时器。行锁 + 状态 CAS 保证并发下只释放一次。
 */
@Component
public class ExpirySettlement {

    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final BudgetAccountRepository budgetAccountRepository;

    public ExpirySettlement(ReservationRepository reservationRepository,
                            LedgerRepository ledgerRepository,
                            BudgetAccountRepository budgetAccountRepository) {
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.budgetAccountRepository = budgetAccountRepository;
    }

    /**
     * 结算某公告当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占：
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放两级额度与在途预算，杜绝重复释放。
     */
    public void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放两级额度与在途预算；
     * 未到期或已非 RESERVED 则不做任何变更。释放始终归属预占创建时的原 campaign。
     */
    public void expireIfDue(Reservation reservation, long now) {
        if (reservation.status() == ReservationStatus.RESERVED
                && now >= reservation.expiresAtUtc()) {
            boolean won = reservationRepository.compareAndSetStatus(
                    reservation.reservationId(),
                    ReservationStatus.RESERVED,
                    ReservationStatus.EXPIRED,
                    now);
            if (won) {
                LocalDate utcDate = reservation.utcDate().toLocalDate();
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
                budgetAccountRepository.findById(reservation.campaignId())
                        .ifPresent(account ->
                                budgetAccountRepository.decrementInFlight(reservation.campaignId()));
            }
        }
    }
}
