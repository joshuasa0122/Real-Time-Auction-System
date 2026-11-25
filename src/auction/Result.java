package auction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What AuctionService returns for a request. The server turns it into a RES message:
 * RES &lt;id&gt; &lt;code&gt; &lt;reason&gt; plus the headers.
 *
 * Codes follow slide 8: 200 OK, 400 bad arguments, 404 unknown auction or account,
 * 409 bid too low or not enough money.
 */
public final class Result {

    private final int code;
    private final String reason;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Result(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    public static Result ok() { return new Result(200, "OK"); }
    public static Result badRequest(String why) { return new Result(400, "Bad Request").header("Error", why); }
    public static Result notFound(String why) { return new Result(404, "Not Found").header("Error", why); }
    public static Result conflict(String why) { return new Result(409, "Conflict").header("Error", why); }

    public Result header(String name, Object value) {
        headers.put(name, String.valueOf(value));
        return this;
    }

    public int code() { return code; }
    public String reason() { return reason; }
    public Map<String, String> headers() { return headers; }
    public boolean isOk() { return code == 200; }
}
