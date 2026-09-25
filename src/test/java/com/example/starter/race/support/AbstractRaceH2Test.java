package com.example.starter.race.support;

import com.example.starter.race.persistence.CourseRepository;
import com.example.starter.race.persistence.RaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * H2 测试基类：每个场景前清空业务数据，避免顺序依赖。
 */
public abstract class AbstractRaceH2Test {

    /** 测试默认赛道标识。 */
    protected static final String COURSE = "course-1";

    @Autowired
    protected RaceRepository repository;

    @Autowired
    protected CourseRepository courseRepository;

    @BeforeEach
    void cleanTables() {
        repository.deleteAllForTesting();
    }

    /** 直接登记一条赛道（绕过服务层，供赛事创建前置使用）。 */
    protected void givenCourse(String courseKey) {
        courseRepository.insertCourse(courseKey, 1_000L);
    }
}
