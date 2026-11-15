// Mirror of app/src/main/java/com/scamguard/judge/PiiRedactor.kt (the phone redacts first; this is the second pass).
// Both are checked against shared/redactor-vectors.json, so they cannot drift apart silently.
// The proxy also runs this on every transcript it receives (defence in depth).

const DIGIT_WORDS = new Set(['zero', 'oh', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine']);
// Speech recognition often writes a spoken digit as its sound-alike. Counted only next to real digit words.
const SOUND_ALIKES = new Set(['for', 'to', 'too', 'ate', 'won']);
const MULTIPLIERS = { double: 2, triple: 3 };
const ID_RE = /[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+/g;
const DIGIT_RUN_RE = /\d(?:[ -]?\d){3,}/g;

const label = (n) => `[${n}-DIGIT NUMBER]`;
const isCodeLength = (n) => n >= 4 && n <= 8;
const bare = (tok) => tok.toLowerCase().replace(/[.,?!;:]+$/, '');

export function redact(text) {
  let codeSpoken = false;
  let out = text.replace(ID_RE, '[ID]');

  out = out.replace(DIGIT_RUN_RE, (m) => {
    const n = m.replace(/\D/g, '').length;
    if (isCodeLength(n)) codeSpoken = true;
    return label(n);
  });

  // Spoken digits: the speech engine writes "four eight two nine", never "4829".
  const tokens = out.split(/\s+/).filter((t) => t.length > 0);
  const result = [];
  let i = 0;
  while (i < tokens.length) {
    let j = i;
    let digits = 0;
    let real = 0;
    while (j < tokens.length) {
      const w = bare(tokens[j]);
      if (DIGIT_WORDS.has(w)) { digits += 1; real += 1; j += 1; continue; }
      if (SOUND_ALIKES.has(w)) { digits += 1; j += 1; continue; }
      if (MULTIPLIERS[w] && j + 1 < tokens.length && DIGIT_WORDS.has(bare(tokens[j + 1]))) {
        digits += MULTIPLIERS[w]; real += 1; j += 2; continue;
      }
      break;
    }
    if (digits >= 4 && real >= 3) {
      if (isCodeLength(digits)) codeSpoken = true;
      result.push(label(digits));
      i = j;
    } else {
      result.push(tokens[i]);
      i += 1;
    }
  }
  return { text: result.join(' '), codeSpoken };
}
