package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.deployops.domain.NginxChangeRequest;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class NginxStaticApiMapperTest {
    @Test
    void actualMapperBindsAndReadsAllFrozenApiFieldsIncludingNullableOffValues() {
        Configuration config = new Configuration(); config.addMapper(NginxChangeRequestRepository.class);
        String namespace = NginxChangeRequestRepository.class.getName();
        var request = NginxChangeRequest.builder().apiProxyEnabled(true).apiPrefix("/api/")
            .apiUpstreamHost("198.18.12.40").apiUpstreamPort(9090).build();
        var bound = config.getMappedStatement(namespace + ".insert").getBoundSql(request);
        assertThat(bound.getParameterMappings()).extracting("property")
            .contains("apiProxyEnabled", "apiPrefix", "apiUpstreamHost", "apiUpstreamPort");
        var meta = config.newMetaObject(bound.getParameterObject());
        assertThat(meta.getValue("apiPrefix")).isEqualTo("/api/");
        assertThat(meta.getValue("apiUpstreamPort")).isEqualTo(9090);
        assertThat(config.getResultMap(namespace + ".nginxChangeResult").getResultMappings())
            .extracting("property").contains("apiProxyEnabled", "apiPrefix", "apiUpstreamHost", "apiUpstreamPort");
        request.setApiProxyEnabled(false); request.setApiPrefix(null); request.setApiUpstreamHost(null); request.setApiUpstreamPort(null);
        var off = config.newMetaObject(config.getMappedStatement(namespace + ".insert").getBoundSql(request).getParameterObject());
        assertThat(off.getValue("apiProxyEnabled")).isEqualTo(false);
        assertThat(off.getValue("apiUpstreamPort")).isNull();
    }
}
