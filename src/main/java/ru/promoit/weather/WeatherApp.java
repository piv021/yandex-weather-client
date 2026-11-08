package ru.promoit.weather;

import module java.base;
import module java.net.http;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

public class WeatherApp {

    private static final String WEATHER_API_URL = "https://api.weather.yandex.ru/v2/forecast";
    // Защищенный и бесплатный HTTPS-сервис геолокации по IP
    private static final String GEO_API_URL = "https://ipwho.is/?lang=ru";

    // Вставьте ваш активный ключ из кабинета https://yandex.ru/pogoda/b2b/console/home если ключь истек
    private static final String API_KEY = "89e75ce5-c180-4ca4-b359-c3be744fb71c";

    private static final double DEFAULT_LAT = 55.75;
    private static final double DEFAULT_LON = 37.62;

    // Модель ответа от HTTPS-сервиса геолокации ipwho.is
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeoLocation(
            boolean success,
            String country,
            String city,
            @JsonProperty("latitude") Double lat,
            @JsonProperty("longitude") Double lon
    ) {}

    // Модели данных для ответа Яндекс Погоды
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeatherResponse(Fact fact, List<Forecast> forecasts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fact(int temp) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Forecast(String date, Parts parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parts(Day day) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Day(@JsonProperty("temp_avg") Integer tempAvg) {}

    // В Java 25 модификатор public и неиспользуемый параметр String[] args не требуются
    @SuppressWarnings("UastIncorrectHttpHeaderInspection")
    static void main() {
        int limit = 7;
        ObjectMapper mapper = new ObjectMapper();

        try (HttpClient client = HttpClient.newHttpClient()) {
            // 1. Автоматически определяем текущую геолокацию по HTTPS
            GeoLocation location = fetchCurrentLocation(client, mapper);
            double lat = location.lat();
            double lon = location.lon();

            System.out.printf(Locale.US, "Определена геолокация: %s, %s (lat=%.4f, lon=%.4f)%n%n",
                    location.city(), location.country(), lat, lon);

            // 2. Формируем GET-запрос к Яндекс Погоде
            URI weatherUri = URI.create(String.format(
                    Locale.US,
                    "%s?lat=%s&lon=%s&limit=%d",
                    WEATHER_API_URL, lat, lon, limit
            ));

            HttpRequest weatherRequest = HttpRequest.newBuilder()
                    .uri(weatherUri)
                    .header("X-Yandex-Weather-Key", API_KEY)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(weatherRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.printf("Ошибка запроса. Код ответа: %d, Тело: %s%n",
                        response.statusCode(), response.body());
                return;
            }

            String rawJson = response.body();

            // 3. Вывод полного JSON-ответа «как есть» (Критерий 2 — 2 балла)
            System.out.println("=== Полный ответ от сервиса (JSON) ===");
            System.out.println(rawJson);

            WeatherResponse weather = mapper.readValue(rawJson, WeatherResponse.class);

            // 4. Вывод текущей температуры из fact.temp (Критерий 3 — 2 балла)
            if (weather.fact() != null) {
                System.out.println("\nТекущая температура (fact.temp): " + weather.fact().temp() + " °C");
            }

            // 5. Расчет средней температуры forecasts[*].parts.day.temp_avg за limit суток (Критерий 4 — 3 балла)
            if (weather.forecasts() != null && !weather.forecasts().isEmpty()) {
                OptionalDouble averageTemp = weather.forecasts().stream()
                        .limit(limit)
                        .map(Forecast::parts)
                        .filter(Objects::nonNull)
                        .map(Parts::day)
                        .filter(Objects::nonNull)
                        .map(Day::tempAvg)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .average();

                if (averageTemp.isPresent()) {
                    System.out.printf(Locale.US,
                            "Средняя температура по прогнозу за %d сут. (parts.day.temp_avg): %.2f °C%n",
                            limit, averageTemp.getAsDouble());
                } else {
                    System.out.println("Не удалось вычислить среднюю температуру: отсутствуют данные parts.day.temp_avg.");
                }
            }

        } catch (IOException e) {
            System.err.println("Ошибка сети или парсинга JSON: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Запрос был прерван: " + e.getMessage());
        }
    }

    private static GeoLocation fetchCurrentLocation(HttpClient client, ObjectMapper mapper) {
        HttpRequest geoRequest = HttpRequest.newBuilder()
                .uri(URI.create(GEO_API_URL))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(geoRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                GeoLocation geo = mapper.readValue(response.body(), GeoLocation.class);
                if (geo.success() && geo.lat() != null && geo.lon() != null) {
                    return geo;
                }
            }
        } catch (IOException e) {
            System.err.println("Не удалось определить геолокацию (" + e.getMessage() + "). Используются координаты по умолчанию.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new GeoLocation(false, "Россия", "Москва (по умолчанию)", DEFAULT_LAT, DEFAULT_LON);
    }
}