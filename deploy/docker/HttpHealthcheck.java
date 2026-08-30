import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpHealthcheck {
    private HttpHealthcheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "pid".equals(args[0])) {
            System.exit(ProcessHandle.of(1).map(handle -> handle.isAlive() ? 0 : 1).orElse(1));
        }
        if (args.length != 1) {
            System.exit(2);
        }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var request = HttpRequest.newBuilder(URI.create(args[0]))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        System.exit(response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1);
    }
}
