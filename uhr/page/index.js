import { createWidget, widget, align, text_style, prop } from '@zos/ui'
import { getDeviceInfo } from '@zos/device'
import * as display from '@zos/display'
import { LocalStorage } from '@zos/storage'
import { replace } from '@zos/router'
import { onKey, offKey, KEY_HOME, KEY_SHORTCUT, KEY_EVENT_CLICK } from '@zos/interaction'

const DEFAULT_COLOR_TOP = 0x00e0a0
const DEFAULT_COLOR_BOTTOM = 0xffa030
const RESET_CONFIRM_MS = 3000

let storage = null
let counts = { top: 0, bottom: 0 }
let colors = { top: DEFAULT_COLOR_TOP, bottom: DEFAULT_COLOR_BOTTOM }
let lightOn = false
let resetArmedAt = 0
let topWidget = null
let bottomWidget = null
let hintWidget = null

function setText(w, text) {
  if (!w) return
  try {
    w.setProperty(prop.MORE, { text: text })
  } catch (e) {
    try {
      w.setProperty(prop.TEXT, text)
    } catch (e2) {}
  }
}

function hint(text) {
  setText(hintWidget, text)
}

function defaultHint() {
  hint(lightOn ? 'Dauerlicht an' : '')
}

function parseColor(text, fallback) {
  const t = String(text || '')
  if (!/^[0-9A-Fa-f]{6}$/.test(t)) return fallback
  return parseInt(t, 16)
}

// Zaehler werden bei jeder Aenderung sofort gespeichert, damit sie ein
// Wegwischen oder Beenden der App ueberleben.
function load() {
  try {
    storage = new LocalStorage()
    const top = Number(storage.getItem('top', 0))
    const bottom = Number(storage.getItem('bottom', 0))
    counts.top = top > 0 ? Math.floor(top) : 0
    counts.bottom = bottom > 0 ? Math.floor(bottom) : 0
    // Farben kommen beim Sync vom Handy und liegen als "RRGGBB" im Speicher.
    colors.top = parseColor(storage.getItem('ct', ''), DEFAULT_COLOR_TOP)
    colors.bottom = parseColor(storage.getItem('cb', ''), DEFAULT_COLOR_BOTTOM)
    return ''
  } catch (e) {
    storage = null
    return 'Speicher-Fehler: ' + e
  }
}

function save() {
  if (!storage) return false
  try {
    storage.setItem('top', counts.top)
    storage.setItem('bottom', counts.bottom)
    return true
  } catch (e) {
    hint('Speichern fehlgeschlagen')
    return false
  }
}

function setNumber(w, value, color) {
  if (!w) return
  try {
    w.setProperty(prop.MORE, { text: String(value), color: color })
  } catch (e) {
    setText(w, String(value))
  }
}

function show() {
  setNumber(topWidget, counts.top, colors.top)
  setNumber(bottomWidget, counts.bottom, colors.bottom)
}

function setLight(on) {
  try {
    if (on) {
      display.setPageBrightTime({ brightTime: 2147483000 })
      if (typeof display.pauseDropWristScreenOff === 'function') display.pauseDropWristScreenOff({ duration: 0 })
      if (typeof display.pausePalmScreenOff === 'function') display.pausePalmScreenOff({ duration: 0 })
    } else {
      if (typeof display.resetPageBrightTime === 'function') display.resetPageBrightTime()
      if (typeof display.resetDropWristScreenOff === 'function') display.resetDropWristScreenOff()
      if (typeof display.resetPalmScreenOff === 'function') display.resetPalmScreenOff()
    }
    lightOn = on
    // Zustand merken, damit das Dauerlicht nach einem Neustart der App wieder an ist.
    if (storage) {
      try {
        storage.setItem('light', on ? 1 : 0)
      } catch (e) {}
    }
    defaultHint()
  } catch (e) {
    hint('Licht-Fehler: ' + e)
  }
}

