package com.example.starter.service;

import com.example.starter.web.dto.ArtifactResponse;
import com.example.starter.web.dto.RegisterArtifactRequest;
import com.example.starter.web.dto.WithdrawRequest;

/**
 * 制品登记与撤回服务。
 */
public interface ArtifactService {

    ArtifactResponse register(RegisterArtifactRequest request);

    ArtifactResponse withdraw(String name, int version, WithdrawRequest request);
}
