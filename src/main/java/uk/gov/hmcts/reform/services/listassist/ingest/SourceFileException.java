package uk.gov.hmcts.reform.services.listassist.ingest;

/**
 * A source file that cannot be read safely as a whole. Its message never
 * contains row values.
 */
class SourceFileException extends RuntimeException {

    static final String SCHEMA_MISMATCH = "schema_mismatch";
    static final String DECODE_FAILED = "decode_failed";

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    SourceFileException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    SourceFileException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    String errorCode() {
        return errorCode;
    }
}
