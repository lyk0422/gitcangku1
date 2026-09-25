package com.example.starter.batch;

import com.example.starter.batch.dto.ExcursionClosureView;
import com.example.starter.batch.dto.ReadingResponse;
import com.example.starter.batch.dto.ReadingView;
import com.example.starter.batch.dto.RegisterTransportSegmentRequest;
import com.example.starter.batch.dto.ReleaseTemperatureHoldRequest;
import com.example.starter.batch.dto.TemperatureHoldReleaseResponse;
import com.example.starter.batch.dto.TemperatureHoldStatusView;
import com.example.starter.batch.dto.TransportSegmentResponse;
import com.example.starter.batch.dto.TransportSegmentView;
import com.example.starter.batch.dto.UploadReadingRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次运输温控服务。
 *
 * <p>运输段左闭右开 [startAt, endAt)，同一批次不得重叠；读数必须落在段内且采集时刻严格递增，
 * 违反 422。每次上传读数后实时评估：存在越界读数或相邻读数间隔超过 30 分钟，
 * 运输段转 EXCURSION（终态，段与读数历史不可改写），并为批次建立温控冻结
 * （对外呈现 TEMPERATURE_HOLD）。冻结期间禁止到货放行、拆分与继续移交（409），
 * 检验不受影响。解除须由质量角色且不同于任一运输录入人的操作人提交调查说明，
 * 并在同一事务内逐段处置全部 EXCURSION 段；解除只移除运输门禁，不改写异常历史。
 *
 * <p>并发策略：运输段登记、读数上传与冻结解除均在事务内对 batch 行 SELECT ... FOR UPDATE
 * 串行化，按事务提交顺序裁决；commandKey 幂等复用 {@link CommandIdempotency}。
 */
@Service
public class TransportService {

    private static final String CMD_SEGMENT = "REGISTER_SEGMENT";
    private static final String CMD_READING = "UPLOAD_READING";
    private static final String CMD_RELEASE = "RELEASE_TEMP_HOLD";

    private static final String SEGMENT_NORMAL = "NORMAL";
    private static final String SEGMENT_EXCURSION = "EXCURSION";

    /**
     * 相邻读数允许的最大间隔：超过 30 分钟即异常。
     */
    private static final Duration MAX_READING_GAP = Duration.ofMinutes(30);

    private final BatchRepository batchRepo;
    private final TransportRepository transportRepo;
    private final CommandIdempotency idempotency;
    private final ObjectMapper objectMapper;

