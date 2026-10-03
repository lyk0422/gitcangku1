package com.example.starter.stratum;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.BizException;
import com.example.starter.stratum.StratumDtos.RegisterRequest;
import com.example.starter.stratum.StratumDtos.StratumRef;
import com.example.starter.stratum.StratumDtos.StratumResponse;

/**
 * 层位登记与查询：遗址编号+层位号唯一标识一个层位。
 */
@Service
public class StratumService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public StratumService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 登记层位；遗址编号+层位号重复时返回 409。 */
    @Transactional
    public StratumResponse register(RegisterRequest req) {
        Instant now = clock.instant();
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO stratum(site_code, stratum_no, description, created_at) VALUES (?,?,?,?)")
                    .params(req.siteCode(), req.stratumNo(), req.description(), Timestamp.from(now))
                    .update(keys);
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("层位已登记: " + req.siteCode() + "/" + req.stratumNo());
        }
        return new StratumResponse(keys.getKey().longValue(), req.siteCode(), req.stratumNo(), req.description(), now);
    }

    /** 按遗址编号+层位号查询层位。 */
    public Optional<StratumRef> findByCode(String siteCode, String stratumNo) {
        return jdbc.sql("SELECT id, site_code, stratum_no FROM stratum WHERE site_code=? AND stratum_no=?")
                .params(siteCode, stratumNo)
                .query((rs, n) -> new StratumRef(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .optional();
    }
}