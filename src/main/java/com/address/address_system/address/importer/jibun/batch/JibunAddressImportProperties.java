package com.address.address_system.address.importer.jibun.batch;

import java.nio.file.Path;
import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "address.import.jibun")
public class JibunAddressImportProperties {

    private boolean enabled;

    private Path file;

    @NotNull
    private LocalDate referenceDate;

    @Min(1)
    @Max(10_000)
    private int chunkSize = 2_000;

    @Min(0)
    @Max(100_000)
    private int maxSkippedRows = 1_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getFile() {
        return file;
    }

    public void setFile(Path file) {
        this.file = file;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public void setReferenceDate(LocalDate referenceDate) {
        this.referenceDate = referenceDate;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getMaxSkippedRows() {
        return maxSkippedRows;
    }

    public void setMaxSkippedRows(int maxSkippedRows) {
        this.maxSkippedRows = maxSkippedRows;
    }
}
