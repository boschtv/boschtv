// Generates the illustrated profile avatars shipped in app/src/main/assets/avatars/ (run once; the output is committed).
//   npm i @dicebear/core@9 @dicebear/bottts@9 @dicebear/avataaars@9 @dicebear/fun-emoji@9 @dicebear/pixel-art@9
//   node tools/avatars/generate.mjs <path to repo root>
// Licenses of the designs: see THIRD_PARTY_NOTICES.md and licenses/dicebear-*.txt.
import { createAvatar } from '@dicebear/core';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.argv[2] ?? '.';
const styles = ['bottts', 'avataaars', 'fun-emoji', 'pixel-art'];
// Fixed seeds, so the same avatars come out every time.
const seeds = ['Floris', 'Sanne', 'Max', 'Luna', 'Noah', 'Mila', 'Finn', 'Zoe', 'Sem', 'Tess', 'Jesse', 'Lotte', 'Daan', 'Evi', 'Bram', 'Fenna'];
for (const style of styles) {
  const mod = await import('@dicebear/' + style);
  const dir = join(root, 'app/src/main/assets/avatars', style);
  mkdirSync(dir, { recursive: true });
  seeds.forEach((seed, i) => {
    const svg = createAvatar(mod, { seed, size: 128 }).toString()
      .replace(/<metadata[\s\S]*?<\/metadata>/, '');   // the license sits in THIRD_PARTY_NOTICES.md instead
    writeFileSync(join(dir, String(i + 1).padStart(2, '0') + '.svg'), svg);
  });
}
