import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const SCRIPT = path.join(import.meta.dirname, 'check-locales.mjs');

const LOCALES = [
  { dir: 'values', bcp47: 'en', pluralCategories: ['one', 'other'] },
  { dir: 'values-b+zh+Hans', bcp47: 'zh-Hans', pluralCategories: ['other'] },
  { dir: 'values-b+zh+Hant', bcp47: 'zh-Hant', pluralCategories: ['other'] },
  { dir: 'values-ja', bcp47: 'ja', pluralCategories: ['other'] },
  { dir: 'values-ko', bcp47: 'ko', pluralCategories: ['other'] },
  { dir: 'values-es', bcp47: 'es', pluralCategories: ['one', 'other'] },
  { dir: 'values-fr', bcp47: 'fr', pluralCategories: ['one', 'other'] },
  { dir: 'values-ru', bcp47: 'ru', pluralCategories: ['one', 'few', 'many', 'other'] },
];

function renderXml({ strings = {}, plurals = {} }) {
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>'];
  for (const [name, value] of Object.entries(strings)) {
    lines.push(`    <string name="${name}">${value}</string>`);
  }
  for (const [name, items] of Object.entries(plurals)) {
    lines.push(`    <plurals name="${name}">`);
    for (const [qty, value] of Object.entries(items)) {
      lines.push(`        <item quantity="${qty}">${value}</item>`);
    }
    lines.push('    </plurals>');
  }
  lines.push('</resources>');
  return lines.join('\n') + '\n';
}

test('Android locale check validates all 8 locales, format specifiers, Russian plurals, and locales_config', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'lody-android-locales-'));
  const main = path.join(root, 'app/src/main');
  const res = path.join(main, 'res');
  const run = () =>
    spawnSync(process.execPath, [SCRIPT], {
      env: { ...process.env, LODY_ANDROID_ROOT: root },
      encoding: 'utf8',
    });

  try {
    await mkdir(path.join(res, 'xml'), { recursive: true });
    await writeFile(
      path.join(main, 'AndroidManifest.xml'),
      '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:localeConfig="@xml/locales_config" /></manifest>',
    );
    await writeFile(
      path.join(res, 'xml/locales_config.xml'),
      `<?xml version="1.0" encoding="utf-8"?>\n<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n${LOCALES.map((l) => `    <locale android:name="${l.bcp47}" />`).join('\n')}\n</locale-config>\n`,
    );

    const writeAll = async (overrides = {}) => {
      for (const locale of LOCALES) {
        const dirPath = path.join(res, locale.dir);
        await mkdir(dirPath, { recursive: true });
        const pluralItems = Object.fromEntries(
          locale.pluralCategories.map((cat) => [cat, '%d items']),
        );
        await writeFile(
          path.join(dirPath, 'strings.xml'),
          renderXml(
            overrides[locale.dir]?.stringsXml ?? {
              strings: {
                summary:
                  locale.dir === 'values-ja' ? '%2$s / %1$s (100%%)' : '%1$s / %2$s (100%%)',
              },
              plurals: { files: pluralItems },
            },
          ),
        );
        await writeFile(
          path.join(dirPath, 'auth.xml'),
          renderXml(
            overrides[locale.dir]?.authXml ?? {
              strings: { login_legal: 'Agree to %1$s and %2$s' },
            },
          ),
        );
      }
    };

    // Valid all-8-locale tree passes.
    await writeAll();
    let result = run();
    assert.equal(result.status, 0, result.stderr);

    // Reject missing key in ko/auth.xml.
    await writeAll({ 'values-ko': { authXml: { strings: {} } } });
    result = run();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /values-ko\/auth\.xml: missing string "login_legal"/);

    // Reject incompatible format specifier in fr/strings.xml.
    await writeAll({
      'values-fr': {
        stringsXml: {
          strings: { summary: '%1$d / %2$s' },
          plurals: { files: { one: '%d fichier', other: '%d fichiers' } },
        },
      },
    });
    result = run();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /values-fr\/strings\.xml: string "summary" has incompatible format arguments/);

    // Reject Russian plurals missing few/many.
    await writeAll({
      'values-ru': {
        stringsXml: {
          strings: { summary: '%1$s / %2$s (100%%)' },
          plurals: { files: { one: '%d файл', other: '%d файлов' } },
        },
      },
    });
    result = run();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /values-ru\/strings\.xml: plurals "files" \(ru\) is missing categories: few, many/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
