package com.example.coral.service;

import java.time.Clock;
import java.util.List;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.QuadratStatus;
import com.example.coral.domain.Review;
import com.example.coral.domain.Transplant;
import com.example.coral.repo.QuadratRepository;
import com.example.coral.repo.ReviewRepository;
import com.example.coral.repo.TransplantRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 移植登记与复核：容量上限12、植株编号唯一、requestId幂等、死亡释放容量。
 * 并发通过样方行锁按事务提交顺序裁决，任何时刻活跃数不超过12。
 */
@Service
public class TransplantService {

    /** 每个样方最多容纳的活跃移植体数量。 */
    public static final int MAX_ACTIVE_PER_QUADRAT = 12;

    private final QuadratRepository quadratRepo;
    private final TransplantRepository transplantRepo;
    private final ReviewRepository reviewRepo;
    private final Clock clock;

    public TransplantService(QuadratRepository quadratRepo, TransplantRepository transplantRepo,
            ReviewRepository reviewRepo, Clock clock) {
        this.quadratRepo = quadratRepo;
        this.transplantRepo = transplantRepo;
        this.reviewRepo = reviewRepo;
        this.clock = clock;
    }

    /**
     * 移植登记：固化来源苗圃、样方与UTC时刻。
     * 同requestId同参重放返回原记录；异参返回409。
     */
    @Transactional
    public Transplant register(String requestId, String plantCode, String nursery,
            String quadratCode) {
        var replay = transplantRepo.findByRequestId(requestId);
        if (replay.isPresent()) {
            Transplant existing = replay.get();
            var quadrat = quadratRepo.lockById(existing.quadratId())
                    .orElseThrow(() -> BusinessException.unprocessable("样方不存在"));
            if (existing.plantCode().equals(plantCode) && existing.nursery().equals(nursery)
                    && quadrat.quadratCode().equals(quadratCode)) {
                return existing;
            }
            throw BusinessException.conflict("requestId已使用且参数不一致");
        }
        var quadrat = quadratRepo.lockByCode(quadratCode)
                .orElseThrow(() -> BusinessException.unprocessable("样方不存在: " + quadratCode));
        if (quadrat.status() == QuadratStatus.SEALED) {
            throw BusinessException.conflict("样方已封存，禁止新增移植");
        }
        if (transplantRepo.findByPlantCode(plantCode).isPresent()) {
            throw BusinessException.conflict("植株编号已存在: " + plantCode);
        }
        if (transplantRepo.countActiveByQuadratId(quadrat.id()) >= MAX_ACTIVE_PER_QUADRAT) {
            throw BusinessException.conflict("样方容量已满（12株活跃移植体）");
        }
        try {
            long id = transplantRepo.insert(requestId, plantCode, nursery, quadrat.id(),
                    clock.instant());
            return transplantRepo.findByPlantCode(plantCode).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw resolveDuplicate(requestId, plantCode);
        }
    }

    /**
     * 复核：将植株标为存活、死亡或待复核；死亡释放容量但记录保留。
     * 死亡后不得再标存活；已封存样方禁止复核。同requestId同参重放返回原记录。
     */
    @Transactional
    public Review review(String requestId, String plantCode, PlantStatus result, String note) {
        var replay = reviewRepo.findByRequestId(requestId);
        if (replay.isPresent()) {
            Review existing = replay.get();
            var owner = transplantRepo.findByPlantCode(plantCode)
                    .orElseThrow(() -> BusinessException.unprocessable("植株不存在: " + plantCode));
            if (existing.transplantId() == owner.id() && existing.result() == result
                    && java.util.Objects.equals(existing.note(), note)) {
                return existing;
            }
            throw BusinessException.conflict("requestId已使用且参数不一致");
        }
        var transplant = transplantRepo.findByPlantCode(plantCode)
                .orElseThrow(() -> BusinessException.unprocessable("植株不存在: " + plantCode));
        var quadrat = quadratRepo.lockById(transplant.quadratId())
                .orElseThrow(() -> BusinessException.unprocessable("样方不存在"));
        if (quadrat.status() == QuadratStatus.SEALED) {
            throw BusinessException.conflict("样方已封存，禁止复核");
        }
        if (transplant.status() == PlantStatus.DEAD && result != PlantStatus.DEAD) {
            throw BusinessException.conflict("死亡植株不得再标为存活或待复核");
        }
        try {
            long id = reviewRepo.insert(requestId, transplant.id(), result, note, clock.instant());
            transplantRepo.updateStatus(transplant.id(), result);
            return reviewRepo.findByRequestId(requestId).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("requestId已使用且参数不一致");
        }
    }

    /** 查询单株：来源苗圃、当前样方与复核链由调用方组装。 */
    @Transactional(readOnly = true)
    public Transplant findByPlantCode(String plantCode) {
        return transplantRepo.findByPlantCode(plantCode)
                .orElseThrow(() -> BusinessException.unprocessable("植株不存在: " + plantCode));
    }

    /** 按幂等请求编号查询移植记录，用于重放判定。 */
    @Transactional(readOnly = true)
    public java.util.Optional<Transplant> findByRequestId(String requestId) {
        return transplantRepo.findByRequestId(requestId);
    }
    @Transactional(readOnly = true)
    public List<Review> reviewChain(long transplantId) {
        return reviewRepo.findByTransplantId(transplantId);
    }

    private BusinessException resolveDuplicate(String requestId, String plantCode) {
        if (transplantRepo.findByRequestId(requestId).isPresent()) {
            return BusinessException.conflict("requestId已使用且参数不一致");
        }
        return BusinessException.conflict("植株编号已存在: " + plantCode);
    }
}