package com.example.starter.sample;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.ApiResult;
import com.example.starter.common.BizException;
import com.example.starter.common.IdempotencyService;
import com.example.starter.common.StoredResponse;
import com.example.starter.sample.SampleDtos.CollectRequest;
import com.example.starter.sample.SampleDtos.SampleResponse;
import com.example.starter.stratum.StratumDtos.StratumRef;
import com.example.starter.stratum.StratumService;

/**
 * 样本采集：编号全项目唯一；采集时固化层位、坐标格与 UTC 时刻；requestId 幂等。
 * 样本一经交接不得转入另一层位或改写采集坐标：系统不提供层位/坐标修改入口，
 * 已入包样本由 package_id 归属约束保护，已交接样本状态为 HANDED_OVER 不可再入包。
 */
@Service
public class SampleService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final StratumService strata;
    private final IdempotencyService idempotency;

    public SampleService(JdbcClient jdbc, Clock clock, StratumService strata, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.strata = strata;
        this.idempotency = idempotency;
    }

    /** 采集样本；编号重复（含编号相同层位不同）返回 409，层位未登记返回 422。 */
    @Transactional
    public StoredResponse collect(CollectRequest req) {
        return idempotency.execute(req.requestId(), "COLLECT", req, () -> doCollect(req));
    }

    private ApiResult doCollect(CollectRequest req) {
        StratumRef stratum = strata.findByCode(req.siteCode(), req.stratumNo())
                .orElseThrow(() -> BizException.unprocessable("层位未登记: " + req.siteCode() + "/" + req.stratumNo()));
        Instant now = clock.instant();
        try {
            jdbc.sql("INSERT INTO sample(sample_no, stratum_id, grid_ref, collected_at, status) VALUES (?,?,?,?,'COLLECTED')")
                    .params(req.sampleNo(), stratum.id(), req.gridRef(), Timestamp.from(now))
                    .update();
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("样本编号已存在（编号相同层位不同亦视为冲突，不当作同一件）: " + req.sampleNo());
        }
        return ApiResult.created(
                new SampleResponse(req.sampleNo(), req.siteCode(), req.stratumNo(), req.gridRef(), now, "COLLECTED"));
    }
}