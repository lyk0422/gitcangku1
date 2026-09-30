package com.example.starter.billing.service.impl;

import com.example.starter.billing.domain.Commitment;
import com.example.starter.billing.domain.DemandOffer;
import com.example.starter.billing.domain.MeterReading;
import com.example.starter.billing.domain.ReadingKind;
import com.example.starter.billing.domain.Settlement;
import com.example.starter.billing.domain.SettlementType;
import com.example.starter.billing.repo.BillingRepository;
import com.example.starter.billing.service.BillingService;
import com.example.starter.billing.service.RewardCalculator;
import com.example.starter.billing.web.dto.AccountSummaryResponse;
import com.example.starter.billing.web.dto.CommitmentResponse;
import com.example.starter.billing.web.dto.CreateCommitmentRequest;
import com.example.starter.billing.web.dto.CreateOfferRequest;
import com.example.starter.billing.web.dto.OfferResponse;
import com.example.starter.billing.web.dto.ReadingResponse;
import com.example.starter.billing.web.dto.RegisterReadingRequest;
import com.example.starter.billing.web.dto.SettleRequest;
import com.example.starter.billing.web.dto.SettlementResponse;
import com.example.starter.support.ClockService;
import com.example.starter.web.error.ConflictException;
import com.example.starter.web.error.NotFoundException;
import com.example.starter.web.error.UnprocessableEntityException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 削峰结算业务链事务服务实现。
 *
 * <p>并发控制：承诺行是同一邀约+用户业务链的锁锚点，读数登记与结算事务均先
 * {@code SELECT ... FOR UPDATE} 锁定承诺行，再锁读数行与账目行，保证结算读取的
 * 基线/实际读数来自同一已提交版本快照，不会与计量更正互相拼接版本。</p>
 */
@Service
public class BillingServiceImpl implements BillingService {

    private final BillingRepository repository;
    private final ClockService clock;

    public BillingServiceImpl(BillingRepository repository, ClockService clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OfferResponse createOffer(CreateOfferRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new UnprocessableEntityException("邀约时段终点必须晚于起点（UTC左闭右开）");
        }
        if (repository.findOffer(request.offerId()) != null) {
            throw new ConflictException("邀约ID已存在: " + request.offerId());
        }
        Instant now = clock.now();
        DemandOffer offer = new DemandOffer(request.offerId(), request.startTime(),
                request.endTime(), now);
        try {
            repository.insertOffer(offer);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("邀约ID已存在: " + request.offerId());
        }
        return toOfferResponse(offer);
    }

    @Override
    @Transactional
    public CommitmentResponse createCommitment(String offerId, CreateCommitmentRequest request) {
        DemandOffer offer = requireOffer(offerId);
        BigDecimal committed = request.committedKwh();
        if (committed.signum() <= 0
                || committed.compareTo(RewardCalculator.MAX_COMMITMENT_KWH) > 0) {
            throw new UnprocessableEntityException(
                    "承诺电量必须在 (0, 50] kWh 之间，当前: " + committed.toPlainString());
        }
        // 幂等：同 requestId 重放原结果；业务参数不同返回 409。
        Commitment byRequest = repository.findCommitmentByRequestId(request.requestId());
        if (byRequest != null) {
            if (!sameCommitmentParams(byRequest, offerId, request.userId(), committed)) {
                throw new ConflictException("requestId已用于其他承诺（异参重放）: " + request.requestId());
            }
            return toCommitmentResponse(byRequest);
        }
        Commitment existing = repository.findCommitment(offerId, request.userId());
        if (existing != null) {
            throw new ConflictException("该用户对此邀约已存在有效承诺");
        }
        Commitment saved;
        try {
            saved = repository.insertCommitment(offer.id(), request.userId(),
                    committed.setScale(1, RoundingMode.UNNECESSARY), request.requestId(), clock.now());
        } catch (DuplicateKeyException ex) {
            Commitment raced = repository.findCommitment(offerId, request.userId());
            if (raced != null) {
                throw new ConflictException("该用户对此邀约已存在有效承诺");
            }
            throw new ConflictException("requestId已被使用: " + request.requestId());
        }
        return toCommitmentResponse(saved);
    }

