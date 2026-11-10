// Étape 1 de la table yahoo-icb.csv (ADR 0020). Entrée : isin;code ICB;industrie;super-secteur;secteur;sous-secteur
// (psql -At -F';' -c "select isin, icb_code, icb_industry, icb_supersector, icb_sector, icb_subsector from company where mic in ('XPAR','XAMS','XBRU','XLIS','XMSM','XOSL','MTAA') and icb_code is not null")
// Usage : node scripts/secteurs-yahoo.js icb.csv yahoo-icb-brut.csv (reprend où il s'est arrêté)
const fs = require("fs");
const [icbFile, outFile] = process.argv.slice(2);
const rows = fs.readFileSync(icbFile, "utf8").trim().split("\n").map(l => l.split(";"));
const done = fs.existsSync(outFile) ? new Set(fs.readFileSync(outFile, "utf8").split("\n").map(l => l.split(";")[0])) : new Set();
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  for (const [isin, ...icb] of rows) {
    if (done.has(isin)) continue;
    let line = isin + ";;;";
    try {
      const r = await fetch(`https://query1.finance.yahoo.com/v1/finance/search?q=${isin}&quotesCount=5&newsCount=0`,
        { headers: { "User-Agent": "Mozilla/5.0 (compatible; analyse-parts/0.1)" } });
      if (r.status === 429) { await sleep(60000); continue; }
      const j = await r.json();
      const q = (j.quotes || []).find(q => q.quoteType === "EQUITY" && q.industry);
      if (q) line = [isin, q.sector, q.industry, icb.join(";")].join(";");
    } catch (e) { line = isin + ";ERR;;"; }
    fs.appendFileSync(outFile, line + "\n");
    await sleep(700);
  }
})();
