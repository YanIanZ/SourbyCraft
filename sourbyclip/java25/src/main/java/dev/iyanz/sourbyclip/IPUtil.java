package dev.iyanz.sourbyclip;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class IPUtil {
    protected static String getCountryByIp() {
        final long timeout = Long.getLong("sourbyclip.getCountryTimeout", 5) * 1000;
        HttpClient client = HttpClient.newHttpClient();
        ExecutorService executor = Executors.newFixedThreadPool(IpApi.values().length);

        try {
            CompletableFuture<String>[] futures = new CompletableFuture[IpApi.values().length];

            for (int i = 0; i < IpApi.values().length; i++) {
                final IpApi api = IpApi.values()[i];
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        HttpResponse<String> response = client.send(createRequest(api.getUrl()), HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() >= 300 && response.statusCode() < 400) {
                            String redirectUrl = response.headers().firstValue("Location").orElse(null);
                            if (redirectUrl != null) {
                                response = client.send(createRequest(redirectUrl), HttpResponse.BodyHandlers.ofString());
                            }
                        }
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            return api.processResponse(response.body());
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                }, executor);
            }

            CompletableFuture<Object> firstCompleted = CompletableFuture.anyOf(futures);

            long startTime = System.currentTimeMillis();
            while (!firstCompleted.isDone()) {
                if (System.currentTimeMillis() - startTime > timeout) return "Unknown";
                Thread.sleep(50);
                firstCompleted = CompletableFuture.anyOf(futures);
            }

            try {
                String result = (String) firstCompleted.get();
                if (result != null) {
                    for (CompletableFuture<String> future : futures) {
                        future.cancel(true);
                    }
                    return result;
                }
            } catch (Exception ignored) {
            }

            for (CompletableFuture<String> future : futures) {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    try {
                        String result = future.getNow(null);
                        if (result != null) {
                            for (CompletableFuture<String> f : futures) {
                                f.cancel(true);
                            }
                            return result;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        return "Unknown";
    }


    private static HttpRequest createRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    private enum IpApi {
        IPINFO("http://ipinfo.io/country", String::trim),
        IP_API("http://ip-api.com/json/?fields=country", string -> {
            JsonObject json = JsonParser.parseString(string).getAsJsonObject();
            return json.get("country").getAsString();
        }),
        IP_SB("https://api.ip.sb/geoip", string -> {
            JsonObject json = JsonParser.parseString(string).getAsJsonObject();
            return json.get("country").getAsString();
        });

        private final String url;
        private final java.util.function.Function<String, String> processor;

        IpApi(String url, java.util.function.Function<String, String> processor) {
            this.url = url;
            this.processor = processor;
        }

        public String getUrl() {
            return url;
        }

        public String processResponse(String response) {
            return processor.apply(response);
        }
    }
}
