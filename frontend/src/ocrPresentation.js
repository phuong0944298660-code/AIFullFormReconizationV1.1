const LOC_TOKEN = /<LOC_\d+>/g
const REPLACEMENT_CHAR = /\uFFFD/g

export function lineText(line) {
  return (line?.spans || [])
    .map((span) => span.text || '')
    .join('')
    .replace(LOC_TOKEN, '')
    .replace(/\s+/g, ' ')
    .trim()
}

export function visibleOcrLines(lines = []) {
  return lines
    .filter((line) => {
      const text = lineText(line)
      if (!text) return false
      if (hasHeavyLocatorNoise(line)) return false
      if (hasHeavyReplacementNoise(text)) return false
      return true
    })
    .map((line) => ({
      ...line,
      spans: cleanSpans(line.spans || [])
    }))
}

export function visibleOcrPages(pages = []) {
  return pages.map((page) => {
    const lines = page.lines || []
    const visibleLines = visibleOcrLines(lines)
    return {
      ...page,
      visibleLines,
      filteredLineCount: Math.max(0, lines.length - visibleLines.length),
      highlightedLineCount: visibleLines.filter((line) => line.hasUserInput).length
    }
  })
}

export function structuredJsonPreview(response) {
  const data = response?.structuredData ?? {}
  return JSON.stringify(data, null, 2)
}

export function responseJsonPreview(response) {
  if (!response) return ''
  const pages = Array.isArray(response.pages) ? response.pages : []
  const compact = {
    ...response,
    pages: pages.map((page) => ({
      ...page,
      sourceImageDataUrl: summarizeDataUrl(page.sourceImageDataUrl)
    }))
  }
  return JSON.stringify(compact, null, 2)
}

export function pageStructuredFieldCount(response, pageNumber) {
  const evidenceRows = pageStructuredFields(response, pageNumber)
  if (evidenceRows.length) return evidenceRows.length
  const pageData = response?.structuredData?.[`page_${pageNumber}`]
  return countLeafFields(pageData)
}

export function structuredFieldRows(response, pageNumber) {
  const evidenceRows = pageStructuredFields(response, pageNumber)
  if (evidenceRows.length) return evidenceRows.map(toEvidenceFieldRow)
  const pageData = response?.structuredData?.[`page_${pageNumber}`]
  const confidenceData = confidencePageData(response, pageNumber)
  const rows = []
  collectFieldRows(pageData, [], rows, confidenceData)
  return rows
}

export function fieldIssueRegions(response, pageNumber) {
  return structuredFieldRows(response, pageNumber)
    .flatMap((row) => row.charSegments || [])
    .filter((segment) => segment.reviewFlag && Array.isArray(segment.bbox) && segment.bbox.length === 4)
    .map((segment) => ({
      id: `${segment.fieldPath}:${segment.index}`,
      bbox: segment.bbox,
      status: segment.status,
      text: segment.text,
      confidence: segment.confidence
    }))
}

export function pageFieldConclusion(rows = []) {
  const total = rows.length
  if (!total) return '本页暂未识别到字段。'
  const high = rows.filter((row) => row.confidence >= 85).length
  const medium = rows.filter((row) => row.confidence >= 70 && row.confidence < 85).length
  const low = rows.filter((row) => row.confidence < 70).length
  const advice = low > 0
    ? '建议优先复核低置信字段。'
    : medium > 0
      ? '建议抽查中等置信字段。'
      : '整体可信度较高，可按需抽查。'
  return `本页共识别 ${total} 个字段，其中 ${high} 个置信率在 85% 以上，${medium} 个在 70%-84% 之间，${low} 个低于 70%，${advice}`
}

export function documentFieldConclusion(response) {
  const rows = documentFieldRows(response)
  const total = rows.length
  if (!total) return '整份文件暂未识别到字段。'
  const high = rows.filter((row) => row.confidence >= 85).length
  const medium = rows.filter((row) => row.confidence >= 70 && row.confidence < 85).length
  const low = rows.filter((row) => row.confidence < 70).length
  const advice = low > 0
    ? '建议优先复核低置信字段。'
    : medium > 0
      ? '建议抽查中等置信字段。'
      : '整体可信度较高，可按需抽查。'
  return `整份文件共识别 ${total} 个字段，其中 ${high} 个置信率在 85% 以上，${medium} 个在 70%-84% 之间，${low} 个低于 70%；${advice}`
}

export function cropPlaceholderText() {
  return '无区域快照'
}

function documentFieldRows(response) {
  const pages = response?.pages || []
  if (pages.length) {
    return pages.flatMap((page) => structuredFieldRows(response, page.page))
  }
  const structuredData = response?.structuredData || {}
  return Object.keys(structuredData)
    .map((key) => key.match(/^page_(\d+)$/)?.[1])
    .filter(Boolean)
    .map(Number)
    .sort((left, right) => left - right)
    .flatMap((pageNumber) => structuredFieldRows(response, pageNumber))
}

function cleanSpans(spans) {
  return spans
    .map((span) => ({
      ...span,
      text: String(span.text || '')
        .replace(LOC_TOKEN, '')
        .replace(/\s+/g, ' ')
    }))
    .filter((span) => span.text.trim())
}

function hasHeavyLocatorNoise(line) {
  const raw = (line?.spans || []).map((span) => span.text || '').join('')
  return (raw.match(LOC_TOKEN) || []).length >= 2
}

function hasHeavyReplacementNoise(text) {
  const count = (text.match(REPLACEMENT_CHAR) || []).length
  return count >= 2 || count / Math.max(1, text.length) > 0.04
}

