package com.example.starter.service;

import com.example.starter.domain.DirectiveStatus;
import com.example.starter.repository.DirectiveRepository;
import com.example.starter.repository.Rows.DifferenceRow;
import com.example.starter.repository.Rows.DirectiveRow;
import com.example.starter.repository.Rows.FrozenSampleRow;
import com.example.starter.repository.Rows.ReconRow;
import com.example.starter.repository.Rows.SampleRow;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.CreateDirectiveRequest;
import com.example.starter.web.dto.Requests.ReadingRequest;
import com.example.starter.web.dto.Requests.ReconcileRequest;
import com.example.starter.web.dto.Responses.DifferenceResponse;
import com.example.starter.web.dto.Responses.DirectiveResponse;
import com.example.starter.web.dto.Responses.FrozenSampleView;
import com.example.starter.web.dto.Responses.ReadingResponse;
import com.example.starter.web.dto.Responses.ReconciliationResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 限发领域服务：指令生命周期、采样上报与迟到更正、对账与差额链。
 * 所有写操作在事务内先锁定指令行，保证并发下快照一致、不重复累计电量。
 */
@Service
public class DirectiveService {

    private static final Duration TICK = Duration.ofMinutes(15);
    private static final BigDecimal KWH_PER_TICK = new BigDecimal("0.25");

    private final DirectiveRepository repository;
    private final Clock clock;

    public DirectiveService(DirectiveRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 创建限发指令；同机组未撤销指令区间重叠时返回 409。 */
    @Transactional
    public DirectiveResponse create(CreateDirectiveRequest request) {
        if (!request.endUtc().isAfter(request.startUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "区间终点必须晚于起点");
        }
        if (Duration.between(request.startUtc(), request.endUtc()).toMillis() % TICK.toMillis() != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "区间长度必须是 15 分钟的整数倍");
        }
        if (!repository.lockUnit(request.unitId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "机组不存在: " + request.unitId());
        }
        if (repository.countOverlapping(request.unitId(), request.startUtc(), request.endUtc()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "同机组已存在重叠的有效指令区间");
        }
        long id = repository.insertDirective(request.unitId(), request.startUtc(), request.endUtc(),
                request.maxPowerKw(), clock.instant());
        return toResponse(repository.findDirective(id).orElseThrow());
    }

