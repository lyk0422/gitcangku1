package com.example.starter.pass.service;

import com.example.starter.pass.api.AllocateRequest;
import com.example.starter.pass.api.CancelRequest;
import com.example.starter.pass.api.CompleteRequest;
import com.example.starter.pass.api.PredictionUpsertRequest;
import com.example.starter.pass.api.PredictionView;
import com.example.starter.pass.api.ReassignRequest;
import com.example.starter.pass.api.SlotView;
import com.example.starter.pass.clock.TimeSource;
import com.example.starter.pass.domain.AllocationStatus;
import com.example.starter.pass.domain.BusinessRuleException;
import com.example.starter.pass.domain.RejectReason;
import com.example.starter.pass.domain.SlotRules;
import com.example.starter.pass.persist.IdempotentRequestPo;
import com.example.starter.pass.persist.IdempotentRequestRepository;
import com.example.starter.pass.persist.PassPredictionPo;
import com.example.starter.pass.persist.PredictionRepository;
import com.example.starter.pass.persist.SatellitePo;
import com.example.starter.pass.persist.SatelliteRepository;
import com.example.starter.pass.persist.SlotAllocationPo;
import com.example.starter.pass.persist.SlotAllocationRepository;
import com.example.starter.pass.persist.StationPo;
import com.example.starter.pass.persist.StationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 卫星过境预测、接收时隙分配与遥测确认的业务入口。
 *
 * <p>所有写操作在单事务内完成“加锁 → 校核 → 落库”，失败整体回滚，
 * 不允许留下部分占用或完成标记。锁顺序统一为：</p>
 * <ol>
 *     <li>分配：卫星行 → 幂等键 → 站点/天线行 → 插入时隙；</li>
 *     <li>预测修订：卫星行 → 该卫星 CONFIRMED 时隙行，与分配在卫星行锁上互斥，
 *     因此无法凭过期预测与修订竞争锁定天线；</li>
 *     <li>完成/取消：时隙行（完成再取幂等键），终态迁移用条件 UPDATE 保证唯一终态；</li>
 *     <li>重分配：站点/天线行 → 时隙行，与上述锁序一致以避免死锁环。</li>
 * </ol>
 */
@Service
public class PassService {

    /** 幂等记录中的分配操作标记。 */
    private static final String OP_ALLOCATE = "ALLOCATE";

    /** 幂等记录中的完成回报操作标记。 */
    private static final String OP_COMPLETE = "COMPLETE";

    private final StationRepository stationRepository;
    private final SatelliteRepository satelliteRepository;
    private final PredictionRepository predictionRepository;
    private final SlotAllocationRepository slotRepository;
    private final IdempotentRequestRepository idempotentRepository;
    private final TimeSource timeSource;
    private final TransactionTemplate transactionTemplate;

