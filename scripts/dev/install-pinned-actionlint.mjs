import { execFileSync, spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const version = "1.7.12";
const releaseBaseUrl = `https://github.com/rhysd/actionlint/releases/download/v${version}`;
const maximumArchiveBytes = 10 * 1024 * 1024;
const maximumExecutableBytes = 20 * 1024 * 1024;
const sourceArchiveName = ".opsmind-actionlint-source-archive";
const releases = new Map([
  ["darwin-arm64", ["actionlint_1.7.12_darwin_arm64.tar.gz", "aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f"]],
  ["darwin-x64", ["actionlint_1.7.12_darwin_amd64.tar.gz", "5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644"]],
  ["linux-arm64", ["actionlint_1.7.12_linux_arm64.tar.gz", "325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6"]],
  ["linux-ia32", ["actionlint_1.7.12_linux_386.tar.gz", "72a44b32c2d032700e6d0c23ca2f540b67519ec68db098ddfcfa96059e61f723"]],
  ["linux-x64", ["actionlint_1.7.12_linux_amd64.tar.gz", "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"]],
  ["win32-arm64", ["actionlint_1.7.12_windows_arm64.zip", "cadcf7ea4efe3a68728893813643cebe1185e5b1d4be5b96245f65c9a4d5ea41"]],
  ["win32-ia32", ["actionlint_1.7.12_windows_386.zip", "cdc8643b2c8dc890c76ad16095da97e75f86572805cc3573cc13f31ea0f19127"]],
  ["win32-x64", ["actionlint_1.7.12_windows_amd64.zip", "6e7241b51e6817ea6a047693d8e6fed13b31819c9a0dd6c5a726e1592d22f6e9"]],
]);

function parseCacheRoot(argumentsList) {
  if (argumentsList.length !== 2 || argumentsList[0] !== "--cache-root") {
    throw new Error("Usage: node scripts/dev/install-pinned-actionlint.mjs --cache-root <absolute-path>");
  }
  if (!path.isAbsolute(argumentsList[1])) {
    throw new Error("The actionlint cache root must be an absolute path.");
  }
  return path.resolve(argumentsList[1]);
}

function executablePath(directory) {
  return path.join(directory, process.platform === "win32" ? "actionlint.exe" : "actionlint");
}

function assertNoSymbolicLinkAncestors(startPath) {
  let currentPath = path.resolve(startPath);
  while (true) {
    try {
      const item = fs.lstatSync(currentPath);
      if (item.isSymbolicLink()) {
        throw new Error(`Actionlint cache path contains a symbolic-link ancestor: ${currentPath}`);
      }
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    const parentPath = path.dirname(currentPath);
    if (parentPath === currentPath) break;
    currentPath = parentPath;
  }
}

function hashRegularFile(filePath, maximumBytes, label) {
  const item = fs.lstatSync(filePath);
  if (!item.isFile() || item.isSymbolicLink()) {
    throw new Error(`${label} is not a regular file: ${filePath}`);
  }
  if (item.size === 0 || item.size > maximumBytes) {
    throw new Error(`${label} has an unsafe size: ${filePath}`);
  }
  return createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function readInstalledVersion(executable) {
  const item = fs.lstatSync(executable);
  if (!item.isFile() || item.isSymbolicLink()) {
    throw new Error(`Pinned actionlint executable is not a regular file: ${executable}`);
  }
  const output = execFileSync(executable, ["-version"], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  return output.split(/\r?\n/, 1)[0].trim();
}

function verifyInstallation(directory) {
  const directoryItem = fs.lstatSync(directory);
  if (!directoryItem.isDirectory() || directoryItem.isSymbolicLink()) {
    throw new Error(`Pinned actionlint directory is unsafe: ${directory}`);
  }
  const executable = executablePath(directory);
  if (!fs.existsSync(executable)) return false;
  const actualVersion = readInstalledVersion(executable);
  if (actualVersion !== version) {
    throw new Error(`Pinned actionlint version mismatch: expected ${version}, actual ${actualVersion}`);
  }
  return true;
}

function verifyCachedInstallation(directory, archivePath, archiveName, expectedSha256) {
  const installedExecutable = executablePath(directory);
  const installedHash = hashRegularFile(installedExecutable, maximumExecutableBytes, "Cached actionlint executable");
  const archiveHash = hashRegularFile(archivePath, maximumArchiveBytes, "Cached actionlint source archive");
  if (archiveHash !== expectedSha256) {
    throw new Error(`Cached actionlint source checksum mismatch: expected ${expectedSha256}, actual ${archiveHash}`);
  }

  const verificationDirectory = fs.mkdtempSync(path.join(path.dirname(directory), `.verify-${process.pid}-`));
  try {
    extractArchive(archivePath, verificationDirectory, archiveName);
    if (process.platform !== "win32") fs.chmodSync(executablePath(verificationDirectory), 0o755);
    if (!verifyInstallation(verificationDirectory)) {
      throw new Error("Verified actionlint source archive did not contain the expected executable.");
    }
    const verifiedHash = hashRegularFile(
      executablePath(verificationDirectory),
      maximumExecutableBytes,
      "Verified actionlint executable"
    );
    if (installedHash !== verifiedHash) {
      throw new Error(`Cached actionlint executable checksum mismatch: expected ${verifiedHash}, actual ${installedHash}`);
    }
  } finally {
    fs.rmSync(verificationDirectory, { force: true, recursive: true });
  }
}

async function downloadArchive(url) {
  const response = await fetch(url, {
    headers: { "User-Agent": "OpsMind-bootstrap" },
    redirect: "follow",
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) throw new Error(`actionlint download failed with HTTP ${response.status}`);

  const advertisedLength = Number(response.headers.get("content-length") || 0);
  if (advertisedLength > maximumArchiveBytes) {
    throw new Error(`actionlint archive exceeds ${maximumArchiveBytes} bytes`);
  }
  if (!response.body) throw new Error("actionlint download returned no response body");
  const chunks = [];
  let receivedBytes = 0;
  for await (const chunk of response.body) {
    receivedBytes += chunk.byteLength;
    if (receivedBytes > maximumArchiveBytes) {
      throw new Error(`actionlint archive exceeds ${maximumArchiveBytes} bytes`);
    }
    chunks.push(Buffer.from(chunk));
  }
  if (receivedBytes === 0) {
    throw new Error("actionlint archive is empty");
  }
  return Buffer.concat(chunks, receivedBytes);
}

function extractArchive(archivePath, destination, archiveName) {
  const extractionArguments = archiveName.endsWith(".tar.gz")
    ? ["-xzf", archivePath, "-C", destination]
    : ["-xf", archivePath, "-C", destination];
  const result = spawnSync("tar", extractionArguments, {
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.error) throw new Error(`Unable to execute tar: ${result.error.message}`);
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout || "unknown extraction error").trim();
    throw new Error(`Unable to extract actionlint archive: ${detail}`);
  }
}

async function install() {
  const cacheRoot = parseCacheRoot(process.argv.slice(2));
  assertNoSymbolicLinkAncestors(cacheRoot);
  const release = releases.get(`${process.platform}-${process.arch}`);
  if (!release) {
    throw new Error(`Unsupported actionlint platform: ${process.platform}/${process.arch}`);
  }

  const [archiveName, expectedSha256] = release;
  const versionParent = path.join(cacheRoot, "tools", "actionlint");
  const targetDirectory = path.join(versionParent, version);
  if (fs.existsSync(targetDirectory)) {
    const targetItem = fs.lstatSync(targetDirectory);
    if (!targetItem.isDirectory() || targetItem.isSymbolicLink()) {
      throw new Error(`Pinned actionlint directory is unsafe: ${targetDirectory}`);
    }
    hashRegularFile(executablePath(targetDirectory), maximumExecutableBytes, "Cached actionlint executable");
    const sourceArchivePath = path.join(targetDirectory, sourceArchiveName);
    let sourceArchive = null;
    if (fs.existsSync(sourceArchivePath)) {
      verifyCachedInstallation(targetDirectory, sourceArchivePath, archiveName, expectedSha256);
    }
    else {
      sourceArchive = await downloadArchive(`${releaseBaseUrl}/${archiveName}`);
      const actualSha256 = createHash("sha256").update(sourceArchive).digest("hex");
      if (actualSha256 !== expectedSha256) {
        throw new Error(`actionlint checksum mismatch: expected ${expectedSha256}, actual ${actualSha256}`);
      }
      const repairDirectory = fs.mkdtempSync(path.join(versionParent, `.repair-${process.pid}-`));
      const repairArchivePath = path.join(repairDirectory, archiveName);
      try {
        fs.writeFileSync(repairArchivePath, sourceArchive, { flag: "wx" });
        verifyCachedInstallation(targetDirectory, repairArchivePath, archiveName, expectedSha256);
        try {
          fs.writeFileSync(sourceArchivePath, sourceArchive, { flag: "wx" });
        } catch (error) {
          if (!fs.existsSync(sourceArchivePath)) throw error;
        }
      } finally {
        fs.rmSync(repairDirectory, { force: true, recursive: true });
      }
    }
    console.log(`Actionlint=READY Version=${version} Source=CACHE`);
    return;
  }

  fs.mkdirSync(versionParent, { recursive: true });
  const temporaryDirectory = fs.mkdtempSync(path.join(versionParent, `.install-${process.pid}-`));
  const archivePath = path.join(temporaryDirectory, `${randomUUID()}-${archiveName}`);
  const extractionDirectory = path.join(temporaryDirectory, "extracted");
  try {
    const archive = await downloadArchive(`${releaseBaseUrl}/${archiveName}`);
    const actualSha256 = createHash("sha256").update(archive).digest("hex");
    if (actualSha256 !== expectedSha256) {
      throw new Error(`actionlint checksum mismatch: expected ${expectedSha256}, actual ${actualSha256}`);
    }

    fs.writeFileSync(archivePath, archive, { flag: "wx" });
    fs.mkdirSync(extractionDirectory);
    extractArchive(archivePath, extractionDirectory, archiveName);
    if (process.platform !== "win32") fs.chmodSync(executablePath(extractionDirectory), 0o755);
    if (!verifyInstallation(extractionDirectory)) {
      throw new Error("Downloaded actionlint archive did not contain the expected executable.");
    }
    fs.writeFileSync(path.join(extractionDirectory, sourceArchiveName), archive, { flag: "wx" });

    try {
      fs.renameSync(extractionDirectory, targetDirectory);
    } catch (error) {
      if (!fs.existsSync(targetDirectory)) throw error;
      const existingSourceArchivePath = path.join(targetDirectory, sourceArchiveName);
      if (fs.existsSync(existingSourceArchivePath)) {
        verifyCachedInstallation(targetDirectory, existingSourceArchivePath, archiveName, expectedSha256);
      }
      else {
        verifyCachedInstallation(targetDirectory, archivePath, archiveName, expectedSha256);
      }
    }
    console.log(`Actionlint=READY Version=${version} Source=VERIFIED_RELEASE Sha256=${expectedSha256}`);
  } finally {
    fs.rmSync(temporaryDirectory, { force: true, recursive: true });
  }
}

install().catch((error) => {
  console.error(`Actionlint=BLOCK Reason=${error.message}`);
  process.exitCode = 2;
});
