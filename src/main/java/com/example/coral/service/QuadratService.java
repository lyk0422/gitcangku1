package com.example.coral.service;

import java.time.Clock;
import java.util.List;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.Quadrat;
import com.example.coral.domain.QuadratStatus;
import com.example.coral.domain.SealSnapshot;
import com.example.coral.domain.Transplant;
import com.example.coral.repo.QuadratRepository;
import com.example.coral.repo.SealSnapshotRepository;
import com.example.coral.repo.TransplantRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 样方管理与封存：封存前必须没有待复核植株，固化各状态数量及植株清单。
 * 封存与复核通过样方行锁竞争，唯一约束保证只形成一个清单版本。
 */
@Service
public class QuadratService {

    private final QuadratRepository quadratRepo;
    private final TransplantRepository transplantRepo;
    private final SealSnapshotRepository sealRepo;
    private final Clock clock;

    public QuadratService(QuadratRepository quadratRepo, TransplantRepository transplantRepo,
            SealSnapshotRepository sealRepo, Clock clock) {
        this.quadratRepo = quadratRepo;
        this.transplantRepo = transplantRepo;
        this.sealRepo = sealRepo;
        this.clock = clock;
    }

    /** 创建合成样方；编号重复返回409。 */
    @Transactional
    public Quadrat create(String quadratCode, String name) {
        try {
            long id = quadratRepo.insert(quadratCode, name, clock.instant());
            return quadratRepo.findByCode(quadratCode).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("样方编号已存在: " + quadratCode);
        }
    }

    /**
     * 封存样方：必须没有待复核植株；快照保留死亡记录。
     * 已封存时重放返回原快照，保证只读一致的清单版本。
     */
    @Transactional
    public SealSnapshot seal(String quadratCode) {
        var quadrat = quadratRepo.lockByCode(quadratCode)
                .orElseThrow(() -> BusinessException.unprocessable("样方不存在: " + quadratCode));
        var existing = sealRepo.findByQuadratId(quadrat.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        int pending = transplantRepo.countByQuadratIdAndStatus(quadrat.id(), PlantStatus.PENDING);
        if (pending > 0) {
            throw BusinessException.conflict("存在" + pending + "株待复核植株，禁止封存");
        }
        List<Transplant> all = transplantRepo.findByQuadratId(quadrat.id());
        int alive = (int) all.stream().filter(t -> t.status() == PlantStatus.ALIVE).count();
        int dead = (int) all.stream().filter(t -> t.status() == PlantStatus.DEAD).count();
        List<String> plantCodes = all.stream().map(Transplant::plantCode).toList();
        var now = clock.instant();
        try {
            long id = sealRepo.insert(quadrat.id(), alive, dead, 0, plantCodes, now);
            quadratRepo.markSealed(quadrat.id(), now);
            return sealRepo.findByQuadratId(quadrat.id()).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("样方已封存");
        }
    }

    @Transactional(readOnly = true)
    public Quadrat findByCode(String quadratCode) {
        return quadratRepo.findByCode(quadratCode)
                .orElseThrow(() -> BusinessException.unprocessable("样方不存在: " + quadratCode));
    }

    /** 按主键查询样方，用于组装单株详情中的当前样方编号。 */
    @Transactional(readOnly = true)
    public Quadrat findById(long id) {
        return quadratRepo.findById(id)
                .orElseThrow(() -> BusinessException.unprocessable("样方不存在"));
    }
    @Transactional(readOnly = true)
    public List<Transplant> plantsOf(long quadratId) {
        return transplantRepo.findByQuadratId(quadratId);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<SealSnapshot> snapshotOf(long quadratId) {
        return sealRepo.findByQuadratId(quadratId);
    }
}