import test from 'node:test'
import assert from 'node:assert/strict'
import {
  visibleOcrPages,
  visibleOcrLines,
  responseJsonPreview,
  structuredJsonPreview,
  pageStructuredFieldCount,
  structuredFieldRows,
  pageFieldConclusion,
  documentFieldConclusion,
  cropPlaceholderText
} from './ocrPresentation.js'
import { pageProgressItems, recognitionProgressState } from './progressState.js'

test('visibleOcrLines removes locator tokens and replacement noise', () => {
  const lines = visibleOcrLines([
    {
      lineNumber: 1,
      hasUserInput: false,
      spans: [{ text: 'Surname in English CHAN', userInput: false }]
    },
    {
      lineNumber: 2,
      hasUserInput: false,
      spans: [{ text: 'noise<LOC_252><LOC_638><LOC_137>', userInput: false }]
    },
    {
      lineNumber: 3,
      hasUserInput: false,
      spans: [{ text: 'bad replacement \uFFFD\uFFFD text', userInput: false }]
    }
  ])

  assert.equal(lines.length, 1)
  assert.equal(lines[0].spans[0].text, 'Surname in English CHAN')
})

test('visibleOcrPages summarizes full-document OCR text by page without fields', () => {
  const pages = visibleOcrPages([
    {
      page: 1,
      lines: [
        {
          lineNumber: 1,
          hasUserInput: true,
          spans: [{ text: 'Surname in English CHAN', userInput: false }]
        },
        {
          lineNumber: 2,
          hasUserInput: false,
          spans: [{ text: 'noise<LOC_252><LOC_638><LOC_137>', userInput: false }]
        }
      ]
    },
    {
      page: 2,
      lines: [
        {
          lineNumber: 1,
          hasUserInput: false,
          spans: [{ text: 'Declaration signed', userInput: false }]
        }
      ]
    }
  ])

  assert.equal(pages.length, 2)
  assert.equal(pages[0].visibleLines.length, 1)
  assert.equal(pages[0].filteredLineCount, 1)
  assert.equal(pages[1].visibleLines[0].spans[0].text, 'Declaration signed')
})

test('structuredJsonPreview renders only LLM structured data', () => {
  const preview = structuredJsonPreview({
    filename: 'sample.pdf',
    pages: [{ sourceImageDataUrl: 'data:image/png;base64,long-image' }],
    structuredData: {
      source_file: 'sample.pdf',
      page_1: {
        surname_en: 'CHAN',
        alias: null
      }
    }
  })

  assert.equal(preview.includes('sourceImageDataUrl'), false)
  assert.equal(preview.includes('"surname_en": "CHAN"'), true)
  assert.equal(preview.includes('"alias": null'), true)
})

test('responseJsonPreview tolerates responses without a pages array', () => {
  const preview = responseJsonPreview({
    filename: 'partial.pdf',
    pageCount: 0,
    structuredData: {
      page_1: {
        name: 'CHAN'
      }
    }
  })

  assert.match(preview, /"filename": "partial.pdf"/)
  assert.match(preview, /"pages": \[\]/)
})

