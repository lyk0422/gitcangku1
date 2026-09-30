package com.example.starter.service;

import com.example.starter.model.Commitment;
import com.example.starter.model.Invitation;
import com.example.starter.model.LedgerEntry;
import com.example.starter.model.MeterReading;
import com.example.starter.model.Settlement;
import com.example.starter.repository.DemandResponseRepository;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.AccountView;
import com.example.starter.web.dto.CommitmentRequest;
import com.example.starter.web.dto.CommitmentView;
import com.example.starter.web.dto.InvitationRequest;
import com.example.starter.web.dto.InvitationView;
import com.example.starter.web.dto.LedgerEntryView;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingVersionView;
import com.example.starter.web.dto.SettlementRequest;
import com.example.starter.web.dto.SettlementView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 削峰需求响应业务服务。
 *
 * <p>约定：时间一律 UTC、时段左闭右开；电量 kWh；金额为整数分。
 * 读数提交/更正与结算都先对承诺行加行锁（FOR UPDATE），保证结算只取同一已提交读数版本，
 * 不会出现旧基线与新实际拼接。</p>
 */
@Service
public class DemandResponseService {

    /** 默认奖励单价：每 kWh 80 分。 */
    static final int DEFAULT_RATE_FEN_PER_KWH = 80;
    /** 承诺电量上限 50 kWh。 */
    static final BigDecimal MAX_COMMITMENT_KWH = new BigDecimal("50");

    private final DemandResponseRepository repository;
    private final Clock clock;

