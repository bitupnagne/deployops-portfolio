package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.List;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class LongIdListTypeHandlerTest {
    final LongIdListTypeHandler handler = new LongIdListTypeHandler();
    @Test void roundTripsPersistedIdsAndPreservesLegacyNull() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        handler.setNonNullParameter(statement,1,List.of(8L,19L),JdbcType.VARCHAR);
        verify(statement).setString(1,"[8, 19]");
        ResultSet rows = mock(ResultSet.class);
        when(rows.getString("ids")).thenReturn("[8, 19]", null);
        assertThat(handler.getNullableResult(rows,"ids")).containsExactly(8L,19L);
        assertThat(handler.getNullableResult(rows,"ids")).isNull();
    }
    @Test void malformedPersistedSelectionNeverExpandsToAllGroups() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        for (String invalid : List.of("not-json","[0]","[-1]","[1,\"20\"]","[99999999999999999999999999]")) {
            when(rows.getString("ids")).thenReturn(invalid);
            assertThatThrownBy(() -> handler.getNullableResult(rows,"ids")).isInstanceOf(SQLException.class);
        }
    }
}
