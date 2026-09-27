package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchRelease;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.repo.BatchReleaseRepository;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;

/**
 * 批次放行门禁：当前修订版本存在未裁决离群值时阻断放行（422）；
 * 放行在批次行锁与证书行锁内读取一致的离群与复核集合，并写入不可变放行快照。
 */
@Service
public class BatchReleaseService {

    private final BatchRepository batches;
    private final ReadingRepository readings;
    private final OutlierRepository outliers;
    private final CertificateRepository certificates;
    private final BatchReleaseRepository releases;

    public BatchReleaseService(BatchRepository batches, ReadingRepository readings,
                               OutlierRepository outliers, CertificateRepository certificates,
                               BatchReleaseRepository releases) {
        this.batches = batches;
        this.readings = readings;
        this.outliers = outliers;
        this.certificates = certificates;
        this.releases = releases;
    }

    /**
     * 放行当前修订版本。存在未裁决离群值返回 422 并列出离群记录；
     * 计入读数的证书已撤销返回 422；当前修订已放行返回 409；无计入读数返回 422。
     */
    @Transactional
    public BatchReleaseResponse release(String batchKey, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        MeasurementBatch batch = batches.findByKeyForUpdate(Inputs.requireText(batchKey, "batchKey"))
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));

        if (batch.status() == BatchStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED",
                    "批次当前修订已放行: " + batchKey + " revision=" + batch.revision());
        }

        List<OutlierRecord> pending = outliers.findPendingByBatchAndRevision(batch.id(), batch.revision());
        if (!pending.isEmpty()) {
            List<ItemFailure> failures = pending.stream()
                    .map(o -> new ItemFailure(String.valueOf(o.id()),
                            List.of("OUTLIER_UNRESOLVED",
                                    "actualDeviation=" + BatchDtoMapper.format(o.actualDeviation()),
                                    "allowedDeviation=" + BatchDtoMapper.format(o.allowedDeviation()))))
                    .toList();
            throw new com.example.starter.calibration.api.BatchReleaseBlockedException(failures);
        }

        List<BatchReading> current = readings.findByBatchAndRevision(batch.id(), batch.revision());
        List<BatchReading> included = current.stream().filter(r -> !r.excluded()).toList();
        if (included.isEmpty()) {
            throw ApiException.unprocessable("NO_INCLUDED_READINGS",
                    "批次当前修订无可放行的计入读数: " + batchKey);
        }

        List<String> revoked = new ArrayList<>();
        for (long certId : included.stream().map(BatchReading::certificateId).distinct().sorted().toList()) {
            Certificate cert = certificates.findByIdForUpdate(certId)
                    .orElseThrow(() -> ApiException.unprocessable("CERTIFICATE_MISSING",
                            "读数关联的证书不存在: " + certId));
            if (cert.revoked()) {
                revoked.add(String.valueOf(certId));
            }
        }
        if (!revoked.isEmpty()) {
            throw ApiException.unprocessable("CERTIFICATE_REVOKED",
                    "计入读数关联的证书已撤销，禁止放行: certificateId=" + String.join(",", revoked));
        }

        BatchStats.Values statsValues = BatchStats.compute(current);
        Instant releasedAt = Instant.now();
        BatchRelease snapshot = new BatchRelease(0L, batch.id(), batch.revision(), releaser, releasedAt,
                statsValues.includedCount(), statsValues.excludedCount(),
                statsValues.mean(), statsValues.min(), statsValues.max());
        releases.insert(snapshot);
        batches.markReleased(batch.id());
        return BatchDtoMapper.toResponse(batch, snapshot);
    }
}