    public DemandResponseService(DemandResponseRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public InvitationView createInvitation(InvitationRequest request) {
        if (request.periodEnd().isBefore(request.periodStart()) || request.periodEnd().equals(request.periodStart())) {
            throw ApiException.unprocessable("邀约时段终点必须晚于起点（UTC 左闭右开）");
        }
        int rate = request.rateFenPerKwh() == null ? DEFAULT_RATE_FEN_PER_KWH : request.rateFenPerKwh();
        if (rate <= 0) {
            throw ApiException.unprocessable("每 kWh 奖励必须为正");
        }
        Instant now = Instant.now(clock);
        try {
            long id = repository.insertInvitation(request.periodStart(), request.periodEnd(), rate, now);
            return new InvitationView(id, request.periodStart(), request.periodEnd(), rate, now);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("该时段邀约已存在");
        }
    }

    public CommitmentView createCommitment(long invitationId, CommitmentRequest request) {
        Invitation invitation = requireInvitation(invitationId);
        if (request.reductionKwh().compareTo(MAX_COMMITMENT_KWH) > 0) {
            throw ApiException.unprocessable("承诺电量不得超过 50 kWh");
        }
        Commitment byRequest = repository.findCommitmentByRequestId(request.requestId()).orElse(null);
        if (byRequest != null) {
            return reconcileCommitment(byRequest, invitationId, request);
        }
        repository.findCommitment(invitationId, request.userId()).ifPresent(existing -> {
            throw ApiException.conflict("该用户对此邀约已有有效承诺");
        });
        Instant now = Instant.now(clock);
        try {
            long id = repository.insertCommitment(invitationId, request.userId(),
                    request.reductionKwh(), request.requestId(), now);
            return new CommitmentView(id, invitationId, request.userId(),
                    request.reductionKwh(), request.requestId(), now);
        } catch (DuplicateKeyException ex) {
            Commitment raced = repository.findCommitmentByRequestId(request.requestId())
                    .or(() -> repository.findCommitment(invitationId, request.userId()))
                    .orElseThrow(() -> ApiException.conflict("承诺冲突"));
            return reconcileCommitment(raced, invitationId, request);
        }
    }

    /**
     * 提交计量读数：首版为计量确认，再次提交即更正；基线与实际同版本、同事务写入。
     */
    @Transactional
    public ReadingVersionView submitReading(long invitationId, ReadingRequest request) {
        Instant now = Instant.now(clock);
        requireInvitation(invitationId);
        Commitment commitment = repository.lockCommitment(invitationId, request.userId())
                .orElseThrow(() -> ApiException.notFound("该用户对此邀约尚无承诺，无法提交计量读数"));

        validateReadingRequest(request, now);

        int latestVersion = repository.findMaxReadingVersion(invitationId, request.userId()).orElse(0);
        if (latestVersion == 0 && request.reason() != null && !request.reason().isBlank()) {
            throw ApiException.unprocessable("首版读数不是更正，不得填写更正缘由");
        }
        if (latestVersion > 0 && (request.reason() == null || request.reason().isBlank())) {
            throw ApiException.unprocessable("读数更正必须提供更正缘由");
        }

        // 无 requestId 的接口靠内容识别重放：最新版本与提交完全一致则原样返回，不生成新版本。
        if (latestVersion > 0) {
            ReadingVersionView latest = loadVersionView(invitationId, request.userId(), latestVersion);
            if (sameAsRequest(latest, request)) {
                return latest;
            }
        }

        int version = latestVersion + 1;
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        MeterReading baseline = new MeterReading(null, invitationId, request.userId(),
                MeterReading.TYPE_BASELINE, version, request.baselineKwh(),
                request.baselinePeriodStart(), request.baselinePeriodEnd(), reason, now);
        MeterReading actual = new MeterReading(null, invitationId, request.userId(),
                MeterReading.TYPE_ACTUAL, version, request.actualKwh(),
                request.actualPeriodStart(), request.actualPeriodEnd(), reason, now);
        try {
            repository.insertReading(baseline);
            repository.insertReading(actual);
        } catch (DuplicateKeyException ex) {
            // 并发提交相同内容的重试：另一事务已提交同版本，回查返回该版本，不重复建版。
            int currentLatest = repository.findMaxReadingVersion(invitationId, request.userId()).orElse(version);
            ReadingVersionView current = loadVersionView(invitationId, request.userId(), currentLatest);
            if (current.version() == version && sameAsRequest(current, request)) {
                return current;
            }
            throw ApiException.conflict("读数版本并发冲突，请重新提交更正");
        }

        // 已做过首次结算：固化的原结算不变，仅追加一笔相对上一入账版本的差额账。
        repository.findSettlement(invitationId, request.userId()).ifPresent(settlement -> {
            List<LedgerEntry> ledger = repository.findLedger(invitationId, request.userId());
            int previousVersion = ledger.stream()
                    .mapToInt(LedgerEntry::newVersion)
                    .max()
                    .orElse(settlement.readingVersion());
            BigDecimal oldReward = rewardOfVersion(invitationId, request.userId(),
                    previousVersion, commitment.reductionKwh());
            BigDecimal newReward = RewardCalculator.rewardKwh(commitment.reductionKwh(),
                    request.baselineKwh(), request.actualKwh());
            int rate = rateOf(invitationId);
            long oldAmount = RewardCalculator.amountFen(oldReward, rate);
            long newAmount = RewardCalculator.amountFen(newReward, rate);
            repository.insertLedger(invitationId, request.userId(), settlement.id(),
                    LedgerEntry.TYPE_CORRECTION, previousVersion, version,
                    newReward.subtract(oldReward), newAmount - oldAmount, reason, now);
        });

        return new ReadingVersionView(version, request.baselineKwh(), request.actualKwh(),
                request.baselinePeriodStart(), request.baselinePeriodEnd(), reason, now);
    }

    /**
     * 首次结算：固化读数版本与金额；同 requestId 重放原结果，异参或已用其他 requestId 结算返回 409。
     */
    @Transactional
    public SettlementView settle(long invitationId, SettlementRequest request) {
        Instant now = Instant.now(clock);
        Invitation invitation = requireInvitation(invitationId);

        // 先锁承诺行：与读数更正互斥，保证下面读到的基线/实际必然是同一已提交版本。
        Commitment commitment = repository.lockCommitment(invitationId, request.userId())
                .orElseThrow(() -> ApiException.notFound("该用户对此邀约尚无承诺，无法结算"));

        Settlement existingByRequest = repository.findSettlementByRequestId(request.requestId()).orElse(null);
        if (existingByRequest != null) {
            if (existingByRequest.invitationId() == invitationId
                    && existingByRequest.userId().equals(request.userId())) {
                return buildSettlementView(existingByRequest, commitment, true);
            }
            throw ApiException.conflict("requestId 已用于其他结算，参数不一致");
        }
        Settlement existing = repository.findSettlement(invitationId, request.userId()).orElse(null);
        if (existing != null) {
            throw ApiException.conflict("该用户对此邀约已结算，结算 requestId 不得重复创建");
        }

        // 邀约时段未结束先返回 409：计量数据允许迟到，未到点不能提前结算（也不产生账目）。
        if (now.isBefore(invitation.periodEnd())) {
            throw ApiException.conflict("邀约时段尚未结束，不能提前结算");
        }

        Integer version = repository.findMaxReadingVersion(invitationId, request.userId()).orElse(null);
        if (version == null) {
            throw ApiException.unprocessable("计量读数缺失，无法结算");
        }
        MeterReading baseline = repository.findReading(invitationId, request.userId(),
                MeterReading.TYPE_BASELINE, version)
                .orElseThrow(() -> ApiException.unprocessable("基线读数缺失，无法结算"));
        MeterReading actual = repository.findReading(invitationId, request.userId(),
                MeterReading.TYPE_ACTUAL, version)
                .orElseThrow(() -> ApiException.unprocessable("实际读数缺失，无法结算"));
        if (baseline.energyKwh().signum() < 0 || actual.energyKwh().signum() < 0) {
            throw ApiException.unprocessable("电量不得为负");
        }
        if (!Objects.equals(baseline.periodStart(), actual.periodStart())
                || !Objects.equals(baseline.periodEnd(), actual.periodEnd())) {
            throw ApiException.unprocessable("基线与实际读数时段不一致，无法结算");
        }
        if (now.isBefore(actual.periodEnd())) {
            throw ApiException.conflict("读数时段尚未结束，不能提前结算");
        }

        BigDecimal rewardKwh = RewardCalculator.rewardKwh(commitment.reductionKwh(),
                baseline.energyKwh(), actual.energyKwh());
        long amountFen = RewardCalculator.amountFen(rewardKwh, invitation.rateFenPerKwh());

        long settlementId;
        try {
            settlementId = repository.insertSettlement(invitationId, request.userId(), commitment.id(),
                    version, rewardKwh, amountFen, request.requestId(), now);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("结算已存在，请勿重复入账");
        }
        try {
            repository.insertLedger(invitationId, request.userId(), settlementId,
                    LedgerEntry.TYPE_FIRST, null, version, rewardKwh, amountFen, null, now);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("相同邀约、用户、读数版本不得重复入账");
        }

        Settlement saved = new Settlement(settlementId, invitationId, request.userId(), commitment.id(),
                version, rewardKwh, amountFen, request.requestId(), now);
        return buildSettlementView(saved, commitment, false);
    }

    public AccountView getAccount(long invitationId, String userId) {
        requireInvitation(invitationId);
        Commitment commitment = repository.findCommitment(invitationId, userId).orElse(null);
        List<ReadingVersionView> readings = loadReadingViews(invitationId, userId);
        Settlement settlement = repository.findSettlement(invitationId, userId).orElse(null);
        List<LedgerEntry> entries = repository.findLedger(invitationId, userId);
        long net = entries.stream().mapToLong(LedgerEntry::amountFen).sum();
        SettlementView settlementView = settlement == null
                ? null
                : buildSettlementView(settlement, commitment, false);
        return new AccountView(invitationId, userId, toCommitmentView(commitment),
                readings, settlementView, net);
    }

    private void validateReadingRequest(ReadingRequest request, Instant now) {
        if (request.baselineKwh().signum() < 0 || request.actualKwh().signum() < 0) {
            throw ApiException.unprocessable("电量不得为负");
        }
        if (!request.baselinePeriodEnd().isAfter(request.baselinePeriodStart())
                || !request.actualPeriodEnd().isAfter(request.actualPeriodStart())) {
            throw ApiException.unprocessable("读数时段终点必须晚于起点（UTC 左闭右开）");
        }
        if (!Objects.equals(request.baselinePeriodStart(), request.actualPeriodStart())
                || !Objects.equals(request.baselinePeriodEnd(), request.actualPeriodEnd())) {
            throw ApiException.unprocessable("基线与实际读数时段不一致");
        }
        if (now.isBefore(request.actualPeriodEnd())) {
            throw ApiException.conflict("读数时段尚未结束，不能提前确认计量");
        }
    }

    private CommitmentView reconcileCommitment(Commitment found, long invitationId, CommitmentRequest request) {
        if (found.invitationId() == invitationId
                && found.userId().equals(request.userId())
                && found.reductionKwh().compareTo(request.reductionKwh()) == 0) {
            return toCommitmentView(found);
        }
        throw ApiException.conflict("requestId 已存在但参数不一致");
    }

    private BigDecimal rewardOfVersion(long invitationId, String userId, int version, BigDecimal committed) {
        MeterReading baseline = repository.findReading(invitationId, userId,
                MeterReading.TYPE_BASELINE, version)
                .orElseThrow(() -> ApiException.unprocessable("旧版本基线读数缺失"));
        MeterReading actual = repository.findReading(invitationId, userId,
                MeterReading.TYPE_ACTUAL, version)
                .orElseThrow(() -> ApiException.unprocessable("旧版本实际读数缺失"));
        return RewardCalculator.rewardKwh(committed, baseline.energyKwh(), actual.energyKwh());
    }

    private int rateOf(long invitationId) {
        return requireInvitation(invitationId).rateFenPerKwh();
    }

    private Invitation requireInvitation(long invitationId) {
        return repository.findInvitation(invitationId)
                .orElseThrow(() -> ApiException.notFound("邀约不存在: " + invitationId));
    }

    private ReadingVersionView loadVersionView(long invitationId, String userId, int version) {
        MeterReading baseline = repository.findReading(invitationId, userId,
                MeterReading.TYPE_BASELINE, version).orElseThrow();
        MeterReading actual = repository.findReading(invitationId, userId,
                MeterReading.TYPE_ACTUAL, version).orElseThrow();
        return new ReadingVersionView(version, baseline.energyKwh(), actual.energyKwh(),
                baseline.periodStart(), baseline.periodEnd(), baseline.correctedReason(), baseline.createdAt());
    }

    private List<ReadingVersionView> loadReadingViews(long invitationId, String userId) {
        Map<Integer, ReadingPair> pairs = new LinkedHashMap<>();
        for (MeterReading reading : repository.findReadings(invitationId, userId)) {
            ReadingPair pair = pairs.computeIfAbsent(reading.version(), v -> new ReadingPair());
            if (MeterReading.TYPE_BASELINE.equals(reading.readingType())) {
                pair.baseline = reading;
            } else {
                pair.actual = reading;
            }
        }
        List<ReadingVersionView> views = new ArrayList<>();
        for (Map.Entry<Integer, ReadingPair> entry : pairs.entrySet()) {
            ReadingPair pair = entry.getValue();
            views.add(new ReadingVersionView(entry.getKey(),
                    pair.baseline.energyKwh(), pair.actual.energyKwh(),
                    pair.baseline.periodStart(), pair.baseline.periodEnd(),
                    pair.baseline.correctedReason(), pair.baseline.createdAt()));
        }
        return views;
    }

    private boolean sameAsRequest(ReadingVersionView view, ReadingRequest request) {
        return view.baselineKwh().compareTo(request.baselineKwh()) == 0
                && view.actualKwh().compareTo(request.actualKwh()) == 0
                && Objects.equals(view.periodStart(), request.baselinePeriodStart())
                && Objects.equals(view.periodEnd(), request.baselinePeriodEnd())
                && Objects.equals(blankToNull(view.reason()), blankToNull(request.reason()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SettlementView buildSettlementView(Settlement settlement, Commitment commitment, boolean replayed) {
        List<LedgerEntryView> entryViews = repository.findLedger(
                        settlement.invitationId(), settlement.userId()).stream()
                .map(e -> new LedgerEntryView(e.id(), e.entryType(), e.oldVersion(), e.newVersion(),
                        e.rewardKwh(), e.amountFen(), e.reason()))
                .toList();
        long net = entryViews.stream().mapToLong(LedgerEntryView::amountFen).sum();
        return new SettlementView(settlement.id(), settlement.invitationId(), settlement.userId(),
                commitment == null ? null : commitment.reductionKwh(),
                settlement.readingVersion(), settlement.rewardKwh(), settlement.amountFen(),
                net, replayed, entryViews);
    }

    private CommitmentView toCommitmentView(Commitment commitment) {
        return commitment == null ? null : new CommitmentView(commitment.id(), commitment.invitationId(),
                commitment.userId(), commitment.reductionKwh(), commitment.requestId(), commitment.createdAt());
    }

    /** 读数版本视图组装过程中的基线/实际配对。 */
    private static final class ReadingPair {
        private MeterReading baseline;
        private MeterReading actual;
    }
}
