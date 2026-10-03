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
import com.example.starter.web.dto.Requests.AddItemRequest;
import com.example.starter.web.dto.Requests.CreatePackageRequest;
import com.example.starter.web.dto.Requests.DamageRequest;
import com.example.starter.web.dto.Requests.HandoverRequest;
import com.example.starter.web.dto.Responses.PackageResponse;
import com.example.starter.web.dto.Responses.SealEventView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 封存包服务：装包、交接、封签异常与重新封存。同一层位最多 20 件样本，
 * 交接整包原子生效；封签破损只追加异常记录并停止后续交接。
 */
@Service
public class PackageService {

    /** 单个封存包容量上限。 */
    public static final int PACKAGE_CAPACITY = 20;

    private final StratumRepository stratumRepository;
    private final SampleRepository sampleRepository;
    private final PackageRepository packageRepository;
    private final SealEventRepository sealEventRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public PackageService(StratumRepository stratumRepository, SampleRepository sampleRepository,
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
     * 在指定层位建包。
     */
    @Transactional
    public PackageResponse create(CreatePackageRequest req) {
        Stratum stratum = stratumRepository.findByKey(req.siteCode(), req.stratumNo())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "STRATUM_NOT_FOUND", "层位不存在，请先登记"));
        long id = packageRepository.insert(stratum.id(), Instant.now(clock));
        packageRepository.assignPackageNo(id, "PKG-%08d".formatted(id));
        return toResponse(packageRepository.findById(id).orElseThrow());
    }

    /**
     * 样本入包：跨层位 422，超过 20 件 422，未采集 422，样本已被占用 409。
     */
    @Transactional
    public PackageResponse addItem(String packageNo, AddItemRequest req) {
        SealPackage pkg = lockPackage(packageNo);
        requireOpen(pkg);
        Sample sample = sampleRepository.findBySampleNo(req.sampleNo())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "SAMPLE_NOT_COLLECTED", "样本未采集，不能入包"));
        if (sample.stratumId() != pkg.stratumId()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CROSS_STRATUM", "样本与封存包不属于同一层位，禁止跨层位混包");
        }
        if (packageRepository.countItems(pkg.id()) >= PACKAGE_CAPACITY) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PACKAGE_FULL", "封存包已满 20 件，整包拒绝");
        }
        int assigned = sampleRepository.assignToPackage(sample.id(), pkg.id());
        if (assigned == 0) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "SAMPLE_ALREADY_ASSIGNED", "样本已在其他封存包中");
        }
        try {
            packageRepository.addItem(pkg.id(), sample.id(), Instant.now(clock));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "SAMPLE_ALREADY_ASSIGNED", "样本已在该封存包中");
        }
        packageRepository.bumpVersion(pkg.id());
        return toResponse(packageRepository.findById(pkg.id()).orElseThrow());
    }

    /**
     * 交接（幂等）：锁定样本清单与封签编号，整包原子生效。
     */
    @Transactional
    public IdempotentOutcome handover(String packageNo, HandoverRequest req) {
        return idempotencyService.execute(req.requestId(), "HANDOVER", req,
                HttpStatus.OK.value(), () -> doHandover(packageNo, req));
    }

    private PackageResponse doHandover(String packageNo, HandoverRequest req) {
        SealPackage pkg = lockPackage(packageNo);
        switch (pkg.status()) {
            case "OPEN" -> { }
            case "HANDED_OVER" -> throw new BusinessException(HttpStatus.CONFLICT,
                    "PACKAGE_ALREADY_HANDED_OVER", "封存包已交接");
            default -> throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PACKAGE_NOT_HANDOVERABLE", "封签异常或已取代的封存包停止交接");
        }
        if (packageRepository.countItems(pkg.id()) == 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PACKAGE_EMPTY", "空包不能交接");
        }
        Instant now = Instant.now(clock);
        packageRepository.markHandedOver(pkg.id(), req.sealNo(), now);
        sampleRepository.updateStatusByPackage(pkg.id(), "HANDED_OVER");
        String note = req.receiver() == null ? "交接完成，锁定样本清单与封签编号"
                : "交接完成，接收方：" + req.receiver();
        sealEventRepository.insert(pkg.id(), "HANDED_OVER", req.sealNo(), note, now);
        return toResponse(packageRepository.findById(pkg.id()).orElseThrow());
    }

    /**
     * 封签破损上报：只追加异常记录并停止后续交接，不删除原封签。
     */
    @Transactional
    public PackageResponse reportDamage(String packageNo, DamageRequest req) {
        SealPackage pkg = lockPackage(packageNo);
        switch (pkg.status()) {
            case "OPEN", "ABNORMAL" -> { }
            case "HANDED_OVER" -> throw new BusinessException(HttpStatus.CONFLICT,
                    "PACKAGE_ALREADY_HANDED_OVER", "封存包已交接，封签状态不可再变更");
            default -> throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PACKAGE_SUPERSEDED", "封存包已被重新封存取代");
        }
        sealEventRepository.insert(pkg.id(), "DAMAGED", pkg.sealNo(), req.note(), Instant.now(clock));
        packageRepository.updateStatus(pkg.id(), "ABNORMAL");
        return toResponse(packageRepository.findById(pkg.id()).orElseThrow());
    }

    /**
     * 重新封存：生成新包并引用旧包异常记录，旧包标记为已取代。
     */
    @Transactional
    public PackageResponse reseal(String packageNo) {
        SealPackage pkg = lockPackage(packageNo);
        if (!"ABNORMAL".equals(pkg.status())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PACKAGE_NOT_ABNORMAL", "仅封签异常的封存包可重新封存");
        }
        long damageEventId = sealEventRepository.findLatestEventId(pkg.id(), "DAMAGED")
                .orElseThrow(() -> new IllegalStateException("异常包缺少破损记录"));
        Instant now = Instant.now(clock);
        long newId = packageRepository.insert(pkg.stratumId(), now);
        String newPackageNo = "PKG-%08d".formatted(newId);
        packageRepository.assignPackageNo(newId, newPackageNo);
        packageRepository.setLineage(newId, pkg.id(), damageEventId);
        packageRepository.copyItems(pkg.id(), newId, now);
        sampleRepository.movePackage(pkg.id(), newId);
        packageRepository.updateStatus(pkg.id(), "SUPERSEDED");
        sealEventRepository.insert(pkg.id(), "SUPERSEDED", pkg.sealNo(),
                "重新封存，由新包 " + newPackageNo + " 接替", now);
        sealEventRepository.insert(newId, "RESEAL_CREATED", null,
                "由异常包 " + pkg.packageNo() + " 重新封存，引用异常记录 #" + damageEventId, now);
        return toResponse(packageRepository.findById(newId).orElseThrow());
    }

    /**
     * 查询封存包：展示版本、样本清单与每次封签状态。
     */
    @Transactional(readOnly = true)
    public PackageResponse getByPackageNo(String packageNo) {
        SealPackage pkg = packageRepository.findByPackageNo(packageNo)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "PACKAGE_NOT_FOUND", "封存包不存在"));
        return toResponse(pkg);
    }

    private SealPackage lockPackage(String packageNo) {
        return packageRepository.findByPackageNoForUpdate(packageNo)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "PACKAGE_NOT_FOUND", "封存包不存在"));
    }

    private void requireOpen(SealPackage pkg) {
        if (!"OPEN".equals(pkg.status())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PACKAGE_NOT_OPEN", "封存包当前状态不允许入包");
        }
    }

    private PackageResponse toResponse(SealPackage pkg) {
        Stratum stratum = stratumRepository.findById(pkg.stratumId()).orElseThrow();
        List<String> items = sampleRepository.findByPackageId(pkg.id()).stream()
                .map(Sample::sampleNo).toList();
        List<SealEventView> events = sealEventRepository.listByPackageId(pkg.id()).stream()
                .map(e -> new SealEventView(e.id(), e.eventType(), e.sealNo(), e.note(), e.recordedAt()))
                .toList();
        return new PackageResponse(pkg.id(), pkg.packageNo(), stratum.siteCode(), stratum.stratumNo(),
                pkg.status(), pkg.sealNo(), pkg.version(), items, events, pkg.supersedesPackageId(),
                pkg.sourceEventId(), pkg.createdAt(), pkg.handedOverAt());
    }
}