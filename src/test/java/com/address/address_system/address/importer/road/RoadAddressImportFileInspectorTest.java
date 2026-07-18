package com.address.address_system.address.importer.road;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoadAddressImportFileInspectorTest {

    private final RoadAddressImportFileInspector inspector = new RoadAddressImportFileInspector();

    @TempDir
    Path tempDirectory;

    @Test
    void calculatesFileMetadataAndSha256() throws Exception {
        Path file = tempDirectory.resolve("도로명.csv");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        RoadAddressImportFile inspected = inspector.inspect(file);

        assertThat(inspected.path()).isEqualTo(file.toAbsolutePath().normalize());
        assertThat(inspected.fileName()).isEqualTo("도로명.csv");
        assertThat(inspected.fileSizeBytes()).isEqualTo(3);
        assertThat(inspected.sha256())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rejectsMissingFile() {
        Path missingFile = tempDirectory.resolve("missing.csv");

        assertThatThrownBy(() -> inspector.inspect(missingFile))
                .isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(RoadAddressImportFailureCode.FILE_NOT_READABLE)
                );
    }

    @Test
    void rejectsMissingConfiguration() {
        assertThatThrownBy(() -> inspector.inspect(null))
                .isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(RoadAddressImportFailureCode.FILE_NOT_CONFIGURED)
                );
    }

    @Test
    void rejectsBlankConfiguration() {
        assertThatThrownBy(() -> inspector.inspect(Path.of("")))
                .isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(RoadAddressImportFailureCode.FILE_NOT_CONFIGURED)
                );
    }
}
