package com.example.starter.release;

import com.example.starter.anomaly.AnomalyRepository;
import com.example.starter.batch.BatchInfo;
import com.example.starter.batch.BatchRepository;
import com.example.starter.common.ApiException;
import com.example.starter.program.ProgramService;
import com.example.starter.program.ProgramVersionInfo;
import com.example.starter.reading.ReadingRepository;
import com.example.starter.reading.TemperatureReading;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 质检放行服务：放行一次性固化所用程序和测温点上限。
 * 通过批次行锁与测温上传串行化，保证不放行后出现未审点；唯一约束保证并发放行只产生一条记录。
 */
@Service
public class ReleaseService {

    private final BatchRepository batchRepository;
    private final ReadingRepository readingRepository;
    private final ReleaseRepository releaseRepository;
    private final AnomalyRepository anomalyRepository;
    private final ProgramService programService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReleaseService(BatchRepository batchRepository, ReadingRepository readingRepository,
                          ReleaseRepository releaseRepository, AnomalyRepository anomalyRepository,
                          ProgramService programService, ObjectMapper objectMapper, Clock clock) {
        this.batchRepository = batchRepository;
        this.readingRepository = readingRepository;
        this.releaseRepository = releaseRepository;
        this.anomalyRepository = anomalyRepository;
        this.programService = programService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 质检放行。轨迹校核不通过时返回 422 及具体段和异常时刻；已驳回或已标记异常的批次不得放行。
     */
    @Transactional
    public Map<String, Object> release(long batchId, String releasedBy) {
        BatchInfo batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (BatchInfo.RELEASED.equals(batch.status())) {
            throw ApiException.conflict("批次已放行，不能重复放行");
        }
        if (BatchInfo.REJECTED.equals(batch.status())) {
            throw ApiException.conflict("批次已驳回，不能补录点再直接放行，只能发起独立复烧批次");
        }
        if (anomalyRepository.existsByBatch(batchId)) {
            throw ApiException.unprocessable("批次已标记异常，只能由质检员驳回或发起独立复烧批次",
                    Map.of("anomalies", anomalyRepository.findByBatch(batchId)));
        }
        ProgramVersionInfo program = programService.getRequired(batch.programVersionId());
        List<TemperatureReading> readings = readingRepository.findByBatchOrderByTime(batchId);
        List<TrajectoryValidator.Violation> violations =
                TrajectoryValidator.validate(program.segments(), readings);
        if (!violations.isEmpty()) {
            throw ApiException.unprocessable("温度轨迹校核不通过，不得放行",
                    Map.of("violations", violations));
        }
        List<TrajectoryValidator.SegmentDeviation> deviations =
                TrajectoryValidator.worstDeviations(program.segments(), readings);
        long readingMaxId = readings.get(readings.size() - 1).id();
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("programVersionId", program.id());
        basis.put("programName", program.name());
        basis.put("programVersionNo", program.versionNo());
        basis.put("readingCount", readings.size());
        basis.put("readingMaxId", readingMaxId);
        basis.put("checkedAt", clock.instant().toString());
        basis.put("segmentDeviations", deviations);
        String basisJson;
        try {
            basisJson = objectMapper.writeValueAsString(basis);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("放行依据序列化失败", e);
        }
        long releaseId;
        try {
            releaseId = releaseRepository.insert(batchId, program.id(), readingMaxId,
                    readings.size(), releasedBy, basisJson);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("批次已存在放行记录，并发放行只产生一条记录");
        }
        batchRepository.updateStatus(batchId, BatchInfo.RELEASED);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("releaseId", releaseId);
        result.put("batchId", batchId);
        result.put("status", BatchInfo.RELEASED);
        result.put("basis", basis);
        return result;
    }

    /**
     * 质检驳回。驳回后不能补录点再直接放行。
     */
    @Transactional
    public Map<String, Object> reject(long batchId, String rejectedBy, String reason) {
        BatchInfo batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (!BatchInfo.FIRING.equals(batch.status())) {
            throw ApiException.conflict("只有烧制中的批次可以驳回，当前状态: " + batch.status());
        }
        batchRepository.updateStatus(batchId, BatchInfo.REJECTED);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("status", BatchInfo.REJECTED);
        result.put("rejectedBy", rejectedBy);
        result.put("reason", reason);
        return result;
    }

    /**
     * 标记异常。标记后批次只能由质检员驳回或发起独立复烧批次。
     */
    @Transactional
    public Map<String, Object> markAnomaly(long batchId, String markedBy, String reason) {
        BatchInfo batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (!BatchInfo.FIRING.equals(batch.status())) {
            throw ApiException.conflict("只有烧制中的批次可以标记异常，当前状态: " + batch.status());
        }
        long id = anomalyRepository.insert(batchId, markedBy, reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anomalyId", id);
        result.put("batchId", batchId);
        result.put("markedBy", markedBy);
        result.put("reason", reason);
        return result;
    }
}