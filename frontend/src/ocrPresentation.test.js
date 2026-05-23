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

test('pageStructuredFieldCount ignores null and blank leaf fields', () => {
  const count = pageStructuredFieldCount({
    structuredData: {
      page_1: {
        part_1: {
          selected_type: '(a)',
          visa_type: null,
          blank_note: ''
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

  assert.equal(count, 2)
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

  assert.equal(rows.length, 4)
  assert.equal(rows[0].fieldName, 'surname en')
  assert.equal(rows[0].displayValue, 'HIDAYATI')
  assert.equal(rows[0].confidence, 91)
  assert.equal(rows[1].displayValue, '已勾选')
  assert.equal(rows[2].displayValue, '未勾选')
  assert.equal(rows[3].displayValue, '已签名，未识别出签名文字')
  assert.equal(rows[3].rawValue, 'present')
  assert.equal(typeof rows[3].confidence, 'number')
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

test('structuredFieldRows hides backend LLM fields that have no applicant value', () => {
  const rows = structuredFieldRows({
    pages: [
      {
        page: 1,
        structuredFields: [
          {
            page: 1,
            path: 'empty_address',
            label: 'empty address',
            value: null,
            displayValue: '未填写',
            confidence: 98,
            bbox: [1, 2, 3, 4],
            snapshotDataUrl: 'data:image/jpeg;base64,crop',
            characters: []
          },
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

  assert.deepEqual(rows.map((row) => row.path), ['present_address'])
})

test('structuredFieldRows fills missing nested rows from structuredData when structuredFields are incomplete', () => {
  const rows = structuredFieldRows({
    pages: [
      {
        page: 2,
        structuredFields: [
          {
            page: 2,
            path: 'household_members.1.hk_identity_card_no',
            label: 'HK identity card no. (if any)',
            value: 'S 663289(7)',
            displayValue: 'S 663289(7)',
            confidence: 95,
            bbox: [1, 2, 3, 4],
            snapshotDataUrl: 'data:image/jpeg;base64,crop',
            characters: []
          }
        ]
      }
    ],
    structuredData: {
      page_2: {
        household_members: [
          {
            name: '梁靖娴',
            year_of_birth: '1981',
            relationship_with_the_employer: '本人',
            hk_identity_card_no: 'S 663289(7)'
          },
          {
            name: '黄志辉',
            year_of_birth: '1978',
            relationship_with_the_employer: '夫妻',
            hk_identity_card_no: 'J 778431(0)'
          }
        ]
      }
    }
  }, 2)

  assert.deepEqual(rows.map((row) => row.path), [
    'household_members.1.name',
    'household_members.1.year_of_birth',
    'household_members.1.relationship_with_the_employer',
    'household_members.1.hk_identity_card_no',
    'household_members.2.name',
    'household_members.2.year_of_birth',
    'household_members.2.relationship_with_the_employer',
    'household_members.2.hk_identity_card_no'
  ])
})

test('structuredFieldRows keeps footer fields after working experience rows by visual page order', () => {
  const rows = structuredFieldRows({
    pages: [
      {
        page: 2,
        structuredFields: [
          {
            page: 2,
            path: 'date',
            label: 'Date',
            value: '20/4/2026',
            displayValue: '20/4/2026',
            confidence: 90,
            bbox: [900, 3180, 1300, 3260],
            snapshotDataUrl: 'data:image/jpeg;base64,date',
            characters: []
          },
          {
            page: 2,
            path: 'signature_of_applicant',
            label: 'Signature of applicant',
            value: 'Siti Nurhaliza',
            displayValue: 'Siti Nurhaliza',
            confidence: 95,
            bbox: [1400, 3160, 2100, 3260],
            snapshotDataUrl: 'data:image/jpeg;base64,signature',
            characters: []
          }
        ]
      }
    ],
    structuredData: {
      page_2: {
        date: '20/4/2026',
        signature_of_applicant: 'Siti Nurhaliza',
        working_experience_as_a_domestic_helper: [
          {
            name_of_employer: 'Mrs. Linda CHEN',
            address: 'Flat 5A, 12/F, Park View',
            period_from: '06/19',
            period_to: '05/22'
          }
        ]
      }
    }
  }, 2)

  assert.deepEqual(rows.map((row) => row.path), [
    'working_experience_as_a_domestic_helper.1.name_of_employer',
    'working_experience_as_a_domestic_helper.1.address',
    'working_experience_as_a_domestic_helper.1.period_from',
    'working_experience_as_a_domestic_helper.1.period_to',
    'date',
    'signature_of_applicant'
  ])
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
          { value: 'A', confidence: 95 },
          { value: 'B', confidence: 88 }
        ]
      },
      {
        page: 2,
        structuredFields: [
          { value: 'C', confidence: 84 },
          { value: 'D', confidence: 63 }
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

test('recognitionProgressState shows backend post-processing progress', () => {
  const state = recognitionProgressState({
    status: 'post_processing',
    pageCount: 2,
    completedPages: 2,
    progress: 86,
    message: 'crop review is running',
    pages: [
      { page: 1, status: 'completed', percent: 100 },
      { page: 2, status: 'completed', percent: 100 }
    ]
  })

  assert.equal(state.percent, 86)
  assert.equal(state.stage, 'crop review is running')
  assert.equal(state.detail, 'crop review is running')
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
