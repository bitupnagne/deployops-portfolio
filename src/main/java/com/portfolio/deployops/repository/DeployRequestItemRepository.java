package com.portfolio.deployops.repository;

import com.portfolio.deployops.domain.DeployRequestItem;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeployRequestItemRepository {

    @Results(id = "deployRequestItemResult", value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "deployRequestId", column = "deploy_request_id"),
        @Result(property = "componentId", column = "component_id"),
        @Result(property = "componentCode", column = "component_code"),
        @Result(property = "imageTag", column = "image_tag"),
        @Result(property = "imageDigest", column = "image_digest"),
        @Result(property = "bundleRef", column = "bundle_ref")
    })
    @Select("""
        SELECT id, deploy_request_id, component_id, component_code, image_tag, image_digest, bundle_ref
        FROM deploy_request_item
        WHERE deploy_request_id = #{deployRequestId}
        ORDER BY id ASC
        """)
    List<DeployRequestItem> findByDeployRequestId(Long deployRequestId);

    @Insert("""
        INSERT INTO deploy_request_item (
            deploy_request_id, component_id, component_code, image_tag, image_digest, bundle_ref
        ) VALUES (
            #{deployRequestId}, #{componentId}, #{componentCode}, #{imageTag}, #{imageDigest}, #{bundleRef}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DeployRequestItem item);

    @Delete("DELETE FROM deploy_request_item WHERE deploy_request_id = #{deployRequestId}")
    int deleteByDeployRequestId(Long deployRequestId);
}
