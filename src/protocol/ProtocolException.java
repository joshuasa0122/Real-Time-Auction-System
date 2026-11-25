package protocol;

/** A malformed message or bad argument. The server answers it with 400 Bad Request. */
public class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }
}
