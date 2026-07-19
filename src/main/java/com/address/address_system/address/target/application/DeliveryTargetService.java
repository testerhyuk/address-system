package com.address.address_system.address.target.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.address.address_system.address.target.model.DeliveryTargetResult;
import com.address.address_system.address.target.model.DeliveryTargetResult.TargetType;
import com.address.address_system.address.target.persistence.DeliveryTargetRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryTargetService {

    private static final Pattern BUILDING_DONG_PATTERN = Pattern.compile(
            "^[0-9A-Za-z가-힣-]{1,39}동$"
    );

    private final DeliveryTargetRepository repository;

    public DeliveryTargetService(DeliveryTargetRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DeliveryTargetResult resolve(UUID roadAddressId, String buildingDong) {
        if (roadAddressId == null) {
            throw new DeliveryTargetException(
                    FailureCode.INVALID_REQUEST,
                    "roadAddressId는 필수입니다"
            );
        }

        String apartmentStatus = repository.lockActiveApartmentStatus(roadAddressId)
                .orElseThrow(() -> new DeliveryTargetException(
                        FailureCode.ADDRESS_NOT_FOUND,
                        "활성 도로명주소를 찾을 수 없습니다"
                ));

        if (buildingDong == null) {
            return repository.saveOrReactivate(
                    roadAddressId,
                    TargetType.BUILDING,
                    null,
                    null,
                    "ADDRESS_SELECTION"
            );
        }

        NormalizedDong normalizedDong = normalizeAndValidate(buildingDong);
        if ("NON_APARTMENT".equals(apartmentStatus)) {
            throw new DeliveryTargetException(
                    FailureCode.BUILDING_DONG_NOT_ALLOWED,
                    "비공동주택 주소에는 건물 동 배송 대상을 생성할 수 없습니다"
            );
        }

        return repository.saveOrReactivate(
                roadAddressId,
                TargetType.BUILDING_DONG,
                normalizedDong.displayValue(),
                normalizedDong.normalizedValue(),
                "EXTERNAL_DELIVERY"
        );
    }

    private NormalizedDong normalizeAndValidate(String value) {
        if (value.isBlank() || value.length() > 40) {
            throw invalidBuildingDong();
        }
        String displayValue = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
        if (!BUILDING_DONG_PATTERN.matcher(displayValue).matches()) {
            throw invalidBuildingDong();
        }
        return new NormalizedDong(
                displayValue,
                displayValue.toLowerCase(Locale.ROOT)
        );
    }

    private DeliveryTargetException invalidBuildingDong() {
        return new DeliveryTargetException(
                FailureCode.DETAIL_ADDRESS_NOT_ALLOWED,
                "buildingDong에는 101동과 같은 건물 동 정보만 허용됩니다"
        );
    }

    private record NormalizedDong(String displayValue, String normalizedValue) {
    }

    public enum FailureCode {
        INVALID_REQUEST,
        ADDRESS_NOT_FOUND,
        BUILDING_DONG_NOT_ALLOWED,
        DETAIL_ADDRESS_NOT_ALLOWED
    }

    public static class DeliveryTargetException extends RuntimeException {

        private final FailureCode failureCode;

        public DeliveryTargetException(FailureCode failureCode, String message) {
            super(message);
            this.failureCode = failureCode;
        }

        public FailureCode getFailureCode() {
            return failureCode;
        }
    }
}
