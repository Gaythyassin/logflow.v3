package logflow;

public class StageException extends Exception {
    private static final long serialVersionUID = 1L;

    public StageException(String message) {
        super(message);
    }

    public StageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StageException(Throwable cause) {
        super(cause);
    }
}
