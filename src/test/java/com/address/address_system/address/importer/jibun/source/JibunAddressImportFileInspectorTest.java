package com.address.address_system.address.importer.jibun.source;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JibunAddressImportFileInspectorTest {

    private final JibunAddressImportFileInspector inspector =
            new JibunAddressImportFileInspector();

    @Test
    void returnsNormalizedMetadataForReadableFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("jibun.csv");
        Files.writeString(
                file,
                "mgmt_num,b_dong_name,ri_name,jibun_main,jibun_sub\n"
                        + "26110110200001000000200000,중앙동7가,,81,2\n",
                StandardCharsets.UTF_8
        );

        JibunAddressImportFileInspector.ImportFile inspected = inspector.inspect(file);

        assertThat(inspected.path()).isEqualTo(file.toAbsolutePath().normalize());
        assertThat(inspected.fileName()).isEqualTo("jibun.csv");
        assertThat(inspected.fileSizeBytes()).isEqualTo(Files.size(file));
        assertThat(inspected.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsMissingConfiguredPath() {
        assertThatThrownBy(() -> inspector.inspect(null))
                .isInstanceOfSatisfying(JibunAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(FailureCode.FILE_NOT_CONFIGURED)
                );
    }
}
