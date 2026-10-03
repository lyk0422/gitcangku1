package com.example.starter.pkg;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.ApiResult;
import com.example.starter.common.BizException;
import com.example.starter.common.IdempotencyService;
import com.example.starter.common.StoredResponse;
import com.example.starter.pkg.PackageDtos.AnomalyRequest;
import com.example.starter.pkg.PackageDtos.AnomalyResponse;
import com.example.starter.pkg.PackageDtos.HandoverRequest;
import com.example.starter.pkg.PackageDtos.PackageResponse;
import com.example.starter.pkg.PackageDtos.ResealRequest;
import com.example.starter.pkg.PackageDtos.SealRequest;
import com.example.starter.stratum.StratumDtos.StratumRef;
import com.example.starter.stratum.StratumService;

/**
 * 封存包服务：封存、交接、封签异常、重新封存。
 * 同层位最多 20 件样本；整包原子；requestId 幂等；并发争夺样本最多一个成功。
 */
@Service
public class PackageService {

    private static final int MAX_SAMPLES_PER_PACKAGE = 20;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final StratumService strata;
    private final IdempotencyService idempotency;

    public PackageService(JdbcClient jdbc, Clock clock, StratumService strata, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.strata = strata;
        this.idempotency = idempotency;
    }

    /** 封存：校验层位/数量/样本状态后整包落库，任一样本失败则整包回滚。 */
    @Transactional
    public StoredResponse seal(SealRequest req) {
        return idempotency.execute(req.requestId(), "SEAL", req, () -> doSeal(req));
    }

    /** 交接：锁定样本清单与封签编号；封签破损后禁止交接。 */
    @Transactional
    public StoredResponse handover(String packageNo, HandoverRequest req) {
        return idempotency.execute(req.requestId(), "HANDOVER",
                List.of(packageNo, req.operator(), req.receiver()), () -> doHandover(packageNo, req));
    }

    /** 封签破损：仅追加异常记录并停止后续交接，原封签保留不删除。 */
    @Transactional
    public StoredResponse reportAnomaly(String packageNo, AnomalyRequest req) {
        return idempotency.execute(req.requestId(), "ANOMALY",
                List.of(packageNo, req.note(), req.operator()), () -> doReportAnomaly(packageNo, req));
    }

    /** 重新封存：基于破损旧包生成新版本包，并引用旧异常记录。 */
    @Transactional
    public StoredResponse reseal(ResealRequest req) {
        return idempotency.execute(req.requestId(), "RESEAL", req, () -> doReseal(req));
    }

    private ApiResult doSeal(SealRequest req) {
        List<String> nos = req.sampleNos() == null ? List.of() : req.sampleNos();
        if (nos.isEmpty()) {
            throw BizException.unprocessable("封存包至少包含 1 件样本");
        }
        if (nos.size() > MAX_SAMPLES_PER_PACKAGE) {
            throw BizException.unprocessable("封存包最多 " + MAX_SAMPLES_PER_PACKAGE + " 件样本，当前 " + nos.size() + " 件");
        }
        if (new HashSet<>(nos).size() != nos.size()) {
            throw BizException.unprocessable("请求内样本编号重复");
        }
        StratumRef stratum = strata.findByCode(req.siteCode(), req.stratumNo())
                .orElseThrow(() -> BizException.unprocessable("层位未登记: " + req.siteCode() + "/" + req.stratumNo()));
        Map<String, SampleRow> samples = new HashMap<>();
        for (String no : nos) {
            SampleRow s = findSample(no);
            if (s.stratumId() != stratum.id()) {
                throw BizException.unprocessable("跨层位混包被拒绝: " + no + " 不属于层位 " + req.siteCode() + "/" + req.stratumNo());
            }
            samples.put(no, s);
        }
        Instant now = clock.instant();
        KeyHolder keys = new GeneratedKeyHolder();
        long pkgId;
        try {
            jdbc.sql("INSERT INTO seal_package(package_no, lineage_no, version, stratum_id, seal_no, status, prev_anomaly_id, created_at)"
                    + " VALUES (?,?,1,?,?,'SEALED',NULL,?)")
                    .params(req.packageNo(), req.packageNo(), stratum.id(), req.sealNo(), Timestamp.from(now))
                    .update(keys);
            pkgId = keys.getKey().longValue();
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("封存包编号已存在: " + req.packageNo());
        }
        insertEvent(pkgId, "SEALED", req.sealNo(), "封存人:" + req.operator());
        for (String no : nos.stream().sorted().toList()) {
            SampleRow s = samples.get(no);
            int updated = jdbc.sql("UPDATE sample SET package_id=?, status='SEALED' WHERE id=? AND package_id IS NULL")
                    .params(pkgId, s.id())
                    .update();
            if (updated == 0) {
                throw BizException.conflict("样本已被其他封存包占用: " + no);
            }
            jdbc.sql("INSERT INTO package_item(package_id, sample_id) VALUES (?,?)").params(pkgId, s.id()).update();
        }
        return ApiResult.created(new PackageResponse(req.packageNo(), req.packageNo(), 1, req.sealNo(), "SEALED", nos.size(), null));
    }
    private ApiResult doHandover(String packageNo, HandoverRequest req) {
        PkgRow pkg = lockPackage(packageNo);
        if ("SEAL_BROKEN".equals(pkg.status())) {
            throw BizException.unprocessable("封签破损，已停止后续交接: " + packageNo);
        }
        if ("HANDED_OVER".equals(pkg.status())) {
            throw BizException.conflict("封存包已交接: " + packageNo);
        }
        jdbc.sql("UPDATE seal_package SET status='HANDED_OVER' WHERE id=?").param(pkg.id()).update();
        insertEvent(pkg.id(), "HANDED_OVER", pkg.sealNo(), "交接人:" + req.operator() + " 接收方:" + req.receiver());
        int count = jdbc.sql("UPDATE sample SET status='HANDED_OVER' WHERE package_id=?").param(pkg.id()).update();
        return ApiResult.ok(new PackageResponse(pkg.packageNo(), pkg.lineageNo(), pkg.version(), pkg.sealNo(),
                "HANDED_OVER", count, null));
    }

