package com.example.starter.service;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.ReadingType;
import com.example.starter.domain.Rows.BatchRow;
import com.example.starter.domain.Rows.CellRow;
import com.example.starter.domain.Rows.HandoverCellRow;
import com.example.starter.domain.Rows.HandoverRow;
import com.example.starter.domain.Rows.ReadingRow;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import com.example.starter.error.RejectionException;
import com.example.starter.repo.BatchRepository;
import com.example.starter.repo.CellRepository;
import com.example.starter.repo.HandoverRepository;
import com.example.starter.repo.IsolationRepository;
import com.example.starter.repo.ReadingRepository;
import com.example.starter.web.dto.ApiResponses.CreateBatchResponse;
import com.example.starter.web.dto.ApiResponses.FinalReadingResponse;
import com.example.starter.web.dto.ApiResponses.FrozenCell;
import com.example.starter.web.dto.ApiResponses.HandoverResponse;
import com.example.starter.web.dto.ApiResponses.IntakeResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 电芯放电核心业务：入批、终测、作废、隔离、交接。 */
@Service
public class DischargeService {

    /** 批次电芯数量上限。 */
    public static final int MAX_CELLS_PER_BATCH = 50;

    /** 交接允许的终测电压上限（伏特）。 */
    public static final double MAX_FINAL_VOLTAGE = 0.5;

    private final BatchRepository batchRepository;
    private final CellRepository cellRepository;
    private final ReadingRepository readingRepository;
    private final IsolationRepository isolationRepository;
    private final HandoverRepository handoverRepository;
    private final Clock clock;

    public DischargeService(BatchRepository batchRepository,
                            CellRepository cellRepository,
                            ReadingRepository readingRepository,
                            IsolationRepository isolationRepository,
                            HandoverRepository handoverRepository,
                            Clock businessClock) {
        this.batchRepository = batchRepository;
        this.cellRepository = cellRepository;
        this.readingRepository = readingRepository;
        this.isolationRepository = isolationRepository;
        this.handoverRepository = handoverRepository;
        this.clock = businessClock;
    }

    /** 创建进行中批次。 */
    @Transactional
    public CreateBatchResponse createBatch() {
        String batchCode = "BATCH-" + shortId();
        Instant now = Instant.now(clock);
        batchRepository.insert(new BatchRow(batchCode, BatchStatus.OPEN, now));
        return new CreateBatchResponse(batchCode, BatchStatus.OPEN.name(), now);
    }

    /** 电芯入批并记录初始电压；电芯被占用抛 409，批次超限或状态非法拒绝。 */
    @Transactional
    public IntakeResponse intake(String requestId, String batchCode, String cellCode,
                                 double initialVoltage) {
        BatchRow batch = lockOpenBatch(batchCode);
        cellRepository.find(cellCode)
                .orElseThrow(() -> new NotFoundException("电芯不存在: " + cellCode));
        if (cellRepository.countByBatch(batchCode) >= MAX_CELLS_PER_BATCH) {
            throw new RejectionException("批次电芯数量超限（最多 " + MAX_CELLS_PER_BATCH + " 枚）",
                    List.of(cellCode));
        }
        if (!cellRepository.occupyForBatch(cellCode, batchCode)) {
            throw new ConflictException("电芯属于另一进行中批次或不可入批: " + cellCode);
        }
        Instant now = Instant.now(clock);
        String readingCode = "RD-" + shortId();
        readingRepository.insert(new ReadingRow(readingCode, cellCode, batchCode,
                ReadingType.INTAKE, initialVoltage, now, true, requestId, now));
        return new IntakeResponse(readingCode, batchCode, cellCode, initialVoltage, now);
    }

    /** 记录终测读数；测量时刻须晚于入批时刻，已交接批次禁止测量。 */
    @Transactional
    public FinalReadingResponse finalReading(String requestId, String batchCode, String cellCode,
                                             double voltage, Instant measuredAt) {
        lockOpenBatch(batchCode);
        CellRow cell = cellRepository.find(cellCode)
                .orElseThrow(() -> new NotFoundException("电芯不存在: " + cellCode));
        if (!batchCode.equals(cell.batchCode())) {
            throw new ConflictException("电芯不在批次 " + batchCode + " 中: " + cellCode);
        }
        ReadingRow intake = readingRepository.findIntake(cellCode, batchCode)
                .orElseThrow(() -> new ConflictException("电芯缺少入批记录: " + cellCode));
        if (!measuredAt.isAfter(intake.measuredAt())) {
            throw new RejectionException("终测时刻须晚于入批时刻", List.of(cellCode));
        }
        String readingCode = "RD-" + shortId();
        readingRepository.insert(new ReadingRow(readingCode, cellCode, batchCode,
                ReadingType.FINAL, voltage, measuredAt, true, requestId,
                Instant.now(clock)));
        return new FinalReadingResponse(readingCode, batchCode, cellCode, voltage, measuredAt);
    }

