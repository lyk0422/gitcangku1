package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.AccountSnapshot;
import com.example.starter.exposure.domain.BudgetAccount;
import com.example.starter.exposure.domain.TransferLine;
import com.example.starter.exposure.repo.BudgetAccountRepository;
import com.example.starter.exposure.repo.BudgetTransferRepository;
import com.example.starter.exposure.repo.BudgetTransferRepository.BudgetTransferRecord;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.AccountStateResponse;
import com.example.starter.exposure.web.ActivateTransferRequest;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.BudgetAccountResponse;
import com.example.starter.exposure.web.CreateBudgetAccountRequest;
import com.example.starter.exposure.web.PreviewTransferRequest;
import com.example.starter.exposure.web.TransferLineRequest;
import com.example.starter.exposure.web.TransferLineResponse;
import com.example.starter.exposure.web.TransferPreviewResponse;
import com.example.starter.exposure.web.TransferResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 活动预算账本与预算转移业务实现。
 *
 * <p>预算转移以规范化明细（按源目标求和、稳定排序）为准，允许 A→B→C→A 闭环；
 * 激活在一个事务内先结算涉及活动的过期预占，再按 campaignId 升序行锁全部账本，
 * 重读窗口/受众/版本/在途后整体校验、整体生效或整体回滚，不逐条转移。
 * 预算恒等式 budget = 可转余额 + 在途 + 已确认 由表级 CHECK 约束兜底。</p>
 */
@Service
public class BudgetServiceImpl implements BudgetService {

    private static final String OP_CREATE_ACCOUNT = "CREATE_BUDGET_ACCOUNT";
    private static final String OP_ACTIVATE_TRANSFER = "ACTIVATE_TRANSFER";
    private static final String STATUS_ACTIVATED = "ACTIVATED";

