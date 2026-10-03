package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.domain.Stratum;
import com.example.starter.error.BusinessException;
import com.example.starter.repo.StratumRepository;
import com.example.starter.web.dto.Requests.RegisterStratumRequest;
import com.example.starter.web.dto.Responses.StratumResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 遗址层位登记服务。
 */
@Service
public class StratumService {

    private final StratumRepository stratumRepository;
    private final Clock clock;

    public StratumService(StratumRepository stratumRepository, Clock clock) {
        this.stratumRepository = stratumRepository;
        this.clock = clock;
    }

    /**
     * 登记层位；遗址编号+层位号重复时返回 409。
     */
    @Transactional
    public StratumResponse register(RegisterStratumRequest req) {
        stratumRepository.findByKey(req.siteCode(), req.stratumNo()).ifPresent(existing -> {
            throw new BusinessException(HttpStatus.CONFLICT, "STRATUM_EXISTS", "遗址编号与层位号组合已存在");
        });
        long id;
        try {
            id = stratumRepository.insert(req.siteCode(), req.stratumNo(), req.description(),
                    Instant.now(clock));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "STRATUM_EXISTS", "遗址编号与层位号组合已存在");
        }
        return toResponse(stratumRepository.findById(id).orElseThrow());
    }

    static StratumResponse toResponse(Stratum stratum) {
        return new StratumResponse(stratum.id(), stratum.siteCode(), stratum.stratumNo(),
                stratum.description(), stratum.createdAt());
    }
}