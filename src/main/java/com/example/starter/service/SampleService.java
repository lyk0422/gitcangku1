package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.example.starter.domain.Sample;
import com.example.starter.domain.SealPackage;
import com.example.starter.domain.Stratum;
import com.example.starter.error.BusinessException;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.idempotency.IdempotentOutcome;
import com.example.starter.repo.PackageRepository;
import com.example.starter.repo.SampleRepository;
import com.example.starter.repo.SealEventRepository;
import com.example.starter.repo.StratumRepository;
import com.example.starter.web.dto.Requests.CollectSampleRequest;
import com.example.starter.web.dto.Responses.SampleResponse;
import com.example.starter.web.dto.Responses.SealEventView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 样本采集与查询服务：采集时固化层位、坐标格与 UTC 时刻，编号全项目唯一。
 */
@Service
public class SampleService {

    private final StratumRepository stratumRepository;
    private final SampleRepository sampleRepository;
    private final PackageRepository packageRepository;
    private final SealEventRepository sealEventRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public SampleService(StratumRepository stratumRepository, SampleRepository sampleRepository,
            PackageRepository packageRepository, SealEventRepository sealEventRepository,
            IdempotencyService idempotencyService, Clock clock) {
        this.stratumRepository = stratumRepository;
        this.sampleRepository = sampleRepository;
        this.packageRepository = packageRepository;
        this.sealEventRepository = sealEventRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 采集样本（幂等）：同 requestId 同参重放返回首次响应，异参返回 409。
     */
    @Transactional
    public IdempotentOutcome collect(CollectSampleRequest req) {
        return idempotencyService.execute(req.requestId(), "COLLECT_SAMPLE", req,
                HttpStatus.CREATED.value(), () -> doCollect(req));
    }

    private SampleResponse doCollect(CollectSampleRequest req) {
        Stratum stratum = stratumRepository.findByKey(req.siteCode(), req.stratumNo())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "STRATUM_NOT_FOUND", "层位不存在，请先登记"));
        Instant now = Instant.now(clock);
        long id;
        try {
            id = sampleRepository.insert(req.sampleNo(), stratum.id(), req.gridRef(), now, now);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "SAMPLE_EXISTS", "样本编号已存在");
        }
        return toResponse(sampleRepository.findById(id).orElseThrow());
    }

    /**
     * 查询样本：展示层位、坐标格、所在包版本与每次封签状态。
     */
    @Transactional(readOnly = true)
    public SampleResponse getBySampleNo(String sampleNo) {
        Sample sample = sampleRepository.findBySampleNo(sampleNo)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "SAMPLE_NOT_FOUND", "样本不存在"));
        return toResponse(sample);
    }

    private SampleResponse toResponse(Sample sample) {
        Stratum stratum = stratumRepository.findById(sample.stratumId()).orElseThrow();
        String packageNo = null;
        Integer packageVersion = null;
        String packageStatus = null;
        String sealNo = null;
        List<SealEventView> events = List.of();
        if (sample.packageId() != null) {
            SealPackage pkg = packageRepository.findById(sample.packageId()).orElseThrow();
            packageNo = pkg.packageNo();
            packageVersion = pkg.version();
            packageStatus = pkg.status();
            sealNo = pkg.sealNo();
            events = sealEventRepository.listByPackageId(pkg.id()).stream()
                    .map(e -> new SealEventView(e.id(), e.eventType(), e.sealNo(), e.note(), e.recordedAt()))
                    .toList();
        }
        return new SampleResponse(sample.id(), sample.sampleNo(), stratum.siteCode(), stratum.stratumNo(),
                sample.gridRef(), sample.collectedAt(), sample.status(), packageNo, packageVersion,
                packageStatus, sealNo, events);
    }
}