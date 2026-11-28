// Regenerate only desktop assets from the exact companion of SF_Symbol_Catalog.
// node desktop/tools/sync-symbols.mjs --refresh  (explicit upstream download)
// node desktop/tools/sync-symbols.mjs --check    (offline reproducibility check)
// node desktop/tools/sync-symbols.mjs --generate (offline SVG regeneration)
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import path from "node:path";

const desktop = fileURLToPath(new URL("../", import.meta.url));
const revision = "f2d22ed50937183de95b6e356e30ed2841e136ad"; // Jetpack_SF_Symbols 1.1.1
const upstream = `https://raw.githubusercontent.com/cosmictaserdev-creator/Jetpack_SF_Symbols/${revision}`;
const sourceDir = path.join(desktop, "kmp/ui/src/jvmSharedMain/kotlin/com/composables/sfsymbols");
// Keep generated companion vectors separate from legacy/user-managed glyph assets.
const assets = path.join(desktop, "rust/app/assets/icon/catalog");
const symbols = {
  shuffle: "SFShuffle", prev: "SFBackwardEndFill", play: "SFPlayFill",
  pause: "SFPauseFill", next: "SFForwardEndFill", repeat: "SFRepeat",
  repeat_one: "SFRepeat1", star: "SFStar", star_filled: "SFStarFill",
  lyrics: "SFTextQuote", lyrics_off: "SFTextQuote", queue: "SFListBullet",
  video: "SFVideo", music_note: "SFMusicNote", podcast: "SFDotRadiowavesLeftAndRight",
  maximize2: "SFArrowUpLeftAndArrowDownRight", miniplayer: "SFPip",
  volume_0: "SFSpeakerSlash", volume_1: "SFSpeakerWave1Fill",
  volume_2: "SFSpeakerWave2", volume_3: "SFSpeakerWave3",
  home: "SFHouseFill", explore: "SFSafari", library: "SFBooksVerticalFill",
  search: "SFMagnifyingglass", download: "SFArrowDownCircle",
  settings: "SFGearshapeFill", person_circle: "SFPersonCropCircle",
  sparkles: "SFSparkles", person: "SFPerson", play_outline: "SFPlayCircle",
  pencil: "SFPencil", paintbrush: "SFPaintbrushPointed", wifi: "SFWifi",
  import: "SFArrowDownDocument", keyboard: "SFKeyboard",
  history: "SFClockArrowTriangleheadCounterclockwiseRotate90", radio: "SFDotRadiowavesLeftAndRight",
  waveform: "SFWaveform", heart: "SFHeart", heart_filled: "SFHeartFill",
  thumbs_down: "SFHandThumbsdown", close: "SFXmark", trash: "SFTrash",
  ellipsis_vertical: "SFEllipsis", list_plus: "SFTextBadgePlus",
  list_minus: "SFTextBadgeMinus", list_checks: "SFChecklist",
  logout: "SFRectanglePortraitAndArrowRight", check: "SFCheckmark",
  upload: "SFArrowUpCircle", users: "SFPerson2Fill",
  copy: "SFDocumentOnDocument", link: "SFLink", headphones: "SFHeadphones",
  folder: "SFFolder", pin: "SFPin", pin_off: "SFPinSlash",
  chevron_left: "SFChevronLeft", chevron_right: "SFChevronRight",
  chevron_up: "SFChevronUp", chevron_down: "SFChevronDown", crown: "SFCrown",
};
// Only these compound paths have reviewed, disconnected *solid* parts.
// Never split arbitrary contours: a contour can be a hole in another contour.
const separateParts = new Set(["SFPauseFill", "SFForwardEndFill", "SFBackwardEndFill"]);
const extraKmp = [
  "SFMicrophoneFill", "SFAccessibility", "SFBell", "SFGlobe", "SFInternaldrive",
  "SFInfoCircle", "SFSliderHorizontal3", "SFLockShield", "SFTag", "SFPerson2Fill",
];
const refresh = process.argv.includes("--refresh");
const check = process.argv.includes("--check");
const generate = process.argv.includes("--generate");
if ([refresh, check, generate].filter(Boolean).length !== 1)
  throw new Error("Specify exactly one of --refresh, --generate or --check");
