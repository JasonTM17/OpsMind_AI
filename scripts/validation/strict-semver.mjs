import process from "node:process";
import { pathToFileURL } from "node:url";

const STRICT_SEMVER =
  /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;

export function normalizeReleaseTag(version) {
  if (!STRICT_SEMVER.test(version)) {
    throw new Error(
      "release version must be strict SemVer with a v prefix, for example v1.2.3 or v1.2.3-rc.1+build.7",
    );
  }

  const tag = version.slice(1).replace("+", "_");
  if (tag.length > 128) {
    throw new Error("normalized OCI release tag exceeds 128 characters");
  }
  return tag;
}

const isEntryPoint =
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href;

if (isEntryPoint) {
  try {
    process.stdout.write(`${normalizeReleaseTag(process.argv[2] ?? "")}\n`);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
