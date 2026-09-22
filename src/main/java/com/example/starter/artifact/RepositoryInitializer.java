package com.example.starter.artifact;

import com.example.starter.artifact.repository.MetaRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化仓库元信息单行（仓库版本号从 0 开始）。
 */
@Component
public class RepositoryInitializer implements ApplicationRunner {

    private final MetaRepository metaRepository;

    public RepositoryInitializer(MetaRepository metaRepository) {
        this.metaRepository = metaRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        metaRepository.ensureInitialized();
    }
}
