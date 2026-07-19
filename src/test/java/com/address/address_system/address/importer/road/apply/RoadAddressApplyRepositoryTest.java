package com.address.address_system.address.importer.road.apply;

import com.address.address_system.address.importer.road.batch.RoadAddressImportException;
import com.address.address_system.address.importer.road.batch.RoadAddressImportFailureCode;
import com.address.address_system.address.importer.road.model.RoadAddressImportMode;

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
class RoadAddressApplyRepositoryTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 7, 18);

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RoadAddressApplyRepository repository =
            new RoadAddressApplyRepository(jdbcTemplate);

    @Test
    void rejectsFullImportWhenOperationalLedgerIsNotEmpty() throws Exception {
        stubBatchContext("VALIDATING", RoadAddressImportMode.FULL, REFERENCE_DATE);
        when(jdbcTemplate.queryForObject(
                eq("SELECT count(*) FROM address.road_address"),
                eq(Long.class)
        )).thenReturn(1L);

        assertThatThrownBy(() -> repository.validateApplication(
                BATCH_ID,
                RoadAddressImportMode.FULL,
                REFERENCE_DATE
        )).isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                assertThat(exception.getFailureCode())
                        .isEqualTo(RoadAddressImportFailureCode.APPLY_PRECONDITION_FAILED)
        );

        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void deltaValidationStoresEveryApplicationRejectionCategory() throws Exception {
        stubBatchContext("VALIDATING", RoadAddressImportMode.DELTA, REFERENCE_DATE);
        when(jdbcTemplate.queryForObject(
                eq("SELECT count(*) FROM address.road_address"),
                eq(Long.class)
        )).thenReturn(1L);
        when(jdbcTemplate.queryForObject(
                contains("max(source_reference_date)"),
                eq(LocalDate.class),
                eq(BATCH_ID)
        )).thenReturn(REFERENCE_DATE.minusDays(1));

        repository.validateApplication(
                BATCH_ID,
                RoadAddressImportMode.DELTA,
                REFERENCE_DATE
        );

        verify(jdbcTemplate).update(contains("APPLY_STALE_CHANGE"), eq(BATCH_ID));
        verify(jdbcTemplate).update(contains("APPLY_TARGET_NOT_FOUND"), eq(BATCH_ID));
        verify(jdbcTemplate).update(contains("APPLY_TARGET_INACTIVE"), eq(BATCH_ID));
        verify(jdbcTemplate).update(contains("APPLY_ACTIVE_CONFLICT"), eq(BATCH_ID));
        verify(jdbcTemplate).update(
                contains("APPLY_REACTIVATION_DATE_CONFLICT"),
                eq(BATCH_ID)
        );
        verify(jdbcTemplate).update(
                contains("UPDATE address.address_road_staging"),
                eq(BATCH_ID),
                eq("APPLY_%")
        );
    }

    @Test
    void rejectsDeltaOlderThanLatestCompletedBatch() throws Exception {
        stubBatchContext("VALIDATING", RoadAddressImportMode.DELTA, REFERENCE_DATE);
        when(jdbcTemplate.queryForObject(
                eq("SELECT count(*) FROM address.road_address"),
                eq(Long.class)
        )).thenReturn(1L);
        when(jdbcTemplate.queryForObject(
                contains("max(source_reference_date)"),
                eq(LocalDate.class),
                eq(BATCH_ID)
        )).thenReturn(REFERENCE_DATE.plusDays(1));

        assertThatThrownBy(() -> repository.validateApplication(
                BATCH_ID,
                RoadAddressImportMode.DELTA,
                REFERENCE_DATE
        )).isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                assertThat(exception.getFailureCode())
                        .isEqualTo(RoadAddressImportFailureCode.APPLY_PRECONDITION_FAILED)
        );
    }

    @Test
    void fullApplyCompletesOnlyWhenInsertedAndAppliedCountsMatch() throws Exception {
        stubBatchContext("APPLYING", RoadAddressImportMode.FULL, REFERENCE_DATE);
        when(jdbcTemplate.queryForObject(
                eq("SELECT count(*) FROM address.road_address"),
                eq(Long.class)
        )).thenReturn(0L);
        when(jdbcTemplate.update(
                contains("INSERT INTO address.road_address"),
                eq(REFERENCE_DATE),
                eq(BATCH_ID)
        )).thenReturn(2);
        when(jdbcTemplate.update(
                contains("apply_action = 'CREATED'"),
                eq(BATCH_ID)
        )).thenReturn(2);
        when(jdbcTemplate.queryForObject(
                contains("processing_status = ?"),
                eq(Long.class),
                eq(BATCH_ID),
                eq("VALID")
        )).thenReturn(0L);
        stubApplyCounts(2, 2, 0);
        when(jdbcTemplate.update(
                contains("SET status = 'COMPLETED'"),
                eq(2L),
                eq(2L),
                eq(0L),
                eq(BATCH_ID)
        )).thenReturn(1);

        RoadAddressApplyRepository.ApplyCounts counts = repository.applyAndComplete(
                BATCH_ID,
                RoadAddressImportMode.FULL,
                REFERENCE_DATE
        );

        assertThat(counts).isEqualTo(new RoadAddressApplyRepository.ApplyCounts(2, 2, 0));
    }

    private void stubBatchContext(
            String status,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) throws Exception {
        when(jdbcTemplate.queryForObject(
                contains("SELECT status, import_mode, source_reference_date"),
                any(RowMapper.class),
                eq(BATCH_ID)
        )).thenAnswer(invocation -> {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("status")).thenReturn(status);
            when(resultSet.getString("import_mode")).thenReturn(importMode.name());
            when(resultSet.getObject("source_reference_date", LocalDate.class))
                    .thenReturn(referenceDate);
            RowMapper mapper = invocation.getArgument(1);
            return mapper.mapRow(resultSet, 0);
        });
    }

    private void stubApplyCounts(long total, long applied, long rejected) throws Exception {
        when(jdbcTemplate.queryForObject(
                contains("AS total_count"),
                any(RowMapper.class),
                eq(BATCH_ID),
                eq(BATCH_ID),
                eq(BATCH_ID),
                eq(BATCH_ID)
        )).thenAnswer(invocation -> {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("total_count")).thenReturn(total);
            when(resultSet.getLong("applied_count")).thenReturn(applied);
            when(resultSet.getLong("rejected_count")).thenReturn(rejected);
            RowMapper mapper = invocation.getArgument(1);
            return mapper.mapRow(resultSet, 0);
        });
    }
}
