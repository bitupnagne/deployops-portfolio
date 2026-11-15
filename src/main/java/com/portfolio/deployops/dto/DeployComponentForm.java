package com.portfolio.deployops.dto;

import lombok.Getter;
import lombok.Setter;

/** 배포 단위 생성/수정 폼. */
@Getter
@Setter
public class DeployComponentForm {

    private String code;
    private String displayName;
    /** WAS | WEB */
    private String role;
    /** CONTAINER | STATIC_BUNDLE | PROXY_RELOAD */
    private String artifactType;
    private String imageRepository;
    private String webRoot;
    /** 소스 빌드 설정. 구버전 클라이언트가 생략하면 기존 값을 보존한다. */
    private String buildTool;
    private String dockerfilePath;
    private String dockerContextPath;
    private String buildCommand;
    private String artifactPath;
    @com.fasterxml.jackson.annotation.JsonIgnore private boolean buildToolProvided;
    @com.fasterxml.jackson.annotation.JsonIgnore private boolean dockerfilePathProvided;
    @com.fasterxml.jackson.annotation.JsonIgnore private boolean dockerContextPathProvided;
    @com.fasterxml.jackson.annotation.JsonIgnore private boolean buildCommandProvided;
    @com.fasterxml.jackson.annotation.JsonIgnore private boolean artifactPathProvided;

    public void setBuildTool(String value) { buildTool = value; buildToolProvided = true; }
    public void setDockerfilePath(String value) { dockerfilePath = value; dockerfilePathProvided = true; }
    public void setDockerContextPath(String value) { dockerContextPath = value; dockerContextPathProvided = true; }
    public void setBuildCommand(String value) { buildCommand = value; buildCommandProvided = true; }
    public void setArtifactPath(String value) { artifactPath = value; artifactPathProvided = true; }
    /** NONE | HTTP_DIRECT | SSH_LOCAL_CURL | CONTAINER_RUNNING. 생략하면 수정 시 기존 설정을 유지한다. */
    private String healthCheckMode;
    private Integer healthCheckPort;
    private String healthCheckPath;
    private String proxyHealthHost;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean proxyHealthHostProvided;

    public void setProxyHealthHost(String proxyHealthHost) {
        this.proxyHealthHost = proxyHealthHost;
        this.proxyHealthHostProvided = true;
    }
    private String proxyHealthHostTest;
    private String proxyHealthHostProd;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean proxyHealthHostTestProvided;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean proxyHealthHostProdProvided;

    public void setProxyHealthHostTest(String value) {
        this.proxyHealthHostTest = value;
        this.proxyHealthHostTestProvided = true;
    }

    public void setProxyHealthHostProd(String value) {
        this.proxyHealthHostProd = value;
        this.proxyHealthHostProdProvided = true;
    }
    private String containerUser;
    private Boolean containerPrivileged;
    private String containerCapAdd;
    private Boolean containerReadOnlyRootFs;
    private Integer deployOrder;
    private Boolean active;
}