    @Override
    @Transactional
    public ReadingResponse registerReading(String offerId, String userId,
                                           RegisterReadingRequest request) {
        DemandOffer offer = requireOffer(offerId);
        ReadingKind kind = parseKind(request.kind());
        BigDecimal value = request.valueKwh();
        if (value.signum() < 0) {
            throw new UnprocessableEntityException("电量读数不能为负: " + value.toPlainString());
        }
        if (!request.periodEnd().isAfter(request.periodStart())) {
            throw new UnprocessableEntityException("读数时段终点必须晚于起点（UTC左闭右开）");
        }
        if (!offer.coversExactly(request.periodStart(), request.periodEnd())) {
            throw new UnprocessableEntityException(
                    "读数时段与邀约时段不一致，须为同一UTC左闭右开时段");
        }
        // 锁承诺行：串行化同一业务链上的读数更正与结算（承诺先于计量确认）。
        Commitment commitment = repository.lockCommitment(offerId, userId);
        if (commitment == null) {
            throw new UnprocessableEntityException("请先作出减量承诺，再登记计量读数");
        }
        MeterReading latest = repository.findLatestReading(offerId, userId, kind);
        int version;
        String reason = normalizeReason(request.correctionReason());
        if (latest == null) {
            version = 1;
            if (reason != null) {
                throw new UnprocessableEntityException("首次读数不允许填写更正缘由");
            }
        } else {
            version = latest.version() + 1;
            if (reason == null) {
                throw new UnprocessableEntityException("读数更正必须提供更正缘由");
            }
        }
        MeterReading saved = repository.insertReading(offerId, userId, kind, version,
                value.setScale(3, RoundingMode.HALF_UP), request.periodStart(), request.periodEnd(),
                reason, clock.now());
        return toReadingResponse(saved);
    }