    public TransportService(BatchRepository batchRepo,
                            TransportRepository transportRepo,
                            CommandIdempotency idempotency,
                            ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.transportRepo = transportRepo;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记运输段：左闭右开且同批次不重叠（违反 422）；温控冻结期间禁止继续移交（409）。
     * X-Actor-Id 为运输录入人，登记后不可修改。
     */
    public StoredResponse registerSegment(String batchKey, String actorId,
                                          RegisterTransportSegmentRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        if (!req.startAt().isBefore(req.endAt())) {
            throw ApiException.unprocessable("startAt 必须早于 endAt（运输段左闭右开）");
        }
        if (req.minTemp().compareTo(req.maxTemp()) > 0) {
            throw ApiException.unprocessable("minTemp 不得高于 maxTemp");
        }
        String fingerprint = CommandIdempotency.fingerprint("segment", batchKey, actor,
                req.segmentKey(), req.startAt().toString(), req.endAt().toString(),
                req.minTemp().toPlainString(), req.maxTemp().toPlainString());
        return idempotency.execute(CMD_SEGMENT, req.commandKey(), fingerprint, () -> {
            batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotency.logged(CMD_SEGMENT, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (transportRepo.findActiveHold(batchKey).isPresent()) {
                throw ApiException.conflict("批次处于温控冻结（TEMPERATURE_HOLD），禁止继续移交");
            }
            if (transportRepo.findSegment(batchKey, req.segmentKey()).isPresent()) {
                throw ApiException.conflict("segmentKey 已存在: " + req.segmentKey());
            }
            for (TransportRepository.SegmentRow existing : transportRepo.findSegments(batchKey)) {
                boolean overlaps = req.startAt().isBefore(Instant.parse(existing.endAt()))
                        && Instant.parse(existing.startAt()).isBefore(req.endAt());
                if (overlaps) {
                    throw ApiException.unprocessable(
                            "运输段与既有段 " + existing.segmentKey() + " 时间重叠");
                }
            }
            String now = now();
            transportRepo.insertSegment(new TransportRepository.SegmentRow(0L, batchKey,
                    req.segmentKey(), req.startAt().toString(), req.endAt().toString(),
                    req.minTemp().toPlainString(), req.maxTemp().toPlainString(), actor,
                    SEGMENT_NORMAL, now));
            TransportSegmentResponse body = new TransportSegmentResponse(batchKey, req.segmentKey(),
                    req.startAt(), req.endAt(), req.minTemp(), req.maxTemp(), actor,
                    SEGMENT_NORMAL, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 上传温度读数：必须落在段内且采集时刻严格递增（违反 422）；异常段不可改写（409）。
     * 越界或与上一读数间隔超过 30 分钟时，段转 EXCURSION 并冻结批次。
     */
    public StoredResponse uploadReading(String batchKey, String segmentKey,
                                        UploadReadingRequest req) {
        String fingerprint = CommandIdempotency.fingerprint("reading", batchKey, segmentKey,
                req.recordedAt().toString(), req.temperature().toPlainString());
        return idempotency.execute(CMD_READING, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = idempotency.logged(CMD_READING, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            TransportRepository.SegmentRow segment = transportRepo.findSegment(batchKey, segmentKey)
                    .orElseThrow(() -> ApiException.notFound(
                            "运输段不存在: " + batchKey + "/" + segmentKey));
            if (SEGMENT_EXCURSION.equals(segment.status())) {
                throw ApiException.conflict("异常运输段与读数历史不可改写: " + segmentKey);
            }
            Instant start = Instant.parse(segment.startAt());
            Instant end = Instant.parse(segment.endAt());
            if (req.recordedAt().isBefore(start) || !req.recordedAt().isBefore(end)) {
                throw ApiException.unprocessable("读数时刻不在运输段 [startAt, endAt) 内: "
                        + req.recordedAt());
            }
            List<TransportRepository.ReadingRow> readings =
                    transportRepo.findReadings(batchKey, segmentKey);
            boolean gapBreach = false;
            if (!readings.isEmpty()) {
                Instant last = Instant.parse(readings.get(readings.size() - 1).recordedAt());
                if (!req.recordedAt().isAfter(last)) {
                    throw ApiException.unprocessable("读数时刻必须严格递增，上一读数时刻: " + last);
                }
                gapBreach = Duration.between(last, req.recordedAt()).compareTo(MAX_READING_GAP) > 0;
            }
            BigDecimal minTemp = new BigDecimal(segment.minTemp());
            BigDecimal maxTemp = new BigDecimal(segment.maxTemp());
            boolean inRange = req.temperature().compareTo(minTemp) >= 0
                    && req.temperature().compareTo(maxTemp) <= 0;

            String now = now();
            transportRepo.insertReading(new TransportRepository.ReadingRow(0L, batchKey, segmentKey,
                    req.recordedAt().toString(), req.temperature().toPlainString(), inRange, now));

            String segmentStatus = SEGMENT_NORMAL;
            if (!inRange || gapBreach) {
                transportRepo.markSegmentExcursion(batchKey, segmentKey);
                segmentStatus = SEGMENT_EXCURSION;
                if (transportRepo.findActiveHold(batchKey).isEmpty()) {
                    transportRepo.insertHold(new TransportRepository.HoldRow(0L, batchKey,
                            batch.status(), now, null, null, null));
                }
            }
            ReadingResponse body = new ReadingResponse(batchKey, segmentKey, req.recordedAt(),
                    req.temperature(), inRange, segmentStatus,
                    effectiveStatus(batchKey, batch.status()), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 解除温控冻结：须质量角色（X-Approval-Role: QUALITY）且不同于任一运输录入人；
     * 同一事务内逐段处置全部 EXCURSION 段，任一段未处置 422 且整次不解除。
     * 解除只移除运输门禁，不改写异常段状态与读数历史。
     */
    public StoredResponse releaseHold(String batchKey, String actorId, String roleHeader,
                                      ReleaseTemperatureHoldRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        if (roleHeader == null || roleHeader.isBlank()) {
            throw ApiException.badRequest("X-Approval-Role 不能为空");
        }
        ApprovalRole role;
        try {
            role = ApprovalRole.valueOf(roleHeader.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("X-Approval-Role 必须为 QUALITY 或 OPERATIONS");
        }
        if (role != ApprovalRole.QUALITY) {
            throw ApiException.unprocessable("解除温控冻结须由质量角色（QUALITY）提交");
        }
        List<String> dispositionKeys = req.dispositions().stream()
                .map(ReleaseTemperatureHoldRequest.SegmentDisposition::segmentKey).toList();
        if (new HashSet<>(dispositionKeys).size() != dispositionKeys.size()) {
            throw ApiException.badRequest("dispositions 内 segmentKey 重复");
        }
        List<String> parts = new ArrayList<>();
        parts.add("release-hold");
        parts.add(batchKey);
        parts.add(actor);
        parts.add(req.investigationNote());
        for (ReleaseTemperatureHoldRequest.SegmentDisposition d : req.dispositions()) {
            parts.add(d.segmentKey());
            parts.add(d.disposition());
        }
        String fingerprint = CommandIdempotency.fingerprint(parts.toArray(new String[0]));
        return idempotency.execute(CMD_RELEASE, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = idempotency.logged(CMD_RELEASE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            TransportRepository.HoldRow hold = transportRepo.findActiveHold(batchKey)
                    .orElseThrow(() -> ApiException.conflict("批次未处于温控冻结"));
            List<TransportRepository.SegmentRow> segments = transportRepo.findSegments(batchKey);
            Set<String> recorders = new HashSet<>();
            for (TransportRepository.SegmentRow s : segments) {
                recorders.add(s.recordedBy());
            }
            if (recorders.contains(actor)) {
                throw ApiException.unprocessable("解除人不得为任一运输段录入人: " + actor);
            }
            Map<String, String> dispositionBySegment = new LinkedHashMap<>();
            for (ReleaseTemperatureHoldRequest.SegmentDisposition d : req.dispositions()) {
                dispositionBySegment.put(d.segmentKey(), d.disposition());
            }
            List<TransportRepository.SegmentRow> excursions = segments.stream()
                    .filter(s -> SEGMENT_EXCURSION.equals(s.status()))
                    .toList();
            for (TransportRepository.SegmentRow e : excursions) {
                if (!dispositionBySegment.containsKey(e.segmentKey())) {
                    throw ApiException.unprocessable(
                            "EXCURSION 段未逐段处置: " + e.segmentKey());
                }
            }
            for (String key : dispositionBySegment.keySet()) {
                boolean isExcursion = excursions.stream()
                        .anyMatch(e -> e.segmentKey().equals(key));
                if (!isExcursion) {
                    throw ApiException.unprocessable("处置目标不是当前 EXCURSION 段: " + key);
                }
            }
            String now = now();
            List<String> disposedKeys = new ArrayList<>(excursions.size());
            for (TransportRepository.SegmentRow e : excursions) {
                transportRepo.insertDisposition(new TransportRepository.DispositionRow(0L, batchKey,
                        e.segmentKey(), dispositionBySegment.get(e.segmentKey()), actor, now));
                disposedKeys.add(e.segmentKey());
            }
            transportRepo.releaseHold(hold.id(), now, actor, req.investigationNote());
            TemperatureHoldReleaseResponse body = new TemperatureHoldReleaseResponse(batchKey,
                    batch.status(), actor, req.investigationNote(), disposedKeys,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 运输段查询：段定义、状态与全部读数（按采集时刻升序），历史不可改写。
     */
    public List<TransportSegmentView> listSegments(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<TransportSegmentView> result = new ArrayList<>();
        for (TransportRepository.SegmentRow s : transportRepo.findSegments(batchKey)) {
            List<ReadingView> readings = transportRepo.findReadings(batchKey, s.segmentKey())
                    .stream()
                    .map(r -> new ReadingView(Instant.parse(r.recordedAt()),
                            new BigDecimal(r.temperature()), r.inRange(),
                            Instant.parse(r.createdAt())))
                    .toList();
            result.add(new TransportSegmentView(s.segmentKey(), Instant.parse(s.startAt()),
                    Instant.parse(s.endAt()), new BigDecimal(s.minTemp()),
                    new BigDecimal(s.maxTemp()), s.recordedBy(), s.status(),
                    Instant.parse(s.createdAt()), readings));
        }
        return result;
    }

    /**
     * 异常闭包查询：每个 EXCURSION 段的异常原因（越界读数/超 30 分钟间隔）、
     * 异常明细与逐段处置结果；未处置时 disposition 为 null。
     */
    public List<ExcursionClosureView> listExcursions(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Map<String, TransportRepository.DispositionRow> dispositionBySegment = new LinkedHashMap<>();
        for (TransportRepository.DispositionRow d : transportRepo.findDispositions(batchKey)) {
            dispositionBySegment.put(d.segmentKey(), d);
        }
        List<ExcursionClosureView> result = new ArrayList<>();
        for (TransportRepository.SegmentRow s : transportRepo.findSegments(batchKey)) {
            if (!SEGMENT_EXCURSION.equals(s.status())) {
                continue;
            }
            List<TransportRepository.ReadingRow> readings =
                    transportRepo.findReadings(batchKey, s.segmentKey());
            List<ExcursionClosureView.OutOfRangeReading> outOfRange = new ArrayList<>();
            List<ExcursionClosureView.GapBreach> gaps = new ArrayList<>();
            for (TransportRepository.ReadingRow r : readings) {
                if (!r.inRange()) {
                    outOfRange.add(new ExcursionClosureView.OutOfRangeReading(
                            Instant.parse(r.recordedAt()), new BigDecimal(r.temperature())));
                }
            }
            for (int i = 1; i < readings.size(); i++) {
                Instant prev = Instant.parse(readings.get(i - 1).recordedAt());
                Instant next = Instant.parse(readings.get(i).recordedAt());
                Duration gap = Duration.between(prev, next);
                if (gap.compareTo(MAX_READING_GAP) > 0) {
                    gaps.add(new ExcursionClosureView.GapBreach(prev, next, gap.toMinutes()));
                }
            }
            List<String> reasons = new ArrayList<>();
            if (!outOfRange.isEmpty()) {
                reasons.add("OUT_OF_RANGE");
            }
            if (!gaps.isEmpty()) {
                reasons.add("READING_GAP_EXCEEDED");
            }
            TransportRepository.DispositionRow d = dispositionBySegment.get(s.segmentKey());
            ExcursionClosureView.DispositionView disposition = d == null ? null
                    : new ExcursionClosureView.DispositionView(d.disposition(), d.actorId(),
                            Instant.parse(d.createdAt()));
            result.add(new ExcursionClosureView(s.segmentKey(), Instant.parse(s.startAt()),
                    Instant.parse(s.endAt()), new BigDecimal(s.minTemp()),
                    new BigDecimal(s.maxTemp()), reasons, outOfRange, gaps, disposition));
        }
        return result;
    }

    /**
     * 温控冻结状态查询：held 表示当前是否处于 TEMPERATURE_HOLD；
     * 已解除时返回最近一次解除信息；从未冻结时冻结字段均为 null。
     */
    public TemperatureHoldStatusView holdStatus(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<TransportRepository.HoldRow> holds = transportRepo.findHolds(batchKey);
        TransportRepository.HoldRow latest = holds.isEmpty() ? null : holds.get(holds.size() - 1);
        boolean held = latest != null && latest.releasedAt() == null;
        return new TemperatureHoldStatusView(batchKey, held,
                effectiveStatus(batchKey, batch.status()),
                latest == null ? null : latest.preStatus(),
                latest == null ? null : Instant.parse(latest.createdAt()),
                latest == null || latest.releasedAt() == null ? null : Instant.parse(latest.releasedAt()),
                latest == null ? null : latest.releaseActor(),
                latest == null ? null : latest.releaseNote());
    }

    /**
     * 批次对外呈现状态：存在未解除的温控冻结时为 TEMPERATURE_HOLD，否则为底层状态。
     */
    private String effectiveStatus(String batchKey, String storedStatus) {
        return transportRepo.findActiveHold(batchKey).isPresent()
                ? BatchStatus.TEMPERATURE_HOLD.name()
                : storedStatus;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
    }
}
