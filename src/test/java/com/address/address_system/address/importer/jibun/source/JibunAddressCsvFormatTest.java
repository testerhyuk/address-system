package com.address.address_system.address.importer.jibun.source;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JibunAddressCsvFormatTest {

    @Test
    void acceptsExpectedHeaderWithUtf8Bom() {
        JibunAddressCsvFormat.validateHeader(
                "\uFEFFmgmt_num,b_dong_name,ri_name,jibun_main,jibun_sub"
        );
    }

    @Test
    void rejectsDifferentHeaderOrder() {
        assertThatThrownBy(() -> JibunAddressCsvFormat.validateHeader(
                "mgmt_num,ri_name,b_dong_name,jibun_main,jibun_sub"
        )).isInstanceOfSatisfying(JibunAddressImportException.class, exception ->
                assertThat(exception.getFailureCode()).isEqualTo(FailureCode.INVALID_HEADER)
        );
    }

    @Test
    void parsesQuotedCommaAsSingleField() throws Exception {
        assertThat(JibunAddressCsvFormat.parseSingleRecord(
                "26110110200001000000200000,중앙동7가,\"가,리\",81,2"
        )).containsExactly(
                "26110110200001000000200000",
                "중앙동7가",
                "가,리",
                "81",
                "2"
        );
    }
}
