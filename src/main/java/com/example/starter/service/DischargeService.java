package com.example.starter.service;

import com.example.starter.domain.BatchRecord;
import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.CellRecord;
import com.example.starter.domain.CellStatus;
import com.example.starter.domain.ReadingRecord;
import com.example.starter.domain.ReadingStatus;
import com.example.starter.exception.BusinessException;
import com.example.starter.exception.ConflictException;
import com.example.starter.exception.NotFoundException;
import com.example.starter.repository.BatchRepository;
import com.example.starter.repository.CellRepository;
import com.example.starter.repository.IsolationRepository;
import com.example.starter.repository.ReadingRepository;
import com.example.starter.repository.RequestLogRepository;
import com.example.starter.repository.SnapshotRepository;
import com.example.starter.service.view.BatchDetailView;
import com.example.starter.service.view.CellDetailView;
import com.example.starter.service.view.CellView;
import com.example.starter.service.view.HandoverView;
import com.example.starter.service.view.IsolationView;
import com.example.starter.service.view.ReadingView;
import com.example.starter.service.view.SnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电芯放电交接业务服务：入批、放电读数、异常隔离、交接签收与查询。
 * 终测合格阈值：电压不超过 0.5 V，且测量时刻晚于入批时刻；批次一次最多 50 枚。
 */
@Service
public class DischargeService {

    /** 终测电压上限（含），单位伏特(V)。 */
    public static final BigDecimal MAX_FINAL_VOLTAGE = new BigDecimal("0.5");
    /** 单批次电芯数量上限。 */
    public static final int MAX_CELLS_PER_BATCH = 50;

