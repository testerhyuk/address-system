package com.address.address_system.address.importer.road;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

@SuppressWarnings("unchecked")
class RoadAddressValidationWriterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RoadAddressValidationWriter writer = new RoadAddressValidationWriter(jdbcTemplate);

    @Test
    void storesViolationsBeforeMarkingRowRejected() {
        RoadAddressValidationResult invalid = new RoadAddressValidationResult(
                row(2),
                List.of(
                        violation("zip_code"),
                        violation("movement_reason_code")
                )
        );
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});

        writer.write(new Chunk<>(List.of(invalid)));

        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).batchUpdate(
                anyString(),
                anyList(),
                eq(2),
                any(ParameterizedPreparedStatementSetter.class)
        );
        order.verify(jdbcTemplate).batchUpdate(
                anyString(),
                any(BatchPreparedStatementSetter.class)
        );
    }

    @Test
    void marksValidRowWithoutWritingRejection() {
        RoadAddressValidationResult valid = new RoadAddressValidationResult(row(2), List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});

        writer.write(new Chunk<>(List.of(valid)));

        verify(jdbcTemplate, never()).batchUpdate(
                anyString(),
                anyList(),
                anyInt(),
                any(ParameterizedPreparedStatementSetter.class)
        );
        verify(jdbcTemplate).batchUpdate(
                anyString(),
                any(BatchPreparedStatementSetter.class)
        );
    }

    @Test
    void rejectsChunkContainingDifferentBatches() {
        RoadAddressValidationResult first = new RoadAddressValidationResult(row(2), List.of());
        RoadAddressStagingRow otherBatchRow = new RoadAddressStagingRow(
                UUID.fromString("00000000-0000-0000-0000-000000000699"),
                3,
                first.row().mgmtNum(),
                first.row().legalAreaCode(),
                first.row().legalDongCode(),
                first.row().sido(),
                first.row().sigungu(),
                first.row().bDongName(),
                first.row().roadCode(),
                first.row().roadName(),
                first.row().undergroundFlag(),
                first.row().buildMain(),
                first.row().buildSub(),
                first.row().zipCode(),
                first.row().effectiveDate(),
                first.row().apartmentFlag(),
                first.row().movementReasonCode(),
                first.row().buildNameOfficial(),
                first.row().buildNameSigungu()
        );

        assertThatThrownBy(() -> writer.write(new Chunk<>(List.of(
                first,
                new RoadAddressValidationResult(otherBatchRow, List.of())
        )))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsWhenLoadedRowWasAlreadyChanged() {
        RoadAddressValidationResult valid = new RoadAddressValidationResult(row(2), List.of());
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{0});

        assertThatThrownBy(() -> writer.write(new Chunk<>(List.of(valid))))
                .isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getFailureCode())
                                .isEqualTo(RoadAddressImportFailureCode.VALIDATION_STATE_CONFLICT)
                );
    }

    @Test
    void storesDuplicateReasonsBeforeRejectingDuplicateRows() {
        UUID batchId = UUID.fromString("00000000-0000-0000-0000-000000000601");

        writer.rejectDuplicateAddressKeys(batchId);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(anyString(), eq(batchId), eq(batchId));
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), eq(batchId));
        assertThat(sqlCaptor.getAllValues()).hasSize(2);
        assertThat(sqlCaptor.getAllValues().get(0)).contains("INSERT INTO");
        assertThat(sqlCaptor.getAllValues().get(1)).contains("UPDATE address.address_road_staging");
    }

    private RoadAddressValidationResult.Violation violation(String fieldName) {
        return new RoadAddressValidationResult.Violation(
                RoadAddressContentValidator.INVALID_FIELD_FORMAT,
                fieldName,
                "잘못된 값",
                "형식이 올바르지 않습니다"
        );
    }

    private RoadAddressStagingRow row(long sourceRowNumber) {
        return new RoadAddressStagingRow(
                UUID.fromString("00000000-0000-0000-0000-000000000601"),
                sourceRowNumber,
                "26110101300600100000100002",
                "26110101",
                "2611010100",
                "부산광역시",
                "중구",
                "영주동",
                "261103006001",
                "초량상로",
                "0",
                "1",
                "2",
                "48910",
                "20260718",
                "1",
                "31",
                "공식건물명",
                "시군구건물명"
        );
    }
}
