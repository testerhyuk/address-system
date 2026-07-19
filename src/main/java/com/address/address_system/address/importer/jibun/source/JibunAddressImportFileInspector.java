package com.address.address_system.address.importer.jibun.source;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class JibunAddressImportFileInspector {

    private static final int HASH_BUFFER_SIZE = 64 * 1024;

    public ImportFile inspect(Path configuredPath) {
        if (configuredPath == null || configuredPath.toString().isBlank()) {
            throw new JibunAddressImportException(
                    FailureCode.FILE_NOT_CONFIGURED,
                    "지번 CSV 파일 경로가 설정되지 않았습니다"
            );
        }

        Path path = configuredPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new JibunAddressImportException(
                    FailureCode.FILE_NOT_READABLE,
                    "지번 CSV 파일을 읽을 수 없습니다: " + path
            );
        }

        String fileName = path.getFileName().toString();
        if (fileName.isBlank() || fileName.length() > 255) {
            throw new JibunAddressImportException(
                    FailureCode.INVALID_FILE_NAME,
                    "지번 CSV 파일명이 비어 있거나 255자를 초과했습니다"
            );
        }

        try {
            validateHeader(path);
            return new ImportFile(
                    path,
                    fileName,
                    Files.size(path),
                    calculateSha256(path)
            );
        }
        catch (IOException exception) {
            throw new JibunAddressImportException(
                    FailureCode.FILE_NOT_READABLE,
                    "지번 CSV 파일 정보를 읽을 수 없습니다: " + path,
                    exception
            );
        }
    }

    private void validateHeader(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                throw new JibunAddressImportException(
                        FailureCode.INVALID_HEADER,
                        "지번 CSV 파일이 비어 있습니다"
                );
            }
            JibunAddressCsvFormat.validateHeader(header);
        }
    }

    private String calculateSha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", exception);
        }

        try (InputStream input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(path), HASH_BUFFER_SIZE),
                digest
        )) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record ImportFile(
            Path path,
            String fileName,
            long fileSizeBytes,
            String sha256
    ) {
    }
}
