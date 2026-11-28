// Static contract checks, not an installer or GUI runtime test.
// node desktop/tools/audit-desktop.mjs
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
const desktop = fileURLToPath(new URL("../", import.meta.url));
const read = relative => readFile(path.join(desktop, relative), "utf8");
const entries = xml => new Map([...xml.matchAll(/<string\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)].map(m => [m[1], m[2]]));
function coverage(english, persian, label) {
  const missing = [...english.keys()].filter(key => !persian.get(key)?.trim());
  assert.deepEqual(missing, [], `${label}: Persian keys missing`);
  for (const [key, text] of english) {
    // Ignore grammatical position, but never lose or invent printf arguments.
    const placeholders = s => [...s.matchAll(/%(?:\d+\$)?[sdf]/g)].map(m => m[0]).sort();
    assert.deepEqual(placeholders(persian.get(key)), placeholders(text), `${label}: ${key} placeholders`);
  }
  console.log(`${label}: ${english.size}/${english.size} Persian resource keys and printf placeholders present.`);
}
const kmpStrings = "kmp/app/src/main/resources/strings/";
coverage(
  entries(await read(kmpStrings + "en.xml") + await read(kmpStrings + "desktop-en.xml")),
  entries(await read(kmpStrings + "fa.xml") + await read(kmpStrings + "desktop-fa.xml")),
  "KMP application",
);
coverage(entries(await read(kmpStrings + "migration-en.xml")), entries(await read(kmpStrings + "migration-fa.xml")), "KMP migration");
const compose = "kmp/ui/src/commonMain/composeResources/";
coverage(
  entries(await read(compose + "values/strings.xml") + await read(compose + "values/vyral.xml")),
  entries(await read(compose + "values-fa/strings.xml") + await read(compose + "values-fa/vyral.xml")),
  "KMP Compose UI",
);
const rustFa = {
  ...JSON.parse(await read("rust/app/src/ui/fa.json")),
  ...JSON.parse(await read("rust/app/src/ui/desktop-fa.json")),
};
assert.equal(rustFa.Explore, "کاوش");
assert.equal(rustFa.Save, "ذخیره");
assert.equal(rustFa["App language"], "زبان برنامه");
console.log(`Rust: ${Object.keys(rustFa).length} combined Persian catalogue entries (not a full call-site coverage claim).`);
const kmpBuild = await read("kmp/app/build.gradle.kts");
const rustMain = await read("rust/app/src/main.rs");
const rustInstaller = await read("rust/app/windows/vyral.iss");
assert(kmpBuild.includes('packageName = "VYRAL-KMP"'));
assert(kmpBuild.includes('vendor = "night"'));
assert(kmpBuild.includes('bundleID = "app.vyral.night.kmp"'));
assert(rustMain.includes('.with_app_id("app.vyral.night.rust")'));
assert(rustMain.includes('ProjectDirs::from("app", "vyral", "VYRAL-Rust")'));
assert(rustInstaller.includes("DefaultDirName={autopf}\\VYRAL\\Rust"));
assert(rustInstaller.includes("Subkey: \"Software\\Classes\\vyral-rust\""));
assert((await read("kmp/app/src/main/kotlin/app/vyral/desktop/DesktopPersistence.kt")).includes('node("app.vyral.desktop")'));
assert(!(await read("kmp/ui/build.gradle.kts")).includes('api("com.github.lylicht.SFStudio'));
assert(!(await read("kmp/ui/build.gradle.kts")).includes('kotlin.srcDir(rootProject.file("sharedUi/'));
assert((await read("kmp/app/src/main/kotlin/app/vyral/desktop/Main.kt")).includes('title = "VYRAL"'));
console.log("Static branding, distinct install/app/data identities and desktop UI isolation: PASS.");
console.log("Actual installation/upgrades and Linux builds are NOT verified by this script; macOS is not a supported target.");
