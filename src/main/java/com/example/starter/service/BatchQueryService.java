package com.example.starter.service;

import com.example.starter.domain.Rows.BatchRow;
import com.example.starter.domain.Rows.CellRow;
import com.example.starter.domain.Rows.HandoverRow;
import com.example.starter.domain.Rows.IsolationRow;
import com.example.starter.domain.Rows.ReadingRow;
import com.example.starter.error.NotFoundException;
import com.example.starter.repo.BatchRepository;
import com.example.starter.repo.CellRepository;
import com.example.starter.repo.HandoverRepository;
import com.example.starter.repo.IsolationRepository;
import com.example.starter.repo.ReadingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 批次查询：读数链、隔离原因与交接快照。 */
@Service
public class BatchQueryService {

    private final BatchRepository batchRepository;
    private final CellRepository cellRepository;
    private final ReadingRepository readingRepository;
    private final IsolationRepository isolationRepository;
    private final HandoverRepository handoverRepository;

    public BatchQueryService(BatchRepository batchRepository,
                             CellRepository cellRepository,
                             ReadingRepository readingRepository,
                             IsolationRepository isolationRepository,
                             HandoverRepository handoverRepository) {
        this.batchRepository = batchRepository;
        this.cellRepository = cellRepository;
        this.readingRepository = readingRepository;
        this.isolationRepository = isolationRepository;
        this.handoverRepository = handoverRepository;
    }

    /** 查询批次全貌；批次不存在抛 404。 */
    @Transactional(readOnly = true)
    public BatchView query(String batchCode) {
        BatchRow batch = batchRepository.find(batchCode)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchCode));
        Map<String, String> isolationReasons = new LinkedHashMap<>();
        for (IsolationRow row : isolationRepository.findByBatch(batchCode)) {
            isolationReasons.put(row.cellCode(), row.reason());
        }
        Map<String, CellRow> cells = new LinkedHashMap<>();
        for (CellRow cell : cellRepository.findByBatch(batchCode)) {
            cells.putIfAbsent(cell.cellCode(), cell);
        }
        List<CellView> cellViews = new ArrayList<>();
        for (String cellCode : unionKeys(cells, isolationReasons)) {
            CellRow cell = cells.get(cellCode);
            String status = cell != null ? cell.status().name() : "ISOLATED";
            List<ReadingView> chain = new ArrayList<>();
            for (ReadingRow r : readingRepository.findChain(cellCode, batchCode)) {
                chain.add(new ReadingView(r.readingCode(), r.readingType().name(), r.voltage(),
                        r.measuredAt(), r.valid()));
            }
            cellViews.add(new CellView(cellCode, status, isolationReasons.get(cellCode), chain));
        }
        HandoverView handover = handoverRepository.findByBatch(batchCode)
                .map(this::toHandoverView)
                .orElse(null);
        return new BatchView(batch.batchCode(), batch.status().name(), batch.createdAt(),
                cellViews, handover);
    }

    private HandoverView toHandoverView(HandoverRow row) {
        List<FrozenCellView> frozen = handoverRepository.findCells(row.handoverCode()).stream()
                .map(c -> new FrozenCellView(c.cellCode(), c.finalReadingCode()))
                .toList();
        return new HandoverView(row.handoverCode(), row.fromParty(), row.toParty(),
                row.handedOverAt(), frozen);
    }

    private static List<String> unionKeys(Map<String, CellRow> cells,
                                          Map<String, String> isolations) {
        List<String> keys = new ArrayList<>(cells.keySet());
        for (String code : isolations.keySet()) {
            if (!keys.contains(code)) {
                keys.add(code);
            }
        }
        return keys.stream().sorted().toList();
    }

    /** 批次视图。 */
    public record BatchView(String batchCode, String status, Instant createdAt,
                            List<CellView> cells, HandoverView handover) {
    }

    /** 电芯视图：当前状态、隔离原因（未隔离为空）与完整读数链。 */
    public record CellView(String cellCode, String status, String isolationReason,
                           List<ReadingView> readings) {
    }

    /** 读数视图。 */
    public record ReadingView(String readingCode, String readingType, double voltage,
                              Instant measuredAt, boolean valid) {
    }

    /** 交接快照视图；未交接为空。 */
    public record HandoverView(String handoverCode, String fromParty, String toParty,
                               Instant handedOverAt, List<FrozenCellView> cells) {
    }

    /** 交接冻结明细视图。 */
    public record FrozenCellView(String cellCode, String finalReadingCode) {
    }
}