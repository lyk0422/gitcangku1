package com.example.starter.well;

import com.example.starter.common.ApiException;
import com.example.starter.common.Payloads;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行段与压力采样业务：建段、采样追加（幂等）、错误读数作废加替代。
 */
@Service
public class WellService {

    private final SegmentRepository segmentRepository;
    private final SampleRepository sampleRepository;
    private final Clock clock;

    public WellService(SegmentRepository segmentRepository, SampleRepository sampleRepository,
                       Clock clock) {
        this.segmentRepository = segmentRepository;
        this.sampleRepository = sampleRepository;
        this.clock = clock;
    }

    /**
     * 新建首个运行段；井已存在未结束段时返回 409，防止新旧段重叠。
     */
    @Transactional
    public WellSegment createSegment(String wellNo, String mode, Instant startUtc) {
        Optional<WellSegment> open = segmentRepository.findOpenForUpdate(wellNo);
        if (open.isPresent()) {
            throw ApiException.conflict("井 " + wellNo + " 已存在未结束运行段，新旧段不能重叠");
        }
        long id = segmentRepository.insert(wellNo, mode, startUtc, clock.instant());
        return new WellSegment(id, wellNo, mode, startUtc, null);
    }

    /**
     * 追加压力采样。requestId 同参重放返回原记录，异参返回 409；
     * 采样时刻早于段开始或晚于当前时刻按时间逆序返回 422。
     */
    @Transactional
    public PressureSample addSample(String wellNo, String requestId, BigDecimal pressureMpa,
                                    Instant sampledAt) {
        String hash = Payloads.hash(wellNo, "|", requestId, "|",
                pressureMpa.stripTrailingZeros().toPlainString(), "|", sampledAt.toString());
        Optional<PressureSample> existing = sampleRepository.findByRequestId(wellNo, requestId);
        if (existing.isPresent()) {
            if (existing.get().payloadHash().equals(hash)) {
                return existing.get();
            }
            throw ApiException.conflict("requestId " + requestId + " 已存在但参数不一致");
        }
        WellSegment open = segmentRepository.findOpen(wellNo)
                .orElseThrow(() -> ApiException.unprocessable("井 " + wellNo + " 没有在运行的运行段"));
        Instant now = clock.instant();
        if (sampledAt.isBefore(open.startUtc()) || sampledAt.isAfter(now)) {
            throw ApiException.unprocessable("采样时刻 " + sampledAt + " 时间逆序：须不早于段开始 "
                    + open.startUtc() + " 且不晚于当前时刻 " + now);
        }
        return insertSample(wellNo, requestId, hash, pressureMpa, sampledAt, now);
    }

    /** 幂等追加采样：同编号同参返回原记录，异参 409，并发插入靠唯一约束兜底。 */
    private PressureSample insertSample(String wellNo, String requestId, String hash,
                                        BigDecimal pressureMpa, Instant sampledAt, Instant now) {
        PressureSample sample = new PressureSample(0, wellNo, requestId, hash, pressureMpa,
                sampledAt, false, null, null);
        try {
            long id = sampleRepository.insert(sample, now);
            return sampleRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            PressureSample replayed = sampleRepository.findByRequestId(wellNo, requestId)
                    .orElseThrow(() -> e);
            if (replayed.payloadHash().equals(hash)) {
                return replayed;
            }
            throw ApiException.conflict("requestId " + requestId + " 已存在但参数不一致");
        }
    }

    /**
     * 作废错误读数并追加替代读数。只追加作废标记，不改原读数；
     * 与切换审核通过对运行段的行锁串行化，基于同一已提交有效集合。
     */
    @Transactional
    public PressureSample voidSample(long sampleId, String reason, String replacementRequestId,
                                     BigDecimal replacementPressure, Instant replacementSampledAt) {
        PressureSample sample = sampleRepository.findByIdForUpdate(sampleId)
                .orElseThrow(() -> ApiException.notFound("采样 " + sampleId + " 不存在"));
        segmentRepository.findOpenForUpdate(sample.wellNo());
        if (sample.voided()) {
            throw ApiException.conflict("采样 " + sampleId + " 已作废，不能重复作废");
        }
        // 替代读数可能属于已关闭的旧段，只要求不晚于当前时刻，幂等规则与正常采样一致。
        Instant now = clock.instant();
        if (replacementSampledAt.isAfter(now)) {
            throw ApiException.unprocessable("替代读数时刻 " + replacementSampledAt
                    + " 时间逆序：不能晚于当前时刻 " + now);
        }
        String replacementHash = Payloads.hash(sample.wellNo(), "|", replacementRequestId, "|",
                replacementPressure.stripTrailingZeros().toPlainString(), "|",
                replacementSampledAt.toString());
        Optional<PressureSample> existingReplacement = sampleRepository.findByRequestId(
                sample.wellNo(), replacementRequestId);
        if (existingReplacement.isPresent()
                && !existingReplacement.get().payloadHash().equals(replacementHash)) {
            throw ApiException.conflict("requestId " + replacementRequestId + " 已存在但参数不一致");
        }
        PressureSample replacement = existingReplacement.orElseGet(() -> insertSample(
                sample.wellNo(), replacementRequestId, replacementHash, replacementPressure,
                replacementSampledAt, now));
        sampleRepository.markVoided(sampleId, reason, now, replacement.id());
        return sampleRepository.findById(sampleId).orElseThrow();
    }

    /** 查询井的运行段时间线。 */
    public List<WellSegment> timeline(String wellNo) {
        return segmentRepository.findByWell(wellNo);
    }
}