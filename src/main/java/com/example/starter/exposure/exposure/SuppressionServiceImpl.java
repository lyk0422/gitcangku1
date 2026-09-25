package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.SuppressionDeleteRecord;
import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.domain.SuppressionIntervalStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.SuppressionRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.CreateSuppressionRequest;
import com.example.starter.exposure.web.SuppressionAddSpec;
import com.example.starter.exposure.web.SuppressionBatchUpdateRequest;
import com.example.starter.exposure.web.SuppressionBatchUpdateResponse;
import com.example.starter.exposure.web.SuppressionHistoryResponse;
import com.example.starter.exposure.web.SuppressionIntervalResponse;
import com.example.starter.exposure.web.SuppressionStatusResponse;
import com.example.starter.exposure.web.SuppressionTerminateAction;
import com.example.starter.exposure.web.SuppressionTerminateSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 访客抑制名单业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键，同键同参重放原成功结果，异参 409，
 * 业务失败随事务回滚不占键。名单变更与曝光申请共享公告行锁这一串行化屏障：
 * 同键并发事务在屏障处排队，持锁后重新复查幂等键，败者直接重放胜者已提交的完整响应，
 * 不会重复执行业务或误报版本冲突；异键事务则在屏障上按提交顺序串行裁决。</p>
 *
 * <p>批量更新先锁定公告行校验 expectedVersion，再一次性校验完整最终区间集合
 * （含新增、删除、提前结束后的最终形态），全部合法后才落库并 CAS 推进版本号，
 * 任一条目不合法整批回滚、原名单不变。</p>
 */
