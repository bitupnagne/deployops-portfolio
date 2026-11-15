package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.dto.ServerGroupForm;
import com.portfolio.deployops.repository.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServerGroupIntervalTest {
    private final ServerGroupRepository groups = mock(ServerGroupRepository.class);
    private final ServerRepository servers = mock(ServerRepository.class);
    private final ServerGroupService service = new ServerGroupService(groups, servers, mock(ProjectRepository.class));

    @ParameterizedTest @ValueSource(ints = {0, 30, 600})
    void createSavesExactAcceptedInterval(int interval) {
        when(groups.save(any())).thenAnswer(call -> call.getArgument(0));
        ServerGroupForm form = form(interval);
        ServerGroup saved = service.create(form);
        assertThat(saved.getRollingIntervalSec()).isEqualTo(interval);
        verify(groups).save(saved);
    }

    @ParameterizedTest @ValueSource(ints = {0, 30, 600})
    void updateReplacesPriorIntervalIncludingZero(int interval) {
        ServerGroup existing = ServerGroup.builder().id(13L).rollingIntervalSec(2).build();
        when(groups.findByIdForUpdate(13L)).thenReturn(Optional.of(existing));
        when(groups.save(any())).thenAnswer(call -> call.getArgument(0));
        ServerGroup saved = service.update(13L, form(interval));
        assertThat(saved.getRollingIntervalSec()).isEqualTo(interval);
        verify(groups).save(existing);
    }

    @Test void omittedIntervalDefaultsToThirtyOnCreate() {
        when(groups.save(any())).thenAnswer(call -> call.getArgument(0));
        assertThat(service.create(form(30)).getRollingIntervalSec()).isEqualTo(30);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 601})
    void invalidIntervalsFailBeforeRepositoryReadsOrWrites(int interval) {
        assertThatThrownBy(() -> service.create(form(interval)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.update(13L, form(interval)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(groups, servers);
    }

    private ServerGroupForm form(int interval) {
        ServerGroupForm form = new ServerGroupForm();
        form.setName("QA");
        form.setEnvironment(com.portfolio.deployops.domain.ServerEnvironment.TEST);
        form.setRollingIntervalSec(interval);
        return form;
    }
}
