import process from "node:process";
import { fileURLToPath } from "node:url";

const identifier =
  String.raw`(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)`;

export const strictSemVerPattern = new RegExp(
  String.raw`^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)` +
    String.raw`(?:-(${identifier}(?:\.${identifier})*))?` +
    String.raw`(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$`,
);

export function parseStrictSemVer(value) {
  const match = strictSemVerPattern.exec(value);
  if (!match) return null;
  return {
    version: value,
    releaseTag: value.replace("+", "_"),
    major: match[1],
    minor: match[2],
    patch: match[3],
    isPrerelease: Boolean(match[4]),
    majorMinor: `${match[1]}.${match[2]}`,
  };
}

function runCli() {
  const result = parseStrictSemVer(process.argv[2] ?? "");
  if (!result) {
    console.error(
      "Release version must be strict SemVer without a v prefix " +
        "(example: 1.2.3 or 1.2.3-rc.1+build.7).",
    );
    process.exit(1);
  }
  if (result.releaseTag.length > 128) {
    console.error("Release version exceeds the OCI tag length limit.");
    process.exit(1);
  }
  console.log(`release_version=${result.version}`);
  console.log(`release_tag=${result.releaseTag}`);
  console.log(`major_minor=${result.majorMinor}`);
  console.log(`is_prerelease=${result.isPrerelease}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) runCli();
