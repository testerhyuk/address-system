package com.address.address_system.address.importer.jibun.apply;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SuppressWarnings({"rawtypes", "unchecked"})
class JibunAddressApplyRepositoryTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000803");
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 7, 18);

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JibunAddressApplyRepository repository =
            new JibunAddressApplyRepository(jdbcTemplate);

    @Test
    void rejectsSnapshotOlderThanLatestCompletedBatch() throws Exception {
        stubBatchContext("VALIDATING", REFERENCE_DATE);
        when(jdbcTemplate.queryForObject(
                contains("max(source_reference_date)"),
                eq(LocalDate.class),
                eq(BATCH_ID)
        )).thenReturn(REFERENCE_DATE.plusDays(1));

        assertThatThrownBy(() -> repository.validateApplication(BATCH_ID, REFERENCE_DATE))
                .isInstanceOfSatisfying(JibunAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(FailureCode.APPLY_PRECONDITION_FAILED)
                );
    }

    @Test
    void skipsRetirementWhenSnapshotContainsRejectedRows() throws Exception {
        stubBatchContext("APPLYING", REFERENCE_DATE);
        when(jdbcTemplate.queryForObject(
                contains("SELECT rejected_row_count"),
                eq(Long.class),
                eq(BATCH_ID)
        )).thenReturn(1L);
        when(jdbcTemplate.update(
                contains("UPDATE address.address_jibun_staging staging"),
                eq(BATCH_ID)
        )).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("processing_status = ?"),
                eq(Long.class),
                eq(BATCH_ID),
                eq("VALID")
        )).thenReturn(0L);
        stubApplyCounts(2, 1, 1);
        when(jdbcTemplate.update(
                contains("SET status = 'COMPLETED'"),
                eq(2L),
                eq(1L),
                eq(1L),
                eq(BATCH_ID)
        )).thenReturn(1);

        JibunAddressApplyRepository.ApplyCounts counts =
                repository.applyAndComplete(BATCH_ID, REFERENCE_DATE);

        assertThat(counts.retirementSkipped()).isTrue();
        verify(jdbcTemplate, never()).update(
                contains("source_row_number = NULL"),
                any(),
                any(),
                any()
        );
    }

    private void stubBatchContext(String status, LocalDate referenceDate) throws Exception {
        when(jdbcTemplate.queryForObject(
                contains("SELECT status, import_mode, source_reference_date"),
                any(RowMapper.class),
                eq(BATCH_ID)
        )).thenAnswer(invocation -> {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("status")).thenReturn(status);
            when(resultSet.getString("import_mode")).thenReturn("FULL");
            when(resultSet.getObject("source_reference_date", LocalDate.class))
                    .thenReturn(referenceDate);
            RowMapper mapper = invocation.getArgument(1);
            return mapper.mapRow(resultSet, 0);
        });
    }

    private void stubApplyCounts(long total, long applied, long rejected) throws Exception {
        when(jdbcTemplate.queryForObject(
                contains("AS applied_count"),
                any(RowMapper.class),
                eq(BATCH_ID),
                eq(BATCH_ID)
        )).thenAnswer(invocation -> {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("total_row_count")).thenReturn(total);
            when(resultSet.getLong("applied_count")).thenReturn(applied);
            when(resultSet.getLong("rejected_row_count")).thenReturn(rejected);
            RowMapper mapper = invocation.getArgument(1);
            return mapper.mapRow(resultSet, 0);
        });
    }
}
