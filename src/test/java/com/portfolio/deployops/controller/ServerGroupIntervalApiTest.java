package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.dto.ServerGroupForm;
import com.portfolio.deployops.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ServerGroupIntervalApiTest {
    private final ServerGroupService groups = mock(ServerGroupService.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ServerGroupApiController(groups, audits))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        ServerGroup saved = ServerGroup.builder().id(13L).name("QA").rollingIntervalSec(0).build();
        when(groups.create(any())).thenReturn(saved);
        when(groups.update(eq(13L), any())).thenReturn(saved);
    }

    @ParameterizedTest @ValueSource(ints = {0, 600})
    void bothMutationEndpointsAcceptBoundariesWithoutChangingJsonValue(int interval) throws Exception {
        String json = "{\"name\":\"QA\",\"environment\":\"PROD\",\"rollingIntervalSec\":" + interval + "}";
        mvc.perform(post("/api/v1/server-groups").contentType("application/json").content(json))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(put("/api/v1/server-groups/13").contentType("application/json").content(json))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        var create = ArgumentCaptor.forClass(ServerGroupForm.class);
        var update = ArgumentCaptor.forClass(ServerGroupForm.class);
        verify(groups).create(create.capture());
        verify(groups).update(eq(13L), update.capture());
        assertThat(create.getValue().getRollingIntervalSec()).isEqualTo(interval);
        assertThat(update.getValue().getRollingIntervalSec()).isEqualTo(interval);
    }

    @Test void omittedJsonFieldRetainsFormDefault() throws Exception {
        mvc.perform(post("/api/v1/server-groups").contentType("application/json").content("{\"name\":\"QA\",\"environment\":\"TEST\"}"))
            .andExpect(status().isCreated());
        var form = ArgumentCaptor.forClass(ServerGroupForm.class);
        verify(groups).create(form.capture());
        assertThat(form.getValue().getRollingIntervalSec()).isEqualTo(30);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 601})
    void bothMutationEndpointsRejectOutOfRangeBeforeServiceOrAudit(int interval) throws Exception {
        String json = "{\"name\":\"QA\",\"rollingIntervalSec\":" + interval + "}";
        mvc.perform(post("/api/v1/server-groups").contentType("application/json").content(json))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(put("/api/v1/server-groups/13").contentType("application/json").content(json))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(groups, audits);
    }
}
