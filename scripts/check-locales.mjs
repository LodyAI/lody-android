#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';

const androidRoot = process.env.LODY_ANDROID_ROOT
  ? path.resolve(process.env.LODY_ANDROID_ROOT)
  : path.resolve(import.meta.dirname, '..');
const mainDir = path.join(androidRoot, 'app/src/main');
const resDir = path.join(mainDir, 'res');

const LOCALE_DIRS = [
  { dir: 'values', bcp47: 'en', pluralCategories: ['one', 'other'] },
  { dir: 'values-b+zh+Hans', bcp47: 'zh-Hans', pluralCategories: ['other'] },
  { dir: 'values-b+zh+Hant', bcp47: 'zh-Hant', pluralCategories: ['other'] },
  { dir: 'values-ja', bcp47: 'ja', pluralCategories: ['other'] },
  { dir: 'values-ko', bcp47: 'ko', pluralCategories: ['other'] },
  { dir: 'values-es', bcp47: 'es', pluralCategories: ['one', 'other'] },
  { dir: 'values-fr', bcp47: 'fr', pluralCategories: ['one', 'other'] },
  { dir: 'values-ru', bcp47: 'ru', pluralCategories: ['one', 'few', 'many', 'other'] },
];

const RESOURCE_FILES = ['strings.xml', 'auth.xml'];

function signature(value) {
  let next = 0;
  return [...value.matchAll(/%(?:(\d+)\$)?[+#0 ,(-]*\d*(?:\.\d+)?([a-zA-Z%])/g)]
    .filter((match) => match[2] !== '%')
    .map((match) => `${match[1] ?? ++next}:${match[2]}`)
    .sort()
    .join(',');
}

function parseResourcesXml(xml) {
  const withoutComments = xml.replace(/<!--[\s\S]*?-->/g, '');
  const strings = new Map();
  const plurals = new Map();

  for (const match of withoutComments.matchAll(
    /<string\b([^>]*)>([\s\S]*?)<\/string>/g,
  )) {
    const attrs = match[1];
    const nameMatch = attrs.match(/\bname="([^"]+)"/);
    if (!nameMatch) continue;
    if (/\btranslatable="false"/.test(attrs)) continue;
    strings.set(nameMatch[1], match[2]);
  }

  for (const match of withoutComments.matchAll(
    /<plurals\b([^>]*)>([\s\S]*?)<\/plurals>/g,
  )) {
    const nameMatch = match[1].match(/\bname="([^"]+)"/);
    if (!nameMatch) continue;
    const items = new Map();
    for (const itemMatch of match[2].matchAll(
      /<item\b[^>]*\bquantity="([^"]+)"[^>]*>([\s\S]*?)<\/item>/g,
    )) {
      items.set(itemMatch[1], itemMatch[2]);
    }
    plurals.set(nameMatch[1], items);
  }

  return { strings, plurals };
}

const problems = [];

// 1. Validate AndroidManifest.xml and locales_config.xml
const manifestPath = path.join(mainDir, 'AndroidManifest.xml');
if (!fs.existsSync(manifestPath)) {
  problems.push(`Missing ${manifestPath}`);
} else {
  const manifest = fs.readFileSync(manifestPath, 'utf8');
  if (!/android:localeConfig="@xml\/locales_config"/.test(manifest)) {
    problems.push('AndroidManifest.xml: missing android:localeConfig="@xml/locales_config"');
  }
}

const localesConfigPath = path.join(resDir, 'xml/locales_config.xml');
if (!fs.existsSync(localesConfigPath)) {
  problems.push(`Missing ${localesConfigPath}`);
} else {
  const configXml = fs.readFileSync(localesConfigPath, 'utf8');
  const declared = new Set(
    [...configXml.matchAll(/<locale\b[^>]*\bandroid:name="([^"]+)"/g)].map((m) => m[1]),
  );
  for (const { bcp47 } of LOCALE_DIRS) {
    if (!declared.has(bcp47)) {
      problems.push(`res/xml/locales_config.xml: missing locale ${bcp47}`);
    }
  }
}

// 2. Validate strings.xml and auth.xml across all 8 locales
for (const fileName of RESOURCE_FILES) {
  const basePath = path.join(resDir, 'values', fileName);
  if (!fs.existsSync(basePath)) {
    problems.push(`Missing base resource file values/${fileName}`);
    continue;
  }
  const base = parseResourcesXml(fs.readFileSync(basePath, 'utf8'));

  for (const { dir, bcp47, pluralCategories } of LOCALE_DIRS) {
    const targetPath = path.join(resDir, dir, fileName);
    if (!fs.existsSync(targetPath)) {
      problems.push(`Missing ${dir}/${fileName} (${bcp47})`);
      continue;
    }
    const target = parseResourcesXml(fs.readFileSync(targetPath, 'utf8'));

    // String key parity and format specifier match
    for (const [key, baseValue] of base.strings) {
      if (!target.strings.has(key)) {
        problems.push(`${dir}/${fileName}: missing string "${key}"`);
        continue;
      }
      const targetValue = target.strings.get(key);
      if (targetValue.trim() === '' && baseValue.trim() !== '') {
        problems.push(`${dir}/${fileName}: empty string "${key}"`);
      } else if (signature(targetValue) !== signature(baseValue)) {
        problems.push(
          `${dir}/${fileName}: string "${key}" has incompatible format arguments (expected "${signature(baseValue)}", got "${signature(targetValue)}")`,
        );
      }
    }
    for (const key of target.strings.keys()) {
      if (!base.strings.has(key)) {
        problems.push(`${dir}/${fileName}: extra string "${key}" not present in values/${fileName}`);
      }
    }

    // Plural key parity, required CLDR categories, and format specifier match
    for (const [key, baseItems] of base.plurals) {
      if (!target.plurals.has(key)) {
        problems.push(`${dir}/${fileName}: missing plurals "${key}"`);
        continue;
      }
      const targetItems = target.plurals.get(key);
      const baseRef = baseItems.get('other') ?? [...baseItems.values()][0] ?? '';
      const expectedSig = signature(baseRef);

      const missingCategories = pluralCategories.filter((cat) => !targetItems.has(cat));
      if (missingCategories.length) {
        problems.push(
          `${dir}/${fileName}: plurals "${key}" (${bcp47}) is missing categories: ${missingCategories.join(', ')}`,
        );
      }
      for (const [quantity, value] of targetItems) {
        if (value.trim() === '') {
          problems.push(`${dir}/${fileName}: empty plurals "${key}" (${quantity})`);
        } else if (signature(value) !== expectedSig) {
          problems.push(
            `${dir}/${fileName}: plurals "${key}" (${quantity}) has incompatible format arguments`,
          );
        }
      }
    }
    for (const key of target.plurals.keys()) {
      if (!base.plurals.has(key)) {
        problems.push(`${dir}/${fileName}: extra plurals "${key}" not present in values/${fileName}`);
      }
    }
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