    public PassService(StationRepository stationRepository,
                       SatelliteRepository satelliteRepository,
                       PredictionRepository predictionRepository,
                       SlotAllocationRepository slotRepository,
                       IdempotentRequestRepository idempotentRepository,
                       TimeSource timeSource,
                       TransactionTemplate transactionTemplate) {
        this.stationRepository = stationRepository;
        this.satelliteRepository = satelliteRepository;
        this.predictionRepository = predictionRepository;
        this.slotRepository = slotRepository;
        this.idempotentRepository = idempotentRepository;
        this.timeSource = timeSource;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 提交预测；卫星已有有效版本时即“修订”：新增下一修订版本，旧版本失效，
     * 并把未开始（CONFIRMED）且不满足新窗口/仰角/容量的时隙转为待重新分配并释放天线；
     * 已完成接收保留旧预测证据不变。
     */
    public PredictionView upsertPrediction(PredictionUpsertRequest request) {
        validateWindow(request.visibleStart(), request.visibleEnd());
        return transactionTemplate.execute(status -> {
            Instant now = timeSource.now();
            SatellitePo satellite = satelliteRepository.lockByCodeOrCreate(request.satelliteCode(), now);

            int nextRevision = predictionRepository.findLatest(satellite.getCode())
                    .map(p -> p.getRevisionNo() + 1)
                    .orElse(1);
            predictionRepository.findActive(satellite.getCode())
                    .ifPresent(old -> predictionRepository.markSuperseded(old.getId()));

            PassPredictionPo po = new PassPredictionPo();
            po.setSatelliteCode(satellite.getCode());
            po.setRevisionNo(nextRevision);
            po.setVisibleStart(request.visibleStart());
            po.setVisibleEnd(request.visibleEnd());
            po.setMinElevationDeg(request.minElevationDeg());
            po.setPlannedBytes(request.plannedBytes());
            po.setSuperseded(false);
            po.setCreatedAt(now);
            long newId = predictionRepository.insert(po);

            // 仅未开始（CONFIRMED）的时隙需要重新校核；RECEIVED/CANCELLED 保持旧证据。
            List<SlotAllocationPo> confirmed =
                    slotRepository.lockBySatelliteAndStatus(satellite.getCode(),
                            AllocationStatus.CONFIRMED.name());
            for (SlotAllocationPo slot : confirmed) {
                Optional<RejectReason> mismatch = SlotRules.validate(
                        slot.getWindowStart(), slot.getWindowEnd(),
                        request.visibleStart(), request.visibleEnd(),
                        request.minElevationDeg(), slot.getStationMinElevationDeg(),
                        request.plannedBytes(), slot.getStationBytesPerMinute());
                if (mismatch.isPresent()) {
                    slotRepository.markPendingReassign(slot.getId(), mismatch.get().code(), now);
                } else {
                    slotRepository.refreshPredictionSnapshot(slot.getId(), newId, nextRevision,
                            request.minElevationDeg(), request.plannedBytes(), now);
                }
            }

            return PredictionView.from(predictionRepository.findById(newId).orElseThrow());
        });
    }

    /**
     * 申请接收时隙：同 requestId 同参重放返回首次结果，异参 409；
     * 依次校核窗口、仰角、容量、天线冲突，任一不满足整体拒绝、不占用部分时隙；
     * 通过后确认，固化预测版本与容量快照。
     */
    public SlotView allocate(AllocateRequest request) {
        validateWindow(request.windowStart(), request.windowEnd());
        String hash = allocatePayloadHash(request);
        // 并发下同 requestId 落败事务等待获胜事务结束：已提交则按重放处理，对方回滚则真实重试。
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return transactionTemplate.execute(status -> doAllocate(request, hash));
            } catch (DuplicateKeyException e) {
                last = e;
                IdempotentRequestPo record =
                        idempotentRepository.lockByRequestId(request.requestId()).orElse(null);
                if (record != null) {
                    return replayAllocate(request, hash, record);
                }
                // 记录仍不存在：获胜事务已回滚，循环重试本请求。
            }
        }
        throw last;
    }

    private SlotView replayAllocate(AllocateRequest request, String hash, IdempotentRequestPo record) {
        if (!OP_ALLOCATE.equals(record.getOperation()) || !record.getPayloadHash().equals(hash)) {
            throw new BusinessRuleException(RejectReason.REQUEST_PARAM_MISMATCH,
                    "requestId " + request.requestId() + " was already used with different parameters");
        }
        return SlotView.from(slotRepository.findById(record.getAllocationId())
                .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                        "allocation bound to requestId not found: " + request.requestId())));
    }

    private SlotView doAllocate(AllocateRequest request, String hash) {
        Instant now = timeSource.now();

        // 卫星行锁是本事务的第一把锁：与预测修订互斥，杜绝凭过期预测锁定天线，
        // 也保证并发的“修订 vs 确认”在同一行锁上公平竞争。
        SatellitePo satellite = satelliteRepository.lockByCodeOrCreate(request.satelliteCode(), now);

        // 幂等键在卫星锁内落库（随本事务回滚）：约束并发重复请求，allocation_id 落库后回填。
        idempotentRepository.tryInsert(request.requestId(), OP_ALLOCATE, hash, 0L, now);

        PassPredictionPo prediction = predictionRepository.findActive(satellite.getCode())
                .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                        "no active prediction for satellite " + satellite.getCode()));

        // 站点（天线）行锁：同天线分配在此串行，重叠查询不会漏看未提交占用。
        StationPo station = stationRepository.lockByCode(request.stationCode());
        if (station == null) {
            throw new BusinessRuleException(RejectReason.NOT_FOUND,
                    "station not found: " + request.stationCode());
        }

        Optional<RejectReason> violation = SlotRules.validate(
                request.windowStart(), request.windowEnd(),
                prediction.getVisibleStart(), prediction.getVisibleEnd(),
                prediction.getMinElevationDeg(), station.getMinElevationDeg(),
                prediction.getPlannedBytes(), station.getBytesPerMinute());
        violation.ifPresent(r -> {
            throw new BusinessRuleException(r, "allocation rejected: " + r.code());
        });

        List<SlotAllocationPo> overlaps = slotRepository.lockConfirmedOverlapping(
                station.getAntennaCode(), request.windowStart(), request.windowEnd(), null);
        if (!overlaps.isEmpty()) {
            throw new BusinessRuleException(RejectReason.ANTENNA_CONFLICT,
                    "antenna " + station.getAntennaCode() + " already booked in overlapping window");
        }

        SlotAllocationPo po = new SlotAllocationPo();
        po.setRequestId(request.requestId());
        po.setSatelliteCode(satellite.getCode());
        po.setStationId(station.getId());
        po.setStationCode(station.getCode());
        po.setAntennaCode(station.getAntennaCode());
        po.setPredictionId(prediction.getId());
        po.setPredictionRevisionNo(prediction.getRevisionNo());
        po.setWindowStart(request.windowStart());
        po.setWindowEnd(request.windowEnd());
        po.setPassMinElevationDeg(prediction.getMinElevationDeg());
        po.setPlannedBytes(prediction.getPlannedBytes());
        po.setStationMinElevationDeg(station.getMinElevationDeg());
        po.setStationBytesPerMinute(station.getBytesPerMinute());
        po.setStatus(AllocationStatus.CONFIRMED.name());
        po.setConfirmedAt(now);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        long id = slotRepository.insert(po);
        idempotentRepository.bindAllocation(request.requestId(), id);

        return SlotView.from(slotRepository.findById(id).orElseThrow());
    }

    /**
     * 接收完成回报：回报 requestId 独立幂等，同参重放返回首次结果，异参 409；
     * 实际字节不得为负或超过计划量；与取消竞争时由时隙行锁与条件 UPDATE 保证只有一个终态。
     */
    public SlotView complete(long slotId, CompleteRequest request) {
        String hash = completePayloadHash(slotId, request);
        return transactionTemplate.execute(status -> doComplete(slotId, request, hash));
    }

    private SlotView doComplete(long slotId, CompleteRequest request, String hash) {
        Instant now = timeSource.now();

        // 时隙行锁是本事务的第一把锁：与取消竞争同一终态时在此公平串行。
        SlotAllocationPo slot = slotRepository.lockById(slotId)
                .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                        "slot not found: " + slotId));

        // 同 requestId 重放：行锁内核对操作类型与参数摘要。
        IdempotentRequestPo existing = idempotentRepository.lockByRequestId(request.requestId())
                .orElse(null);
        if (existing != null) {
            return replayExisting(existing, slotId, hash, request.requestId());
        }

        AllocationStatus current = AllocationStatus.valueOf(slot.getStatus());
        return switch (current) {
            case CONFIRMED -> {
                SlotRules.checkActualBytes(request.actualBytes(), slot.getPlannedBytes())
                        .ifPresent(r -> {
                            throw new BusinessRuleException(r, "actual bytes rejected: " + r.code());
                        });
                int updated = slotRepository.markReceived(
                        slot.getId(), request.actualBytes(), now);
                if (updated == 0) {
                    // 持锁后仍可能读到并发已提交的终态：同参重放放行，其余按终态冲突拒绝。
                    IdempotentRequestPo racer = idempotentRepository
                            .findByRequestId(request.requestId()).orElse(null);
                    if (racer != null) {
                        yield replayExisting(racer, slotId, hash, request.requestId());
                    }
                    throw new BusinessRuleException(RejectReason.SLOT_ALREADY_TERMINATED,
                            "slot reached another terminal state concurrently: " + slotId);
                }
                idempotentRepository.tryInsert(request.requestId(), OP_COMPLETE, hash, slotId, now);
                yield SlotView.from(slotRepository.findById(slotId).orElseThrow());
            }
            case RECEIVED -> {
                // 并发下同 requestId 的回报在持锁后见到已接收：按同参重放处理。
                IdempotentRequestPo racer = idempotentRepository
                        .findByRequestId(request.requestId()).orElse(null);
                if (racer != null) {
                    yield replayExisting(racer, slotId, hash, request.requestId());
                }
                throw new BusinessRuleException(RejectReason.SLOT_ALREADY_TERMINATED,
                        "slot already received: " + slotId);
            }
            case CANCELLED -> throw new BusinessRuleException(RejectReason.SLOT_ALREADY_TERMINATED,
                    "slot was cancelled and cannot receive: " + slotId);
            case PENDING_REASSIGN -> throw new BusinessRuleException(RejectReason.SLOT_NOT_CONFIRMED,
                    "slot is pending reassign and cannot receive: " + slotId);
        };
    }

    private SlotView replayExisting(IdempotentRequestPo record, long slotId, String hash, String requestId) {
        if (!OP_COMPLETE.equals(record.getOperation()) || !record.getPayloadHash().equals(hash)
                || record.getAllocationId() != slotId) {
            throw new BusinessRuleException(RejectReason.REQUEST_PARAM_MISMATCH,
                    "completion requestId " + requestId + " was already used with different parameters");
        }
        return SlotView.from(slotRepository.findById(record.getAllocationId())
                .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                        "slot bound to completion requestId not found: " + requestId)));
    }

    /** 取消接收计划：仅 CONFIRMED 可取消，释放天线并记录明确取消原因。 */
    public SlotView cancel(long slotId, CancelRequest request) {
        return transactionTemplate.execute(status -> {
            SlotAllocationPo slot = slotRepository.lockById(slotId)
                    .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                            "slot not found: " + slotId));
            AllocationStatus current = AllocationStatus.valueOf(slot.getStatus());
            return switch (current) {
                case CONFIRMED -> {
                    int updated = slotRepository.markCancelled(slotId, request.reason(), timeSource.now());
                    if (updated == 0) {
                        throw new BusinessRuleException(RejectReason.SLOT_ALREADY_TERMINATED,
                                "slot reached another terminal state concurrently: " + slotId);
                    }
                    yield SlotView.from(slotRepository.findById(slotId).orElseThrow());
                }
                case RECEIVED, CANCELLED -> throw new BusinessRuleException(
                        RejectReason.SLOT_ALREADY_TERMINATED,
                        "slot already terminal (" + current + "): " + slotId);
                case PENDING_REASSIGN -> throw new BusinessRuleException(RejectReason.SLOT_NOT_CONFIRMED,
                        "slot is pending reassign: " + slotId);
            };
        });
    }

    /** 待重新分配时隙按卫星最新预测版本重新校核并确认到新站点/窗口。 */
    public SlotView reassign(long slotId, ReassignRequest request) {
        validateWindow(request.windowStart(), request.windowEnd());
        return transactionTemplate.execute(status -> {
            Instant now = timeSource.now();

            // 先读时隙（不加锁）确定卫星与现状；随后按“站点/天线 → 时隙”的统一顺序加锁，
            // 与分配、修订的锁序保持一致，避免与并发事务互锁。
            SlotAllocationPo existing = slotRepository.findById(slotId)
                    .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                            "slot not found: " + slotId));
            if (!AllocationStatus.PENDING_REASSIGN.name().equals(existing.getStatus())) {
                throw new BusinessRuleException(RejectReason.SLOT_NOT_PENDING,
                        "slot is not pending reassign (" + existing.getStatus() + "): " + slotId);
            }

            StationPo station = stationRepository.lockByCode(request.stationCode());
            if (station == null) {
                throw new BusinessRuleException(RejectReason.NOT_FOUND,
                        "station not found: " + request.stationCode());
            }
            // 持站点锁后再锁时隙行并复核状态：预测修订若同时处理该时隙会在此串行。
            SlotAllocationPo slot = slotRepository.lockById(slotId).orElseThrow();
            if (!AllocationStatus.PENDING_REASSIGN.name().equals(slot.getStatus())) {
                throw new BusinessRuleException(RejectReason.SLOT_NOT_PENDING,
                        "slot is not pending reassign (" + slot.getStatus() + "): " + slotId);
            }
            PassPredictionPo prediction = predictionRepository.findActive(slot.getSatelliteCode())
                    .orElseThrow(() -> new BusinessRuleException(RejectReason.STALE_PREDICTION,
                            "no active prediction for satellite " + slot.getSatelliteCode()));

            Optional<RejectReason> violation = SlotRules.validate(
                    request.windowStart(), request.windowEnd(),
                    prediction.getVisibleStart(), prediction.getVisibleEnd(),
                    prediction.getMinElevationDeg(), station.getMinElevationDeg(),
                    prediction.getPlannedBytes(), station.getBytesPerMinute());
            violation.ifPresent(r -> {
                throw new BusinessRuleException(r, "reassign rejected: " + r.code());
            });

            List<SlotAllocationPo> overlaps = slotRepository.lockConfirmedOverlapping(
                    station.getAntennaCode(), request.windowStart(), request.windowEnd(), slotId);
            if (!overlaps.isEmpty()) {
                throw new BusinessRuleException(RejectReason.ANTENNA_CONFLICT,
                        "antenna " + station.getAntennaCode() + " already booked in overlapping window");
            }

            int updated = slotRepository.confirmReassign(slotId, station.getId(), station.getCode(),
                    station.getAntennaCode(), prediction.getId(), prediction.getRevisionNo(),
                    prediction.getMinElevationDeg(), prediction.getPlannedBytes(),
                    station.getMinElevationDeg(), station.getBytesPerMinute(),
                    request.windowStart(), request.windowEnd(), now);
            if (updated == 0) {
                throw new BusinessRuleException(RejectReason.STALE_PREDICTION,
                        "slot changed before reassign committed: " + slotId);
            }
            return SlotView.from(slotRepository.findById(slotId).orElseThrow());
        });
    }

    /** 预测版本列表（版本时间线），修订号倒序。 */
    public List<PredictionView> getPredictionVersions(String satelliteCode) {
        return predictionRepository.findVersions(satelliteCode).stream()
                .map(PredictionView::from)
                .toList();
    }

    /** 某卫星的接收时隙时间线，按窗口起点排序。 */
    public List<SlotView> getTimeline(String satelliteCode) {
        return slotRepository.findBySatellite(satelliteCode).stream()
                .map(SlotView::from)
                .toList();
    }

    /** 按状态查询时隙（例如 PENDING_REASSIGN 待重排列表），非法状态码按 400 拒绝。 */
    public List<SlotView> getSlotsByStatus(String status) {
        try {
            AllocationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(RejectReason.INVALID_REQUEST, "unknown status: " + status);
        }
        return slotRepository.findByStatus(status).stream()
                .map(SlotView::from)
                .toList();
    }

    /** 按主键查时隙（接收实绩、取消原因等）。 */
    public SlotView getSlot(long id) {
        return SlotView.from(slotRepository.findById(id)
                .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                        "slot not found: " + id)));
    }

    /** 按分配 requestId 查时隙。 */
    public SlotView findSlotByRequestId(String requestId) {
        return SlotView.from(slotRepository.findByRequestId(requestId)
                .orElseThrow(() -> new BusinessRuleException(RejectReason.NOT_FOUND,
                        "slot not found for requestId: " + requestId)));
    }

    private static void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BusinessRuleException(RejectReason.WINDOW_GAP,
                    "window must be a non-empty half-open interval [start, end)");
        }
    }

    /**
     * 分配请求的规范化参数摘要；仅纳入业务参数，不含空格/格式歧义。
     */
    private static String allocatePayloadHash(AllocateRequest request) {
        String canonical = String.join("|",
                "ALLOCATE",
                request.requestId(),
                request.satelliteCode(),
                request.stationCode(),
                request.windowStart().toString(),
                request.windowEnd().toString());
        return sha256Hex(canonical);
    }

    /**
     * 完成回报的规范化参数摘要，绑定时隙主键与实际字节。
     */
    private static String completePayloadHash(long slotId, CompleteRequest request) {
        String canonical = String.join("|",
                "COMPLETE",
                Long.toString(slotId),
                Long.toString(request.actualBytes()));
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
