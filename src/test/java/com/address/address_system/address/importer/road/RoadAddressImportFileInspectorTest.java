package com.address.address_system.address.importer.road;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoadAddressImportFileInspectorTest {

    private final RoadAddressImportFileInspector inspector =
            new RoadAddressImportFileInspector(new RoadAddressCsvHeaderValidator());

    @TempDir
    Path tempDirectory;

    @Test
    void calculatesFileMetadataAndDetectsSchema() throws Exception {
        Path file = tempDirectory.resolve("도로명.csv");
        String contents = String.join(",", RoadAddressCsvFormat.SNAPSHOT_HEADER) + "\r\n";
        Files.writeString(file, contents, StandardCharsets.UTF_8);

        RoadAddressImportFile inspected = inspector.inspect(file);

        assertThat(inspected.path()).isEqualTo(file.toAbsolutePath().normalize());
        assertThat(inspected.fileName()).isEqualTo("도로명.csv");
        assertThat(inspected.fileSizeBytes()).isEqualTo(contents.getBytes(StandardCharsets.UTF_8).length);
        assertThat(inspected.sha256()).matches("[0-9a-f]{64}");
        assertThat(inspected.schema()).isEqualTo(RoadAddressCsvFormat.Schema.SNAPSHOT);
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

    @Test
    void rejectsUnsupportedHeader() throws Exception {
        Path file = tempDirectory.resolve("unsupported.csv");
        Files.writeString(file, "a,b,c\r\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> inspector.inspect(file))
                .isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(RoadAddressImportFailureCode.INVALID_HEADER)
                );
    }
}
