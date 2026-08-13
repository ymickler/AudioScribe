#!/usr/bin/env node
// Erzeugt PROGRESS.md aus SPEC_TRANSPARENCY.md.
//
// Der Fortschritt wird bewusst NICHT von Hand gepflegt: eine Prozentzahl, die jemand
// (Mensch oder Agent) selbst schätzt, driftet unweigerlich von der Realität ab. Hier ist
// sie per Konstruktion exakt = abgehakte Kästchen / alle Kästchen der Spezifikation.
//
// Aufruf: node tools/progress.mjs        (schreibt PROGRESS.md)
//         node tools/progress.mjs --check (schreibt nichts, gibt nur die Zusammenfassung aus)

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const specPath = join(repoRoot, 'SPEC_TRANSPARENCY.md');
const progressPath = join(repoRoot, 'PROGRESS.md');

// Frei formulierbare Notiz zum aktuellen Arbeitsstand - als Argument oder via Umgebung,
// damit sie beim Abhaken einer Aufgabe im selben Aufruf mitgesetzt werden kann.
const noteArg = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const currentNote = noteArg.length > 0 ? noteArg.join(' ') : process.env.PROGRESS_NOTE || '';

const spec = readFileSync(specPath, 'utf8');
const lines = spec.split('\n');

const sections = [];
let current = null;

for (const line of lines) {
  const heading = /^## (\d+)\.\s+(.*)$/.exec(line);
  if (heading) {
    current = { number: Number(heading[1]), title: heading[2].trim(), total: 0, done: 0 };
    sections.push(current);
    continue;
  }
  const box = /^- \[( |x)\]/.exec(line);
  if (box && current) {
    current.total += 1;
    if (box[1] === 'x') current.done += 1;
  }
}

const tracked = sections.filter((s) => s.total > 0);
const total = tracked.reduce((sum, s) => sum + s.total, 0);
const done = tracked.reduce((sum, s) => sum + s.done, 0);
const percent = total === 0 ? 0 : Math.round((done / total) * 100);

function bar(fraction, width = 20) {
  const filled = Math.round(fraction * width);
  return '█'.repeat(filled) + '░'.repeat(width - filled);
}

function statusIcon(section) {
  if (section.done === 0) return '⬜';
  if (section.done === section.total) return '✅';
  return '🟨';
}

const rows = tracked
  .map((s) => {
    const pct = Math.round((s.done / s.total) * 100);
    return `| ${statusIcon(s)} | ${s.number}. ${s.title} | ${s.done}/${s.total} | ${pct} % |`;
  })
  .join('\n');

const output = `# Umsetzungsstand

<!-- Diese Datei wird von tools/progress.mjs erzeugt. Nicht von Hand bearbeiten -
     stattdessen die Kästchen in SPEC_TRANSPARENCY.md abhaken und das Skript laufen lassen. -->

## Gesamt: ${percent} %

\`${bar(total === 0 ? 0 : done / total)}\`  **${done} von ${total}** Teilaufgaben erledigt

${currentNote ? `**Aktuell in Arbeit:** ${currentNote}\n` : ''}
## Pakete

| | Punkt | Erledigt | Anteil |
|---|---|---|---|
${rows}

---

Alle Teilaufgaben und ihre Akzeptanzkriterien stehen in [SPEC_TRANSPARENCY.md](SPEC_TRANSPARENCY.md).
Jedes Kästchen zählt gleich viel, der Gesamtwert ist exakt \`erledigte Kästchen / alle Kästchen\`.
`;

if (process.argv.includes('--check')) {
  console.log(`${done}/${total} (${percent} %)`);
} else {
  writeFileSync(progressPath, output);
  console.log(`PROGRESS.md aktualisiert: ${done}/${total} (${percent} %)`);
}
