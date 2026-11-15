package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.domain.NginxChangeStatus;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NginxChangeRequestRepository {
    @Select("SELECT purpose,position,source_server_id,host,port FROM nginx_change_upstream WHERE request_id=#{id} ORDER BY purpose,position")
    @Results({@Result(property="sourceServerId",column="source_server_id")})
    List<com.portfolio.deployops.domain.NginxUpstream> findUpstreams(Long id);

    @Insert("INSERT INTO nginx_change_upstream(request_id,purpose,position,source_server_id,host,port) VALUES(#{requestId},#{item.purpose},#{item.position},#{item.sourceServerId},#{item.host},#{item.port})")
    int insertUpstream(@Param("requestId") Long requestId, @Param("item") com.portfolio.deployops.domain.NginxUpstream item);

    String SELECT_COLUMNS = """
        SELECT n.*, s.name AS server_display_name, s.ip_address AS server_ip, s.server_type AS server_type,
               s.ssh_port AS server_ssh_port, s.deploy_account AS server_deploy_account, s.auth_type AS server_auth_type,
               s.control_mode AS server_control_mode, s.agent_binding_id AS server_agent_binding_id,
               requester.name AS requester_name, requester.login_id AS requester_login_id,
               approver.name AS approver_name, approver.login_id AS approver_login_id
        FROM nginx_change_request n
        JOIN server s ON s.id = n.server_id
        JOIN users requester ON requester.id = n.requested_by_id
        LEFT JOIN users approver ON approver.id = n.approved_by_id
        """;

    @Select(SELECT_COLUMNS + " ORDER BY n.created_at DESC")
    @Results(id = "nginxChangeResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "rawConfig", column = "raw_config"),
        @Result(property = "rawConfigSha256", column = "raw_config_sha256"),
        @Result(property = "requestedControlMode", column = "requested_control_mode"),
        @Result(property = "requestedAgentBindingId", column = "requested_agent_binding_id"),
        @Result(property = "upstreamSnapshots", column = "id", many = @org.apache.ibatis.annotations.Many(select="findUpstreams")),
        @Result(property = "secondaryUpstreamHost", column = "secondary_upstream_host"),
        @Result(property = "secondaryUpstreamPort", column = "secondary_upstream_port"),
        @Result(property = "upstreamMaxFails", column = "upstream_max_fails"),
        @Result(property = "upstreamFailTimeoutSec", column = "upstream_fail_timeout_sec"),
        @Result(property = "proxyConnectTimeoutSec", column = "proxy_connect_timeout_sec"),
        @Result(property = "apiProxyEnabled", column = "api_proxy_enabled"),
        @Result(property = "apiPrefix", column = "api_prefix"),
        @Result(property = "apiUpstreamHost", column = "api_upstream_host"),
        @Result(property = "apiUpstreamPort", column = "api_upstream_port"),
        @Result(property = "server.id", column = "server_id"),
        @Result(property = "server.name", column = "server_display_name"),
        @Result(property = "server.ipAddress", column = "server_ip"),
        @Result(property = "server.serverType", column = "server_type"),
        @Result(property = "server.sshPort", column = "server_ssh_port"),
        @Result(property = "server.deployAccount", column = "server_deploy_account"),
        @Result(property = "server.authType", column = "server_auth_type"),
        @Result(property = "server.controlMode", column = "server_control_mode"),
        @Result(property = "server.agentBindingId", column = "server_agent_binding_id"),
        @Result(property = "requestedBy.id", column = "requested_by_id"),
        @Result(property = "requestedBy.name", column = "requester_name"),
        @Result(property = "requestedBy.loginId", column = "requester_login_id"),
        @Result(property = "approvedBy.id", column = "approved_by_id"),
        @Result(property = "approvedBy.name", column = "approver_name"),
        @Result(property = "approvedBy.loginId", column = "approver_login_id")
    })
    List<NginxChangeRequest> findAll();

    @Select(SELECT_COLUMNS + " WHERE n.id = #{id}")
    @org.apache.ibatis.annotations.ResultMap("nginxChangeResult")
    Optional<NginxChangeRequest> findById(Long id);

    /** 서버·정책별 가장 최근 성공 동작. 과거 APPLIED 이력을 현재 상태로 오인하지 않게 한다. */
    @Select("""
        SELECT id FROM nginx_change_request
        WHERE server_id = #{serverId} AND policy_name = #{policyName}
          AND status IN ('APPLIED','ROLLED_BACK')
        ORDER BY applied_at DESC, id DESC LIMIT 1
        """)
    Long findLatestCompletedId(@Param("serverId") Long serverId, @Param("policyName") String policyName);

    @Select("SELECT DISTINCT policy_name FROM nginx_change_request WHERE server_id=#{serverId} AND status IN ('APPLIED','ROLLED_BACK') ORDER BY policy_name")
    List<String> findCompletedPolicyNames(Long serverId);

    @Insert("""
        INSERT INTO nginx_change_request (
          server_id, requested_by_id, rollback_of_id, status, policy_name, raw_config, raw_config_sha256, policy_type, server_name, listen_port,
          upstream_host, upstream_port, secondary_upstream_host, secondary_upstream_port,
          upstream_max_fails, upstream_fail_timeout_sec, proxy_connect_timeout_sec, web_root, api_proxy_enabled, api_prefix, api_upstream_host, api_upstream_port,
          websocket_enabled, tls_enabled, tls_protocols, http2_enabled,
          redirect_http, hsts_enabled, client_max_body_mb, proxy_read_timeout_sec, proxy_send_timeout_sec,
          proxy_buffering, security_headers, allowed_cidrs, certificate_name, certificate_file_name,
          private_key_file_name, storage_key, backup_path, rollback_snapshot_hash, expected_native_request_id,
          expected_current_snapshot_hash, restore_target_id, restore_absent, requested_control_mode, requested_agent_binding_id, created_at
        ) VALUES (
          #{server.id}, #{requestedBy.id}, #{rollbackOfId}, #{status}, #{policyName}, #{rawConfig}, #{rawConfigSha256}, #{policyType}, #{serverName}, #{listenPort},
          #{upstreamHost}, #{upstreamPort}, #{secondaryUpstreamHost}, #{secondaryUpstreamPort},
          #{upstreamMaxFails}, #{upstreamFailTimeoutSec}, #{proxyConnectTimeoutSec}, #{webRoot}, #{apiProxyEnabled}, #{apiPrefix}, #{apiUpstreamHost}, #{apiUpstreamPort},
          #{websocketEnabled}, #{tlsEnabled}, #{tlsProtocols}, #{http2Enabled},
          #{redirectHttp}, #{hstsEnabled}, #{clientMaxBodyMb}, #{proxyReadTimeoutSec}, #{proxySendTimeoutSec},
          #{proxyBuffering}, #{securityHeaders}, #{allowedCidrs}, #{certificateName}, #{certificateFileName},
          #{privateKeyFileName}, #{storageKey}, #{backupPath}, #{rollbackSnapshotHash}, #{expectedNativeRequestId},
          #{expectedCurrentSnapshotHash}, #{restoreTargetId}, #{restoreAbsent}, #{requestedControlMode}, #{requestedAgentBindingId}, NOW(6)
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NginxChangeRequest request);

    String TARGET_BINDING_MATCH = """
          AND EXISTS (
            SELECT 1 FROM server target WHERE target.id = nginx_change_request.server_id
              AND COALESCE(target.control_mode, 'SSH') = COALESCE(nginx_change_request.requested_control_mode, 'SSH')
              AND (COALESCE(target.control_mode, 'SSH') <> 'AGENT'
                OR (nginx_change_request.requested_agent_binding_id IS NOT NULL
                  AND target.agent_binding_id = nginx_change_request.requested_agent_binding_id))
          )
        """;

    @Update("""
        UPDATE nginx_change_request
        SET status = 'APPLYING', approved_by_id = #{approverId}, approved_at = NOW(6), error_message = NULL
        WHERE id = #{id} AND status = 'PENDING' AND requested_by_id <> #{approverId}
        """ + TARGET_BINDING_MATCH)
    int acquireApproval(@Param("id") Long id, @Param("approverId") Long approverId);

    /**
     * 롤백 승인을 얻는 바로 그 UPDATE에서 대상이 최신 세대인지 다시 확인한다.
     * 서비스의 사전 조회와 상태 변경 사이에 새 정책이 적용되는 경쟁 조건을 닫는다.
     */
    @Update("""
        UPDATE nginx_change_request
        SET status = 'APPLYING', approved_by_id = #{approverId}, approved_at = NOW(6), error_message = NULL
        WHERE id = #{id} AND status = 'PENDING' AND requested_by_id <> #{approverId}
          AND rollback_of_id = #{sourceId}
          AND rollback_of_id = (
            SELECT latest.id FROM (
              SELECT id FROM nginx_change_request
              WHERE server_id = #{serverId} AND policy_name = #{policyName}
                AND status IN ('APPLIED','ROLLED_BACK')
              ORDER BY applied_at DESC, id DESC LIMIT 1
            ) latest
          )
        """ + TARGET_BINDING_MATCH)
    int acquireRollbackApproval(@Param("id") Long id, @Param("approverId") Long approverId,
                                @Param("sourceId") Long sourceId, @Param("serverId") Long serverId,
                                @Param("policyName") String policyName);

    @Update("""
        UPDATE nginx_change_request
        SET status = #{status}, backup_path = #{backupPath}, error_message = #{errorMessage},
            applied_at = CASE WHEN #{status} IN ('APPLIED','ROLLED_BACK') THEN NOW(6) ELSE applied_at END
        WHERE id = #{id}
        """)
    int finish(@Param("id") Long id, @Param("status") NginxChangeStatus status,
               @Param("backupPath") String backupPath, @Param("errorMessage") String errorMessage);

    @Update("""
        UPDATE nginx_change_request SET backup_snapshot_hash=#{backupHash}, applied_snapshot_hash=#{appliedHash},
          applied_config_hash=#{configHash}, native_request_id=#{nativeId}
        WHERE id=#{id} AND status='APPLYING'
        """)
    int storeNativeProof(@Param("id") Long id, @Param("backupHash") String backupHash,
        @Param("appliedHash") String appliedHash, @Param("configHash") String configHash,
        @Param("nativeId") Long nativeId);

    @Update("UPDATE nginx_change_request SET status = 'REJECTED', approved_by_id = #{approverId}, approved_at = NOW(6), error_message = #{reason} WHERE id = #{id} AND status = 'PENDING' AND requested_by_id <> #{approverId}")
    int reject(@Param("id") Long id, @Param("approverId") Long approverId, @Param("reason") String reason);

    @Update("UPDATE nginx_change_request SET status = 'ROLLED_BACK' WHERE id = #{id} AND status = 'APPLIED'")
    int markSourceRolledBack(Long id);

    @Update("""
        UPDATE nginx_change_request
        SET certificate_subject = #{subject}, certificate_issuer = #{issuer},
            certificate_sans = #{sans}, certificate_not_after = #{notAfter},
            certificate_expiry_notified_days = NULL
        WHERE id = #{id}
        """)
    int updateCertificateMetadata(@Param("id") Long id, @Param("subject") String subject,
                                  @Param("issuer") String issuer, @Param("sans") String sans,
                                  @Param("notAfter") java.time.LocalDateTime notAfter);

    /**
     * 지금 실제로 서비스 중인 인증서만 고른다 — 서버·정책마다 <가장 최근 APPLIED> 한 건.
     * 지난 변경 이력의 옛 인증서까지 만료 알림에 끼면 이미 교체한 것으로 계속 경고한다.
     */
    @Select(SELECT_COLUMNS + """
        WHERE n.status IN ('APPLIED','ROLLED_BACK')
          AND n.certificate_not_after IS NOT NULL
          AND n.id = (
            SELECT latest.id FROM nginx_change_request latest
            WHERE latest.server_id = n.server_id
              AND latest.policy_name = n.policy_name
              AND latest.status IN ('APPLIED','ROLLED_BACK')
            ORDER BY latest.applied_at DESC, latest.id DESC LIMIT 1
          )
        ORDER BY n.certificate_not_after ASC
        """)
    @org.apache.ibatis.annotations.ResultMap("nginxChangeResult")
    List<NginxChangeRequest> findCurrentCertificates();

    @Update("UPDATE nginx_change_request SET certificate_expiry_notified_days = #{days} WHERE id = #{id}")
    int markExpiryNotified(@Param("id") Long id, @Param("days") Integer days);

    /** 승인도 반려도 되지 않은 채 남아 인증서 임시본만 붙들고 있는 요청. */
    @Select(SELECT_COLUMNS + """
         WHERE n.status = 'PENDING' AND n.storage_key IS NOT NULL AND n.created_at < #{cutoff}
        """)
    @org.apache.ibatis.annotations.ResultMap("nginxChangeResult")
    List<NginxChangeRequest> findStalePendingWithUploads(@Param("cutoff") java.time.LocalDateTime cutoff);

    /** 요청 이력은 남기고 인증서 참조만 끊는다. */
    @Update("UPDATE nginx_change_request SET storage_key = NULL WHERE id = #{id}")
    int clearStorageKey(Long id);
}