@Service
public class SuppressionServiceImpl implements SuppressionService {

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final SuppressionRepository suppressionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public SuppressionServiceImpl(Clock clock,
                                  CampaignRepository campaignRepository,
                                  SuppressionRepository suppressionRepository,
                                  IdempotencyRepository idempotencyRepository,
                                  ObjectMapper objectMapper,
                                  TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.suppressionRepository = suppressionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public SuppressionIntervalResponse createInterval(String campaignId, CreateSuppressionRequest request) {
        String fingerprint = campaignId + "|" + request.visitorId() + "|"
                + request.startAtUtc() + "|" + request.endAtUtc();
        return runIdempotentOnCampaign(request.requestId(), Operation.CREATE_SUPPRESSION, campaignId,
                SuppressionIntervalResponse.class, campaign -> fingerprint, campaign -> {
                    long now = clock.millis();
                    requireValidRange(request.startAtUtc(), request.endAtUtc());

                    List<SuppressionInterval> sameVisitor =
                            suppressionRepository.lockAllByCampaign(campaignId).stream()
                                    .filter(i -> i.status() == SuppressionIntervalStatus.ACTIVE)
                                    .filter(i -> i.visitorId().equals(request.visitorId()))
                                    .toList();
                    for (SuppressionInterval existing : sameVisitor) {
                        if (overlaps(request.startAtUtc(), request.endAtUtc(),
                                existing.startAtUtc(), existing.endAtUtc())) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "suppression interval overlaps existing interval "
                                            + existing.intervalId() + " for visitor " + request.visitorId());
                        }
                    }

                    SuppressionInterval interval = new SuppressionInterval(
                            newId(),
                            campaignId,
                            request.visitorId(),
                            request.startAtUtc(),
                            request.endAtUtc(),
                            request.endAtUtc(),
                            SuppressionIntervalStatus.ACTIVE,
                            now, now, null, null);
                    suppressionRepository.insertInterval(interval);
                    advanceVersion(campaign);
                    return SuppressionIntervalResponse.from(interval);
                });
    }

    @Override
    public SuppressionBatchUpdateResponse batchUpdate(String campaignId, SuppressionBatchUpdateRequest request) {
        List<SuppressionAddSpec> adds = request.addIntervals() == null ? List.of() : request.addIntervals();
        List<SuppressionTerminateSpec> terminates = request.terminateIntervals() == null
                ? List.of() : request.terminateIntervals();
        String fingerprint = campaignId + "|v" + request.expectedVersion() + "|adds="
                + canonicalAdds(adds) + "|terms=" + canonicalTerminates(terminates);

        return runIdempotentOnCampaign(request.requestId(), Operation.BATCH_SUPPRESSION, campaignId,
                SuppressionBatchUpdateResponse.class, campaign -> fingerprint, campaign ->
                        doBatchUpdate(campaignId, request, adds, terminates, campaign));
    }

    @Override
    public SuppressionStatusResponse queryStatus(String campaignId, String visitorId, Long atUtc) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            long at = atUtc != null ? atUtc : clock.millis();
            SuppressionInterval hit =
                    suppressionRepository.lockActiveHitting(campaignId, visitorId, at).orElse(null);
            if (hit == null) {
                return new SuppressionStatusResponse(campaignId, visitorId, at, false, null);
            }
            String reason = "visitor " + visitorId + " is suppressed by interval " + hit.intervalId()
                    + " of campaign " + campaignId + " (UTC half-open [" + hit.startAtUtc()
                    + ", " + hit.endAtUtc() + "), hit at " + at;
            return new SuppressionStatusResponse(campaignId, visitorId, at, true,
                    new com.example.starter.exposure.web.SuppressionReasonResponse(
                            hit.intervalId(), visitorId,
                            hit.startAtUtc(), hit.endAtUtc(), reason));
        });
    }

    @Override
    public SuppressionHistoryResponse queryHistory(String campaignId, String visitorId) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            List<SuppressionIntervalResponse> intervals =
                    suppressionRepository.findByCampaignAndVisitor(campaignId, visitorId).stream()
                            .map(SuppressionIntervalResponse::from)
                            .toList();
            List<com.example.starter.exposure.web.SuppressionDeleteRecordResponse> records =
                    suppressionRepository.findDeleteRecords(campaignId, visitorId).stream()
                            .map(com.example.starter.exposure.web.SuppressionDeleteRecordResponse::from)
                            .toList();
            return new SuppressionHistoryResponse(campaignId, visitorId, intervals, records);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    private enum Operation {
        CREATE_SUPPRESSION,
        BATCH_SUPPRESSION
    }

    private record PreparedAdd(String visitorId, long startAtUtc, long endAtUtc) {
    }

    private record PreparedTerminate(SuppressionInterval target, boolean delete, Long newEndUtc) {
    }

    private record FinalInterval(String intervalId, String visitorId, long startAtUtc, long endAtUtc) {
    }

    private SuppressionBatchUpdateResponse doBatchUpdate(
            String campaignId, SuppressionBatchUpdateRequest request,
            List<SuppressionAddSpec> adds, List<SuppressionTerminateSpec> terminates,
            Campaign campaign) {
        long now = clock.millis();
        if (campaign.version() != request.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "campaign version mismatch: expected " + request.expectedVersion()
                            + ", actual " + campaign.version());
        }

        // 全量行锁读取本公告名单，构建编号 -> 区间工作副本
        Map<String, SuppressionInterval> working = new LinkedHashMap<>();
        for (SuppressionInterval interval : suppressionRepository.lockAllByCampaign(campaignId)) {
            working.put(interval.intervalId(), interval);
        }

        // 第一阶段：纯校验，不做任何写入
        List<PreparedAdd> preparedAdds = new ArrayList<>();
        for (SuppressionAddSpec add : adds) {
            requireValidRange(add.startAtUtc(), add.endAtUtc());
            preparedAdds.add(new PreparedAdd(add.visitorId(), add.startAtUtc(), add.endAtUtc()));
        }
        List<PreparedTerminate> preparedTerminates = new ArrayList<>();
        List<String> seenTargets = new ArrayList<>();
        for (SuppressionTerminateSpec spec : terminates) {
            if (seenTargets.contains(spec.intervalId())) {
                throw unprocessable("interval referenced more than once in one batch: "
                        + spec.intervalId());
            }
            seenTargets.add(spec.intervalId());
            SuppressionInterval target = working.get(spec.intervalId());
            if (target == null || !target.campaignId().equals(campaignId)) {
                throw unprocessable("interval not found in campaign: " + spec.intervalId());
            }
            if (target.status() != SuppressionIntervalStatus.ACTIVE) {
                throw unprocessable("interval already deleted: " + spec.intervalId());
            }
            if (spec.action() == SuppressionTerminateAction.DELETE) {
                if (spec.newEndAtUtc() != null) {
                    throw unprocessable("newEndAtUtc must be absent for DELETE: "
                            + spec.intervalId());
                }
                // 左闭右开：now == startAtUtc 时区间已经开始，不可删除
                if (target.startAtUtc() <= now) {
                    throw unprocessable("interval already started, cannot delete: "
                            + spec.intervalId());
                }
                preparedTerminates.add(new PreparedTerminate(target, true, null));
            } else if (spec.action() == SuppressionTerminateAction.END_EARLY) {
                Long newEnd = spec.newEndAtUtc();
                if (newEnd == null) {
                    throw unprocessable("newEndAtUtc required for END_EARLY: " + spec.intervalId());
                }
                if (now < target.startAtUtc()) {
                    throw unprocessable("interval not started yet, use DELETE instead: "
                            + spec.intervalId());
                }
                if (now >= target.endAtUtc()) {
                    throw unprocessable("interval already ended: " + spec.intervalId());
                }
                // 结束时刻不得早于当前时刻，且必须严格晚于开始时刻（半开区间非空）
                if (newEnd < now || newEnd <= target.startAtUtc()) {
                    throw unprocessable("newEndAtUtc must not be earlier than now "
                            + "and must be later than startAtUtc: " + spec.intervalId());
                }
                if (newEnd >= target.endAtUtc()) {
                    throw unprocessable("newEndAtUtc must be earlier than current end: "
                            + spec.intervalId());
                }
                preparedTerminates.add(new PreparedTerminate(target, false, newEnd));
            } else {
                throw unprocessable("unsupported terminate action: " + spec.action());
            }
        }

        // 组装完整最终 ACTIVE 区间集合并做同访客重叠检测
        java.util.Set<String> deleteIds = new java.util.HashSet<>();
        Map<String, Long> earlyEndById = new LinkedHashMap<>();
        for (PreparedTerminate prepared : preparedTerminates) {
            if (prepared.delete) {
                deleteIds.add(prepared.target.intervalId());
            } else {
                earlyEndById.put(prepared.target.intervalId(), prepared.newEndUtc);
            }
        }
        List<FinalInterval> finalIntervals = new ArrayList<>();
        for (SuppressionInterval interval : working.values()) {
            if (interval.status() != SuppressionIntervalStatus.ACTIVE
                    || deleteIds.contains(interval.intervalId())) {
                continue;
            }
            long end = earlyEndById.getOrDefault(interval.intervalId(), interval.endAtUtc());
            finalIntervals.add(new FinalInterval(interval.intervalId(),
                    interval.visitorId(), interval.startAtUtc(), end));
        }
        for (PreparedAdd add : preparedAdds) {
            finalIntervals.add(new FinalInterval(
                    "(new)", add.visitorId(), add.startAtUtc(), add.endAtUtc));
        }
        assertNoOverlap(finalIntervals);

        // 第二阶段：全部合法，按固定顺序落库（区间变更/删除记录 -> 新增 -> 版本推进）
        for (PreparedTerminate prepared : preparedTerminates) {
            SuppressionInterval target = prepared.target;
            if (prepared.delete) {
                if (!suppressionRepository.markDeleted(target.intervalId(), now)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "interval state changed concurrently: " + target.intervalId());
                }
                suppressionRepository.insertDeleteRecord(new SuppressionDeleteRecord(
                        newId(),
                        target.intervalId(),
                        campaignId,
                        target.visitorId(),
                        target.startAtUtc(),
                        target.originalEndAtUtc(),
                        now,
                        request.requestId()));
            } else {
                if (!suppressionRepository.shortenEnd(
                        target.intervalId(), prepared.newEndUtc, now)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "interval state changed concurrently: " + target.intervalId());
                }
            }
        }
        for (PreparedAdd add : preparedAdds) {
            suppressionRepository.insertInterval(new SuppressionInterval(
                    newId(),
                    campaignId,
                    add.visitorId(),
                    add.startAtUtc(),
                    add.endAtUtc(),
                    add.endAtUtc(),
                    SuppressionIntervalStatus.ACTIVE,
                    now, now, null, null));
        }
        int newVersion = campaign.version() + 1;
        if (!campaignRepository.compareAndSetVersion(campaignId, campaign.version(), newVersion)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "campaign version changed concurrently: " + campaignId);
        }

        List<SuppressionIntervalResponse> active =
                suppressionRepository.findActiveByCampaign(campaignId).stream()
                        .map(SuppressionIntervalResponse::from)
                        .toList();
        return new SuppressionBatchUpdateResponse(campaignId, newVersion, active);
    }

    /**
     * 以公告行锁为串行化屏障的幂等执行模板：
     * 先查幂等键，再锁公告行；持锁后重新复查幂等键（同键败者在此重放胜者结果），
     * 不存在才执行业务并写入幂等记录。
     */
    private <T> T runIdempotentOnCampaign(String requestId, Operation operation, String campaignId,
                                          Class<T> responseType,
                                          Function<Campaign, String> fingerprintFn,
                                          Function<Campaign, T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    IdempotencyRecord existing = idempotencyRepository.lockById(requestId).orElse(null);
                    // 公告行锁：名单变更/曝光申请的共同串行化屏障
                    Campaign campaign = lockCampaign(campaignId);
                    // 屏障放行后胜者可能已提交：重新复查幂等键
                    IdempotencyRecord record = existing != null
                            ? existing : idempotencyRepository.findById(requestId).orElse(null);
                    String fingerprint = fingerprintFn.apply(campaign);
                    if (record != null) {
                        if (!record.operation().equals(operation.name())
                                || !record.requestFingerprint().equals(fingerprint)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "idempotency key reused with different parameters: " + requestId);
                        }
                        try {
                            return objectMapper.readValue(record.responseJson(), responseType);
                        } catch (Exception e) {
                            throw new IllegalStateException("failed to replay idempotent response", e);
                        }
                    }
                    T result = action.apply(campaign);
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), fingerprint, writeJson(result)), clock.millis());
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发且未在屏障后读到胜者记录（胜者恰在其后提交）：等待后整事务重试
                if (System.currentTimeMillis() >= deadline) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "concurrent idempotency key conflict: " + requestId);
                }
                sleepBriefly();
            }
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
        }
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private Campaign lockCampaign(String campaignId) {
        return campaignRepository.lockById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private void advanceVersion(Campaign campaign) {
        if (!campaignRepository.compareAndSetVersion(
                campaign.campaignId(), campaign.version(), campaign.version() + 1)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "campaign version changed concurrently: " + campaign.campaignId());
        }
    }

    private void requireValidRange(long startAtUtc, long endAtUtc) {
        if (startAtUtc >= endAtUtc) {
            throw unprocessable("invalid UTC half-open interval: startAtUtc (" + startAtUtc
                    + ") must be earlier than endAtUtc (" + endAtUtc + ")");
        }
    }

    private ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 半开区间相交判定：相邻（一端结束等于另一端开始）不算重叠。 */
    private boolean overlaps(long start1, long end1, long start2, long end2) {
        return start1 < end2 && start2 < end1;
    }

    /**
     * 对最终区间集合按访客分组、按开始时刻排序，逐条与同访客前一区间比较；
     * prev.end &gt; cur.start 即重叠（相等为相邻，合法）。
     */
    private void assertNoOverlap(List<FinalInterval> intervals) {
        Map<String, List<FinalInterval>> byVisitor = new LinkedHashMap<>();
        for (FinalInterval interval : intervals) {
            byVisitor.computeIfAbsent(interval.visitorId(), k -> new ArrayList<>()).add(interval);
        }
        for (Map.Entry<String, List<FinalInterval>> entry : byVisitor.entrySet()) {
            List<FinalInterval> list = entry.getValue();
            list.sort(Comparator.comparingLong(FinalInterval::startAtUtc)
                    .thenComparing(FinalInterval::intervalId));
            for (int i = 1; i < list.size(); i++) {
                FinalInterval prev = list.get(i - 1);
                FinalInterval cur = list.get(i);
                if (prev.endAtUtc() > cur.startAtUtc()) {
                    throw unprocessable("overlapping suppression intervals for visitor " + entry.getKey()
                            + ": " + prev.intervalId() + " and " + cur.intervalId());
                }
            }
        }
    }

    private String canonicalAdds(List<SuppressionAddSpec> adds) {
        StringBuilder sb = new StringBuilder();
        for (SuppressionAddSpec add : adds) {
            sb.append(add.visitorId()).append(':')
                    .append(add.startAtUtc()).append(':')
                    .append(add.endAtUtc()).append(';');
        }
        return sb.toString();
    }

    private String canonicalTerminates(List<SuppressionTerminateSpec> terminates) {
        StringBuilder sb = new StringBuilder();
        for (SuppressionTerminateSpec spec : terminates) {
            sb.append(spec.intervalId()).append(':')
                    .append(spec.action()).append(':')
                    .append(spec.newEndAtUtc() == null ? "" : spec.newEndAtUtc()).append(';');
        }
        return sb.toString();
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