function keyCallback(key, keyEvent) {
  try {
    if (keyEvent === KEY_EVENT_CLICK) {
      if (key === KEY_HOME) counts.top += 1
      else if (key === KEY_SHORTCUT) counts.bottom += 1
      else return true
      resetArmedAt = 0
      show()
      if (save()) defaultHint()
    }
  } catch (e) {
    hint('Fehler: ' + e)
  }
  // true = Standardverhalten der Taste unterdruecken (App bleibt offen)
  return true
}

Page({
  build() {
    let width = 466
    try {
      const info = getDeviceInfo()
      if (info && info.width) width = info.width
    } catch (e) {}

    const loadError = load()

    createWidget(widget.TEXT, {
      x: 0, y: 34, w: width, h: 30,
      text: 'oben',
      text_size: 22,
      color: 0x888888,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V
    })

    topWidget = createWidget(widget.TEXT, {
      x: 0, y: 62, w: width, h: 120,
      text: String(counts.top),
      text_size: 104,
      color: colors.top,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V
    })

    createWidget(widget.FILL_RECT, {
      x: 83, y: 190, w: width - 166, h: 2,
      color: 0x333333
    })

    bottomWidget = createWidget(widget.TEXT, {
      x: 0, y: 198, w: width, h: 120,
      text: String(counts.bottom),
      text_size: 104,
      color: colors.bottom,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V
    })

    createWidget(widget.TEXT, {
      x: 0, y: 316, w: width, h: 30,
      text: 'unten',
      text_size: 22,
      color: 0x888888,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V
    })

    createWidget(widget.BUTTON, {
      x: 97, y: 352, w: 84, h: 48,
      radius: 24,
      text: 'Licht',
      text_size: 19,
      normal_color: 0x2a2a2a,
      press_color: 0x555555,
      click_func: () => {
        resetArmedAt = 0
        setLight(!lightOn)
      }
    })

    createWidget(widget.BUTTON, {
      x: 191, y: 352, w: 84, h: 48,
      radius: 24,
      text: 'Sync',
      text_size: 19,
      normal_color: 0x1f3a5f,
      press_color: 0x3a6aa8,
      click_func: () => {
        resetArmedAt = 0
        save()
        try {
          replace({ url: 'page/sync' })
        } catch (e) {
          hint('Sync-Fehler: ' + e)
        }
      }
    })

    createWidget(widget.BUTTON, {
      x: 285, y: 352, w: 84, h: 48,
      radius: 24,
      text: 'Reset',
      text_size: 19,
      normal_color: 0x2a2a2a,
      press_color: 0x803030,
      click_func: () => {
        const now = Date.now()
        if (resetArmedAt && now - resetArmedAt < RESET_CONFIRM_MS) {
          resetArmedAt = 0
          counts.top = 0
          counts.bottom = 0
          show()
          if (save()) hint('zurueckgesetzt')
        } else {
          resetArmedAt = now
          hint('nochmal tippen = loeschen')
        }
      }
    })

    hintWidget = createWidget(widget.TEXT, {
      x: 110, y: 402, w: width - 220, h: 44,
      text: loadError,
      text_size: 16,
      color: 0x999999,
      align_h: align.CENTER_H,
      align_v: align.CENTER_V,
      text_style: text_style.WRAP
    })

    try {
      onKey({ callback: keyCallback })
    } catch (e) {
      hint('Tasten-Fehler: ' + e)
    }

    // Gespeichertes Dauerlicht wiederherstellen.
    let savedLight = false
    if (storage) {
      try {
        savedLight = Number(storage.getItem('light', 0)) === 1
      } catch (e) {}
    }
    if (savedLight) setLight(true)

    // Nach dem Aufwecken wieder diese App zeigen statt des Zifferblatts.
    try {
      display.setWakeUpRelaunch({ relaunch: true })
    } catch (e) {}
  },

  onDestroy() {
    save()
    try {
      offKey()
    } catch (e) {}
  }
})
