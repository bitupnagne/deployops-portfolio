package com.portfolio.deployops.service;

import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectContainerIdentity;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProjectRuntimeIsolationService {
    private final ProjectRepository projects;

    /** Check current mappings even when the caller is executing an older approved snapshot. */
    public void assertIsolated(Project project, Server server) {
        if (project == null || project.getId() == null || server == null || server.getId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"프로젝트와 대상 서버 식별자가 필요합니다.");
        String name = ProjectContainerIdentity.resolve(project);
        for (Project other : projects.findSharingTargetServer(server.getId(),server.getIpAddress())) {
            if (!project.getId().equals(other.getId()) && name.equals(ProjectContainerIdentity.resolve(other))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "대상 서버의 컨테이너 이름 '" + name + "'이 다른 프로젝트(ID " + other.getId()
                    + ")와 겹칩니다. 기존 컨테이너는 자동 변경하지 않습니다. 서버 그룹을 분리하거나 "
                    + "새 프로젝트의 고유 컨테이너로 데이터를 이관한 뒤 기존 프로젝트 매핑을 해제하세요.");
            }
        }
    }
}
