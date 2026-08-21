package com.minenova.discord;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AIService {

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public AIService(String endpoint, String apiKey, String model) {
        this.endpoint = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public CompletableFuture<String> askAsync(String systemPrompt, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ask(systemPrompt, userMessage);
            } catch (Exception e) {
                return "Błąd: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> askWithContextAsync(String systemPrompt, String userMessage, java.util.List<Map<String, String>> history) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return askWithContext(systemPrompt, userMessage, history);
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });
    }

    public String askWithContext(String systemPrompt, String userMessage, java.util.List<Map<String, String>> history) throws IOException, InterruptedException {
        System.out.println("[AI] Sending to: " + endpoint + " | Model: " + model);
        System.out.println("[AI] Question: " + userMessage);
        System.out.println("[AI] Context messages: " + history.size());

        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        for (Map<String, String> entry : history) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", entry.get("role"));
            msg.addProperty("content", entry.get("content"));
            messages.add(msg);
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        body.add("messages", messages);
        body.addProperty("max_tokens", 2000);
        body.addProperty("temperature", 0.7);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint + "chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .timeout(Duration.ofSeconds(60))
            .build();

        System.out.println("[AI] Request URL: " + request.uri());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
                System.out.println("[AI] Response (" + response.statusCode() + "): " + content.substring(0, Math.min(content.length(), 200)) + "...");
                return content;
            }
            System.out.println("[AI] Empty response from provider");
            return "No response from AI.";
        } else {
            System.out.println("[AI] ERROR (" + response.statusCode() + "): " + response.body());
            return "AI error (HTTP " + response.statusCode() + "): " + response.body();
        }
    }

    public String ask(String systemPrompt, String userMessage) throws IOException, InterruptedException {
        System.out.println("[AI] Sending to: " + endpoint + " | Model: " + model);
        System.out.println("[AI] Question: " + userMessage);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        body.add("messages", messages);
        body.addProperty("max_tokens", 2000);
        body.addProperty("temperature", 0.7);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint + "chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .timeout(Duration.ofSeconds(60))
            .build();

        System.out.println("[AI] Request URL: " + request.uri());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                String content = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
                System.out.println("[AI] Response (" + response.statusCode() + "): " + content.substring(0, Math.min(content.length(), 200)) + "...");
                return content;
            }
            System.out.println("[AI] Empty response from provider");
            return "Brak odpowiedzi z AI.";
        } else {
            System.out.println("[AI] ERROR (" + response.statusCode() + "): " + response.body());
            return "AI error (HTTP " + response.statusCode() + "): " + response.body();
        }
    }

    public boolean isConfigured() {
        return !apiKey.isBlank() && !model.isBlank();
    }
}