const hash = text => createHash("sha256").update(text).digest("hex");
const manifest = { repository: "cosmictaserdev-creator/Jetpack_SF_Symbols", revision, files: {} };
const sources = new Map();
await mkdir(assets, { recursive: true });
const adapters = await readFile(path.join(desktop, "kmp/ui/src/jvmSharedMain/kotlin/app/vyral/night/ui/icons/SfIcons.kt"), "utf8");
const selected = new Set([
  ...[...Object.values(symbols), ...extraKmp].map(name => `monochrome/${name}.kt`),
  ...[...adapters.matchAll(/import com\.composables\.sfsymbols\.(monochrome|dualtone)\.(SF\w+)/g)]
    .map(([, variant, name]) => `${variant}/${name}.kt`),
]);
for (const relative of selected) {
  const symbol = path.basename(relative, ".kt");
  const local = path.join(sourceDir, relative);
  await mkdir(path.dirname(local), { recursive: true });
  let source;
  if (refresh) {
    const response = await fetch(`${upstream}/sfsymbols/src/commonMain/kotlin/composables/sfsymbols/${relative}`);
    if (!response.ok) throw new Error(`${symbol}: upstream HTTP ${response.status}`);
    source = await response.text();
    await writeFile(local, source);
  } else source = await readFile(local, "utf8");
  sources.set(relative, source);
  manifest.files[relative] = hash(source);
}
for (const [filename, symbol] of Object.entries(symbols)) {
  const source = sources.get(`monochrome/${symbol}.kt`);
  const width = source.match(/viewportWidth = ([\d.]+)f/)[1];
  const height = source.match(/viewportHeight = ([\d.]+)f/)[1];
  const paths = [...source.matchAll(/addSfPath\("([^"]+)"(?:,\s*fillAlpha = ([\d.]+)f)?\)/g)];
  if (!paths.length) throw new Error(`No supported paths in ${symbol}; inspect upstream changes`);
  const layers = paths.flatMap(([, d, alpha], index) => {
    const parts = separateParts.has(symbol) ? d.match(/M[^M]+/g) : [d];
    return parts.map((part, partIndex) => ({
      id: `${symbol}-path-${index}-part-${partIndex}`, d: part, alpha: alpha ?? "1.0",
    }));
  });
  const variable = ["radio", "podcast", "wifi", "volume_1", "volume_2", "volume_3"].includes(filename)
    ? ` data-variable="${layers.map((_, index) => index).reverse().join(" ")}"` : "";
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" data-source="${revision}" data-symbol="Monochrome.${symbol}" data-groups="${layers.length}"${variable}>` +
    layers.map((layer, i) => `<path id="${layer.id}" d="${layer.d}" fill="#fff" fill-opacity="${layer.alpha}" fill-rule="nonzero" data-group="${i}"/>`).join("") + "</svg>\n";
  const file = path.join(assets, `${filename}.svg`);
  if (!check) await writeFile(file, svg);
  else if (await readFile(file, "utf8") !== svg) throw new Error(`Stale generated asset: ${file}`);
}
const manifestPath = path.join(desktop, "symbols-manifest.json");
const json = JSON.stringify(manifest, null, 2) + "\n";
if (refresh) {
  await writeFile(manifestPath, json);
  const response = await fetch(`${upstream}/LICENSE`);
  if (!response.ok) throw new Error("Cannot obtain upstream license");
  const license = await response.text();
  await writeFile(path.join(desktop, "rust/app/assets/licenses/Jetpack-SF-Symbols.txt"), license);
  const kmpLicenses = path.join(desktop, "kmp/ui/src/jvmMain/resources/licenses");
  await mkdir(kmpLicenses, { recursive: true });
  await writeFile(path.join(kmpLicenses, "Jetpack-SF-Symbols.txt"), license);
} else if (await readFile(manifestPath, "utf8") !== json) throw new Error("Symbol source hashes differ");
console.log(`${check ? "Verified" : "Generated"} ${Object.keys(symbols).length} Rust vectors and ${sources.size} companion source files (${revision}).`);
