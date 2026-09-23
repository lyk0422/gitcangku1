package com.example.starter.calibration.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BatchDiffResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.RevisionChainResponse;
import com.example.starter.calibration.model.BatchLineage;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.ReleaseBatch;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.LineageRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 只读查询服务：批次详情、批次差异、修订链。不产生任何写操作。
 */
@Service
public class QueryService {

    private final BatchRepository batches;
    private final ReleaseRepository releases;
    private final MeasurementRepository measurements;
    private final LineageRepository lineages;

    public QueryService(BatchRepository batches,
                        ReleaseRepository releases,
                        MeasurementRepository measurements,
                        LineageRepository lineages) {
        this.batches = batches;
        this.releases = releases;
        this.measurements = measurements;
        this.lineages = lineages;
    }

    /**
     * 批次详情：批次头与逐位置测量快照；批次不存在返回 404。
     */
    @Transactional(readOnly = true)
    public BatchResponse batch(String batchId) {
        ReleaseBatch batch = batches.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + batchId));
        List<BatchResponse.PositionMeasurement> positions = new ArrayList<>();
        for (ReleaseRecord record : releases.findByBatchId(batchId)) {
            Measurement measurement = measurements.findById(record.measurementId())
                    .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING",
                            "批次位置对应测量不存在: " + record.measurementId()));
            positions.add(new BatchResponse.PositionMeasurement(
                    record.position(), measurement.id(), measurement.measurementKey(),
                    measurement.version(), measurement.status().name(), measurement.passed(),
                    DtoMapper.format(measurement.computedValue())));
        }
        return new BatchResponse(batch.batchId(), batch.releasedBy(), batch.status().name(),
                batch.createdAt(), batch.reviewedAt(), List.copyOf(positions));
    }

    /**
     * 批次差异：以某批次为来源的最新重新放行血缘，逐位置比较来源与采用测量。
     * 批次不存在 404；该批次尚未被重新放行 404。
     */
    @Transactional(readOnly = true)
    public BatchDiffResponse diff(String batchId) {
        batches.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + batchId));
        List<BatchLineage> lineage = lineages.findBySourceBatchId(batchId);
        if (lineage.isEmpty()) {
            throw ApiException.notFound("批次尚未被重新放行，无差异可查: " + batchId);
        }
        String newBatchId = lineage.get(0).newBatchId();
        List<BatchDiffResponse.PositionDiff> positions = new ArrayList<>();
        for (BatchLineage entry : lineage) {
            Measurement source = measurements.findById(entry.sourceMeasurementId()).orElseThrow();
            Measurement used = measurements.findById(entry.usedMeasurementId()).orElseThrow();
            positions.add(new BatchDiffResponse.PositionDiff(
                    entry.position(),
                    source.measurementKey(), source.version(),
                    used.measurementKey(), used.version(),
                    entry.revised(),
                    DtoMapper.format(source.computedValue()),
                    DtoMapper.format(used.computedValue()),
                    source.passed(), used.passed()));
        }
        return new BatchDiffResponse(batchId, newBatchId, List.copyOf(positions));
    }

    /**
     * 修订链：以任一测量键查询其所属链上原始测量与全部后继修订（按版本顺序）。
     */
    @Transactional(readOnly = true)
    public RevisionChainResponse revisionChain(String measurementKey) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        long rootId = measurement.rootId() == null ? measurement.id() : measurement.rootId();
        List<Measurement> chain = measurements.findChainByRootId(rootId);
        Measurement root = chain.stream().filter(m -> m.id() == rootId).findFirst().orElseThrow();
        List<RevisionChainResponse.ChainVersion> versions = new ArrayList<>();
        for (Measurement entry : chain) {
            versions.add(new RevisionChainResponse.ChainVersion(
                    entry.measurementKey(), entry.version(), entry.status().name(),
                    DtoMapper.format(entry.rawReading()),
                    DtoMapper.format(entry.computedValue()),
                    entry.passed(), entry.note(), entry.createdAt()));
        }
        return new RevisionChainResponse(root.measurementKey(), List.copyOf(versions));
    }
}
