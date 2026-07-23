package ai.opsmind.platform.common.api;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

public record OperatorProjection<T>(T body, int redactionCount) {

    public static final String MEDIA_TYPE_VALUE =
        "application/vnd.opsmind.operator-projection.v1+json";
    public static final MediaType MEDIA_TYPE = MediaType.parseMediaType(MEDIA_TYPE_VALUE);
    public static final String CLASSIFICATION_HEADER = "X-OpsMind-Projection-Class";
    public static final String REDACTION_VERSION_HEADER = "X-OpsMind-Redaction-Version";
    public static final String REDACTION_COUNT_HEADER = "X-OpsMind-Redaction-Count";
    public static final String CLASSIFICATION = "operator-browser-safe-v1";
    public static final String REDACTION_VERSION = "display-redaction-v1";

    public OperatorProjection {
        if (body == null || redactionCount < 0 || redactionCount > 999_999) {
            throw new IllegalArgumentException("Operator projection metadata is invalid.");
        }
    }

    public ResponseEntity.BodyBuilder responseBuilder() {
        return ResponseEntity.ok()
            .contentType(MEDIA_TYPE)
            .cacheControl(CacheControl.noStore())
            .varyBy("Accept")
            .header(CLASSIFICATION_HEADER, CLASSIFICATION)
            .header(REDACTION_VERSION_HEADER, REDACTION_VERSION)
            .header(REDACTION_COUNT_HEADER, Integer.toString(redactionCount));
    }

    public static boolean requested(String acceptHeader)
        throws HttpMediaTypeNotAcceptableException {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        List<MediaType> accepted;
        try {
            accepted = MediaType.parseMediaTypes(acceptHeader);
        }
        catch (InvalidMediaTypeException exception) {
            throw notAcceptable();
        }
        double vendorQuality = maximumQuality(accepted, true);
        double jsonQuality = maximumQuality(accepted, false);
        if (vendorQuality <= 0 && jsonQuality <= 0) {
            throw notAcceptable();
        }
        return vendorQuality > 0 && vendorQuality >= jsonQuality;
    }

    private static double maximumQuality(List<MediaType> accepted, boolean exactVendor) {
        return accepted.stream()
            .filter(mediaType -> exactVendor
                ? isExactVendor(mediaType)
                : MediaType.APPLICATION_JSON.isCompatibleWith(mediaType))
            .mapToDouble(MediaType::getQualityValue)
            .max()
            .orElse(-1);
    }

    private static boolean isExactVendor(MediaType mediaType) {
        return MEDIA_TYPE.getType().equalsIgnoreCase(mediaType.getType())
            && MEDIA_TYPE.getSubtype().equalsIgnoreCase(mediaType.getSubtype())
            && mediaType.getParameters().keySet().stream()
                .allMatch(parameter -> "q".equalsIgnoreCase(parameter));
    }

    private static HttpMediaTypeNotAcceptableException notAcceptable() {
        return new HttpMediaTypeNotAcceptableException(
            List.of(MediaType.APPLICATION_JSON, MEDIA_TYPE)
        );
    }
}