    /** 作废读数，历史保留。 */
    @Transactional
    public void invalidateReading(String readingCode) {
        readingRepository.find(readingCode)
                .orElseThrow(() -> new NotFoundException("读数不存在: " + readingCode));
        readingRepository.invalidate(readingCode);
    }

    /** 隔离异常电芯：退出批次、记录原因、保留读数历史。 */
    @Transactional
    public void isolate(String batchCode, String cellCode, String reason) {
        lockOpenBatch(batchCode);
        CellRow cell = cellRepository.find(cellCode)
                .orElseThrow(() -> new NotFoundException("电芯不存在: " + cellCode));
        if (!batchCode.equals(cell.batchCode())) {
            throw new ConflictException("电芯不在批次 " + batchCode + " 中: " + cellCode);
        }
        cellRepository.markIsolated(cellCode);
        isolationRepository.insert(cellCode, batchCode, reason, Instant.now(clock));
    }

    /** 交接签收：校验全部电芯最新有效终测，冻结清单、读数编号、双方与 UTC 时刻。 */
    @Transactional
    public HandoverResponse handover(String requestId, String batchCode, String fromParty,
                                     String toParty) {
        lockOpenBatch(batchCode);
        List<CellRow> cells = cellRepository.findByBatch(batchCode);
        if (cells.isEmpty()) {
            throw new RejectionException("批次无在批电芯，拒绝交接", List.of());
        }
        if (cells.size() > MAX_CELLS_PER_BATCH) {
            throw new RejectionException("批次电芯数量超限（最多 " + MAX_CELLS_PER_BATCH + " 枚）",
                    cells.stream().map(CellRow::cellCode).toList());
        }
        List<String> missing = new ArrayList<>();
        List<String> tooHigh = new ArrayList<>();
        List<FrozenCell> frozen = new ArrayList<>();
        for (CellRow cell : cells) {
            ReadingRow intake = readingRepository.findIntake(cell.cellCode(), batchCode)
                    .orElse(null);
            ReadingRow latestFinal = readingRepository
                    .findLatestValidFinalForUpdate(cell.cellCode(), batchCode)
                    .orElse(null);
            if (intake == null || latestFinal == null
                    || !latestFinal.measuredAt().isAfter(intake.measuredAt())) {
                missing.add(cell.cellCode());
                continue;
            }
            if (latestFinal.voltage() > MAX_FINAL_VOLTAGE) {
                tooHigh.add(cell.cellCode());
                continue;
            }
            frozen.add(new FrozenCell(cell.cellCode(), latestFinal.readingCode()));
        }
        if (!missing.isEmpty()) {
            throw new RejectionException("电芯缺少有效终测，拒绝整批交接", missing);
        }
        if (!tooHigh.isEmpty()) {
            throw new RejectionException("电芯终测电压超过 " + MAX_FINAL_VOLTAGE + " V，拒绝整批交接",
                    tooHigh);
        }
        Instant now = Instant.now(clock);
        String handoverCode = "HO-" + shortId();
        handoverRepository.insert(new HandoverRow(handoverCode, batchCode, fromParty, toParty,
                now, requestId));
        for (FrozenCell cell : frozen) {
            handoverRepository.insertCell(
                    new HandoverCellRow(handoverCode, cell.cellCode(), cell.finalReadingCode()));
        }
        batchRepository.updateStatus(batchCode, BatchStatus.HANDED_OVER);
        cellRepository.markHandedOver(batchCode);
        return new HandoverResponse(handoverCode, batchCode, fromParty, toParty, now, frozen);
    }

    private BatchRow lockOpenBatch(String batchCode) {
        BatchRow batch = batchRepository.findForUpdate(batchCode)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchCode));
        if (batch.status() != BatchStatus.OPEN) {
            throw new ConflictException("批次已交接，禁止变更: " + batchCode);
        }
        return batch;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}