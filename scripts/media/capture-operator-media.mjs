// Regenerates the operator media used by README.md.
//
// A screenshot in a README goes stale silently: the UI moves, the image does
// not, and nobody notices until a reader is misled. This script exists so the
// media can be rebuilt from the same fixture stack the browser suite uses,
// rather than reproduced by hand.
//
// Usage, from the repository root:
//   node scripts/media/capture-operator-media.mjs
//
// It writes docs/media/operator-investigation-workspace.png, derives the
// walkthrough GIF from it, and rewrites docs/media/media-manifest.json. The
// secret scanner verifies every field it writes, so run
// scripts/governance/scan-project-secrets.ps1 afterwards.

import { createHash } from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const appRoot = path.join(repositoryRoot, "apps/operator-web");
const mediaRoot = path.join(repositoryRoot, "docs/media");
const screenshotPath = path.join(mediaRoot, "operator-investigation-workspace.png");
const walkthroughPath = path.join(mediaRoot, "operator-investigation-workspace-walkthrough.gif");
const manifestPath = path.join(mediaRoot, "media-manifest.json");

const COMPLETED_RUN = "10000000-0000-4000-8000-000000000701";
const ORGANIZATION = "10000000-0000-4000-8000-000000000702";
const PROJECT = "10000000-0000-4000-8000-000000000703";
const INCIDENT = "10000000-0000-4000-8000-000000000704";
const VIEWPORT_WIDTH = 1280;
const GIF_WIDTH = 720;
const GIF_HEIGHT = 480;
const GIF_FRAMES = 10;

function fail(message) {
  console.error(`MediaCapture=BLOCK Reason=${message}`);
  process.exit(1);
}

async function main() {
  // Resolved from the app workspace, which is where the browser suite already
  // pins its Playwright version.
  const { chromium } = await import(
    pathToFileURL(path.join(appRoot, "node_modules/@playwright/test/index.mjs")).href
  ).catch(() => fail("playwright-unavailable"));

  const port = process.env.OPSMIND_E2E_WEB_PORT ?? "3300";
  const stack = spawn(
    process.execPath,
    [path.join(appRoot, "tests/support/start-e2e-stack.mjs")],
    {
      cwd: appRoot,
      // The badge in the header reads "Unspecified" when this is unset, which
      // is accurate in a bare test process and misleading in a README hero.
      env: {
        ...process.env,
        OPSMIND_DEPLOYMENT_ENVIRONMENT: "local",
        OPSMIND_E2E_WEB_PORT: port,
      },
      stdio: "inherit",
    },
  );

  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await waitForHealth(`${baseUrl}/api/health`);
    const browser = await chromium.launch();
    try {
      const page = await browser.newPage({ viewport: { width: VIEWPORT_WIDTH, height: 900 } });
      await page.emulateMedia({ reducedMotion: "reduce" });
      await page.goto(
        `${baseUrl}/organizations/${ORGANIZATION}/projects/${PROJECT}`
          + `/incidents/${INCIDENT}/investigations/${COMPLETED_RUN}`,
        { waitUntil: "networkidle" },
      );
      await page.getByRole("heading", { name: "Cited conclusion" }).waitFor();
      await page.screenshot({ path: screenshotPath, fullPage: true });
    } finally {
      await browser.close();
    }
  } finally {
    stack.kill();
  }

  buildWalkthrough();
  writeManifest();
  console.log("MediaCapture=PASS");
}

async function waitForHealth(url) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    }
    catch {
      // The stack is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  fail("stack-did-not-become-healthy");
}

function buildWalkthrough() {
  // A bounded vertical pan over the reviewed screenshot. The GIF shows only
  // content that already passed review as a still, so it cannot disclose
  // anything the screenshot did not.
  const { width, height } = readPngDimensions(screenshotPath);
  if (width !== VIEWPORT_WIDTH) fail(`unexpected-screenshot-width-${width}`);
  const cropHeight = Math.round((GIF_HEIGHT / GIF_WIDTH) * width);
  const travel = Math.max(0, height - cropHeight);
  const arguments_ = ["-delay", "60", "-loop", "0"];
  for (let frame = 0; frame < GIF_FRAMES; frame += 1) {
    const offset = Math.round((travel * frame) / Math.max(1, GIF_FRAMES - 1));
    arguments_.push(
      "(", screenshotPath,
      "-crop", `${width}x${cropHeight}+0+${offset}`,
      "+repage",
      "-resize", `${GIF_WIDTH}x${GIF_HEIGHT}!`,
      ")",
    );
  }
  arguments_.push("-layers", "optimize", walkthroughPath);
  const result = spawnSync("magick", arguments_, { stdio: "inherit" });
  if (result.status !== 0) fail("imagemagick-failed");
}

function readPngDimensions(filePath) {
  const header = fs.readFileSync(filePath).subarray(0, 33);
  if (header.subarray(1, 4).toString("latin1") !== "PNG") fail("not-a-png");
  return { width: header.readUInt32BE(16), height: header.readUInt32BE(20) };
}

function digestOf(filePath) {
  return createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function writeManifest() {
  const existing = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const screenshot = readPngDimensions(screenshotPath);
  const manifest = {
    schemaVersion: 1,
    media: existing.media.map((entry) => {
      const absolute = path.join(repositoryRoot, entry.path);
      const shared = { sha256: digestOf(absolute), byteSize: fs.statSync(absolute).size };
      if (entry.mediaType === "image/png") {
        return { ...entry, ...shared, width: screenshot.width, height: screenshot.height };
      }
      return { ...entry, ...shared, width: GIF_WIDTH, height: GIF_HEIGHT, frames: GIF_FRAMES };
    }),
  };
  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

await main();
