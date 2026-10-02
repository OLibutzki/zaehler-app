import * as ui from '@zos/ui'
import * as hmBle from '@zos/ble'
import * as display from '@zos/display'
import { LocalStorage } from '@zos/storage'
import { replace } from '@zos/router'
import { onKey, offKey, KEY_EVENT_CLICK } from '@zos/interaction'
import { BLEMaster } from '../lib/easy-ble'

// Sync mit der Handy-App "Zaehler Sync" per Bluetooth LE.
// Das Handy bietet den Dienst FFE0 an. Die Uhr liest die Einstellungen aus FFE1
// ("RRGGBB,RRGGBB" = Farbe oben, Farbe unten) und schreibt die Zaehlerstaende
// als "oben,unten" nach FFE2. Beide Werte bleiben unter 20 Byte.
const SERVICE = 'FFE0'
const CONFIG = 'FFE1'
const STATE = 'FFE2'
const SCAN_MS = 15000
const STEP_TIMEOUT_MS = 8000

const LOG_TOP = 196
const LINE_H = 28
const LINE_CHARS = 30

let ble = null
let storage = null
let scanning = false
let busy = false
let finished = false
let headWidget = null
let lineCount = 0
let spacer = null
let scanTimer = null
let stepTimer = null

function setHead(text, color) {
  if (!headWidget) return
  try {
    headWidget.setProperty(ui.prop.MORE, { text: text, color: color })
  } catch (e) {}
}

// Jede Protokollzeile ist ein eigenes Widget; so bleibt die Seite scrollbar.
function log(line) {
  try {
    let rest = line
    let first = true
    while (rest.length) {
      ui.createWidget(ui.widget.TEXT, {
        x: 53, y: LOG_TOP + lineCount * LINE_H, w: 360, h: LINE_H,
        text: rest.slice(0, LINE_CHARS),
        text_size: 18,
        color: first ? 0xbbbbbb : 0x888888,
        align_h: ui.align.CENTER_H,
        align_v: ui.align.CENTER_V
      })
      lineCount += 1
      rest = rest.slice(LINE_CHARS)
      first = false
    }
    if (spacer) {
      try { ui.deleteWidget(spacer) } catch (e) {}
    }
    spacer = ui.createWidget(ui.widget.TEXT, {
      x: 53, y: LOG_TOP + lineCount * LINE_H, w: 360, h: 170,
      text: '', text_size: 18, color: 0x000000
    })
  } catch (e) {}
}

function mac(ab) {
  const b = new Uint8Array(ab)
  let s = ''
  for (let i = 0; i < b.length; i++) {
    s += (i ? ':' : '') + (b[i] < 16 ? '0' : '') + b[i].toString(16)
  }
  return s
}

function hasService(res) {
  try {
    const arr = res.service_uuid_array || []
    for (let i = 0; i < arr.length; i++) {
      if (String(arr[i]).toLowerCase().indexOf('ffe0') >= 0) return true
    }
  } catch (e) {}
  return false
}

function toText(data) {
  try {
    if (typeof data === 'string') return data
    const b = new Uint8Array(data)
    let text = ''
    for (let i = 0; i < b.length; i++) text += String.fromCharCode(b[i])
    return text
  } catch (e) {
    return ''
  }
}

// "00E0A0,FFA030" -> zwei Farbwerte. Gibt null zurueck, wenn der Text nicht passt.
function parseConfig(text) {
  const m = /^([0-9A-Fa-f]{6}),([0-9A-Fa-f]{6})$/.exec(String(text).trim())
  if (!m) return null
  return { top: m[1].toUpperCase(), bottom: m[2].toUpperCase() }
}

function clearStepTimer() {
  try {
    if (stepTimer) clearTimeout(stepTimer)
  } catch (e) {}
  stepTimer = null
}

function armStepTimer(what) {
  clearStepTimer()
  try {
    stepTimer = setTimeout(() => {
      fail(what + ': keine Antwort')
    }, STEP_TIMEOUT_MS)
  } catch (e) {}
}

function stopScan() {
  if (!scanning) return
  scanning = false
  try {
    hmBle.mstStopScan()
  } catch (e) {}
}

function disconnect() {
  try {
    if (ble) ble.quit()
  } catch (e) {}
  ble = null
}

function fail(message) {
  if (finished) return
  finished = true
  clearStepTimer()
  stopScan()
  disconnect()
  log(message)
  setHead('Sync fehlgeschlagen', 0xff6a5a)
}

function succeed(top, bottom) {
  if (finished) return
  finished = true
  clearStepTimer()
  disconnect()
  log('Fertig')
  setHead('Sync fertig\noben ' + top + '   unten ' + bottom, 0x00e0a0)
}

