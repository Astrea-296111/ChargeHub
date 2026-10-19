import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
/** Small dependency-free probe; it contains no credentials and prints no body. */
public final class Healthcheck {
    public static void main(String[] args) {
        try {
            String port = System.getenv().getOrDefault("CHARGEHUB_PORT", "8080");
            if (!port.matches("[0-9]{1,5}")) System.exit(1);
            HttpURLConnection connection = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + port + "/actuator/health/readiness").toURL().openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) System.exit(1);
            String body = new String(connection.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
            connection.disconnect();
            if (!body.matches("(?s).*\\\"status\\\"\\s*:\\s*\\\"UP\\\".*")) System.exit(1);
        } catch (Exception exception) {
            System.exit(1);
        }
    }
}
