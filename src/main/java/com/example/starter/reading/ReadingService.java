package com.example.starter.reading;

import com.example.starter.anomaly.AnomalyRepository;
import com.example.starter.batch.BatchInfo;
import com.example.starter.batch.BatchRepository;
import com.example.starter.common.ApiException;
import com.example.starter.program.ProgramService;
import com.example.starter.program.ProgramVersionInfo;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测温点上传服务：requestId 幂等（同参重放返回原结果，异参409），测温点只能追加。
 * 与放行共用批次行锁，保证放行要么包含最后测温点并完成全轨迹校核，要么按旧上限拒绝。
 */
@Service
public class ReadingService {

    private final BatchRepository batchRepository;
    private final ReadingRepository readingRepository;
    private final UploadRequestRepository uploadRequestRepository;
    private final AnomalyRepository anomalyRepository;
    private final ProgramService programService;

    public ReadingService(BatchRepository batchRepository, ReadingRepository readingRepository,
                          UploadRequestRepository uploadRequestRepository,
                          AnomalyRepository anomalyRepository, ProgramService programService) {
        this.batchRepository = batchRepository;
        this.readingRepository = readingRepository;
        this.uploadRequestRepository = uploadRequestRepository;
        this.anomalyRepository = anomalyRepository;
        this.programService = programService;
    }

    /**
     * 上传测温点。同一 requestId 同参重放返回首次结果，异参报 409。
     */
    @Transactional
    public UploadResult upload(long batchId, String requestId, List<ReadingInput> inputs) {
        BatchInfo batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (inputs == null || inputs.isEmpty()) {
            throw ApiException.badRequest("测温点不能为空");
        }
        String hash = payloadHash(batchId, inputs);
        var existing = uploadRequestRepository.find(requestId);
        if (existing.isPresent()) {
            UploadRequestRepository.UploadRequest record = existing.get();
            if (record.batchId() != batchId || !record.payloadHash().equals(hash)) {
                throw ApiException.conflict("requestId 已使用且参数不同: " + requestId);
            }
            return new UploadResult(record.responseBody(), true);
        }
        if (!BatchInfo.FIRING.equals(batch.status())) {
            throw ApiException.conflict("批次已" + (BatchInfo.RELEASED.equals(batch.status()) ? "放行" : "驳回")
                    + "，测温点按放行时固化的上限拒绝追加");
        }
        if (anomalyRepository.existsByBatch(batchId)) {
            throw ApiException.conflict("批次已标记异常，只能由质检员驳回或发起独立复烧批次，禁止补录测温点");
        }
        ProgramVersionInfo program = programService.getRequired(batch.programVersionId());
        for (ReadingInput in : inputs) {
            if (in.measuredAt() == null || in.temperatureC() == null) {
                throw ApiException.badRequest("测温时刻与温度必填");
            }
            if (in.measuredAt().isBefore(program.firingStart())
                    || in.measuredAt().isAfter(program.firingEnd())) {
                throw ApiException.badRequest("测温时刻超出烧制区间: " + in.measuredAt());
            }
            if (readingRepository.existsByBatchAndTime(batchId, in.measuredAt())) {
                throw ApiException.conflict("测温时刻已存在，测温点只能追加不能覆盖: " + in.measuredAt());
            }
        }
        long maxId = 0;
        try {
            for (ReadingInput in : inputs) {
                maxId = readingRepository.insert(
                        new TemperatureReading(0, batchId, in.measuredAt(), in.temperatureC()));
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("测温时刻重复（同批上传或并发冲突）");
        }
        String body = "{\"batchId\":" + batchId + ",\"requestId\":\"" + requestId
                + "\",\"acceptedCount\":" + inputs.size() + ",\"readingMaxId\":" + maxId + "}";
        try {
            uploadRequestRepository.insert(requestId, batchId, hash, body);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("requestId 并发冲突: " + requestId);
        }
        return new UploadResult(body, false);
    }

    private String payloadHash(long batchId, List<ReadingInput> inputs) {
        StringBuilder sb = new StringBuilder().append(batchId);
        inputs.stream()
                .sorted(Comparator.comparing(i -> String.valueOf(i.measuredAt())))
                .forEach(i -> sb.append('|').append(i.measuredAt()).append('=')
                        .append(i.temperatureC() == null ? "null" : i.temperatureC().stripTrailingZeros().toPlainString()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 测温点入参。
     */
    public record ReadingInput(Instant measuredAt, BigDecimal temperatureC) {
    }

    /**
     * 上传结果：responseBody 为响应报文，replayed 表示是否幂等重放。
     */
    public record UploadResult(String responseBody, boolean replayed) {
    }
}