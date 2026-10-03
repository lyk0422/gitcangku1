package com.example.starter.coral;

import com.example.starter.coral.CoralRepository.PlotRow;
import com.example.starter.coral.CoralRepository.ReviewRow;
import com.example.starter.coral.CoralRepository.SealRow;
import com.example.starter.coral.CoralRepository.TransplantRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 珊瑚移植业务入口：移植登记、复核、样方封存与查询。
 * 容量与封存通过样方行锁在事务内序列化，按事务提交顺序裁决竞争。
 */
@Service
public class CoralService {

    private final CoralRepository repository;
    private final Clock clock;

    public CoralService(CoralRepository repository, Clock coralClock) {
        this.repository = repository;
        this.clock = coralClock;
    }

    /**
     * 登记移植：固化来源苗圃、样方与UTC时刻。同requestId同参重放返回原记录，异参409。
     */
    @Transactional
    public TransplantView registerTransplant(RegisterTransplantRequest request) {
        TransplantRow replay = repository.findTransplantByRequestId(request.requestId());
        if (replay != null) {
            PlotRow replayPlot = repository.findPlotByIdForUpdate(replay.plotId());
            if (replay.plantCode().equals(request.plantCode())
                    && replay.nursery().equals(request.nursery())
                    && replayPlot.plotCode().equals(request.plotCode())) {
                return toTransplantView(replay, replayPlot);
            }
            throw BizException.conflict("requestId已使用且参数不一致");
        }
        PlotRow plot = repository.findPlotForUpdate(request.plotCode());
        if (plot == null) {
            throw BizException.notFound("样方不存在: " + request.plotCode());
        }
        if (plot.status() == PlotStatus.SEALED) {
            throw BizException.conflict("样方已封存，禁止新增移植");
        }
        if (repository.findTransplantByPlantCode(request.plantCode()) != null) {
            throw BizException.conflict("植株编号已存在: " + request.plantCode());
        }
        if (repository.countActiveTransplants(plot.id()) >= plot.capacity()) {
            throw BizException.conflict("样方活跃移植体已满" + plot.capacity() + "株");
        }
        repository.insertTransplant(request.requestId(), request.plantCode(),
                request.nursery(), plot.id(), Instant.now(clock));
        return toTransplantView(repository.findTransplantByPlantCode(request.plantCode()), plot);
    }

    /**
     * 复核：将植株标为存活、死亡或待复核。死亡为终态，不得再标存活或待复核。
     */
    @Transactional
    public TransplantView review(String plantCode, ReviewRequest request) {
        ReviewRow replay = repository.findReviewByRequestId(request.requestId());
        if (replay != null) {
            TransplantRow owner = repository.findTransplantByPlantCode(plantCode);
            if (owner != null && owner.id() == replay.transplantId()
                    && replay.result() == request.result()) {
                PlotRow plot = repository.findPlotByIdForUpdate(owner.plotId());
                return toTransplantView(owner, plot);
            }
            throw BizException.conflict("requestId已使用且参数不一致");
        }
        TransplantRow transplant = repository.findTransplantByPlantCode(plantCode);
        if (transplant == null) {
            throw BizException.notFound("植株不存在: " + plantCode);
        }
        PlotRow plot = repository.findPlotByIdForUpdate(transplant.plotId());
        if (plot.status() == PlotStatus.SEALED) {
            throw BizException.conflict("样方已封存，禁止复核");
        }
        if (transplant.status() == TransplantStatus.DEAD
                && request.result() != TransplantStatus.DEAD) {
            throw BizException.unprocessable("死亡植株不得再标为存活或待复核");
        }
        repository.insertReview(request.requestId(), transplant.id(), request.result(),
                Instant.now(clock));
        repository.updateTransplantStatus(transplant.id(), request.result());
        return toTransplantView(repository.findTransplantByPlantCode(plantCode), plot);
    }