function summarizeDataUrl(value) {
  if (!value) return ''
  if (String(value).startsWith('data:')) return `${String(value).slice(0, 56)}...`
  return value
}

function countLeafFields(value) {
  if (value === null || value === undefined) {
    return value === null ? 1 : 0
  }
  if (Array.isArray(value)) {
    return value.reduce((total, item) => total + countLeafFields(item), 0)
  }
  if (typeof value === 'object') {
    return Object.values(value).reduce((total, item) => total + countLeafFields(item), 0)
  }
  return 1
}

function collectFieldRows(value, path, rows, confidenceData) {
  if (value === undefined) return
  if (isMetadataObject(value)) {
    const valueNode = Object.hasOwn(value, 'value') ? value.value : value.text
    rows.push(toFieldRow(path, valueNode, value.confidence ?? lookupConfidence(confidenceData, path)))
    return
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => {
      collectFieldRows(item, [...path, String(index + 1)], rows, confidenceData)
    })
    return
  }
  if (value !== null && typeof value === 'object') {
    Object.entries(value).forEach(([key, child]) => {
      if (key.startsWith('_')) return
      collectFieldRows(child, [...path, key], rows, confidenceData)
    })
    return
  }
  rows.push(toFieldRow(path, value, lookupConfidence(confidenceData, path)))
}

function isMetadataObject(value) {
  return value
    && typeof value === 'object'
    && !Array.isArray(value)
    && (Object.hasOwn(value, 'value') || Object.hasOwn(value, 'text'))
    && (Object.hasOwn(value, 'confidence') || Object.keys(value).length <= 3)
}

function toFieldRow(path, rawValue, explicitConfidence) {
  const displayValue = displayFieldValue(path, rawValue)
  return {
    id: path.join('.'),
    section: humanizePath(path.slice(0, -1)),
    fieldName: humanizeKey(path.at(-1) || 'field'),
    path: path.join('.'),
    rawValue,
    displayValue,
    confidence: normalizeConfidence(explicitConfidence, path, rawValue),
    bbox: [],
    snapshotDataUrl: '',
    ocrText: '',
    ocrStatus: 'not_run',
    charSegments: charSegmentsFromText(displayValue, [])
  }
}

function toEvidenceFieldRow(field) {
  const path = String(field.path || 'field')
  const pathParts = path.split('.').filter(Boolean)
  const displayValue = field.displayValue ?? displayFieldValue(pathParts, field.value)
  return {
    id: `${field.page || ''}:${path}`,
    section: humanizePath(pathParts.slice(0, -1)),
    fieldName: field.label || humanizeKey(pathParts.at(-1) || 'field'),
    path,
    rawValue: field.value,
    displayValue,
    confidence: normalizeConfidence(field.confidence, pathParts, field.value),
    bbox: field.bbox || [],
    snapshotDataUrl: field.snapshotDataUrl || '',
    ocrText: '',
    ocrStatus: 'not_run',
    ocrConfidence: 0,
    charSegments: charSegmentsFromText(displayValue, field.characters || [], path)
  }
}

function displayFieldValue(path, value) {
  if (value === null || value === undefined || value === '') return '未填写'
  if (typeof value === 'boolean') return value ? '已勾选' : '未勾选'
  if (isSignaturePath(path) && String(value).trim().toLowerCase() === 'present') {
    return '已签名，未识别出签名文字'
  }
  if (isSignaturePath(path) && String(value).trim().toLowerCase() === 'illegible_signature') {
    return '签名文字无法辨认'
  }
  return String(value)
}

function normalizeConfidence(value, path, rawValue) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.max(0, Math.min(100, value <= 1 ? Math.round(value * 100) : Math.round(value)))
  }
  if (rawValue === null || rawValue === undefined || rawValue === '') return 70
  if (isSignaturePath(path) && String(rawValue).trim().toLowerCase() === 'present') return 55
  return 82
}

function humanizePath(path) {
  return path.map(humanizeKey).filter(Boolean).join(' / ')
}

function humanizeKey(key) {
  return String(key || '')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function isSignaturePath(path) {
  return path.some((part) => /signature|签名|簽名/i.test(String(part)))
}

function confidencePageData(response, pageNumber) {
  const data = response?.structuredData || {}
  const pageKey = `page_${pageNumber}`
  return data._confidence?.[pageKey]
    || data._field_confidence?.[pageKey]
    || data.field_confidence?.[pageKey]
    || data[pageKey]?._confidence
    || {}
}

function pageStructuredFields(response, pageNumber) {
  const page = (response?.pages || []).find((item) => item.page === pageNumber)
  return Array.isArray(page?.structuredFields) ? page.structuredFields : []
}

function charSegmentsFromText(displayValue, characters = [], fieldPath = '') {
  if (characters.length) {
    return characters.map((character, fallbackIndex) => {
      const status = character.status || 'ok'
      return {
        index: character.index ?? fallbackIndex,
        text: character.text ?? '',
        confidence: normalizeConfidence(character.confidence, [], ''),
        status,
        bbox: character.bbox || [],
        ocrText: '',
        fieldPath,
        reviewFlag: status !== 'ok'
      }
    })
  }
  return String(displayValue || '').split('').map((text, index) => ({
    index,
    text,
    confidence: 100,
    status: 'ok',
    bbox: [],
    ocrText: '',
    fieldPath,
    reviewFlag: false
  }))
}

function lookupConfidence(confidenceData, path) {
  let current = confidenceData
  for (const part of path) {
    if (current === null || current === undefined) return undefined
    current = current[part]
  }
  return current
}
