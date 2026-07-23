import {
  createPrivateKey,
  createPublicKey,
  createSign,
  timingSafeEqual,
} from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:https";

const required = [
  "OPSMIND_IDENTITY_TLS_KEY_FILE",
  "OPSMIND_IDENTITY_TLS_CERT_FILE",
  "OPSMIND_CAPABILITY_PRIVATE_KEY_FILE",
  "OPSMIND_CAPABILITY_JWKS_FILE",
  "OPSMIND_RUNNER_CLIENT_SECRET",
  "OPSMIND_WORKLOAD_CLIENT_SECRET",
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`${name} is required`);
}

const host = process.env.OPSMIND_IDENTITY_HOST ?? "127.0.0.1";
if (host !== "127.0.0.1") throw new Error("fixture identity must bind exact loopback");
const port = Number.parseInt(process.env.OPSMIND_IDENTITY_PORT ?? "19100", 10);
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error("fixture identity port is invalid");
}
const origin = `https://${host}:${port}`;
const issuer = `${origin}/opsmind`;
const identityKeyId = "cross-service-identity-v1";
const capabilityKeyId = "cross-service-capability-v1";
const identityPrivateKey = createPrivateKey(
  readFileSync(process.env.OPSMIND_IDENTITY_TLS_KEY_FILE),
);
const capabilityPrivateKey = createPrivateKey(
  readFileSync(process.env.OPSMIND_CAPABILITY_PRIVATE_KEY_FILE),
);

function publicJwk(privateKey, keyId) {
  const publicKey = createPublicKey(privateKey).export({ format: "jwk" });
  return { ...publicKey, alg: "RS256", kid: keyId, use: "sig" };
}

const identityJwks = { keys: [publicJwk(identityPrivateKey, identityKeyId)] };
const capabilityJwks = { keys: [publicJwk(capabilityPrivateKey, capabilityKeyId)] };
writeFileSync(
  process.env.OPSMIND_CAPABILITY_JWKS_FILE,
  `${JSON.stringify(capabilityJwks)}\n`,
  { encoding: "utf8", flag: "wx" },
);

const clients = new Map([
  [
    "opsmind-cross-service-runner",
    {
      clientCredential: process.env.OPSMIND_RUNNER_CLIENT_SECRET,
      subject: "cross-service-operator",
      audience: "opsmind-platform-api",
      scope: "incident:read incident:analyze",
      tokenUse: null,
    },
  ],
  [
    "opsmind-platform-api",
    {
      clientCredential: process.env.OPSMIND_WORKLOAD_CLIENT_SECRET,
      subject: "opsmind-platform-api",
      audience: "opsmind-tool-gateway-workload",
      scope: "tool.execute",
      tokenUse: "workload",
    },
  ],
]);
const stats = {
  runner_tokens: 0,
  workload_tokens: 0,
  identity_jwks_requests: 0,
  capability_jwks_requests: 0,
};

function base64UrlJson(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function signToken(clientId, client) {
  const issuedAt = Math.floor(Date.now() / 1000);
  const header = base64UrlJson({ alg: "RS256", kid: identityKeyId, typ: "JWT" });
  const payload = {
    iss: issuer,
    sub: client.subject,
    aud: [client.audience],
    iat: issuedAt,
    exp: issuedAt + 180,
    jti: `${clientId}-${issuedAt}-${stats.runner_tokens + stats.workload_tokens}`,
    scope: client.scope,
    ...(client.tokenUse
      ? { token_use: client.tokenUse, client_id: clientId, azp: clientId }
      : { amr: ["mfa"], name: "Cross-service operator" }),
  };
  const encodedPayload = base64UrlJson(payload);
  const unsigned = `${header}.${encodedPayload}`;
  const signer = createSign("RSA-SHA256");
  signer.update(unsigned);
  signer.end();
  return `${unsigned}.${signer.sign(identityPrivateKey).toString("base64url")}`;
}

function safeEqual(left, right) {
  const leftBytes = Buffer.from(left);
  const rightBytes = Buffer.from(right);
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

function parseBasicAuthorization(value) {
  if (!value?.startsWith("Basic ")) return null;
  let decoded;
  try {
    decoded = Buffer.from(value.slice(6), "base64").toString("utf8");
  } catch {
    return null;
  }
  const separator = decoded.indexOf(":");
  if (separator < 1) return null;
  return {
    clientId: decodeURIComponent(decoded.slice(0, separator)),
    clientCredential: decodeURIComponent(decoded.slice(separator + 1)),
  };
}

function sendJson(response, status, document) {
  const body = Buffer.from(JSON.stringify(document));
  response.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Length": body.length,
    "Content-Type": "application/json",
  });
  response.end(body);
}

function tokenRequest(request, response) {
  const chunks = [];
  let total = 0;
  request.on("data", (chunk) => {
    total += chunk.length;
    if (total > 4096) request.destroy();
    else chunks.push(chunk);
  });
  request.on("end", () => {
    const auth = parseBasicAuthorization(request.headers.authorization);
    const client = auth ? clients.get(auth.clientId) : null;
    const form = new URLSearchParams(Buffer.concat(chunks).toString("utf8"));
    if (
      !auth
      || !client
      || !safeEqual(auth.clientCredential, client.clientCredential)
      || form.get("grant_type") !== "client_credentials"
      || form.get("scope") !== client.scope
    ) {
      sendJson(response, 401, { error: "invalid_client" });
      return;
    }
    if (auth.clientId === "opsmind-cross-service-runner") stats.runner_tokens += 1;
    else stats.workload_tokens += 1;
    sendJson(response, 200, {
      ["access_token"]: signToken(auth.clientId, client),
      token_type: "Bearer",
      expires_in: 180,
      scope: client.scope,
    });
  });
}

const server = createServer(
  {
    cert: readFileSync(process.env.OPSMIND_IDENTITY_TLS_CERT_FILE),
    key: readFileSync(process.env.OPSMIND_IDENTITY_TLS_KEY_FILE),
    minVersion: "TLSv1.2",
  },
  (request, response) => {
    if (
      request.method === "GET"
      && [
        "/opsmind/.well-known/openid-configuration",
        "/.well-known/openid-configuration/opsmind",
        "/.well-known/oauth-authorization-server/opsmind",
      ].includes(request.url)
    ) {
      sendJson(response, 200, {
        issuer,
        jwks_uri: `${issuer}/jwks`,
        token_endpoint: `${issuer}/oauth2/token`,
        authorization_endpoint: `${issuer}/authorize`,
        response_types_supported: ["code"],
        subject_types_supported: ["public"],
        id_token_signing_alg_values_supported: ["RS256"],
      });
      return;
    }
    if (request.method === "GET" && request.url === "/opsmind/jwks") {
      stats.identity_jwks_requests += 1;
      sendJson(response, 200, identityJwks);
      return;
    }
    if (request.method === "GET" && request.url === "/opsmind/capability-jwks") {
      stats.capability_jwks_requests += 1;
      sendJson(response, 200, capabilityJwks);
      return;
    }
    if (request.method === "GET" && request.url === "/__opsmind/status") {
      sendJson(response, 200, {
        schema: "opsmind-fixture-identity-status-v1",
        ...stats,
      });
      return;
    }
    if (request.method === "POST" && request.url === "/opsmind/oauth2/token") {
      tokenRequest(request, response);
      return;
    }
    response.writeHead(404, { "Content-Length": 0 });
    response.end();
  },
);

server.listen(port, host, () => {
  process.stdout.write(`FixtureIdentity=READY Origin=${origin}\n`);
});
