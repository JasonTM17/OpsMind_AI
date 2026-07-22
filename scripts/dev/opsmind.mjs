import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const commandArguments = process.argv.slice(2);

if (process.platform === "win32") {
  const translatedArguments = commandArguments.map((argument) =>
    argument === "--dry-run" ? "-DryRun" : argument,
  );
  const result = spawnSync(
    "powershell.exe",
    [
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      path.join(scriptDirectory, "opsmind.ps1"),
      ...translatedArguments,
    ],
    { stdio: "inherit", shell: false },
  );
  process.exit(result.status ?? 1);
}

const result = spawnSync(
  "sh",
  [path.join(scriptDirectory, "opsmind.sh"), ...commandArguments],
  { stdio: "inherit", shell: false },
);
process.exit(result.status ?? 1);
