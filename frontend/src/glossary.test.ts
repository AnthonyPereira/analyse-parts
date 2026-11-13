import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { GLOSSARY } from './glossary'

/** Tous les fichiers .ts et .tsx du site, hors tests. */
function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return sourceFiles(path)
    return /\.tsx?$/.test(name) && !name.endsWith('.test.ts') ? [path] : []
  })
}

/**
 * Règle du site : chaque mot affiché avec une bulle d'aide (<Term id="…">, help="…", term: '…')
 * a son entrée dans le lexique. Un identifiant mal tapé donnerait une bulle vide.
 */
describe('lexique', () => {
  const sources = sourceFiles(join(import.meta.dirname, '.')).map(f => readFileSync(f, 'utf8'))
  const ids = new Set(sources.flatMap(s =>
    [...s.matchAll(/(?:<Term id=|help=|term: ?)["']([A-Z0-9_]+)["']/g)].map(m => m[1])))

  it('trouve des identifiants dans le code', () => {
    expect(ids.size).toBeGreaterThan(20)
  })

  it('a une entrée pour chaque identifiant utilisé', () => {
    expect([...ids].filter(id => !GLOSSARY[id])).toEqual([])
  })

  it('renvoie vers des entrées qui existent', () => {
    const broken = Object.entries(GLOSSARY).flatMap(([id, e]) =>
      (e.see ?? []).filter(s => !GLOSSARY[s]).map(s => `${id} → ${s}`))
    expect(broken).toEqual([])
  })
})