function runSync(dev, top, bottom) {
  log('Handy gefunden, verbinde ...')
  try {
    ble = new BLEMaster()
    const started = ble.connect(dev, (res) => {
      try {
        if (finished) return
        if (!res.connected) {
          fail('Verbindung: ' + res.status)
          return
        }
        log('Verbunden')
        const services = {}
        services[SERVICE] = {}
        services[SERVICE][CONFIG] = []
        services[SERVICE][STATE] = []
        const profile = ble.generateProfileObject(services)
        if (!profile) {
          fail('Profil nicht erzeugt')
          return
        }
        armStepTimer('Profil')
        ble.startListener(profile, (resp) => {
          try {
            if (finished) return
            if (!resp.success) {
              fail('Profil: ' + resp.message + ' ' + (resp.code || ''))
              return
            }
            let configRead = false

            ble.on.charaValueArrived((uuid, data) => {
              if (finished || configRead) return
              configRead = true
              const raw = toText(data)
              const cfg = parseConfig(raw)
              log('Einstellungen: ' + raw)
              if (cfg) {
                try {
                  storage.setItem('ct', cfg.top)
                  storage.setItem('cb', cfg.bottom)
                  log('Farben uebernommen')
                } catch (e) {
                  log('Farben nicht gespeichert')
                }
              } else {
                log('Einstellungen unlesbar')
              }

              // Zaehlerstaende schreiben
              try {
                const text = top + ',' + bottom
                const bytes = new Uint8Array(text.length)
                for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i)
                const ok = hmBle.mstWriteCharacteristic(ble.get.profilePID(), STATE, bytes.buffer, bytes.length)
                if (ok === false) {
                  fail('Schreiben abgelehnt')
                  return
                }
                log('Sende Stand ' + text + ' ...')
                armStepTimer('Schreiben')
              } catch (e) {
                fail('Schreib-Fehler: ' + e)
              }
            })

            ble.on.charaWriteComplete((uuid, status) => {
              if (finished) return
              if (status === 0) succeed(top, bottom)
              else fail('Schreiben: Status ' + status)
            })

            log('Lese Einstellungen ...')
            armStepTimer('Lesen')
            const rd = ble.read.characteristic(CONFIG)
            if (rd && rd.success === false) fail('Lesen: ' + rd.error)
          } catch (e) {
            fail('Fehler: ' + e)
          }
        })
      } catch (e) {
        fail('Fehler: ' + e)
      }
    })
    if (started === false) fail('Verbinden abgelehnt')
  } catch (e) {
    fail('Verbinden-Fehler: ' + e)
  }
}

function start() {
  let top = 0
  let bottom = 0
  try {
    storage = new LocalStorage()
    top = Number(storage.getItem('top', 0)) || 0
    bottom = Number(storage.getItem('bottom', 0)) || 0
  } catch (e) {
    fail('Speicher-Fehler')
    return
  }

  setHead('Suche Handy ...', 0xffffff)
  log('Suche gestartet')
  try {
    const r = hmBle.mstStartScan((res) => {
      try {
        if (busy || finished || !hasService(res)) return
        busy = true
        stopScan()
        try {
          if (scanTimer) clearTimeout(scanTimer)
        } catch (e) {}
        setHead('Verbinde ...', 0xffffff)
        runSync(mac(res.dev_addr), top, bottom)
      } catch (e) {
        fail('Such-Fehler: ' + e)
      }
    })
    scanning = r !== false
    if (!scanning) {
      fail('Suche abgelehnt')
      return
    }
  } catch (e) {
    fail('Such-Fehler: ' + e)
    return
  }
  try {
    scanTimer = setTimeout(() => {
      if (!busy && !finished) {
        fail('Handy nicht gefunden. Ist die App "Zaehler Sync" geoeffnet?')
      }
    }, SCAN_MS)
  } catch (e) {}
}

Page({
  build() {
    // Der Modulzustand kann von einem frueheren Aufruf stammen.
    ble = null
    scanning = false
    busy = false
    finished = false
    lineCount = 0
    spacer = null

    ui.createWidget(ui.widget.TEXT, {
      x: 0, y: 30, w: 466, h: 34,
      text: 'Sync',
      text_size: 24,
      color: 0x888888,
      align_h: ui.align.CENTER_H,
      align_v: ui.align.CENTER_V
    })

    headWidget = ui.createWidget(ui.widget.TEXT, {
      x: 50, y: 64, w: 366, h: 70,
      text: '',
      text_size: 26,
      color: 0xffffff,
      align_h: ui.align.CENTER_H,
      align_v: ui.align.CENTER_V,
      text_style: ui.text_style.WRAP
    })

    ui.createWidget(ui.widget.BUTTON, {
      x: 163, y: 140, w: 140, h: 44,
      radius: 22,
      text: 'QR-Code',
      text_size: 19,
      normal_color: 0x2a2a2a,
      press_color: 0x555555,
      click_func: () => {
        try {
          replace({ url: 'page/qr' })
        } catch (e) {}
      }
    })

    try {
      display.setPageBrightTime({ brightTime: 60000 })
    } catch (e) {}

    try {
      onKey({
        callback: (key, keyEvent) => {
          if (keyEvent === KEY_EVENT_CLICK) {
            try {
              replace({ url: 'page/index' })
            } catch (e) {}
          }
          return true
        }
      })
    } catch (e) {}

    start()
  },

  onDestroy() {
    finished = true
    clearStepTimer()
    try {
      if (scanTimer) clearTimeout(scanTimer)
    } catch (e) {}
    stopScan()
    disconnect()
    try {
      offKey()
    } catch (e) {}
  }
})
