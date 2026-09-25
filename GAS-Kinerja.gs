// ============================================================
// AI Productivity V3 - Voice Kinerja -> Google Sheets
// Tempel ke: Google Sheet > Extensions > Apps Script
// ============================================================

const SHEET_ID = 'GANTI_DENGAN_ID_GOOGLE_SHEET_ANDA';
const API_KEY  = 'GANTI_DENGAN_KUNCI_RAHASIA_ANDA';
const LOG_SHEET = 'Log Kinerja';
const DAILY_SHEET = 'Ringkasan Harian';

function setupKinerjaSheet() {
  const ss = SpreadsheetApp.openById(SHEET_ID);

  let log = ss.getSheetByName(LOG_SHEET);
  if (!log) log = ss.insertSheet(LOG_SHEET);
  if (log.getLastRow() === 0) {
    log.appendRow([
      'Event ID',
      'Timestamp Server',
      'Tanggal',
      'Jam',
      'Kinerja',
      'Kategori',
      'Tugas Aktif',
      'Mode',
      'Fokus Hari',
      'Fokus Hari (detik)',
      'Fokus Sesi',
      'Fokus Sesi (detik)',
      'Away Hari',
      'Distraksi Hari',
      'Sumber',
      'Aplikasi'
    ]);
    log.setFrozenRows(1);
  }

  let daily = ss.getSheetByName(DAILY_SHEET);
  if (!daily) daily = ss.insertSheet(DAILY_SHEET);
  if (daily.getLastRow() === 0) {
    daily.appendRow([
      'Tanggal',
      'Update Terakhir',
      'Jumlah Capture Kerja',
      'Fokus Hari',
      'Fokus Hari (detik)',
      'Away Hari',
      'Distraksi Hari',
      'Kinerja Terakhir'
    ]);
    daily.setFrozenRows(1);
  }

  return 'OK - Sheet siap';
}

function doGet() {
  return json_({
    ok: true,
    service: 'AI Productivity V3 GAS',
    log_sheet: LOG_SHEET,
    daily_sheet: DAILY_SHEET
  });
}

function doPost(e) {
  try {
    const body = JSON.parse((e && e.postData && e.postData.contents) || '{}');

    if (String(body.api_key || '') !== String(API_KEY)) {
      return json_({ ok: false, error: 'unauthorized' });
    }

    if (body.type === 'test') {
      return json_({ ok: true, message: 'connection-ok' });
    }

    if (body.type !== 'kinerja') {
      return json_({ ok: false, error: 'unknown-type' });
    }

    const eventId = safe_(body.event_id);
    if (!eventId) return json_({ ok: false, error: 'missing-event-id' });

    const ss = SpreadsheetApp.openById(SHEET_ID);
    let log = ss.getSheetByName(LOG_SHEET);
    if (!log) {
      setupKinerjaSheet();
      log = ss.getSheetByName(LOG_SHEET);
    }

    // Anti-duplikasi: penting karena aplikasi bisa retry saat koneksi putus.
    if (eventExists_(log, eventId)) {
      return json_({ ok: true, duplicate: true, message: 'already-saved' });
    }

    log.appendRow([
      eventId,
      new Date(),
      safe_(body.tanggal),
      safe_(body.jam),
      safe_(body.kinerja),
      safe_(body.kategori),
      safe_(body.tugas_aktif),
      safe_(body.mode),
      safe_(body.focus_hari),
      Number(body.focus_hari_detik || 0),
      safe_(body.focus_sesi),
      Number(body.focus_sesi_detik || 0),
      Number(body.away_hari || 0),
      Number(body.distraksi_hari || 0),
      safe_(body.source),
      safe_(body.app)
    ]);

    updateDaily_(ss, body);

    return json_({ ok: true, message: 'saved', event_id: eventId });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function eventExists_(sheet, eventId) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return false;
  const finder = sheet.getRange(2, 1, lastRow - 1, 1)
    .createTextFinder(eventId)
    .matchEntireCell(true)
    .findNext();
  return !!finder;
}

function updateDaily_(ss, body) {
  let daily = ss.getSheetByName(DAILY_SHEET);
  if (!daily) {
    setupKinerjaSheet();
    daily = ss.getSheetByName(DAILY_SHEET);
  }

  const tanggal = safe_(body.tanggal);
  let row = -1;
  const last = daily.getLastRow();
  if (last >= 2) {
    const found = daily.getRange(2, 1, last - 1, 1)
      .createTextFinder(tanggal)
      .matchEntireCell(true)
      .findNext();
    if (found) row = found.getRow();
  }

  if (row < 0) {
    daily.appendRow([
      tanggal,
      new Date(),
      1,
      safe_(body.focus_hari),
      Number(body.focus_hari_detik || 0),
      Number(body.away_hari || 0),
      Number(body.distraksi_hari || 0),
      safe_(body.kinerja)
    ]);
  } else {
    const count = Number(daily.getRange(row, 3).getValue() || 0) + 1;
    daily.getRange(row, 2, 1, 7).setValues([[
      new Date(),
      count,
      safe_(body.focus_hari),
      Number(body.focus_hari_detik || 0),
      Number(body.away_hari || 0),
      Number(body.distraksi_hari || 0),
      safe_(body.kinerja)
    ]]);
  }
}

function safe_(v) {
  if (v === null || v === undefined) return '';
  const s = String(v);
  // Cegah formula injection jika hasil speech diawali karakter formula.
  return /^[=+\-@]/.test(s) ? "'" + s : s;
}

function json_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