    private final BatchRepository batchRepo;
    private final CellRepository cellRepo;
    private final ReadingRepository readingRepo;
    private final IsolationRepository isolationRepo;
    private final SnapshotRepository snapshotRepo;
    private final RequestLogRepository requestLogRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DischargeService(BatchRepository batchRepo, CellRepository cellRepo,
                            ReadingRepository readingRepo, IsolationRepository isolationRepo,
                            SnapshotRepository snapshotRepo, RequestLogRepository requestLogRepo,
                            ObjectMapper objectMapper, Clock clock) {
        this.batchRepo = batchRepo;
        this.cellRepo = cellRepo;
        this.readingRepo = readingRepo;
        this.isolationRepo = isolationRepo;
        this.snapshotRepo = snapshotRepo;
        this.requestLogRepo = requestLogRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 新建空批次；批次编号重复时拒绝。
     */
    @Transactional
    public BatchDetailView createBatch(String batchNo) {
        if (batchRepo.findByBatchNo(batchNo).isPresent()) {
            throw new BusinessException("批次编号已存在: " + batchNo);
        }
        batchRepo.insert(batchNo, clock.instant());
        return getBatch(batchNo);
    }

    /**
     * 电芯入批：记录初始电压与入批时刻（UTC）。
     * 同一电芯属于另一进行中批次、批次已交接或数量达 50 上限时拒绝；
     * 并发争夺同一电芯时唯一约束保证最多一个成功，失败事务回滚不占请求键。
     */
    @Transactional
    public CellView enrollCell(String batchNo, String requestId, String cellCode,
                               BigDecimal initialVoltage) {
        String hash = "ENROLL|" + batchNo + "|" + cellCode + "|" + initialVoltage.stripTrailingZeros().toPlainString();
        return replayOrExecute(requestId, "ENROLL", hash, CellView.class, () -> {
            BatchRecord batch = lockOpenBatch(batchNo);
            var existing = cellRepo.findByCellCode(cellCode);
            if (existing.isPresent()) {
                BatchRecord owner = batchRepo.findById(existing.get().batchId()).orElseThrow();
                if (owner.status() == BatchStatus.OPEN) {
                    throw new BusinessException(
                            "电芯 " + cellCode + " 属于另一进行中批次 " + owner.batchNo(), List.of(cellCode));
                }
                throw new BusinessException("电芯编号已使用: " + cellCode, List.of(cellCode));
            }
            if (cellRepo.countActiveByBatchId(batch.id()) >= MAX_CELLS_PER_BATCH) {
                throw new BusinessException("批次电芯数量达到 50 上限", List.of(cellCode));
            }
            long cellId;
            try {
                cellId = cellRepo.insert(cellCode, batch.id(), initialVoltage, clock.instant());
            } catch (DuplicateKeyException e) {
                throw new BusinessException("电芯 " + cellCode + " 已被其他批次并发入批", List.of(cellCode));
            }
            return toCellView(cellRepo.findById(cellId).orElseThrow());
        });
    }

    /**
     * 记录放电读数。已交接批次禁止重新测量，已隔离电芯不再测量。
     */
    @Transactional
    public ReadingView recordReading(String batchNo, String requestId, String cellCode,
                                     BigDecimal voltage, Instant measuredAt) {
        String hash = "READING|" + batchNo + "|" + cellCode + "|"
                + voltage.stripTrailingZeros().toPlainString() + "|" + measuredAt;
        return replayOrExecute(requestId, "READING", hash, ReadingView.class, () -> {
            BatchRecord batch = lockOpenBatch(batchNo);
            CellRecord cell = requireCellInBatch(batch, cellCode);
            if (cell.status() == CellStatus.ISOLATED) {
                throw new BusinessException("电芯已隔离，不再测量: " + cellCode, List.of(cellCode));
            }
            long id = readingRepo.insert(cell.id(), voltage, measuredAt, requestId, clock.instant());
            return toReadingView(readingRepo.findById(id).orElseThrow(), cellCode);
        });
    }
    /**
     * 作废读数：仅翻转状态，历史保留。与交接通过批次行锁串行化，
     * 交接只能读到作废后仍有效的终测。已交接批次不允许作废。
     */
    @Transactional
    public ReadingView voidReading(long readingId) {
        ReadingRecord reading = readingRepo.findById(readingId)
                .orElseThrow(() -> new NotFoundException("读数不存在: " + readingId));
        CellRecord cell = cellRepo.findById(reading.cellId()).orElseThrow();
        BatchRecord batch = batchRepo.findById(cell.batchId()).orElseThrow();
        lockOpenBatch(batch.batchNo());
        if (reading.status() == ReadingStatus.VOID) {
            return toReadingView(reading, cell.cellCode());
        }
        readingRepo.markVoid(readingId);
        return toReadingView(readingRepo.findById(readingId).orElseThrow(), cell.cellCode());
    }

    /**
     * 隔离异常电芯：保留其全部历史读数，后续交接清单不再包含它。
     * 重复隔离返回当前状态。
     */
    @Transactional
    public CellView isolateCell(String batchNo, String cellCode, String reason) {
        BatchRecord batch = lockOpenBatch(batchNo);
        CellRecord cell = requireCellInBatch(batch, cellCode);
        if (cell.status() == CellStatus.ISOLATED) {
            return toCellView(cell);
        }
        cellRepo.markIsolated(cell.id());
        isolationRepo.insert(cell.id(), reason, clock.instant());
        return toCellView(cellRepo.findById(cell.id()).orElseThrow());
    }

    /**
     * 交接签收：校验每枚在批电芯都有晚于入批时刻、电压不超过 0.5 V 的最新有效终测，
     * 任一不满足则整批拒绝并指出电芯编号；成功后冻结电芯清单、读数编号、
     * 交接双方与 UTC 时刻。
     */
    @Transactional
    public HandoverView handover(String batchNo, String requestId, String from, String to) {
        String hash = "HANDOVER|" + batchNo + "|" + from + "|" + to;
        return replayOrExecute(requestId, "HANDOVER", hash, HandoverView.class, () -> {
            BatchRecord batch = batchRepo.findByBatchNoForUpdate(batchNo)
                    .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
            if (batch.status() == BatchStatus.HANDED_OVER) {
                throw new BusinessException("批次已交接，不允许重复交接: " + batchNo);
            }
            List<CellRecord> active = cellRepo.findByBatchId(batch.id()).stream()
                    .filter(c -> c.status() == CellStatus.ACTIVE).toList();
            if (active.isEmpty()) {
                throw new BusinessException("批次无在批电芯，无法交接");
            }
            if (active.size() > MAX_CELLS_PER_BATCH) {
                throw new BusinessException("批次电芯数量超过 50 上限",
                        active.stream().map(CellRecord::cellCode).toList());
            }
            List<String> missing = new ArrayList<>();
            List<String> tooHigh = new ArrayList<>();
            List<String> tooEarly = new ArrayList<>();
            List<ReadingRecord> finals = new ArrayList<>();
            for (CellRecord cell : active) {
                var latest = readingRepo.findLatestValid(cell.id());
                if (latest.isEmpty()) {
                    missing.add(cell.cellCode());
                    continue;
                }
                ReadingRecord reading = latest.get();
                if (reading.voltage().compareTo(MAX_FINAL_VOLTAGE) > 0) {
                    tooHigh.add(cell.cellCode());
                    continue;
                }
                if (!reading.measuredAt().isAfter(cell.enrolledAt())) {
                    tooEarly.add(cell.cellCode());
                    continue;
                }
                finals.add(reading);
            }
            if (!missing.isEmpty() || !tooHigh.isEmpty() || !tooEarly.isEmpty()) {
                Set<String> codes = new LinkedHashSet<>();
                codes.addAll(missing);
                codes.addAll(tooHigh);
                codes.addAll(tooEarly);
                StringBuilder msg = new StringBuilder("整批交接被拒绝:");
                if (!missing.isEmpty()) {
                    msg.append(" 缺测电芯").append(missing);
                }
                if (!tooHigh.isEmpty()) {
                    msg.append(" 终测电压超过0.5V电芯").append(tooHigh);
                }
                if (!tooEarly.isEmpty()) {
                    msg.append(" 终测时刻未晚于入批时刻电芯").append(tooEarly);
                }
                throw new BusinessException(msg.toString(), List.copyOf(codes));
            }
            Instant now = clock.instant();
            List<SnapshotView> snapshotViews = new ArrayList<>();
            for (int i = 0; i < active.size(); i++) {
                CellRecord cell = active.get(i);
                ReadingRecord reading = finals.get(i);
                snapshotRepo.insert(batch.id(), cell.id(), cell.cellCode(), reading.id(),
                        reading.voltage(), reading.measuredAt());
                snapshotViews.add(new SnapshotView(cell.cellCode(), reading.id(),
                        reading.voltage(), reading.measuredAt()));
            }
            batchRepo.markHandedOver(batch.id(), from, to, now);
            return new HandoverView(batchNo, BatchStatus.HANDED_OVER.name(), from, to, now,
                    List.copyOf(snapshotViews));
        });
    }
    /**
     * 查询批次明细：每枚电芯的读数链、隔离原因与交接快照。
     */
    @Transactional(readOnly = true)
    public BatchDetailView getBatch(String batchNo) {
        BatchRecord batch = batchRepo.findByBatchNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        List<CellDetailView> cells = new ArrayList<>();
        for (CellRecord cell : cellRepo.findByBatchId(batch.id())) {
            List<ReadingView> readings = readingRepo.findByCellId(cell.id()).stream()
                    .map(r -> toReadingView(r, cell.cellCode())).toList();
            IsolationView isolation = isolationRepo.findLatestByCellId(cell.id())
                    .map(i -> new IsolationView(i.reason(), i.isolatedAt())).orElse(null);
            cells.add(new CellDetailView(toCellView(cell), readings, isolation));
        }
        List<SnapshotView> snapshots = snapshotRepo.findByBatchId(batch.id()).stream()
                .map(s -> new SnapshotView(s.cellCode(), s.readingId(), s.voltage(), s.measuredAt()))
                .toList();
        return new BatchDetailView(batch.batchNo(), batch.status().name(), batch.createdAt(),
                batch.handoverFrom(), batch.handoverTo(), batch.handoverAt(),
                List.copyOf(cells), snapshots);
    }

    private BatchRecord lockOpenBatch(String batchNo) {
        BatchRecord batch = batchRepo.findByBatchNoForUpdate(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
        if (batch.status() == BatchStatus.HANDED_OVER) {
            throw new BusinessException("批次已交接，不允许重新测量或变更: " + batchNo);
        }
        return batch;
    }

    private CellRecord requireCellInBatch(BatchRecord batch, String cellCode) {
        CellRecord cell = cellRepo.findByCellCode(cellCode)
                .orElseThrow(() -> new NotFoundException("电芯不存在: " + cellCode));
        if (cell.batchId() != batch.id()) {
            throw new BusinessException("电芯 " + cellCode + " 不属于批次 " + batch.batchNo(),
                    List.of(cellCode));
        }
        return cell;
    }

    /**
     * 幂等控制：requestId 同类型同参数重放首次成功响应，异参抛 409；
     * 业务失败抛异常导致事务回滚，请求键不被占用。
     */
    private <T> T replayOrExecute(String requestId, String type, String hash, Class<T> viewType,
                                  Supplier<T> action) {
        var existing = requestLogRepo.findById(requestId);
        if (existing.isPresent()) {
            var log = existing.get();
            if (!log.requestType().equals(type) || !log.requestHash().equals(hash)) {
                throw new ConflictException("requestId 已使用且参数不一致: " + requestId);
            }
            try {
                return objectMapper.readValue(log.responseJson(), viewType);
            } catch (Exception e) {
                throw new IllegalStateException("幂等响应解析失败: " + requestId, e);
            }
        }
        T result = action.get();
        try {
            requestLogRepo.insert(requestId, type, hash,
                    objectMapper.writeValueAsString(result), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("requestId 并发冲突: " + requestId);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败: " + requestId, e);
        }
        return result;
    }

    private CellView toCellView(CellRecord cell) {
        return new CellView(cell.id(), cell.cellCode(), cell.initialVoltage(),
                cell.enrolledAt(), cell.status().name());
    }

    private ReadingView toReadingView(ReadingRecord reading, String cellCode) {
        return new ReadingView(reading.id(), cellCode, reading.voltage(), reading.measuredAt(),
                reading.status().name(), reading.requestId());
    }
}