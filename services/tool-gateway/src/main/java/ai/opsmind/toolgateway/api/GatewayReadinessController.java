package ai.opsmind.toolgateway.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class GatewayReadinessController {

    private final GatewayReadiness readiness;

    public GatewayReadinessController(GatewayReadiness readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/ready")
    ResponseEntity<Map<String, String>> ready() {
        boolean ready = readiness.ready();
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("status", ready ? "UP" : "DOWN"));
    }
}
