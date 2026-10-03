package com.example.starter.batch;

import com.example.starter.anomaly.AnomalyRepository;
import com.example.starter.common.ApiException;
import com.example.starter.program.ProgramService;
import com.example.starter.program.ProgramVersionInfo;
import com.example.starter.reading.ReadingRepository;
import com.example.starter.reading.TemperatureReading;
import com.example.starter.release.ReleaseRepository;
import com.example.starter.release.TrajectoryValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批次服务：建批时锁定程序版本，支持独立复烧批次与批次详情查询。
 */
@Service
public class BatchService {

    private final BatchRepository batchRepository;
    private final ReadingRepository readingRepository;
    private final ReleaseRepository releaseRepository;
    private final AnomalyRepository anomalyRepository;
    private final ProgramService programService;

    public BatchService(BatchRepository batchRepository, ReadingRepository readingRepository,
                        ReleaseRepository releaseRepository, AnomalyRepository anomalyRepository,
                        ProgramService programService) {
        this.batchRepository = batchRepository;
        this.readingRepository = readingRepository;
        this.releaseRepository = releaseRepository;
        this.anomalyRepository = anomalyRepository;
        this.programService = programService;
    }

    /**
     * 新建批次并锁定程序版本。
     */
    @Transactional
    public BatchInfo create(String batchNo, long programVersionId, Long refireOfId) {
        programService.getRequired(programVersionId);
        if (refireOfId != null) {
            batchRepository.findById(refireOfId)
                    .orElseThrow(() -> ApiException.notFound("复烧来源批次不存在: " + refireOfId));
        }
        long id;
        try {
            id = batchRepository.insert(batchNo, programVersionId, refireOfId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("批次号已存在: " + batchNo);
        }
        return batchRepository.findById(id).orElseThrow();
    }

    /**
     * 发起独立复烧批次：不修改原批次任何数据，默认沿用原程序版本。
     */
    @Transactional
    public BatchInfo refire(long sourceBatchId, String newBatchNo, Long programVersionId) {
        BatchInfo source = batchRepository.findById(sourceBatchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + sourceBatchId));
        long versionId = programVersionId != null ? programVersionId : source.programVersionId();
        return create(newBatchNo, versionId, sourceBatchId);
    }

    /**
     * 批次详情：程序版本、各段最坏偏差、异常标记与放行依据。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(long batchId) {
        BatchInfo batch = batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        ProgramVersionInfo program = programService.getRequired(batch.programVersionId());
        List<TemperatureReading> readings = readingRepository.findByBatchOrderByTime(batchId);
        List<TrajectoryValidator.SegmentDeviation> deviations =
                TrajectoryValidator.worstDeviations(program.segments(), readings);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch", batch);
        result.put("programVersion", program);
        result.put("readingCount", readings.size());
        result.put("segmentDeviations", deviations);
        result.put("anomalies", anomalyRepository.findByBatch(batchId));
        result.put("release", releaseRepository.findByBatch(batchId).orElse(null));
        return result;
    }
}