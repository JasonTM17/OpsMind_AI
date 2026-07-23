import { readFileSync, writeFileSync } from "node:fs";
import { request } from "node:https";

const required = [
  "OPSMIND_TOKEN_ENDPOINT",
  "OPSMIND_TOKEN_CA_FILE",
  "OPSMIND_TOKEN_CLIENT_ID",
  "OPSMIND_TOKEN_CLIENT_SECRET",
  "OPSMIND_TOKEN_SCOPE",
  "OPSMIND_TOKEN_OUTPUT_FILE",
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`${name} is required`);
}

const endpoint = new URL(process.env.OPSMIND_TOKEN_ENDPOINT);
if (endpoint.protocol !== "https:" || endpoint.hostname !== "127.0.0.1") {
  throw new Error("fixture token endpoint must use exact loopback HTTPS");
}
const body = new URLSearchParams({
  grant_type: "client_credentials",
  scope: process.env.OPSMIND_TOKEN_SCOPE,
}).toString();
const authorization = Buffer.from(
  `${encodeURIComponent(process.env.OPSMIND_TOKEN_CLIENT_ID)}:${encodeURIComponent(process.env.OPSMIND_TOKEN_CLIENT_SECRET)}`,
).toString("base64");

const response = await new Promise((resolve, reject) => {
  const outgoing = request(
    endpoint,
    {
      method: "POST",
      ca: readFileSync(process.env.OPSMIND_TOKEN_CA_FILE),
      headers: {
        Accept: "application/json",
        Authorization: `Basic ${authorization}`,
        "Content-Length": Buffer.byteLength(body),
        "Content-Type": "application/x-www-form-urlencoded",
      },
      rejectUnauthorized: true,
      timeout: 5000,
    },
    (incoming) => {
      const chunks = [];
      let size = 0;
      incoming.on("data", (chunk) => {
        size += chunk.length;
        if (size > 32768) incoming.destroy(new Error("token response exceeded limit"));
        else chunks.push(chunk);
      });
      incoming.on("end", () => resolve({
        status: incoming.statusCode,
        contentType: incoming.headers["content-type"],
        body: Buffer.concat(chunks).toString("utf8"),
      }));
    },
  );
  outgoing.on("error", reject);
  outgoing.on("timeout", () => outgoing.destroy(new Error("token request timed out")));
  outgoing.end(body);
});

if (
  response.status !== 200
  || !response.contentType?.toLowerCase().startsWith("application/json")
) {
  throw new Error("fixture token endpoint rejected the request");
}
const document = JSON.parse(response.body);
if (
  !document
  || typeof document.access_token !== "string"
  || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/u.test(document.access_token)
  || document.token_type !== "Bearer"
  || document.scope !== process.env.OPSMIND_TOKEN_SCOPE
) {
  throw new Error("fixture token response is invalid");
}
writeFileSync(process.env.OPSMIND_TOKEN_OUTPUT_FILE, document.access_token, {
  encoding: "utf8",
  flag: "wx",
  mode: 0o600,
});
process.stdout.write("TokenFetch=PASS\n");
