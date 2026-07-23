package ai.opsmind.toolgateway.contract;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.opsmind.toolgateway.application.RequestDigester;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class GatewayJsonContractTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void productionMapperRejectsDuplicateFieldsAndTrailingJson() {
        assertThatThrownBy(() -> jsonMapper.readTree("{\"action\":\"read\",\"action\":\"write\"}"))
            .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> jsonMapper.readTree("{} {}"))
            .isInstanceOf(JacksonException.class);
    }

    @Test
    void canonicalFixturesDeserializeAndCarryVerifiedRequestDigest() throws IOException {
        Path fixtureRoot = repositoryRoot().resolve("packages/contracts/fixtures/tool-gateway");
        ToolExecutionRequest request = jsonMapper.readValue(
            fixtureRoot.resolve("tool-execution-request-v1.valid.json").toFile(),
            ToolExecutionRequest.class
        );
        var response = jsonMapper.readTree(
            fixtureRoot.resolve("tool-execution-response-v1.valid.json").toFile()
        );

        assertThat(new RequestDigester(jsonMapper).digest(request))
            .isEqualTo("45e795204343e4e5dc30decd9aa73b80966abe6ba900062df09f9cd81b84a1d0")
            .isEqualTo(response.get("request_digest").asString());
        assertThat(response.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(response.get("evidence").size()).isEqualTo(1);
    }

    @Test
    void platformInvestigationFixtureUsesExactGatewayCanonicalBytes() throws IOException {
        Path fixture = repositoryRoot().resolve(
            "packages/contracts/fixtures/tool-gateway/"
                + "investigation-tool-execution-request-v1.canonical.json"
        );
        byte[] canonicalBytes = Files.readString(fixture).stripTrailing()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ToolExecutionRequest request = jsonMapper.readValue(canonicalBytes, ToolExecutionRequest.class);

        assertThat(new RequestDigester(jsonMapper).digest(request))
            .isEqualTo(RequestDigester.sha256(canonicalBytes));
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("package.json"))
                && Files.isDirectory(candidate.resolve("packages/contracts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root cannot be located.");
    }
}