    private final Clock clock;
    private final BudgetAccountRepository budgetAccountRepository;
    private final BudgetTransferRepository budgetTransferRepository;
    private final ReservationRepository reservationRepository;
    private final ExpirySettlement expirySettlement;
    private final IdempotentExecutor idempotentExecutor;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public BudgetServiceImpl(Clock clock,
                             BudgetAccountRepository budgetAccountRepository,
                             BudgetTransferRepository budgetTransferRepository,
                             ReservationRepository reservationRepository,
                             ExpirySettlement expirySettlement,
                             IdempotentExecutor idempotentExecutor,
                             ObjectMapper objectMapper,
                             TransactionTemplate txTemplate) {
        this.clock = clock;
        this.budgetAccountRepository = budgetAccountRepository;
        this.budgetTransferRepository = budgetTransferRepository;
        this.reservationRepository = reservationRepository;
        this.expirySettlement = expirySettlement;
        this.idempotentExecutor = idempotentExecutor;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public BudgetAccountResponse createAccount(CreateBudgetAccountRequest request) {
        String fingerprint = request.campaignId() + "|" + request.tenantId() + "|"
                + request.windowStartUtc() + "|" + request.windowEndUtc() + "|"
                + request.audienceRule() + "|" + request.initialBudget();
        return idempotentExecutor.run(request.requestId(), OP_CREATE_ACCOUNT, fingerprint,
                BudgetAccountResponse.class, () -> {
                    if (request.windowStartUtc() >= request.windowEndUtc()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "windowStartUtc must be before windowEndUtc");
                    }
                    if (budgetAccountRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "budget account already exists: " + request.campaignId());
                    }
                    BudgetAccount account = new BudgetAccount(
                            request.campaignId(),
                            request.tenantId(),
                            request.windowStartUtc(),
                            request.windowEndUtc(),
                            request.audienceRule(),
                            request.initialBudget(),
                            0,
                            0,
                            0,
                            clock.millis());
                    try {
                        budgetAccountRepository.insert(account);
                    } catch (DuplicateKeyException duplicate) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "budget account already exists: " + request.campaignId());
                    }
                    return BudgetAccountResponse.from(account);
                });
    }

    @Override
    public BudgetAccountResponse getAccount(String campaignId) {
        return budgetAccountRepository.findById(campaignId)
                .map(BudgetAccountResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "budget account not found: " + campaignId));
    }

    @Override
    public TransferPreviewResponse preview(PreviewTransferRequest request) {
        List<TransferLine> lines = normalize(request.lines());
        Map<String, Integer> expectedVersions = expectedVersions(request.lines());
        return txTemplate.execute(status -> {
            long now = clock.millis();
            List<String> ids = involvedIds(lines);
            List<BudgetAccount> accounts = new ArrayList<>();
            for (String id : ids) {
                accounts.add(budgetAccountRepository.findById(id)
                        .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "budget account not found: " + id)));
            }
            requireSameRules(accounts);
            checkExpectedVersions(accounts, expectedVersions);

            // 只读预览：不写数据，在途按“已存储在途 - 已到期未结算预占”折算
            Map<String, AccountSnapshot> postStates = new LinkedHashMap<>();
            for (BudgetAccount account : accounts) {
                int effectiveInFlight = account.inFlight()
                        - reservationRepository.countExpiredReserved(account.campaignId(), now);
                long newBudget = account.budget() + deltaOf(lines, account.campaignId());
                long postTransferable = newBudget - effectiveInFlight - account.confirmed();
                if (postTransferable < 0) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "insufficient transferable budget for campaign "
                                    + account.campaignId());
                }
                postStates.put(account.campaignId(), new AccountSnapshot(
                        account.campaignId(), (int) newBudget, effectiveInFlight,
                        account.confirmed(), account.version()));
            }
            return new TransferPreviewResponse(
                    lines.stream().map(TransferLineResponse::from).toList(),
                    postStates.values().stream().sorted()
                            .map(AccountStateResponse::from).toList());
        });
    }

    @Override
    public TransferResponse activate(ActivateTransferRequest request) {
        // 指纹基于原始明细的规范串（免校验、排序后与顺序无关）：同参重放首次快照，
        // 明细换序等价，异参 409；参数校验在事务内统一进行
        String fingerprint = request.transferKey() + "|" + canonicalRaw(request.lines());
        return idempotentExecutor.run(request.requestId(), OP_ACTIVATE_TRANSFER, fingerprint,
                TransferResponse.class, () -> {
                    List<TransferLine> lines = normalize(request.lines());
                    Map<String, Integer> expectedVersions = expectedVersions(request.lines());
                    long now = clock.millis();
                    List<String> ids = involvedIds(lines);

                    // 先结算涉及活动的过期预占（锁序：预占单 -> 额度账 -> 预算账本），
                    // 再统一升序行锁预算账本，与申请/回执路径的加锁顺序一致，避免死锁
                    for (String id : ids) {
                        expirySettlement.settleExpired(id, now);
                    }
                    List<BudgetAccount> accounts = budgetAccountRepository.lockByIds(ids);
                    if (accounts.size() != ids.size()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "budget account missing for some campaign in transfer");
                    }
                    requireSameRules(accounts);
                    checkExpectedVersions(accounts, expectedVersions);

                    // 窗口为左闭右开：到达起始时刻即视为已开始，禁止激活
                    long windowStart = accounts.get(0).windowStartUtc();
                    if (now >= windowStart) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "delivery window already started at " + windowStart);
                    }

                    // 后态校验：转移后各活动总预算必须非负且仍覆盖在途与已确认
                    // （可转余额只计算尚未预占的预算，在途/已确认不得被转走）
                    for (BudgetAccount account : accounts) {
                        long newBudget = account.budget() + deltaOf(lines, account.campaignId());
                        long postTransferable = newBudget - account.inFlight()
                                - account.confirmed();
                        if (postTransferable < 0) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "insufficient transferable budget for campaign "
                                            + account.campaignId());
                        }
                    }

                    List<AccountSnapshot> before = accounts.stream()
                            .map(AccountSnapshot::of).sorted().toList();

                    // 整体生效：一次性更新全部活动预算并逐活动增版
                    Map<String, AccountSnapshot> afterMap = new LinkedHashMap<>();
                    for (BudgetAccount account : accounts) {
                        int newBudget = (int) (account.budget()
                                + deltaOf(lines, account.campaignId()));
                        budgetAccountRepository.applyTransfer(account.campaignId(), newBudget);
                        afterMap.put(account.campaignId(), new AccountSnapshot(
                                account.campaignId(), newBudget, account.inFlight(),
                                account.confirmed(), account.version() + 1));
                    }
                    List<AccountSnapshot> after = afterMap.values().stream().sorted().toList();

                    // 冻结规范化明细与前后账本快照；transferKey 唯一冲突明确返回 409
                    BudgetTransferRecord record = new BudgetTransferRecord(
                            request.transferKey(), request.requestId(),
                            writeJson(lines), writeJson(before), writeJson(after), now);
                    try {
                        budgetTransferRepository.insertTransfer(record);
                    } catch (DuplicateKeyException duplicate) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "transferKey already exists: " + request.transferKey());
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        budgetTransferRepository.insertLine(request.transferKey(), i, lines.get(i));
                    }

                    return toResponse(record, lines, before, after);
                });
    }

    @Override
    public TransferResponse getTransfer(String transferKey) {
        BudgetTransferRecord record = budgetTransferRepository.findTransfer(transferKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "transfer not found: " + transferKey));
        List<TransferLine> lines = budgetTransferRepository.findLines(transferKey);
        List<AccountSnapshot> before = readSnapshots(record.beforeSnapshotJson());
        List<AccountSnapshot> after = readSnapshots(record.afterSnapshotJson());
        return toResponse(record, lines, before, after);
    }

    // ---- 内部辅助（作用域末尾） ----

    /**
     * 规范化明细：拒绝源目标相同（422），按（源,目标）求和并按字典序稳定排序。
     * 求和后数量溢出 int 视为参数非法（422）。
     */
    private List<TransferLine> normalize(List<TransferLineRequest> requestLines) {
        TreeMap<String, TreeMap<String, Long>> sums = new TreeMap<>();
        for (TransferLineRequest line : requestLines) {
            if (line.sourceCampaignId().equals(line.targetCampaignId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "source and target campaign must differ: " + line.sourceCampaignId());
            }
            sums.computeIfAbsent(line.sourceCampaignId(), k -> new TreeMap<>())
                    .merge(line.targetCampaignId(), (long) line.amount(), Long::sum);
        }
        List<TransferLine> normalized = new ArrayList<>();
        sums.forEach((source, targets) -> targets.forEach((target, amount) -> {
            if (amount > Integer.MAX_VALUE) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "normalized amount overflows for " + source + " -> " + target);
            }
            normalized.add(new TransferLine(source, target, amount.intValue()));
        }));
        return normalized;
    }

    /**
     * 汇总各活动期望版本：同一活动在不同明细中的期望版本必须一致，否则请求自相矛盾（422）。
     */
    private Map<String, Integer> expectedVersions(List<TransferLineRequest> requestLines) {
        Map<String, Integer> expected = new HashMap<>();
        for (TransferLineRequest line : requestLines) {
            mergeExpected(expected, line.sourceCampaignId(), line.sourceExpectedVersion());
            mergeExpected(expected, line.targetCampaignId(), line.targetExpectedVersion());
        }
        return expected;
    }

    private void mergeExpected(Map<String, Integer> expected, String campaignId, int version) {
        Integer existing = expected.putIfAbsent(campaignId, version);
        if (existing != null && existing != version) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "conflicting expected versions for campaign " + campaignId);
        }
    }

    /** 规范化明细涉及的全部活动编号，升序去重。 */
    private List<String> involvedIds(List<TransferLine> lines) {
        return lines.stream()
                .flatMap(line -> java.util.stream.Stream.of(
                        line.sourceCampaignId(), line.targetCampaignId()))
                .distinct().sorted().toList();
    }

    /** 某活动净变动（转入 - 转出）。 */
    private long deltaOf(List<TransferLine> lines, String campaignId) {
        long delta = 0;
        for (TransferLine line : lines) {
            if (line.targetCampaignId().equals(campaignId)) {
                delta += line.amount();
            }
            if (line.sourceCampaignId().equals(campaignId)) {
                delta -= line.amount();
            }
        }
        return delta;
    }

    /** 全部活动必须同租户、同窗口、同受众规则，否则 422。 */
    private void requireSameRules(List<BudgetAccount> accounts) {
        BudgetAccount first = accounts.get(0);
        for (BudgetAccount account : accounts) {
            if (!account.tenantId().equals(first.tenantId())
                    || account.windowStartUtc() != first.windowStartUtc()
                    || account.windowEndUtc() != first.windowEndUtc()
                    || !account.audienceRule().equals(first.audienceRule())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "campaigns differ in tenant, window or audience rule: "
                                + account.campaignId());
            }
        }
    }

    /** 任一活动当前版本与期望版本不一致则 409（激活时重读，任一变化即失败）。 */
    private void checkExpectedVersions(List<BudgetAccount> accounts,
                                       Map<String, Integer> expectedVersions) {
        for (BudgetAccount account : accounts) {
            Integer expected = expectedVersions.get(account.campaignId());
            if (expected != null && expected != account.version()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "version mismatch for campaign " + account.campaignId()
                                + ": expected " + expected + " but was " + account.version());
            }
        }
    }

    /** 原始明细的规范字符串：排序后与明细顺序无关，换序等价。 */
    private String canonicalRaw(List<TransferLineRequest> lines) {
        return lines.stream()
                .map(line -> line.sourceCampaignId() + ">" + line.targetCampaignId()
                        + ":" + line.amount()
                        + "@" + line.sourceExpectedVersion() + "," + line.targetExpectedVersion())
                .sorted()
                .reduce("", (a, b) -> a + b + ";");
    }

    private TransferResponse toResponse(BudgetTransferRecord record, List<TransferLine> lines,
                                        List<AccountSnapshot> before, List<AccountSnapshot> after) {
        return new TransferResponse(
                record.transferKey(),
                STATUS_ACTIVATED,
                lines.stream().map(TransferLineResponse::from).toList(),
                before.stream().sorted().map(AccountStateResponse::from).toList(),
                after.stream().sorted().map(AccountStateResponse::from).toList(),
                record.createdAtUtc());
    }

    private List<AccountSnapshot> readSnapshots(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize transfer snapshot", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize transfer evidence", e);
        }
    }
}
