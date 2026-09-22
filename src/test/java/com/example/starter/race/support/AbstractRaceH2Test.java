package com.example.starter.race.support;

import com.example.starter.race.persistence.RaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * H2 测试基类：每个场景前清空业务数据，避免顺序依赖。
 */
public abstract class AbstractRaceH2Test {

    @Autowired
    protected RaceRepository repository;

    @BeforeEach
    void cleanTables() {
        repository.deleteAllForTesting();
    }
}
