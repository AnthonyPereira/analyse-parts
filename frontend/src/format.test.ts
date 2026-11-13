import { describe, expect, it } from 'vitest'
import { formatAmount, formatRatio, scoreTone, weightsFor } from './format'
import { compactAmount } from './charts/theme'
import { parseProfile } from './profile'

// Intl met une espace insécable (ou fine) entre les milliers et avant « % » : on les normalise.
const plain = (s: string) => s.replace(/[  ]/g, ' ')

describe('mise en forme', () => {
  it('affiche un ratio selon son unité', () => {
    expect(plain(formatRatio(0.153, 'PERCENT'))).toBe('15,3 %')
    expect(plain(formatRatio(1.8, 'MULTIPLE'))).toBe('1,8x')
  })

  it('affiche les montants en millions ou milliards', () => {
    expect(plain(formatAmount(80_807_000_000, 'EUR'))).toBe('80,8 Md €')
    expect(plain(formatAmount(-12_400_000, 'USD'))).toBe('-12 M $')
  })

  it('abrège les graduations des axes', () => {
    expect(compactAmount(80e9)).toBe('80 Md')
    expect(compactAmount(500e6)).toBe('500 M')
    expect(compactAmount(2_500)).toBe('3 k')
  })
})

describe('score', () => {
  it('colore la note par tranche', () => {
    expect(scoreTone(null)).toBe('none')
    expect(scoreTone(39)).toBe('bad')
    expect(scoreTone(40)).toBe('mid')
    expect(scoreTone(60)).toBe('good')
  })

  it('prend les poids des banques et des assurances pour leurs super-secteurs', () => {
    const profile = { weights: { ROE: 1 }, bankWeights: { ROE: 2 }, insurerWeights: { ROE: 3 } }
    expect(weightsFor(profile, '3010')).toEqual({ ROE: 2 })
    expect(weightsFor(profile, '3030')).toEqual({ ROE: 3 })
    expect(weightsFor(profile, '5510')).toEqual({ ROE: 1 })
    expect(weightsFor(undefined, null)).toEqual({})
  })

  it('lit le profil de l\'URL, Qualité par défaut', () => {
    expect(parseProfile('VALUE')).toBe('VALUE')
    expect(parseProfile('n\'importe quoi')).toBe('QUALITY')
    expect(parseProfile(null)).toBe('QUALITY')
  })
})