    /** 确认指令：仅允许在区间开始前确认；requestId 同参重放、异参 409；与撤销竞争只落一种终态。 */
    @Transactional
    public DirectiveResponse confirm(long id, ConfirmRequest request) {
        DirectiveRow row = lockDirective(id);
        DirectiveStatus status = DirectiveStatus.valueOf(row.status());
        if (status == DirectiveStatus.CONFIRMED || status == DirectiveStatus.ENDED) {
            if (request.requestId().equals(row.confirmRequestId())) {
                if (!request.confirmedBy().equals(row.confirmedBy())) {
                    throw new ApiException(HttpStatus.CONFLICT, "requestId 已使用且参数不一致");
                }
                return toResponse(row);
            }
            throw new ApiException(HttpStatus.CONFLICT, "指令已确认");
        }
        if (status == DirectiveStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, "指令已撤销，不能确认");
        }
        Instant now = clock.instant();
        if (!now.isBefore(row.startUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "确认必须早于区间起点");
        }
        repository.confirmDirective(id, request.requestId(), request.confirmedBy(), now);
        return toResponse(repository.findDirective(id).orElseThrow());
    }

    /** 撤销指令：仅 ISSUED 可撤销；与确认竞争只落一种终态；已撤销重复调用返回当前状态。 */
    @Transactional
    public DirectiveResponse cancel(long id) {
        DirectiveRow row = lockDirective(id);
        DirectiveStatus status = DirectiveStatus.valueOf(row.status());
        if (status == DirectiveStatus.CANCELLED) {
            return toResponse(row);
        }
        if (status != DirectiveStatus.ISSUED) {
            throw new ApiException(HttpStatus.CONFLICT, "指令已" + (status == DirectiveStatus.CONFIRMED ? "确认" : "结束") + "，不能撤销");
        }
        repository.cancelDirective(id);
        return toResponse(repository.findDirective(id).orElseThrow());
    }

    /** 上报采样：刻度须在区间内且 15 分钟对齐；同刻度重复上报为迟到更正，追加新版本。 */
    @Transactional
    public ReadingResponse reportReading(long id, ReadingRequest request) {
        DirectiveRow row = lockDirective(id);
        DirectiveStatus status = DirectiveStatus.valueOf(row.status());
        if (status == DirectiveStatus.ISSUED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "指令未确认，不能上报采样");
        }
        if (status == DirectiveStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, "指令已撤销，不能上报采样");
        }
        Instant tick = request.tickUtc();
        if (tick.isBefore(row.startUtc()) || !tick.isBefore(row.endUtc())
                || Duration.between(row.startUtc(), tick).toMillis() % TICK.toMillis() != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "采样刻度不在指令区间内或未按 15 分钟对齐");
        }
        int version = repository.latestSampleVersion(id, tick).orElse(0) + 1;
        repository.insertSample(id, tick, version, request.avgPowerKw(), clock.instant());
        return new ReadingResponse(tick, version, request.avgPowerKw(), clock.instant());
    }

    /** 对账：校验失败整单拒绝并返回问题刻度；固化指令版本与全部刻度；重复对账不重复累计。 */
    @Transactional
    public ReconciliationResponse reconcile(long id, ReconcileRequest request) {
        DirectiveRow row = lockDirective(id);
        var existing = repository.findReconByRequestId(request.requestId());
        if (existing.isPresent()) {
            ReconRow done = existing.get();
            if (!done.auditor().equals(request.auditor()) || done.directiveId() != id) {
                throw new ApiException(HttpStatus.CONFLICT, "requestId 已使用且参数不一致");
            }
            return toResponse(done);
        }
        DirectiveStatus status = DirectiveStatus.valueOf(row.status());
        if (status == DirectiveStatus.ISSUED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "指令未确认，不能对账",
                    List.of(new ReconcileIssue(null, "NOT_CONFIRMED", "指令未确认")));
        }
        if (status == DirectiveStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, "指令已撤销，不能对账");
        }
        if (clock.instant().isBefore(row.endUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "限发区间尚未结束，不能对账",
                    List.of(new ReconcileIssue(null, "WINDOW_NOT_FINISHED", "区间未结束")));
        }
        Map<Instant, SampleRow> latest = new LinkedHashMap<>();
        repository.latestSamples(id).forEach(s -> latest.put(s.tickUtc(), s));
        List<ReconcileIssue> issues = new ArrayList<>();
        List<SampleRow> ordered = new ArrayList<>();
        for (Instant tick = row.startUtc(); tick.isBefore(row.endUtc()); tick = tick.plus(TICK)) {
            SampleRow sample = latest.get(tick);
            if (sample == null) {
                issues.add(new ReconcileIssue(tick, "MISSING_SAMPLE", "缺少该刻度采样"));
            } else {
                ordered.add(sample);
                if (sample.avgPowerKw() > row.maxPowerKw()) {
                    issues.add(new ReconcileIssue(tick, "EXCEEDS_CAP",
                            "平均功率 " + sample.avgPowerKw() + " kW 超过上限 " + row.maxPowerKw() + " kW"));
                }
            }
        }
        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "对账校验失败", issues);
        }
        var previous = repository.latestRecon(id);
        if (previous.isPresent() && sameSnapshot(repository.frozenSamples(previous.get().id()), ordered)) {
            return toResponse(previous.get());
        }
        BigDecimal total = ordered.stream()
                .map(s -> energyOf(s.avgPowerKw()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int version = previous.map(r -> r.reconVersion() + 1).orElse(1);
        Instant now = clock.instant();
        long reconId = repository.insertRecon(id, version, row.directiveVersion(),
                request.requestId(), request.auditor(), total, now);
        for (SampleRow sample : ordered) {
            repository.insertReconSample(reconId, id, sample.tickUtc(), sample.sampleVersion(),
                    sample.avgPowerKw(), energyOf(sample.avgPowerKw()));
        }
        previous.ifPresent(prev -> repository.insertDifference(id, reconId, prev.id(),
                total.subtract(prev.totalEnergyKwh()), now));
        repository.markEnded(id);
        return toResponse(repository.findReconByRequestId(request.requestId()).orElseThrow());
    }

    /** 查询指令详情。 */
    @Transactional(readOnly = true)
    public DirectiveResponse getDirective(long id) {
        return toResponse(repository.findDirective(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "指令不存在: " + id)));
    }

    /** 查询指令下每刻度最新采样。 */
    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(long id) {
        getDirective(id);
        return repository.latestSamples(id).stream()
                .map(s -> new ReadingResponse(s.tickUtc(), s.sampleVersion(), s.avgPowerKw(), s.reportedUtc()))
                .toList();
    }

    /** 查询指令全部对账版本（含固化采样）。 */
    @Transactional(readOnly = true)
    public List<ReconciliationResponse> listReconciliations(long id) {
        getDirective(id);
        return repository.reconsByDirective(id).stream().map(this::toResponse).toList();
    }

    /** 查询指令差额链。 */
    @Transactional(readOnly = true)
    public List<DifferenceResponse> listDifferences(long id) {
        getDirective(id);
        return repository.differencesByDirective(id).stream()
                .map(d -> new DifferenceResponse(d.id(), d.reconciliationId(), d.previousReconciliationId(),
                        d.deltaKwh(), d.createdUtc()))
                .toList();
    }

    private DirectiveRow lockDirective(long id) {
        return repository.lockDirective(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "指令不存在: " + id));
    }

    private static BigDecimal energyOf(int avgPowerKw) {
        return BigDecimal.valueOf(avgPowerKw).multiply(KWH_PER_TICK);
    }

    private static boolean sameSnapshot(List<FrozenSampleRow> frozen, List<SampleRow> current) {
        if (frozen.size() != current.size()) {
            return false;
        }
        Map<Instant, FrozenSampleRow> byTick = new LinkedHashMap<>();
        frozen.forEach(f -> byTick.put(f.tickUtc(), f));
        return current.stream().allMatch(s -> {
            FrozenSampleRow f = byTick.get(s.tickUtc());
            return f != null && f.sampleVersion() == s.sampleVersion() && f.avgPowerKw() == s.avgPowerKw();
        });
    }

    private ReconciliationResponse toResponse(ReconRow row) {
        List<FrozenSampleView> samples = repository.frozenSamples(row.id()).stream()
                .map(f -> new FrozenSampleView(f.tickUtc(), f.sampleVersion(), f.avgPowerKw(), f.energyKwh()))
                .toList();
        return new ReconciliationResponse(row.id(), row.directiveId(), row.reconVersion(), row.directiveVersion(),
                row.auditor(), row.totalEnergyKwh(), row.createdUtc(), samples);
    }

    private static DirectiveResponse toResponse(DirectiveRow row) {
        return new DirectiveResponse(row.id(), row.unitId(), row.startUtc(), row.endUtc(), row.maxPowerKw(),
                row.status(), row.directiveVersion(), row.confirmedBy(), row.confirmedUtc());
    }
}