    /**
     * 封存样方：要求无待复核植株；固化各状态数量与植株清单，同一事务提交保证清单版本一致。
     */
    @Transactional
    public SealView sealPlot(String plotCode, SealRequest request) {
        PlotRow plot = repository.findPlotForUpdate(plotCode);
        if (plot == null) {
            throw BizException.notFound("样方不存在: " + plotCode);
        }
        SealRow existing = repository.findSealByPlotId(plot.id());
        if (existing != null) {
            if (existing.requestId().equals(request.requestId())) {
                return toSealView(plotCode, existing);
            }
            throw BizException.conflict("样方已封存");
        }
        List<TransplantRow> transplants = repository.findTransplantsByPlotId(plot.id());
        long pending = transplants.stream()
                .filter(t -> t.status() == TransplantStatus.PENDING).count();
        if (pending > 0) {
            throw BizException.unprocessable("存在" + pending + "株待复核植株，禁止封存");
        }
        int alive = (int) transplants.stream()
                .filter(t -> t.status() == TransplantStatus.ALIVE).count();
        int dead = (int) transplants.stream()
                .filter(t -> t.status() == TransplantStatus.DEAD).count();
        List<SealPlantItem> plants = transplants.stream()
                .map(t -> new SealPlantItem(t.plantCode(), t.status()))
                .toList();
        Instant sealedAt = Instant.now(clock);
        repository.insertSeal(request.requestId(), plot.id(), sealedAt, alive, dead, 0, plants);
        return new SealView(plotCode, request.requestId(), sealedAt, alive, dead, 0, plants);
    }

    /** 查询单株：来源苗圃、当前样方、复核链与所属样方封存摘要。 */
    @Transactional(readOnly = true)
    public TransplantView getTransplant(String plantCode) {
        TransplantRow transplant = repository.findTransplantByPlantCode(plantCode);
        if (transplant == null) {
            throw BizException.notFound("植株不存在: " + plantCode);
        }
        PlotRow plot = repository.findPlotById(transplant.plotId());
        return toTransplantView(transplant, plot);
    }

    /** 查询样方：容量、各状态在册数量与封存快照。 */
    @Transactional(readOnly = true)
    public PlotView getPlot(String plotCode) {
        PlotRow plot = repository.findPlotByCode(plotCode);
        if (plot == null) {
            throw BizException.notFound("样方不存在: " + plotCode);
        }
        List<TransplantRow> transplants = repository.findTransplantsByPlotId(plot.id());
        long alive = transplants.stream()
                .filter(t -> t.status() == TransplantStatus.ALIVE).count();
        long dead = transplants.stream()
                .filter(t -> t.status() == TransplantStatus.DEAD).count();
        long pending = transplants.stream()
                .filter(t -> t.status() == TransplantStatus.PENDING).count();
        SealRow seal = repository.findSealByPlotId(plot.id());
        return new PlotView(plot.plotCode(), plot.status(), plot.capacity(), alive + pending,
                alive, dead, pending, seal == null ? null : toSealView(plotCode, seal));
    }

    private TransplantView toTransplantView(TransplantRow transplant, PlotRow plot) {
        List<ReviewView> reviews = repository.findReviewsByTransplantId(transplant.id())
                .stream()
                .map(r -> new ReviewView(r.requestId(), r.result(), r.reviewedAt()))
                .toList();
        return new TransplantView(transplant.plantCode(), transplant.nursery(), plot.plotCode(),
                transplant.status(), transplant.transplantedAt(), reviews,
                toSealSummary(plot.id()));
    }

    private SealSummary toSealSummary(long plotId) {
        SealRow seal = repository.findSealByPlotId(plotId);
        if (seal == null) {
            return SealSummary.notSealed();
        }
        return new SealSummary(true, seal.sealedAt(), seal.aliveCount(), seal.deadCount(),
                seal.pendingCount());
    }

    private SealView toSealView(String plotCode, SealRow seal) {
        return new SealView(plotCode, seal.requestId(), seal.sealedAt(), seal.aliveCount(),
                seal.deadCount(), seal.pendingCount(), seal.plants());
    }
}