import { execFileSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const version = "2.4.0";
const releaseBaseUrl = `https://github.com/google/osv-scanner/releases/download/v${version}`;
const maximumExecutableBytes = 80 * 1024 * 1024;
const releases = new Map([
  ["darwin-arm64", ["osv-scanner_darwin_arm64", "9ca3185ad63e9ab54f7cb90f46a7362be02d80e37f0123d095a54355ea202f5d"]],
  ["darwin-x64", ["osv-scanner_darwin_amd64", "088119325156321c34c456ac3703d6013538fd71cbac82b891ab34db491e4d66"]],
  ["linux-arm64", ["osv-scanner_linux_arm64", "44e580752910f0ff36ec99aff59af20f65df1e859aa31e5605a8f0d055b496e9"]],
  ["linux-x64", ["osv-scanner_linux_amd64", "15314940c10d26af9c6649f150b8a47c1262e8fc7e17b1d1029b0e479e8ed8a0"]],
  ["win32-arm64", ["osv-scanner_windows_arm64.exe", "1ce89d7d8ef083634648ef0f193fe1254f36f46f4bdc93d61178adacc2e60da0"]],
  ["win32-x64", ["osv-scanner_windows_amd64.exe", "0cdd113610126d5dfd5e12ad0e0b4f3e879291ff19bb43b0c52ed2f2c2df1a37"]],
]);

function cacheRoot(argumentsList) {
  if (argumentsList.length !== 2 || argumentsList[0] !== "--cache-root") {
    throw new Error("Usage: node scripts/dev/install-pinned-osv-scanner.mjs --cache-root <absolute-path>");
  }
  if (!path.isAbsolute(argumentsList[1])) {
    throw new Error("The OSV-Scanner cache root must be an absolute path.");
  }
  return path.resolve(argumentsList[1]);
}

function assertSafeAncestors(targetPath) {
  let current = path.resolve(targetPath);
  while (true) {
    try {
      if (fs.lstatSync(current).isSymbolicLink()) {
        throw new Error(`OSV-Scanner cache path contains a symbolic-link ancestor: ${current}`);
      }
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    const parent = path.dirname(current);
    if (parent === current) return;
    current = parent;
  }
}

function regularFileHash(filePath) {
  const item = fs.lstatSync(filePath);
  if (!item.isFile() || item.isSymbolicLink() || item.size < 1 || item.size > maximumExecutableBytes) {
    throw new Error(`Pinned OSV-Scanner executable is unsafe: ${filePath}`);
  }
  return createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function verifyExecutable(filePath, expectedHash) {
  const actualHash = regularFileHash(filePath);
  if (actualHash !== expectedHash) {
    throw new Error(`OSV-Scanner checksum mismatch: expected ${expectedHash}, actual ${actualHash}`);
  }
  const output = execFileSync(filePath, ["--version"], {
    encoding: "utf8",
    maxBuffer: 1024 * 1024,
    stdio: ["ignore", "pipe", "pipe"],
    timeout: 10_000,
    windowsHide: true,
  });
  if (!output.split(/\r?\n/, 1)[0].includes(`version: ${version}`)) {
    throw new Error(`Pinned OSV-Scanner version is not ${version}.`);
  }
}

async function download(url) {
  const response = await fetch(url, {
    headers: { "User-Agent": "OpsMind-bootstrap" },
    redirect: "follow",
    signal: AbortSignal.timeout(180_000),
  });
  if (!response.ok || !response.body) {
    throw new Error(`OSV-Scanner download failed with HTTP ${response.status}`);
  }
  const advertisedBytes = Number(response.headers.get("content-length") || 0);
  if (advertisedBytes > maximumExecutableBytes) {
    throw new Error("OSV-Scanner download exceeds the bounded size.");
  }
  const chunks = [];
  let receivedBytes = 0;
  for await (const chunk of response.body) {
    receivedBytes += chunk.byteLength;
    if (receivedBytes > maximumExecutableBytes) {
      throw new Error("OSV-Scanner download exceeds the bounded size.");
    }
    chunks.push(Buffer.from(chunk));
  }
  if (receivedBytes === 0) throw new Error("OSV-Scanner download is empty.");
  return Buffer.concat(chunks, receivedBytes);
}

async function install() {
  const root = cacheRoot(process.argv.slice(2));
  assertSafeAncestors(root);
  const release = releases.get(`${process.platform}-${process.arch}`);
  if (!release) throw new Error(`Unsupported OSV-Scanner platform: ${process.platform}/${process.arch}`);

  const [assetName, expectedHash] = release;
  const directory = path.join(root, "tools", "osv-scanner", version);
  const executable = path.join(directory, process.platform === "win32" ? "osv-scanner.exe" : "osv-scanner");
  assertSafeAncestors(directory);
  if (fs.existsSync(executable)) {
    verifyExecutable(executable, expectedHash);
    console.log(`OSVScanner=READY Version=${version} Source=CACHE Path=${executable}`);
    return;
  }

  fs.mkdirSync(directory, { recursive: true });
  assertSafeAncestors(directory);
  const temporary = path.join(directory, `.download-${process.pid}-${randomUUID()}`);
  try {
    fs.writeFileSync(temporary, await download(`${releaseBaseUrl}/${assetName}`), { flag: "wx" });
    if (process.platform !== "win32") fs.chmodSync(temporary, 0o755);
    verifyExecutable(temporary, expectedHash);
    if (fs.existsSync(executable)) verifyExecutable(executable, expectedHash);
    else fs.renameSync(temporary, executable);
    console.log(`OSVScanner=READY Version=${version} Source=VERIFIED_RELEASE Sha256=${expectedHash} Path=${executable}`);
  } finally {
    if (fs.existsSync(temporary)) fs.rmSync(temporary, { force: true });
  }
}

install().catch((error) => {
  console.error(`OSVScanner=BLOCK Reason=${error.message}`);
  process.exitCode = 2;
});
