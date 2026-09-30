package com.example.starter.race.support;

import com.example.starter.race.persistence.RaceRepository;
import com.example.starter.race.persistence.RosterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * H2 测试基类：每个场景前清空业务数据，避免顺序依赖。
 * 团队名单表先于个人成绩与赛事表清理，避免外键约束冲突。
 */
public abstract class AbstractRaceH2Test {

    @Autowired
    protected RaceRepository repository;

    @Autowired
    protected RosterRepository rosterRepository;

    @BeforeEach
    void cleanTables() {
        rosterRepository.deleteAllForTesting();
        repository.deleteAllForTesting();
    }
}
