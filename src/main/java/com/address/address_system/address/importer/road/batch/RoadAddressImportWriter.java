package com.address.address_system.address.importer.road.batch;

import com.address.address_system.address.importer.road.model.RoadAddressImportRecord;
import com.address.address_system.address.importer.road.model.RoadAddressRejectedRow;
import com.address.address_system.address.importer.road.model.RoadAddressStagingRow;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

public class RoadAddressImportWriter implements ItemWriter<RoadAddressImportRecord> {

    private static final String INSERT_STAGING_SQL = """
            INSERT INTO address.address_road_staging (
                batch_id,
                source_row_number,
                mgmt_num,
                legal_area_code,
                legal_dong_code,
                sido,
                sigungu,
                b_dong_name,
                road_code,
                road_name,
                underground_flag,
                build_main,
                build_sub,
                zip_code,
                effective_date,
                apartment_flag,
                movement_reason_code,
                build_nm_official,
                build_nm_sgg
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_REJECTION_SQL = """
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final int maxSkippedRows;

    public RoadAddressImportWriter(JdbcTemplate jdbcTemplate, int maxSkippedRows) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxSkippedRows = maxSkippedRows;
    }

    @Override
    public void write(Chunk<? extends RoadAddressImportRecord> chunk) {
        List<RoadAddressStagingRow> stagingRows = chunk.getItems().stream()
                .filter(RoadAddressStagingRow.class::isInstance)
                .map(RoadAddressStagingRow.class::cast)
                .toList();
        List<RoadAddressRejectedRow> rejectedRows = chunk.getItems().stream()
                .filter(RoadAddressRejectedRow.class::isInstance)
                .map(RoadAddressRejectedRow.class::cast)
                .toList();

        ensureSingleBatch(chunk.getItems());
        enforceSkipLimit(rejectedRows);

        if (!stagingRows.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    INSERT_STAGING_SQL,
                    stagingRows,
                    stagingRows.size(),
                    this::setStagingParameters
            );
        }
        if (!rejectedRows.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    INSERT_REJECTION_SQL,
                    rejectedRows,
                    rejectedRows.size(),
                    this::setRejectionParameters
            );
        }
    }

    private void ensureSingleBatch(List<? extends RoadAddressImportRecord> items) {
        Set<UUID> batchIds = items.stream()
                .map(RoadAddressImportRecord::batchId)
                .collect(java.util.stream.Collectors.toSet());
        if (batchIds.size() > 1) {
            throw new IllegalStateException("하나의 청크에 서로 다른 주소 적재 배치가 포함되었습니다");
        }
    }

    private void enforceSkipLimit(List<RoadAddressRejectedRow> rejectedRows) {
        if (rejectedRows.isEmpty()) {
            return;
        }

        UUID batchId = rejectedRows.get(0).batchId();
        Long existingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM address.address_import_rejection WHERE batch_id = ?",
                Long.class,
                batchId
        );
        long totalRejected = (existingCount == null ? 0 : existingCount) + rejectedRows.size();
        if (totalRejected > maxSkippedRows) {
            throw new RoadAddressImportException(
                    RoadAddressImportFailureCode.SKIP_LIMIT_EXCEEDED,
                    "CSV 형식 오류 행이 허용 한도 " + maxSkippedRows + "건을 초과했습니다"
            );
        }
    }

    private void setStagingParameters(PreparedStatement statement, RoadAddressStagingRow row)
            throws SQLException {
        statement.setObject(1, row.batchId());
        statement.setLong(2, row.sourceRowNumber());
        statement.setString(3, row.mgmtNum());
        statement.setString(4, row.legalAreaCode());
        statement.setString(5, row.legalDongCode());
        statement.setString(6, row.sido());
        statement.setString(7, row.sigungu());
        statement.setString(8, row.bDongName());
        statement.setString(9, row.roadCode());
        statement.setString(10, row.roadName());
        statement.setString(11, row.undergroundFlag());
        statement.setString(12, row.buildMain());
        statement.setString(13, row.buildSub());
        statement.setString(14, row.zipCode());
        statement.setString(15, row.effectiveDate());
        statement.setString(16, row.apartmentFlag());
        statement.setString(17, row.movementReasonCode());
        statement.setString(18, row.buildNameOfficial());
        statement.setString(19, row.buildNameSigungu());
    }

    private void setRejectionParameters(PreparedStatement statement, RoadAddressRejectedRow row)
            throws SQLException {
        statement.setObject(1, row.batchId());
        statement.setLong(2, row.sourceRowNumber());
        statement.setString(3, row.reasonCode());
        statement.setString(4, row.fieldName());
        statement.setString(5, row.rejectedValue());
        statement.setString(6, row.reasonDetail());
    }
}
