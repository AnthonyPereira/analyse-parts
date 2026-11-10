// Étape 2 de la table yahoo-icb.csv (ADR 0020).
// Usage : node scripts/construire-yahoo-icb.js yahoo-icb-brut.csv backend/src/main/resources/yahoo-icb.csv
// Table secteur Yahoo -> ICB : pour chaque sous-secteur Yahoo, l'ICB le plus fréquent chez Euronext ;
// pour chaque secteur Yahoo (« * »), le super-secteur ICB le plus fréquent, avec son sous-secteur le plus courant.
const fs = require("fs");
const [raw, out] = process.argv.slice(2);
const rows = fs.readFileSync(raw, "utf8").trim().split("\n").map(l => l.split(";")).filter(r => r[2] && r[1] !== "ERR");
const most = (list, key) => { const c = new Map(); list.forEach(x => { const k = key(x); c.set(k, (c.get(k) || { n: 0, x }) ); c.get(k).n++; });
  return [...c.values()].sort((a, b) => b.n - a.n)[0]; };
const lines = ["# secteur Yahoo;sous-secteur Yahoo;code ICB;industrie;super-secteur;secteur;sous-secteur",
  "# Construite le 10 novembre 2026 à partir de " + rows.length + " sociétés d'Euronext (ADR 0020). « * » : tout le secteur Yahoo."];
const icb = r => r.slice(3, 8).join(";");
const bySector = new Map(), byIndustry = new Map();
rows.forEach(r => { (bySector.get(r[1]) || bySector.set(r[1], []).get(r[1])).push(r); (byIndustry.get(r[2]) || byIndustry.set(r[2], []).get(r[2])).push(r); });
let report = [];
for (const [sector, list] of [...bySector].sort()) {
  // super-secteur dominant, puis son ICB le plus courant
  const ss = most(list, r => r[3].slice(0, 4));
  const inSs = list.filter(r => r[3].startsWith(ss.x[3].slice(0, 4)));
  lines.push([sector, "*", icb(most(inSs, icb).x)].join(";"));
  report.push(`${sector} * : ${ss.n}/${list.length}`);
}
for (const [industry, list] of [...byIndustry].sort()) {
  const m = most(list, icb);
  // un sous-secteur Yahoo partagé entre plusieurs super-secteurs ICB : on garde le dominant s'il fait la majorité
  const ss = most(list, r => r[3].slice(0, 4));
  if (ss.n * 2 < list.length) { report.push(`IGNORÉ ${industry} (${ss.n}/${list.length})`); continue; }
  const pick = most(list.filter(r => r[3].startsWith(ss.x[3].slice(0, 4))), icb).x;
  // peu de sociétés : gardé seulement si l'industrie ICB concorde avec celle du secteur Yahoo
  const sectorIndustry = (lines.find(l => l.startsWith(list[0][1] + ";*;")) || "").split(";")[2] || "";
  if (list.length <= 2 && pick[3].slice(0, 2) !== sectorIndustry.slice(0, 2)) { report.push("IGNORÉ " + industry + " (peu de sociétés, " + pick[4] + ")"); continue; }
  lines.push([list[0][1], industry, icb(pick)].join(";"));
  report.push(`${industry} : ${ss.n}/${list.length}`);
}
// un financier inconnu n'est pas une banque : services financiers, et non le barème bancaire
const am = lines.find(l => l.startsWith("Financial Services;Asset Management;"));
const fsIdx = lines.findIndex(l => l.startsWith("Financial Services;*;"));
if (am && fsIdx >= 0) lines[fsIdx] = "Financial Services;*;" + am.split(";").slice(2).join(";");
fs.writeFileSync(out, lines.join("\n") + "\n");
console.log(report.join("\n"));