    private ApiResult doReportAnomaly(String packageNo, AnomalyRequest req) {
        PkgRow pkg = lockPackage(packageNo);
        long eventId = insertEvent(pkg.id(), "SEAL_BROKEN", pkg.sealNo(), "异常:" + req.note() + " 上报人:" + req.operator());
        String status = pkg.status();
        if ("SEALED".equals(status)) {
            jdbc.sql("UPDATE seal_package SET status='SEAL_BROKEN' WHERE id=?").param(pkg.id()).update();
            status = "SEAL_BROKEN";
        }
        return ApiResult.created(new AnomalyResponse(eventId, pkg.packageNo(), pkg.sealNo(), status));
    }

    private ApiResult doReseal(ResealRequest req) {
        PkgRow old = lockPackage(req.oldPackageNo());
        if (!"SEAL_BROKEN".equals(old.status())) {
            throw BizException.unprocessable("仅封签破损的包可重新封存: " + req.oldPackageNo());
        }
        Long anomalyId = jdbc.sql("SELECT id FROM seal_event WHERE package_id=? AND event_type='SEAL_BROKEN' ORDER BY id DESC LIMIT 1")
                .param(old.id())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> BizException.unprocessable("缺少封签异常记录: " + req.oldPackageNo()));
        KeyHolder keys = new GeneratedKeyHolder();
        long newId;
        try {
            jdbc.sql("INSERT INTO seal_package(package_no, lineage_no, version, stratum_id, seal_no, status, prev_anomaly_id, created_at)"
                    + " VALUES (?,?,?,?,?,'SEALED',?,?)")
                    .params(req.packageNo(), old.lineageNo(), old.version() + 1, old.stratumId(), req.sealNo(),
                            anomalyId, Timestamp.from(clock.instant()))
                    .update(keys);
            newId = keys.getKey().longValue();
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("封存包编号或版本冲突: " + req.packageNo());
        }
        jdbc.sql("INSERT INTO package_item(package_id, sample_id) SELECT ?, sample_id FROM package_item WHERE package_id=?")
                .params(newId, old.id())
                .update();
        int moved = jdbc.sql("UPDATE sample SET package_id=?, status='SEALED' WHERE package_id=?")
                .params(newId, old.id())
                .update();
        insertEvent(newId, "RESEALED", req.sealNo(), "重新封存，引用异常#" + anomalyId + " 操作人:" + req.operator());
        return ApiResult.created(new PackageResponse(req.packageNo(), old.lineageNo(), old.version() + 1, req.sealNo(),
                "SEALED", moved, anomalyId));
    }

    private SampleRow findSample(String sampleNo) {
        return jdbc.sql("SELECT id, sample_no, stratum_id, status FROM sample WHERE sample_no=?")
                .param(sampleNo)
                .query((rs, n) -> new SampleRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)))
                .optional()
                .orElseThrow(() -> BizException.unprocessable("样本未采集，不能入包: " + sampleNo));
    }

    private PkgRow lockPackage(String packageNo) {
        return jdbc.sql("SELECT id, package_no, lineage_no, version, stratum_id, seal_no, status FROM seal_package WHERE package_no=? FOR UPDATE")
                .param(packageNo)
                .query((rs, n) -> new PkgRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getLong(5), rs.getString(6), rs.getString(7)))
                .optional()
                .orElseThrow(() -> BizException.unprocessable("封存包不存在: " + packageNo));
    }

    private long insertEvent(long packageId, String type, String sealNo, String detail) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO seal_event(package_id, event_type, seal_no, detail, occurred_at) VALUES (?,?,?,?,?)")
                .params(packageId, type, sealNo, detail, Timestamp.from(clock.instant()))
                .update(keys);
        return keys.getKey().longValue();
    }

    private record PkgRow(long id, String packageNo, String lineageNo, int version, long stratumId, String sealNo,
            String status) {
    }

    private record SampleRow(long id, String sampleNo, long stratumId, String status) {
    }
}