test('responseJsonPreview compacts nested image data urls before rendering JSON tab', () => {
  const longPageImage = `data:image/png;base64,${'a'.repeat(8000)}`
  const longCropImage = `data:image/jpeg;base64,${'b'.repeat(8000)}`
  const preview = responseJsonPreview({
    filename: 'heavy.pdf',
    pages: [
      {
        page: 1,
        sourceImageDataUrl: longPageImage,
        structuredFields: [
          {
            path: 'name',
            value: 'CHAN',
            snapshotDataUrl: longCropImage
          }
        ]
      }
    ]
  })

  assert.equal(preview.includes('a'.repeat(1000)), false)
  assert.equal(preview.includes('b'.repeat(1000)), false)
  assert.match(preview, /"sourceImageDataUrl": "data:image\/png;base64,/)
  assert.match(preview, /"snapshotDataUrl": "data:image\/jpeg;base64,/)
  assert.ok(preview.length < 2000)
})

test('pageStructuredFieldCount counts leaf fields including null values', () => {
  const count = pageStructuredFieldCount({
    structuredData: {
      page_1: {
        part_1: {
          selected_type: '(a)',
          visa_type: null
        },
        part_2: {
          surname_en: 'CHAN'
        }
      },
      page_2: {
        address: 'Hong Kong'
      }
    }
  }, 1)

  assert.equal(count, 3)
})

test('no_applicant_input marker is not counted or rendered as a visible field', () => {
  const response = {
    structuredData: {
      page_4: {
        no_applicant_input: true
      }
    }
  }

  assert.equal(pageStructuredFieldCount(response, 4), 0)
  assert.deepEqual(structuredFieldRows(response, 4), [])
})

test('structuredFieldRows flattens page JSON into field extraction rows', () => {
  const rows = structuredFieldRows({
    structuredData: {
      page_1: {
        personal_particulars: {
          surname_en: 'HIDAYATI',
          alias: null,
          female_checked: true,
          male_checked: false,
          signature_of_applicant: 'present'
        }
      },
      _confidence: {
        page_1: {
          personal_particulars: {
            surname_en: 91
          }
        }
      }
    }
  }, 1)

  assert.equal(rows.length, 5)
  assert.equal(rows[0].fieldName, 'surname en')
  assert.equal(rows[0].displayValue, 'HIDAYATI')
  assert.equal(rows[0].confidence, 91)
  assert.equal(rows[1].displayValue, '未填写')
  assert.equal(rows[2].displayValue, '已勾选')
  assert.equal(rows[3].displayValue, '未勾选')
  assert.equal(rows[4].displayValue, '已签名，未识别出签名文字')
  assert.equal(rows[4].rawValue, 'present')
  assert.equal(typeof rows[4].confidence, 'number')
})

test('structuredFieldRows renders yes-no option booleans as selected option meaning', () => {
  const rows = structuredFieldRows({
    structuredData: {
      page_3: {
        supplied_facilities: {
          pillow: false,
          refrigerator: false,
          table: false,
          bed: true,
          pillow_checked: false
        }
      }
    }
  }, 3)

  assert.equal(rows.find((row) => row.path === 'supplied_facilities.pillow').displayValue, '没有')
  assert.equal(rows.find((row) => row.path === 'supplied_facilities.refrigerator').displayValue, '没有')
  assert.equal(rows.find((row) => row.path === 'supplied_facilities.table').displayValue, '没有')
  assert.equal(rows.find((row) => row.path === 'supplied_facilities.bed').displayValue, '有')
  assert.equal(rows.find((row) => row.path === 'supplied_facilities.pillow_checked').displayValue, '未勾选')
})

test('structuredFieldRows uses backend LLM field evidence with crop snapshots', () => {
  const rows = structuredFieldRows({
    pages: [
      {
        page: 1,
        structuredFields: [
          {
            page: 1,
            path: 'personal.surname_en',
            label: 'Surname in English',
            value: 'AGUIJAR',
            displayValue: 'AGUIJAR',
            confidence: 68,
            bbox: [10, 20, 110, 40],
            snapshotDataUrl: 'data:image/jpeg;base64,crop',
            characters: [
              { index: 0, text: 'A', confidence: 95, status: 'ok', bbox: [10, 20, 20, 40] },
              { index: 1, text: 'G', confidence: 95, status: 'ok', bbox: [20, 20, 30, 40] },
              { index: 2, text: 'U', confidence: 95, status: 'ok', bbox: [30, 20, 40, 40] },
              { index: 3, text: 'I', confidence: 95, status: 'ok', bbox: [40, 20, 50, 40] },
              { index: 4, text: 'J', confidence: 92, status: 'ok', bbox: [50, 20, 60, 40] },
              { index: 5, text: 'A', confidence: 95, status: 'ok', bbox: [60, 20, 70, 40] },
              { index: 6, text: 'R', confidence: 62, status: 'ok', bbox: [70, 20, 80, 40] }
            ]
          }
        ]
      }
    ],
    structuredData: {
      page_1: {
        personal: {
          surname_en: 'AGUIJAR'
        }
      }
    }
  }, 1)

  assert.equal(rows.length, 1)
  assert.equal(rows[0].fieldName, 'Surname in English')
  assert.equal(rows[0].snapshotDataUrl, 'data:image/jpeg;base64,crop')
  assert.equal(rows[0].ocrText, '')
  assert.equal(rows[0].ocrStatus, 'not_run')
  assert.equal(rows[0].charSegments.every((segment) => segment.reviewFlag === false), true)
})

test('structuredFieldRows keeps LLM fields without OCR model status', () => {
  const rows = structuredFieldRows({
    pages: [
      {
        page: 1,
        structuredFields: [
          {
            page: 1,
            path: 'present_address',
            label: 'present address',
            value: 'Flat 7',
            displayValue: 'Flat 7',
            confidence: 98,
            bbox: [],
            snapshotDataUrl: '',
            characters: []
          }
        ]
      }
    ],
    structuredData: {}
  }, 1)

  assert.equal(rows.length, 1)
  assert.equal(rows[0].displayValue, 'Flat 7')
  assert.equal(rows[0].ocrStatus, 'not_run')
  assert.equal(rows[0].snapshotDataUrl, '')
  assert.equal(rows[0].charSegments.every((segment) => segment.reviewFlag === false), true)
})

test('structuredFieldRows displays applicant-written household counts instead of binary flags', () => {
  const rows = structuredFieldRows({
    pages: [
      {
        page: 3,
        structuredFields: [
          {
            page: 3,
            path: '家庭人数_3名成人',
            label: '3名成人',
            value: 1,
            displayValue: '1',
            confidence: 98,
            characters: [{ index: 0, text: '1', confidence: 100, status: 'ok' }]
          },
          {
            page: 3,
            path: '家庭人数_1名小孩',
            label: '1名小孩',
            value: 0,
            displayValue: '0',
            confidence: 98,
            characters: [{ index: 0, text: '0', confidence: 100, status: 'ok' }]
          },
          {
            page: 3,
            path: '家庭人数_1名将出生的婴儿',
            label: '1名将出生的婴儿',
            value: 0,
            displayValue: '0',
            confidence: 98,
            characters: [{ index: 0, text: '0', confidence: 100, status: 'ok' }]
          },
          {
            page: 3,
            path: '家庭人数_0家庭成员需要经常照料',
            label: '0家庭成员需要经常照料',
            value: 0,
            displayValue: '0',
            confidence: 98,
            characters: [{ index: 0, text: '0', confidence: 100, status: 'ok' }]
          },
          {
            page: 3,
            path: '雇工数目',
            label: '雇工数目',
            value: 0,
            displayValue: '0',
            confidence: 98
          }
        ]
      }
    ]
  }, 3)

  assert.deepEqual(rows.map((row) => row.displayValue), ['3', '1', '1', '0', '0'])
  assert.deepEqual(rows.map((row) => row.charSegments.map((segment) => segment.text).join('')), ['3', '1', '1', '0', '0'])
})

test('crop placeholder is generic when LLM evidence has no snapshot', () => {
  assert.equal(cropPlaceholderText({ snapshotDataUrl: '' }), '无区域快照')
})

test('pageFieldConclusion summarizes confidence distribution for the active page', () => {
  const conclusion = pageFieldConclusion([
    { confidence: 92 },
    { confidence: 86 },
    { confidence: 78 },
    { confidence: 61 }
  ])

  assert.equal(
    conclusion,
    '本页共识别 4 个字段，其中 2 个置信率在 85% 以上，1 个在 70%-84% 之间，1 个低于 70%，建议优先复核低置信字段。'
  )
})

test('documentFieldConclusion summarizes confidence distribution across all pages', () => {
  const conclusion = documentFieldConclusion({
    pages: [
      {
        page: 1,
        structuredFields: [
          { confidence: 95 },
          { confidence: 88 }
        ]
      },
      {
        page: 2,
        structuredFields: [
          { confidence: 84 },
          { confidence: 63 }
        ]
      }
    ]
  })

  assert.equal(
    conclusion,
    '整份文件共识别 4 个字段，其中 2 个置信率在 85% 以上，1 个在 70%-84% 之间，1 个低于 70%；建议优先复核低置信字段。'
  )
})

test('recognitionProgressState uses backend job progress instead of elapsed-time guesses', () => {
  const state = recognitionProgressState({
    status: 'running',
    pageCount: 5,
    completedPages: 2,
    progress: 40,
    pages: [
      { page: 1, status: 'completed', percent: 100 },
      { page: 2, status: 'completed', percent: 100 },
      {
        page: 3,
        status: 'running',
        percent: 0,
        message: '正在识别第 3 页。',
        attempt: 2,
        attemptReason: 'empty_retry',
        elapsedMillis: 84000,
        currentAttemptMillis: 12200
      },
      { page: 4, status: 'pending', percent: 0 },
      { page: 5, status: 'pending', percent: 0 }
    ]
  })

  assert.equal(state.percent, 40)
  assert.equal(state.stage, 'Page 3 识别中')
  assert.match(state.detail, /已完成 2 \/ 5 页/)
  assert.match(state.detail, /第 2 次请求/)
  assert.match(state.detail, /总耗时 1分24秒/)
  assert.match(state.detail, /本次 12秒/)
})

test('pageProgressItems labels each backend-reported page status', () => {
  const items = pageProgressItems({
    pages: [
      { page: 1, status: 'completed', percent: 100, elapsedMillis: 1234, attempt: 1 },
      { page: 2, status: 'running', percent: 0, elapsedMillis: 5200, attempt: 2 },
      { page: 3, status: 'pending', percent: 0 },
      { page: 4, status: 'failed', percent: 100 }
    ]
  })

  assert.deepEqual(items.map((item) => item.label), ['已完成', '识别中', '等待', '失败'])
  assert.equal(items[0].diagnostic, '1次 / 1秒')
  assert.equal(items[1].diagnostic, '2次 / 5秒')
})
