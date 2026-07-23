package ai.opsmind.platform.investigation.integration;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@FunctionalInterface
interface ToolGatewayHttpExchange {

    HttpResponse<byte[]> send(HttpRequest request, Duration timeout);
}