    @Override
    @Transactional
    public SettlementResponse settle(String offerId, String userId, SettleRequest request) {
        DemandOffer offer = requireOffer(offerId);
        Instant now = clock.now();
        if (!offer.isFinishedAt(now)) {
            throw new ConflictException("邀约时段尚未结束，不允许提前结算（UTC右开）");
        }
        // requestId 幂等优先：先无锁查，命中且同参直接重放原结果。
        Settlement byRequest = repository.findSettlementByRequestId(request.requestId());
        if (byRequest != null) {
            if (!byRequest.offerId().equals(offerId) || !byRequest.userId().equals(userId)) {
                throw new ConflictException("requestId已用于其他结算（异参重放）: " + request.requestId());
            }
            return toSettlementResponse(byRequest, null, null);
        }

        // 锁承诺行，再锁两类读数与全部账目，得到同一已提交版本的一致性快照。
        Commitment commitment = repository.lockCommitment(offerId, userId);
        if (commitment == null) {
            throw new UnprocessableEntityException("尚未作出减量承诺，无法结算");
        }
        List<MeterReading> baselines = repository.lockReadings(offerId, userId, ReadingKind.BASELINE);
        List<MeterReading> actuals = repository.lockReadings(offerId, userId, ReadingKind.ACTUAL);
        List<Settlement> lockedSettlements = repository.lockSettlements(offerId, userId);

        // 拿到行锁后复查 requestId，避免并发重放插入重复键。
        Settlement racedRequest = repository.findSettlementByRequestId(request.requestId());
        if (racedRequest != null) {
            return toSettlementResponse(racedRequest, null, null);
        }

        MeterReading baseline = baselines.isEmpty() ? null : baselines.get(0);
        MeterReading actual = actuals.isEmpty() ? null : actuals.get(0);
        validateReadingsForSettlement(offer, baseline, actual);

        Settlement initial = lockedSettlements.stream()
                .filter(item -> item.type() == SettlementType.INITIAL)
                .findFirst()
                .orElse(null);

        BigDecimal reward = RewardCalculator.rewardKwh(commitment.committedKwh(),
                baseline.valueKwh(), actual.valueKwh());
        long total = RewardCalculator.amountFen(reward);
        BigDecimal reduction = baseline.valueKwh().subtract(actual.valueKwh()).max(BigDecimal.ZERO)
                .setScale(3, RoundingMode.HALF_UP);

        if (initial == null) {
            Settlement saved = insertSettlementQuietly(new Settlement(
                    null, offerId, userId, SettlementType.INITIAL,
                    baseline.id(), actual.id(), baseline.version(), actual.version(),
                    commitment.committedKwh(),
                    baseline.valueKwh(), actual.valueKwh(), reduction, reward,
                    total, total, null, null, request.requestId(), now));
            return toSettlementResponse(saved, null, null);
        }

        Settlement previous = lockedSettlements.get(lockedSettlements.size() - 1);
        if (previous.baselineVersion() == baseline.version()
                && previous.actualVersion() == actual.version()) {
            throw new ConflictException("相同邀约、用户与读数版本组合不得重复入账");
        }
        String reason = pickCorrectionReason(baseline, actual, previous);
        long delta = total - previous.totalAmountFen();
        Settlement saved = insertSettlementQuietly(new Settlement(
                null, offerId, userId, SettlementType.ADJUSTMENT,
                baseline.id(), actual.id(), baseline.version(), actual.version(),
                commitment.committedKwh(),
                baseline.valueKwh(), actual.valueKwh(), reduction, reward,
                delta, total, previous.id(), reason, request.requestId(), now));
        return toSettlementResponse(saved, previous.baselineVersion(), previous.actualVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSummaryResponse getSummary(String offerId, String userId) {
        requireOffer(offerId);
        Commitment commitment = repository.findCommitment(offerId, userId);
        MeterReading baseline = repository.findLatestReading(offerId, userId, ReadingKind.BASELINE);
        MeterReading actual = repository.findLatestReading(offerId, userId, ReadingKind.ACTUAL);
        List<Settlement> settlements = repository.findSettlements(offerId, userId);

        List<SettlementResponse> items = new ArrayList<>();
        Settlement prior = null;
        for (Settlement settlement : settlements) {
            Integer prevBaseline = prior == null ? null : prior.baselineVersion();
            Integer prevActual = prior == null ? null : prior.actualVersion();
            items.add(toSettlementResponse(settlement, prevBaseline, prevActual));
            prior = settlement;
        }
        Long net = settlements.isEmpty() ? null
                : settlements.stream().mapToLong(Settlement::amountFen).sum();
        BigDecimal latestReward = settlements.isEmpty() ? null
                : settlements.get(settlements.size() - 1).rewardKwh();
        return new AccountSummaryResponse(
                offerId,
                userId,
                commitment == null ? null : commitment.committedKwh(),
                baseline == null ? null : baseline.version(),
                baseline == null ? null : baseline.valueKwh(),
                actual == null ? null : actual.version(),
                actual == null ? null : actual.valueKwh(),
                latestReward,
                net,
                items);
    }

    // ==================== 内部方法 ====================

    private Settlement insertSettlementQuietly(Settlement settlement) {
        try {
            return repository.insertSettlement(settlement);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("相同邀约、用户与读数版本组合不得重复入账，或requestId冲突");
        }
    }

    private void validateReadingsForSettlement(DemandOffer offer, MeterReading baseline,
                                               MeterReading actual) {
        if (baseline == null || actual == null) {
            throw new UnprocessableEntityException(
                    "读数缺失：基线读数" + (baseline == null ? "缺失" : "已确认")
                            + "，实际读数" + (actual == null ? "缺失" : "已确认"));
        }
        if (baseline.valueKwh().signum() < 0 || actual.valueKwh().signum() < 0) {
            throw new UnprocessableEntityException("电量读数不能为负");
        }
        if (!baseline.periodStart().equals(actual.periodStart())
                || !baseline.periodEnd().equals(actual.periodEnd())) {
            throw new UnprocessableEntityException("基线与实际读数时段不一致");
        }
        if (!offer.coversExactly(baseline.periodStart(), baseline.periodEnd())
                || !offer.coversExactly(actual.periodStart(), actual.periodEnd())) {
            throw new UnprocessableEntityException("读数时段与邀约时段不一致");
        }
    }

    private String pickCorrectionReason(MeterReading baseline, MeterReading actual,
                                        Settlement previous) {
        if (baseline.version() != previous.baselineVersion() && baseline.isCorrection()) {
            return baseline.correctionReason();
        }
        if (actual.version() != previous.actualVersion() && actual.isCorrection()) {
            return actual.correctionReason();
        }
        return "读数更正后重算";
    }

    private ReadingKind parseKind(String kind) {
        try {
            return ReadingKind.valueOf(kind);
        } catch (IllegalArgumentException ex) {
            throw new UnprocessableEntityException("未知读数种类: " + kind + "，须为 BASELINE 或 ACTUAL");
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private DemandOffer requireOffer(String offerId) {
        DemandOffer offer = repository.findOffer(offerId);
        if (offer == null) {
            throw new NotFoundException("邀约不存在: " + offerId);
        }
        return offer;
    }

    private boolean sameCommitmentParams(Commitment commitment, String offerId, String userId,
                                         BigDecimal committedKwh) {
        return commitment.offerId().equals(offerId)
                && commitment.userId().equals(userId)
                && commitment.committedKwh().compareTo(committedKwh) == 0;
    }

    private OfferResponse toOfferResponse(DemandOffer offer) {
        return new OfferResponse(offer.id(), offer.startTime(), offer.endTime(), offer.createdAt());
    }

    private CommitmentResponse toCommitmentResponse(Commitment commitment) {
        return new CommitmentResponse(commitment.id(), commitment.offerId(), commitment.userId(),
                commitment.committedKwh(), commitment.requestId(), commitment.createdAt());
    }

    private ReadingResponse toReadingResponse(MeterReading reading) {
        return new ReadingResponse(reading.id(), reading.kind().name(), reading.version(),
                reading.valueKwh(), reading.periodStart(), reading.periodEnd(),
                reading.correctionReason(), reading.createdAt());
    }

    private SettlementResponse toSettlementResponse(Settlement settlement,
                                                     Integer previousBaselineVersion,
                                                     Integer previousActualVersion) {
        return new SettlementResponse(
                settlement.id(),
                settlement.type().name(),
                settlement.baselineVersion(),
                settlement.actualVersion(),
                previousBaselineVersion,
                previousActualVersion,
                settlement.baselineReadingId(),
                settlement.actualReadingId(),
                settlement.committedKwh(),
                settlement.baselineKwh(),
                settlement.actualKwh(),
                settlement.reductionKwh(),
                settlement.rewardKwh(),
                settlement.amountFen(),
                settlement.totalAmountFen(),
                settlement.correctedSettlementId(),
                settlement.correctionReason(),
                settlement.requestId(),
                settlement.createdAt());
    }
}
