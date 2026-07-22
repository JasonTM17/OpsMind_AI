import fs from "node:fs";
import path from "node:path";

function usage() {
  console.error(
    "Usage: node scripts/dev/recover-stale-command-lock.mjs --lock-path <absolute-path> --confirm-stale",
  );
}

function parseArguments(argumentsList) {
  if (argumentsList.length !== 3 || argumentsList[0] !== "--lock-path" || argumentsList[2] !== "--confirm-stale") {
    usage();
    throw new Error("An explicit --confirm-stale acknowledgement is required.");
  }
  if (!path.isAbsolute(argumentsList[1])) throw new Error("The lock path must be absolute.");
  return path.resolve(argumentsList[1]);
}

function assertSafeLockPath(lockPath) {
  const normalized = lockPath.split(path.sep).join("/").replace(/\/+$/, "");
  if (!normalized.endsWith("/.opsmind/command-locks/heavy")) {
    throw new Error("Refusing to operate outside a repository .opsmind/command-locks/heavy path.");
  }
  let current = lockPath;
  while (true) {
    const parent = path.dirname(current);
    if (fs.existsSync(current)) {
      const item = fs.lstatSync(current);
      if (item.isSymbolicLink()) throw new Error(`Lock path contains a symbolic link: ${current}`);
    }
    if (parent === current) break;
    current = parent;
  }
}

function readOwner(ownerPath) {
  if (!fs.existsSync(ownerPath)) throw new Error("Lock owner metadata is missing; refusing automatic recovery.");
  const values = new Map();
  for (const line of fs.readFileSync(ownerPath, "utf8").split(/\r?\n/)) {
    const separator = line.indexOf("=");
    if (separator > 0) values.set(line.slice(0, separator), line.slice(separator + 1));
  }
  const pid = Number(values.get("ProcessId"));
  if (!Number.isInteger(pid) || pid <= 0) throw new Error("Lock owner metadata has an invalid PID.");
  return { pid, token: values.get("Token") || "", startedUtc: values.get("StartedUtc") || "" };
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error?.code === "ESRCH") return false;
    if (error?.code === "EPERM") return true;
    throw error;
  }
}

function recover(argumentsList) {
  const lockPath = parseArguments(argumentsList);
  assertSafeLockPath(lockPath);
  if (!fs.existsSync(lockPath)) {
    console.log("StaleLockRecovery=NOOP LockAbsent=true");
    return;
  }
  const lockItem = fs.lstatSync(lockPath);
  if (!lockItem.isDirectory() || lockItem.isSymbolicLink()) throw new Error("Lock path is not a safe directory.");
  const owner = readOwner(path.join(lockPath, "owner.txt"));
  if (isProcessAlive(owner.pid)) {
    throw new Error(`Refusing recovery because owner PID ${owner.pid} is still alive.`);
  }
  const entries = fs.readdirSync(lockPath);
  if (entries.some((entry) => entry !== "owner.txt")) {
    throw new Error("Refusing recovery because the lock directory contains unexpected entries.");
  }
  fs.unlinkSync(path.join(lockPath, "owner.txt"));
  fs.rmdirSync(lockPath);
  if (fs.existsSync(lockPath)) throw new Error("Lock directory remains after recovery.");
  console.log(`StaleLockRecovery=PASS ProcessId=${owner.pid} StartedUtc=${owner.startedUtc}`);
}

try {
  recover(process.argv.slice(2));
} catch (error) {
  console.error(`StaleLockRecovery=BLOCK Reason=${error.message}`);
  process.exitCode = 2;
}
